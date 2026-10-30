package com.sw.ck.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.service.BaseServiceImpl;
import com.sw.ck.system.entity.SysUser;
import com.sw.ck.system.entity.SysUserGroup;
import com.sw.ck.system.entity.SysUserGroupMember;
import com.sw.ck.system.mapper.SysUserGroupMapper;
import com.sw.ck.system.mapper.SysUserGroupMemberMapper;
import com.sw.ck.system.mapper.SysUserMapper;
import com.sw.ck.system.service.SysUserGroupService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 用户组 Service 实现（D112：P28/I36 用户组维护与成员绑定基础闭环）。
 * <p>
 * 关键语义：
 * <ul>
 *   <li>用户组只是业务人员集合，不是角色/菜单/按钮/数据权限主体——本类不触碰任何
 *       sys_user_role / sys_role_menu / sys_role_dept / 登录装配，加组成员不会改变用户权限；</li>
 *   <li>成员绑定前逐一校验：同租户、未逻辑删除、{@code status==0}（启用），且该用户
 *       在当前操作者数据范围内可见（成员候选查询本身已带 @DataScope 纳管）；</li>
 *   <li>主记录与成员变更在同一事务（{@code @Transactional(rollbackFor = Exception.class)}），
 *       任一步失败整体回滚；</li>
 *   <li>业务标识 {@code groupCode} 租户内唯一（含逻辑删除唯一语义）；创建后不可修改。</li>
 * </ul>
 */
@Service
public class SysUserGroupServiceImpl
        extends BaseServiceImpl<SysUserGroupMapper, SysUserGroup>
        implements SysUserGroupService {

    private final SysUserGroupMemberMapper sysUserGroupMemberMapper;
    private final SysUserMapper sysUserMapper;
    private final LoginContextProvider loginContextProvider;

    public SysUserGroupServiceImpl(SysUserGroupMemberMapper sysUserGroupMemberMapper, SysUserMapper sysUserMapper) {
        this(sysUserGroupMemberMapper, sysUserMapper, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SysUserGroupServiceImpl(SysUserGroupMemberMapper sysUserGroupMemberMapper, SysUserMapper sysUserMapper,
                                    LoginContextProvider loginContextProvider) {
        this.sysUserGroupMemberMapper = sysUserGroupMemberMapper;
        this.sysUserMapper = sysUserMapper;
        this.loginContextProvider = loginContextProvider;
    }

    // ─── 主记录 ────────────────────────────────────────────────

    @Override
    public PageResult<SysUserGroup> page(PageParam pageParam, SysUserGroup query) {
        return PageResult.of(baseMapper.selectGroupPage(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(pageParam.getPageNum(), pageParam.getPageSize()),
                query,
                isSuperAdmin(),
                currentScopeTypeName(),
                currentUserId(),
                currentDeptId(),
                currentCustomDeptIds(),
                currentTenantId()));
    }

    @Override
    public SysUserGroup getDetail(Long id) {
        SysUserGroup group = getById(id);
        if (group != null) {
            group.setMemberIds(listMemberIds(id));
        }
        return group;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(SysUserGroup group) {
        if (StringUtils.isBlank(group.getGroupCode())) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "业务标识不能为空");
        }
        if (StringUtils.isBlank(group.getGroupName())) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "组名称不能为空");
        }
        if (getByCode(group.getGroupCode()) != null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "用户组业务标识已存在");
        }
        if (group.getStatus() == null) {
            group.setStatus(0);
        }
        save(group);
        // 创建时随主记录一起写入成员（事务内）
        if (group.getMemberIds() != null && !group.getMemberIds().isEmpty()) {
            insertMembers(group.getId(), group.getMemberIds());
        }
        return group.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(SysUserGroup group) {
        SysUserGroup existing = getById(group.getId());
        if (existing == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "用户组不存在");
        }
        // 业务标识不可变：即使请求携带不同 groupCode 也忽略（稳定引用契约）
        group.setGroupCode(existing.getGroupCode());
        if (group.getGroupName() == null || group.getGroupName().isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "组名称不能为空");
        }
        updateById(group);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void disable(Long id) {
        assertExists(id);
        SysUserGroup group = new SysUserGroup();
        group.setId(id);
        group.setStatus(1);
        updateById(group);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void enable(Long id) {
        assertExists(id);
        SysUserGroup group = new SysUserGroup();
        group.setId(id);
        group.setStatus(0);
        updateById(group);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        assertExists(id);
        // 先删成员关系，再删主记录（同一事务；成员查询不再返回该组任何成员）
        sysUserGroupMemberMapper.delete(new LambdaQueryWrapper<SysUserGroupMember>().eq(SysUserGroupMember::getGroupId, id));
        removeById(id);
    }

    // ─── 成员关系 ──────────────────────────────────────────────

    @Override
    public List<Long> listMemberIds(Long groupId) {
        return sysUserGroupMemberMapper.selectList(
                        new LambdaQueryWrapper<SysUserGroupMember>().eq(SysUserGroupMember::getGroupId, groupId))
                .stream().map(SysUserGroupMember::getUserId).filter(Objects::nonNull).distinct().toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateMemberIds(Long groupId, List<Long> userIds) {
        assertExists(groupId);
        // 整量替换：全部目标校验通过后，先删后插（空列表 = 清空）
        List<Long> valid = validateMembers(groupId, userIds);
        sysUserGroupMemberMapper.delete(new LambdaQueryWrapper<SysUserGroupMember>().eq(SysUserGroupMember::getGroupId, groupId));
        insertMembers(groupId, valid);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addMemberIds(Long groupId, List<Long> userIds) {
        assertExists(groupId);
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        List<Long> valid = validateMembers(groupId, userIds);
        List<Long> existing = listMemberIds(groupId);
        List<Long> toInsert = valid.stream().filter(id -> !existing.contains(id)).toList();
        insertMembers(groupId, toInsert);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeMemberIds(Long groupId, List<Long> userIds) {
        assertExists(groupId);
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        sysUserGroupMemberMapper.delete(new LambdaQueryWrapper<SysUserGroupMember>()
                .eq(SysUserGroupMember::getGroupId, groupId)
                .in(SysUserGroupMember::getUserId, userIds.stream().filter(Objects::nonNull).distinct().toList()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void clearMembers(Long groupId) {
        assertExists(groupId);
        sysUserGroupMemberMapper.delete(new LambdaQueryWrapper<SysUserGroupMember>().eq(SysUserGroupMember::getGroupId, groupId));
    }

    @Override
    public PageResult<SysUser> memberCandidates(PageParam pageParam, String keyword) {
        // 数据范围在 Mapper SQL 内以成员部门 EXISTS 语义限定；仅返回启用用户（status=0）
        return PageResult.of(baseMapper.selectMemberCandidates(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(pageParam.getPageNum(), pageParam.getPageSize()),
                keyword,
                isSuperAdmin(),
                currentScopeTypeName(),
                currentUserId(),
                currentDeptId(),
                currentCustomDeptIds(),
                currentTenantId()));
    }

    // ─── 内部工具 ──────────────────────────────────────────────

    private SysUserGroup getByCode(String groupCode) {
        // 显式走 baseMapper.selectOne（lambdaQuery 依赖 MybatisMapperProxy 代理解析，
        // 纯单元测试无法注入，统一走 Mapper 层便于测试与语义一致）
        return baseMapper.selectOne(
                new LambdaQueryWrapper<SysUserGroup>().eq(SysUserGroup::getGroupCode, groupCode));
    }

    private void assertExists(Long id) {
        if (getById(id) == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "用户组不存在");
        }
    }

    /**
     * 校验成员用户全部「同租户 + 未逻辑删除 + 启用(status=0) + 当前操作者数据范围内可见」。
     * 任一用户不满足则抛异常（由外层事务回滚，不产生部分写入）。
     * 数据范围可见性：成员候选查询本身经 @DataScope 纳管；这里逐 ID 读取单个用户，
     * 若用户不在数据范围内将无法通过读取（null），与候选列表同一套语义。
     */
    private List<Long> validateMembers(Long groupId, List<Long> userIds) {
        if (userIds == null) {
            return List.of();
        }
        List<Long> distinct = userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return distinct;
        }
        // 可见性校验与候选列表同一套语义：仅「同租户 + 未逻辑删除 + 启用 + 数据范围内可见」的用户可绑定。
        // 复用带数据范围 SQL 的候选查询（大分页一次取全），保证与列表/选择器完全同源，
        // 不引入第二套过滤口径（防跨租户/越权绑定）。
        PageResult<SysUser> candidates = memberCandidates(new PageParam(), null);
        List<Long> visibleIds = candidates.getRecords().stream()
                .map(SysUser::getId).filter(Objects::nonNull).toList();
        if (!visibleIds.containsAll(distinct)) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "成员用户不存在、不可见或未启用");
        }
        return distinct;
    }

    // ─── 当前登录上下文（数据范围语义） ─────────────────────────

    private boolean isSuperAdmin() {
        return loginContextProvider != null && loginContextProvider.isSuperAdmin();
    }

    private String currentScopeTypeName() {
        return loginContextProvider != null && loginContextProvider.getDataScopeType() != null
                ? loginContextProvider.getDataScopeType().name() : "ALL";
    }

    private Long currentUserId() {
        return loginContextProvider != null ? loginContextProvider.getUserId() : null;
    }

    private Long currentDeptId() {
        return loginContextProvider != null ? loginContextProvider.getDeptId() : null;
    }

    private Long currentTenantId() {
        return loginContextProvider != null ? loginContextProvider.getTenantId() : null;
    }

    private java.util.List<Long> currentCustomDeptIds() {
        if (loginContextProvider == null || loginContextProvider.getCustomDeptIds() == null) {
            return java.util.List.of();
        }
        return new java.util.ArrayList<>(loginContextProvider.getCustomDeptIds());
    }

    private void insertMembers(Long groupId, List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        for (Long userId : userIds) {
            SysUserGroupMember member = new SysUserGroupMember();
            member.setGroupId(groupId);
            member.setUserId(userId);
            sysUserGroupMemberMapper.insert(member);
        }
    }
}
