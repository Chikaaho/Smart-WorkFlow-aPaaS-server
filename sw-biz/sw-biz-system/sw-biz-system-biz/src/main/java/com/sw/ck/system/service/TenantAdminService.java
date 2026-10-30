package com.sw.ck.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.persistence.IdempotentInsert;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.entity.SysDept;
import com.sw.ck.system.entity.SysRole;
import com.sw.ck.system.entity.SysRoleMenu;
import com.sw.ck.system.entity.SysTenant;
import com.sw.ck.system.entity.SysTenantStatusAudit;
import com.sw.ck.system.entity.SysUser;
import com.sw.ck.system.entity.SysUserRole;
import com.sw.ck.system.mapper.SysDeptMapper;
import com.sw.ck.system.mapper.SysRoleMapper;
import com.sw.ck.system.mapper.SysRoleMenuMapper;
import com.sw.ck.system.mapper.SysTenantMapper;
import com.sw.ck.system.mapper.SysTenantStatusAuditMapper;
import com.sw.ck.system.mapper.SysUserMapper;
import com.sw.ck.system.mapper.SysUserRoleMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.time.LocalDateTime;

/**
 * 租户管理服务（T020 / DR02）。
 * <p>
 * sys_tenant 是全局表（无租户隔离语义），所有读取都在 {@link TenantLineSuspension} 下完成；
 * 新建租户时租户、根部门、首任管理员、角色模板复制与关联在同一事务内完成，失败零半成品。
 * 租户 0 是保留对象：名称不可改、状态不可改。
 * </p>
 */
@Service
public class TenantAdminService {

    /** 超级租户：系统超管与保留对象所在租户。 */
    private static final long ROOT_TENANT_ID = 0L;
    /** 管理员角色模板编码（从租户 0 复制规则，不复制人员与业务数据）。 */
    private static final String ADMIN_ROLE_CODE = "admin";

    private final SysTenantMapper tenantMapper;
    private final SysDeptMapper deptMapper;
    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysTenantStatusAuditMapper statusAuditMapper;
    private final PasswordEncoder passwordEncoder;
    private final PlatformTransactionManager transactionManager;

    public TenantAdminService(SysTenantMapper tenantMapper,
                              SysDeptMapper deptMapper,
                              SysUserMapper userMapper,
                              SysRoleMapper roleMapper,
                              SysRoleMenuMapper roleMenuMapper,
                              SysUserRoleMapper userRoleMapper,
                              SysTenantStatusAuditMapper statusAuditMapper,
                              PasswordEncoder passwordEncoder,
                              PlatformTransactionManager transactionManager) {
        this.tenantMapper = tenantMapper;
        this.deptMapper = deptMapper;
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.roleMenuMapper = roleMenuMapper;
        this.userRoleMapper = userRoleMapper;
        this.statusAuditMapper = statusAuditMapper;
        this.passwordEncoder = passwordEncoder;
        this.transactionManager = transactionManager;
    }

    /** 新建租户命令。 */
    public record CreateTenantCommand(String name, String adminUsername, String adminPassword, String requestId) {}

    /** 列表行。adminUsername 为空表示未配置首任管理员。 */
    public record TenantView(Long id, String code, String name, Long adminUserId, String adminUsername,
                             Integer status, java.time.LocalDateTime createTime) {}

    public record AdminCandidate(Long id, String username, String realName) {}

    /** 租户启停与过期记录。 */
    public record StatusAuditView(Long id, String action, Integer oldStatus, Integer newStatus,
                                  LocalDateTime expireTime, String reason, Long operatorId,
                                  LocalDateTime occurredAt, String result) {}

    /** 分页查询（按名称模糊、状态精确）。 */
    public PageResult<TenantView> page(long pageNum, long pageSize, String name, Integer status) {
        assertSuperAdmin();
        long current = Math.max(pageNum, 1);
        long size = Math.min(Math.max(pageSize, 1), 200);
        try (TenantLineSuspension.Suspended ignored = TenantLineSuspension.suspended()) {
            var wrapper = Wrappers.<SysTenant>lambdaQuery()
                    .like(name != null && !name.isBlank(), SysTenant::getName, name == null ? null : name.trim())
                    .eq(status != null, SysTenant::getStatus, status)
                    .orderByAsc(SysTenant::getId);
            Page<SysTenant> page = tenantMapper.selectPage(new Page<>(current, size), wrapper);
            List<TenantView> rows = new ArrayList<>(page.getRecords().size());
            for (SysTenant tenant : page.getRecords()) {
                rows.add(toView(tenant));
            }
            PageResult<TenantView> result = new PageResult<>();
            result.setRecords(rows);
            result.setTotal(page.getTotal());
            result.setPageNum(page.getCurrent());
            result.setPageSize(page.getSize());
            return result;
        }
    }

    /** 详情。 */
    public TenantView detail(Long id) {
        assertSuperAdmin();
        return toView(requireTenant(id));
    }

    /** 创建租户 + 首任管理员 + 根部门 + 管理员角色模板，一次事务完成。 */
    @Transactional(rollbackFor = Exception.class)
    public Long create(CreateTenantCommand command) {
        assertSuperAdmin();
        if (command == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "租户信息不能为空");
        }
        String requestId = trim(command.requestId());
        if (!requestId.matches("[A-Za-z0-9_-]{16,64}")) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "创建请求标识无效，请重新打开新增窗口");
        }
        SysTenant previous = findByCreationRequest(requestId);
        if (previous != null) {
            return previous.getId();
        }
        String name = trim(command.name());
        String username = trim(command.adminUsername());
        String password = command.adminPassword();
        if (name.isEmpty() || name.length() > 64) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "租户名称不能为空且不超过 64 个字符");
        }
        validateAdminCredentials(username, password);
        SysTenant tenant = new SysTenant();
        tenant.setName(name);
        tenant.setCode("t" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        tenant.setStatus(0);
        tenant.setCreationRequestId(requestId);
        tenant.setDescription("0.2.0 新建租户");
        try {
            IdempotentInsert.execute(transactionManager, () -> tenantMapper.insert(tenant));
        } catch (DuplicateKeyException duplicate) {
            // 唯一键冲突只回滚保存点；并发首请求提交后返回同一租户，不再次初始化。
            SysTenant existing = findByCreationRequest(requestId);
            if (existing != null) return existing.getId();
            throw duplicate;
        }
        Long tenantId = tenant.getId();

        Long adminUserId = initializeAdministrator(tenant, username, password, true);

        tenant.setAdminUserId(adminUserId);
        if (tenantMapper.updateById(tenant) != 1) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "管理员关联保存失败，请重试");
        }

        return tenantId;
    }

    /** 修改租户名称（租户 0 只读）。 */
    @Transactional(rollbackFor = Exception.class)
    public void rename(Long id, String name) {
        assertSuperAdmin();
        SysTenant tenant = requireTenant(id);
        if (ROOT_TENANT_ID == tenant.getId()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "超管租户为保留对象，不能编辑");
        }
        String trimmed = trim(name);
        if (trimmed.isEmpty() || trimmed.length() > 64) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "租户名称不能为空且不超过 64 个字符");
        }
        tenant.setName(trimmed);
        tenantMapper.updateById(tenant);
    }

    /** 启用/停用（0=启用 1=停用；租户 0 只读）。 */
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, Integer status, String reason) {
        assertSuperAdmin();
        if (status == null || (status != 0 && status != 1)) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "租户状态只能为启用或停用");
        }
        SysTenant tenant = requireTenant(id);
        if (ROOT_TENANT_ID == tenant.getId()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "超管租户为保留对象，不能停用");
        }
        String normalizedReason = trim(reason);
        if (normalizedReason.isEmpty() || normalizedReason.length() > 500) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "请填写变更原因，且不超过 500 个字符");
        }
        Integer previousStatus = tenant.getStatus();
        tenant.setStatus(status);
        if (tenantMapper.updateById(tenant) != 1) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "租户状态更新失败，请重试");
        }
        recordStatusAudit(id, status == 1 ? "DISABLE" : "ENABLE", previousStatus, status,
                tenant.getExpireTime(), normalizedReason, status == 1 ? "已停用" : "已启用");
    }

    /** 查询租户最近的状态变更；包含手工启停与系统过期识别。 */
    public List<StatusAuditView> statusAudits(Long id) {
        assertSuperAdmin();
        requireTenant(id);
        try (TenantLineSuspension.Suspended ignored = TenantLineSuspension.suspended()) {
            return statusAuditMapper.selectList(Wrappers.<SysTenantStatusAudit>lambdaQuery()
                            .eq(SysTenantStatusAudit::getTenantId, id)
                            .orderByDesc(SysTenantStatusAudit::getOccurredAt)
                            .last("LIMIT 50"))
                    .stream()
                    .map(row -> new StatusAuditView(row.getId(), row.getAction(), row.getOldStatus(),
                            row.getNewStatus(), row.getExpireTime(), row.getReason(), row.getOperatorId(),
                            row.getOccurredAt(), row.getResult()))
                    .toList();
        }
    }

    /** 只列出目标租户内仍有效且拥有租户管理员角色的用户。 */
    public List<AdminCandidate> adminCandidates(Long id) {
        assertSuperAdmin();
        assertMutableTenant(requireTenant(id));
        try (TenantLineSuspension.Suspended ignored = TenantLineSuspension.suspended()) {
            return userMapper.selectActiveUserIdsByRoleCodes(List.of(ADMIN_ROLE_CODE), id).stream()
                    .map(userMapper::selectById).filter(user -> user != null && id.equals(user.getTenantId())
                            && Integer.valueOf(0).equals(user.getStatus()))
                    .map(user -> new AdminCandidate(user.getId(), user.getUsername(), user.getRealName()))
                    .toList();
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void assignAdministrator(Long id, Long adminUserId) {
        assertSuperAdmin();
        SysTenant tenant = lockMutableTenant(id);
        if (adminUserId == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "请选择管理员");
        }
        boolean eligible = adminCandidates(id).stream().anyMatch(user -> adminUserId.equals(user.id()));
        if (!eligible) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "请选择该租户内有效的管理员");
        }
        tenant.setAdminUserId(adminUserId);
        if (tenantMapper.updateById(tenant) != 1) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "管理员关联保存失败，请重试");
        }
    }

    /** 对尚未配置管理员的存量租户，原子创建并绑定首任管理员。 */
    @Transactional(rollbackFor = Exception.class)
    public Long createAdministrator(Long id, String adminUsername, String adminPassword) {
        assertSuperAdmin();
        SysTenant tenant = lockMutableTenant(id);
        if (tenant.getAdminUserId() != null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "已配置管理员，请选择现有管理员维护关联");
        }
        String username = trim(adminUsername);
        validateAdminCredentials(username, adminPassword);
        Long userId = initializeAdministrator(tenant, username, adminPassword, false);
        tenant.setAdminUserId(userId);
        if (tenantMapper.updateById(tenant) != 1) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "管理员关联保存失败，请重试");
        }
        return userId;
    }

    private SysTenant lockMutableTenant(Long id) {
        if (id == null) throw new BaseException(CommonErrorCode.PARAM_ERROR, "租户标识不能为空");
        try (TenantLineSuspension.Suspended ignored = TenantLineSuspension.suspended()) {
            SysTenant tenant = tenantMapper.selectGlobalByIdForUpdate(id);
            if (tenant == null) throw new BaseException(CommonErrorCode.PARAM_ERROR, "租户不存在或已删除");
            assertMutableTenant(tenant);
            return tenant;
        }
    }

    private void assertMutableTenant(SysTenant tenant) {
        if (Long.valueOf(ROOT_TENANT_ID).equals(tenant.getId())) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "超管租户为保留对象，不能编辑");
        }
    }

    private SysTenant requireTenant(Long id) {
        if (id == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "租户标识不能为空");
        }
        try (TenantLineSuspension.Suspended ignored = TenantLineSuspension.suspended()) {
            SysTenant tenant = tenantMapper.selectGlobalById(id);
            if (tenant == null) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR, "租户不存在或已删除");
            }
            return tenant;
        }
    }

    private TenantView toView(SysTenant tenant) {
        SysUser admin = null;
        if (tenant.getAdminUserId() != null) {
            try (TenantLineSuspension.Suspended ignored = TenantLineSuspension.suspended()) {
                admin = userMapper.selectById(tenant.getAdminUserId());
            }
        }
        String username = admin != null && tenant.getId().equals(admin.getTenantId())
                && Integer.valueOf(0).equals(admin.getStatus()) ? admin.getUsername() : null;
        return new TenantView(tenant.getId(), tenant.getCode(), tenant.getName(), tenant.getAdminUserId(),
                username, tenant.getStatus(), tenant.getCreateTime());
    }

    private SysTenant findByCreationRequest(String requestId) {
        try (TenantLineSuspension.Suspended ignored = TenantLineSuspension.suspended()) {
            return tenantMapper.selectOne(Wrappers.<SysTenant>lambdaQuery()
                    .eq(SysTenant::getCreationRequestId, requestId));
        }
    }

    private Long initializeAdministrator(SysTenant tenant, String username, String password, boolean newTenant) {
        Long tenantId = tenant.getId();
        if (userMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getTenantId, tenantId).eq(SysUser::getUsername, username)) > 0) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "该租户内账号已存在");
        }
        SysDept rootDept = new SysDept();
        rootDept.setTenantId(tenantId);
        rootDept.setParentId(0L);
        rootDept.setName(tenant.getName());
        rootDept.setCode("root");
        rootDept.setSort(0);
        rootDept.setStatus(0);
        if (!newTenant) {
            List<SysDept> roots = deptMapper.selectList(Wrappers.<SysDept>lambdaQuery()
                    .eq(SysDept::getTenantId, tenantId).eq(SysDept::getParentId, 0L)
                    .eq(SysDept::getCode, "root").eq(SysDept::getStatus, 0));
            if (roots.size() == 1) rootDept = roots.get(0);
            else rootDept.setCode("admin_" + UUID.randomUUID().toString().substring(0, 8));
        }
        boolean createdRoot = rootDept.getId() == null;
        if (createdRoot) deptMapper.insert(rootDept);

        SysUser admin = new SysUser();
        admin.setTenantId(tenantId);
        admin.setUsername(username);
        admin.setRealName(username);
        admin.setDeptId(rootDept.getId());
        admin.setStatus(0);
        admin.setIsAdmin(0);
        admin.setPassword(passwordEncoder.encode(password));
        userMapper.insert(admin);

        SysRole role = null;
        if (!newTenant) {
            role = roleMapper.selectOne(Wrappers.<SysRole>lambdaQuery()
                    .eq(SysRole::getTenantId, tenantId).eq(SysRole::getCode, ADMIN_ROLE_CODE));
            if (role != null && !Integer.valueOf(1).equals(role.getStatus())) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR, "租户管理员角色已停用，请先启用");
            }
        }
        if (role == null) {
            SysRole template = findAdminRoleTemplate();
            role = new SysRole();
            role.setTenantId(tenantId);
            role.setName(template.getName());
            role.setCode(template.getCode());
            role.setSort(template.getSort());
            role.setStatus(template.getStatus());
            role.setDataScope(template.getDataScope());
            role.setBuiltIn(false);
            role.setDescription("由租户 0 管理员角色模板复制");
            roleMapper.insert(role);

            List<SysRoleMenu> templateMenus;
            try (TenantLineSuspension.Suspended ignored = TenantLineSuspension.suspended()) {
                templateMenus = roleMenuMapper.selectList(Wrappers.<SysRoleMenu>lambdaQuery()
                        .eq(SysRoleMenu::getRoleId, template.getId()));
            }
            for (SysRoleMenu templateMenu : templateMenus) {
                SysRoleMenu copy = new SysRoleMenu();
                copy.setTenantId(tenantId);
                copy.setRoleId(role.getId());
                copy.setMenuId(templateMenu.getMenuId());
                roleMenuMapper.insert(copy);
            }
        }

        SysUserRole userRole = new SysUserRole();
        userRole.setTenantId(tenantId);
        userRole.setUserId(admin.getId());
        userRole.setRoleId(role.getId());
        userRoleMapper.insert(userRole);

        if (createdRoot) {
            rootDept.setLeaderId(admin.getId());
            if (deptMapper.updateById(rootDept) != 1) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR, "根部门管理员关联保存失败");
            }
        }
        return admin.getId();
    }

    private SysRole findAdminRoleTemplate() {
        try (TenantLineSuspension.Suspended ignored = TenantLineSuspension.suspended()) {
            SysRole role = roleMapper.selectOne(Wrappers.<SysRole>lambdaQuery()
                    .eq(SysRole::getTenantId, ROOT_TENANT_ID)
                    .eq(SysRole::getCode, ADMIN_ROLE_CODE)
                    .last("LIMIT 1"));
            if (role == null) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR, "缺少管理员角色模板，无法初始化租户");
            }
            if (!Integer.valueOf(1).equals(role.getStatus())) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR, "管理员角色模板已停用，无法初始化租户");
            }
            return role;
        }
    }

    private void recordStatusAudit(Long tenantId, String action, Integer oldStatus, Integer newStatus,
                                   LocalDateTime expireTime, String reason, String result) {
        LoginUser login = LoginUserHolder.get();
        Long operatorId = login == null || login.getUserId() == null ? 0L : login.getUserId();
        LocalDateTime now = LocalDateTime.now();
        SysTenantStatusAudit audit = new SysTenantStatusAudit();
        audit.setTenantId(tenantId);
        audit.setAction(action);
        audit.setOldStatus(oldStatus);
        audit.setNewStatus(newStatus);
        audit.setExpireTime(expireTime);
        audit.setReason(reason);
        audit.setOperatorId(operatorId);
        audit.setOccurredAt(now);
        audit.setResult(result);
        audit.setCreateTime(now);
        audit.setCreateBy(operatorId);
        audit.setUpdateTime(now);
        audit.setUpdateBy(operatorId);
        audit.setDeleted(0);
        audit.setVersion(0L);
        try (TenantLineSuspension.Suspended ignored = TenantLineSuspension.suspended()) {
            statusAuditMapper.insert(audit);
        }
    }

    /** 只有租户 0 的系统超级管理员可以维护租户。 */
    private void assertSuperAdmin() {
        LoginUser login = LoginUserHolder.get();
        if (login == null || !login.isSuperAdmin() || !Long.valueOf(ROOT_TENANT_ID).equals(login.getTenantId())) {
            // 权限语义独立于参数错误：HTTP 200 + body.code=403，保留可定位文案；
            // 不把“仅系统超级管理员”覆盖成通用“请求参数有误”。
            throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(), "仅系统超级管理员可以维护租户");
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static void validateAdminCredentials(String username, String password) {
        if (username.isEmpty() || username.length() > 50 || username.matches(".*\\s.*")) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "管理员账号不能为空、不能含空白且不超过 50 个字符");
        }
        if (password == null || password.length() < 6 || password.length() > 64) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "初始密码长度需为 6-64 个字符");
        }
    }
}

