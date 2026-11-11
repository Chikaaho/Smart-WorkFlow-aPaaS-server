package com.sw.ck.system.service;

import com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.entity.SysDept;
import com.sw.ck.system.entity.SysPost;
import com.sw.ck.system.entity.SysRole;
import com.sw.ck.system.entity.SysUser;
import com.sw.ck.system.mapper.SysDeptMapper;
import com.sw.ck.system.mapper.SysPostMapper;
import com.sw.ck.system.mapper.SysRoleMapper;
import com.sw.ck.system.mapper.SysUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 人员批量导入导出（1.0.0 F1，P27）。
 * <p>
 * 语义（与表单数据导入一致）：
 * <ul>
 *   <li>模板：xlsx、带模板签名自定义属性；列固定为账号/姓名/手机/邮箱/部门编码/岗位编码/角色编码/状态，
 *       无密码列；签名不符（旧模板/别的模板）整体拒绝</li>
 *   <li>导入：管理员填一个统一初始密码，全部导入行共用；部门/岗位/角色按<b>编码</b>匹配，
 *       0 条或多条命中都是行级错误；账号已存在、文件内重复、必填缺失都是行级错误；
 *       任一行出错整批不入库（事务回滚，0 行落库）；只新增不覆盖</li>
 *   <li>手机号无唯一约束（已知限制）：文件内重复=行级错误；与库内已有重复=警告不拦截</li>
 *   <li>上限与表单导入一致：500 行 / 5MB</li>
 *   <li>导出：按当前列表筛选（数据范围由 {@code sysUserService.page} 的 @DataScope 拼接），
 *       列与模板一致并附部门名称等可读列；不导出密码</li>
 * </ul>
 */
@Slf4j
@Service
public class UserImportExportService {

    /** 模板签名自定义属性名。 */
    private static final String TEMPLATE_SIG_PROPERTY = "sw-user-import-template-signature";

    /** 模板签名（列集固定，结构变更时同步改此串即令旧模板失效）。 */
    private static final String TEMPLATE_SIGNATURE =
            "sys-user-import@v1|username|realName|phone|email|deptCode|postCodes|roleCodes|status";

    /** 模板/导出列头（与签名一一对应）。 */
    private static final String[] HEADERS = {
            "账号", "姓名", "手机", "邮箱", "部门编码", "岗位编码", "角色编码", "状态"};

    /** 导出附带的可读列。 */
    private static final String[] EXPORT_HEADERS = {
            "账号", "姓名", "手机", "邮箱", "部门编码", "部门名称", "岗位编码", "角色编码", "状态"};

    private static final int MAX_IMPORT_ROWS = 500;
    private static final long MAX_IMPORT_BYTES = 5 * 1024 * 1024;

    private final SysUserMapper userMapper;
    private final SysDeptMapper deptMapper;
    private final SysPostMapper postMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserService sysUserService;
    private final PlatformTransactionManager transactionManager;
    private final DataFormatter dataFormatter = new DataFormatter();

    public UserImportExportService(SysUserMapper userMapper,
                                   SysDeptMapper deptMapper,
                                   SysPostMapper postMapper,
                                   SysRoleMapper roleMapper,
                                   SysUserService sysUserService,
                                   PlatformTransactionManager transactionManager) {
        this.userMapper = userMapper;
        this.deptMapper = deptMapper;
        this.postMapper = postMapper;
        this.roleMapper = roleMapper;
        this.sysUserService = sysUserService;
        this.transactionManager = transactionManager;
    }

    // ==================== 模板 ====================

    /** 生成导入模板：单表头行 + 模板签名自定义属性（无密码列）。 */
    public byte[] downloadTemplate() {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("人员导入");
            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                header.createCell(i).setCellValue(HEADERS[i]);
                sheet.setColumnWidth(i, 16 * 256);
            }
            workbook.getProperties().getCustomProperties().addProperty(TEMPLATE_SIG_PROPERTY, TEMPLATE_SIGNATURE);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BaseException(CommonErrorCode.SYSTEM_ERROR, "模板生成失败，请重试");
        }
    }

    // ==================== 导入 ====================

    /**
     * 导入结果：整批原子，任一行出错 0 行落库。
     *
     * @param totalRows    文件数据行数
     * @param successCount 成功落库行数（整批成功 = totalRows，否则 0）
     * @param warnings     非阻断警告（库内手机号重复等）
     * @param errors       行级错误（行号 + 原因）
     */
    public record UserImportResult(int totalRows, int successCount, List<String> warnings,
                                   List<RowError> errors) {
    }

    public record RowError(int rowNum, String message) {
    }

    /**
     * 导入用户。
     *
     * @param file             xlsx 文件字节（≤5MB）
     * @param initialPassword  统一初始密码（非空；导入账号全部共用，首登必须改密）
     * @param requestedTenant  目标租户（超管必须显式指定；普通租户管理员忽略且仅本租户）
     */
    public UserImportResult importUsers(byte[] file, String initialPassword, Long requestedTenant) {
        LoginUser actor = LoginUserHolder.get();
        if (actor == null) {
            throw new BaseException(CommonErrorCode.UNAUTHORIZED, "登录状态已失效，请重新登录");
        }
        if (initialPassword == null || initialPassword.isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "请填写导入账号的统一初始密码");
        }
        if (file == null || file.length == 0) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "导入文件为空");
        }
        if (file.length > MAX_IMPORT_BYTES) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR,
                    "导入文件超过 5MB 上限，请压缩行数后重试");
        }
        Long tenantId = resolveImportTenant(actor, requestedTenant);

        // 解析 + 校验（只读；错误收集，不落库）
        ParsedSheet parsed = parseSheet(file);
        if (parsed.rows.isEmpty()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "Excel 文件没有数据行");
        }
        if (parsed.rows.size() > MAX_IMPORT_ROWS) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR,
                    "导入行数超限：当前 " + parsed.rows.size() + " 行，上限 " + MAX_IMPORT_ROWS + " 行");
        }

        List<RowError> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> fileUsernames = new HashSet<>();
        Map<String, List<String>> filePhones = new LinkedHashMap<>();

        try (TenantLineSuspension.Suspended ignored = TenantLineSuspension.suspended()) {
            Map<String, SysDept> deptByCode = uniqueByCode(
                    deptMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.<SysDept>lambdaQuery()
                            .eq(SysDept::getTenantId, tenantId).eq(SysDept::getDeleted, 0)),
                    SysDept::getCode, SysDept::getName, "部门编码", errors, warnings);
            List<SysPost> posts = postMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.<SysPost>lambdaQuery()
                    .eq(SysPost::getTenantId, tenantId).eq(SysPost::getDeleted, 0));
            List<SysRole> roles = roleMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.<SysRole>lambdaQuery()
                    .eq(SysRole::getTenantId, tenantId).eq(SysRole::getDeleted, 0)
                    .ne(SysRole::getCode, "superadmin"));

            List<ParsedRow> rows = parsed.rows;
            List<long[]> postLinks = new ArrayList<>();
            List<List<Long>> roleLinks = new ArrayList<>();
            List<SysUser> toCreate = new ArrayList<>();

            for (ParsedRow row : rows) {
                int n = row.rowNum();
                // 必填
                if (blank(row.username())) {
                    errors.add(new RowError(n, "账号不能为空"));
                    continue;
                }
                if (blank(row.realName())) {
                    errors.add(new RowError(n, "姓名不能为空"));
                    continue;
                }
                // 文件内账号重复
                if (!fileUsernames.add(row.username())) {
                    errors.add(new RowError(n, "账号在文件内重复: " + row.username()));
                    continue;
                }
                // 库内账号已存在（只新增，不覆盖）
                Long existed = userMapper.selectCount(com.baomidou.mybatisplus.core.toolkit.Wrappers.<SysUser>lambdaQuery()
                        .eq(SysUser::getTenantId, tenantId).eq(SysUser::getUsername, row.username())
                        .eq(SysUser::getDeleted, 0));
                if (existed != null && existed > 0) {
                    errors.add(new RowError(n, "账号已存在: " + row.username()));
                    continue;
                }
                // 手机号：文件内重复=错误；库内重复=警告
                if (!blank(row.phone())) {
                    List<String> dupRows = filePhones.computeIfAbsent(row.phone(), k -> new ArrayList<>());
                    if (!dupRows.isEmpty()) {
                        errors.add(new RowError(n, "手机号在文件内重复: " + row.phone()));
                        continue;
                    }
                    dupRows.add("row" + n);
                    Long phoneHits = userMapper.selectCount(com.baomidou.mybatisplus.core.toolkit.Wrappers.<SysUser>lambdaQuery()
                            .eq(SysUser::getTenantId, tenantId).eq(SysUser::getPhone, row.phone())
                            .eq(SysUser::getDeleted, 0));
                    if (phoneHits != null && phoneHits > 0) {
                        warnings.add("第 " + n + " 行手机号与库内已有用户重复（已知限制，不拦截导入）");
                    }
                }
                // 部门/岗位/角色按编码匹配：0/多命中行级错误
                SysDept dept = blank(row.deptCode()) ? null : deptByCode.get(row.deptCode());
                if (!blank(row.deptCode()) && dept == null) {
                    errors.add(new RowError(n, "部门编码未命中或多命中: " + row.deptCode()));
                    continue;
                }
                long[] postIds = matchCodes(row.postCodes(), posts, "岗位编码", n, errors);
                if (postIds == null) {
                    continue;
                }
                long[] roleIdsArr = matchCodes(row.roleCodes(), roles, "角色编码", n, errors);
                if (roleIdsArr == null) {
                    continue;
                }
                List<Long> roleIds = new ArrayList<>();
                for (long id : roleIdsArr) {
                    roleIds.add(id);
                }
                // 状态：启用/停用（默认启用）
                Integer status = parseStatus(row.status());
                if (status == null) {
                    errors.add(new RowError(n, "状态取值无效（应为 启用/停用）: " + row.status()));
                    continue;
                }

                SysUser user = new SysUser();
                user.setTenantId(tenantId);
                user.setUsername(row.username());
                user.setRealName(row.realName());
                user.setPhone(blank(row.phone()) ? null : row.phone());
                user.setEmail(blank(row.email()) ? null : row.email());
                user.setDeptId(dept == null ? null : dept.getId());
                user.setStatus(status);
                toCreate.add(user);
                postLinks.add(postIds);
                roleLinks.add(roleIds);
            }

            if (!errors.isEmpty()) {
                // 整批不入库：0 行落库（F1-2）
                log.info("用户导入整批拒绝: tenantId={} total={} errors={}", tenantId, rows.size(), errors.size());
                return new UserImportResult(rows.size(), 0, warnings, errors);
            }

            // 整批原子落库：任一行插入失败全部回滚；逐行走 createWithAssociations
            // （统一初始密码 + 首登强制改密由 create() 统一处理，F1-4）
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            Integer created = tx.execute(status -> {
                try {
                    for (int i = 0; i < toCreate.size(); i++) {
                        SysUser user = toCreate.get(i);
                        sysUserService.createWithAssociations(user, initialPassword,
                                roleLinks.get(i), toPostList(postLinks.get(i), user.getDeptId()));
                    }
                    return toCreate.size();
                } catch (Exception e) {
                    status.setRollbackOnly();
                    throw e;
                }
            });
            log.info("用户导入完成: tenantId={} total={} created={} warnings={}",
                    tenantId, rows.size(), created, warnings.size());
            return new UserImportResult(rows.size(), created == null ? 0 : created, warnings, List.of());
        }
    }

    // ==================== 导出 ====================

    /** 按筛选导出（数据范围由 sysUserService.page 的 @DataScope 拼接）；不导出密码。 */
    public byte[] exportUsers(UserPageQuery query) {
        List<SysUser> users = new ArrayList<>();
        long pageNum = 1;
        while (true) {
            PageParam param = new PageParam();
            param.setPageNum(pageNum);
            param.setPageSize(500);
            var page = (query == null
                    ? sysUserService.page(param)
                    : sysUserService.page(param, query));
            users.addAll(page.getRecords());
            if (page.getRecords().size() < 500) {
                break;
            }
            pageNum++;
        }

        // 不挂载 suspending：非超管由租户拦截器常规隔离，数据范围由 page() 的 @DataScope 拼接（F1-3）
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("人员导出");
            Row header = sheet.createRow(0);
            for (int i = 0; i < EXPORT_HEADERS.length; i++) {
                header.createCell(i).setCellValue(EXPORT_HEADERS[i]);
                sheet.setColumnWidth(i, 16 * 256);
            }
            int r = 1;
            for (SysUser user : users) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(nullSafe(user.getUsername()));
                row.createCell(1).setCellValue(nullSafe(user.getRealName()));
                row.createCell(2).setCellValue(nullSafe(user.getPhone()));
                row.createCell(3).setCellValue(nullSafe(user.getEmail()));
                String deptCode = "";
                String deptName = "";
                if (user.getDeptId() != null) {
                    SysDept dept = deptMapper.selectById(user.getDeptId());
                    if (dept != null) {
                        deptCode = nullSafe(dept.getCode());
                        deptName = nullSafe(dept.getName());
                    }
                }
                row.createCell(4).setCellValue(deptCode);
                row.createCell(5).setCellValue(deptName);
                row.createCell(6).setCellValue(joinPostCodes(user.getId()));
                row.createCell(7).setCellValue(joinRoleCodes(user.getId()));
                row.createCell(8).setCellValue(user.getStatus() != null && user.getStatus() == 1 ? "停用" : "启用");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BaseException(CommonErrorCode.SYSTEM_ERROR, "导出失败，请重试");
        }
    }

    // ==================== 内部 ====================

    /** 导入目标租户解析（F1-6）：超管必须显式指定；普通租户管理员固定本租户。 */
    private Long resolveImportTenant(LoginUser actor, Long requested) {
        if (actor.isSuperAdmin() && Long.valueOf(0).equals(actor.getTenantId())) {
            if (requested == null) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR, "请先选择目标租户");
            }
            return requested;
        }
        if (requested != null && !requested.equals(actor.getTenantId())) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "不能导入其他租户的人员");
        }
        return actor.getTenantId();
    }

    private record ParsedSheet(List<ParsedRow> rows) {
    }

    private record ParsedRow(int rowNum, String username, String realName, String phone,
                             String email, String deptCode, String postCodes,
                             String roleCodes, String status) {
    }

    /** 解析工作簿：签名校验 + 表头逐列校验 + 数据行收集；签名/表头不符整体拒绝。 */
    private ParsedSheet parseSheet(byte[] file) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(file))) {
            // 与 FormImportExportService 同款读取：属性缺失时 getProperty 返回 null，
            // NPE 落入下方统一 catch 按“无效模板”拒绝
            String signature = workbook.getProperties().getCustomProperties()
                    .getProperty(TEMPLATE_SIG_PROPERTY).getLpwstr();
            if (!TEMPLATE_SIGNATURE.equals(signature)) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR,
                        "导入模板无效：请下载最新模板后按原格式填写");
            }
            var sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(0);
            if (header == null) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR, "导入模板缺少表头行");
            }
            for (int i = 0; i < HEADERS.length; i++) {
                String cell = dataFormatter.formatCellValue(header.getCell(i));
                if (!HEADERS[i].equals(cell == null ? "" : cell.trim())) {
                    throw new BaseException(CommonErrorCode.PARAM_ERROR,
                            "导入模板表头与最新模板不一致（第 " + (i + 1) + " 列应为 " + HEADERS[i] + "）");
                }
            }
            List<ParsedRow> rows = new ArrayList<>();
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null || isBlankRow(row)) {
                    continue;
                }
                rows.add(new ParsedRow(r + 1,
                        cell(row, 0), cell(row, 1), cell(row, 2), cell(row, 3),
                        cell(row, 4), cell(row, 5), cell(row, 6), cell(row, 7)));
            }
            return new ParsedSheet(rows);
        } catch (BaseException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            log.warn("用户导入文件解析失败: {}", e.getMessage());
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "导入文件不是有效的最新模板 xlsx");
        }
    }

    private boolean isBlankRow(Row row) {
        for (int c = 0; c < HEADERS.length; c++) {
            if (!blank(cell(row, c))) {
                return false;
            }
        }
        return true;
    }

    private String cell(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            // 手机号等数字列按整数文本读，避免科学计数法
            return dataFormatter.formatCellValue(cell);
        }
        String value = cell.getCellType() == CellType.STRING
                ? cell.getStringCellValue() : dataFormatter.formatCellValue(cell);
        return value == null ? "" : value.trim();
    }

    /** 编码 → 实体唯一映射；0/多命中进错误清单（键缺失即未命中）。 */
    private <T> Map<String, T> uniqueByCode(List<T> entities, java.util.function.Function<T, String> code,
                                            java.util.function.Function<T, String> name,
                                            String label, List<RowError> errors, List<String> warnings) {
        Map<String, T> map = new LinkedHashMap<>();
        for (T entity : entities) {
            String key = code.apply(entity);
            if (key == null || key.isBlank()) {
                continue;
            }
            T prev = map.putIfAbsent(key, entity);
            if (prev != null) {
                warnings.add(label + "「" + key + "」存在多条同名记录（"
                        + name.apply(prev) + " / " + name.apply(entity) + "），导入将按未命中处理");
                map.remove(key);
            }
        }
        return map;
    }

    /** 按编码集合匹配（空格分隔）：任一未命中/多命中即行级错误；返回命中的 id 数组或 null（已记错误）。 */
    private <T> long[] matchCodes(String codesCell, List<T> entities, String label,
                                  int rowNum, List<RowError> errors) {
        if (blank(codesCell)) {
            return new long[0];
        }
        Map<String, List<T>> byCode = new LinkedHashMap<>();
        for (T entity : entities) {
            String code = codeOf(entity);
            if (code != null && !code.isBlank()) {
                byCode.computeIfAbsent(code, k -> new ArrayList<>()).add(entity);
            }
        }
        List<Long> ids = new ArrayList<>();
        for (String code : codesCell.split("[,;，；\\s]+")) {
            if (blank(code)) {
                continue;
            }
            List<T> hits = byCode.get(code);
            if (hits == null || hits.size() != 1) {
                errors.add(new RowError(rowNum, label + "未命中或多命中: " + code));
                return null;
            }
            ids.add(idOf(hits.get(0)));
        }
        return ids.stream().mapToLong(Long::longValue).toArray();
    }

    private String codeOf(Object entity) {
        if (entity instanceof SysPost post) {
            return post.getCode();
        }
        if (entity instanceof SysRole role) {
            return role.getCode();
        }
        return null;
    }

    private Long idOf(Object entity) {
        if (entity instanceof SysPost post) {
            return post.getId();
        }
        if (entity instanceof SysRole role) {
            return role.getId();
        }
        return null;
    }

    private Integer parseStatus(String status) {
        if (blank(status) || "启用".equals(status) || "1".equals(status)) {
            return 0;
        }
        if ("停用".equals(status) || "0".equals(status)) {
            return 1;
        }
        return null;
    }

    private String joinPostCodes(Long userId) {
        List<Long> postIds = sysUserService.listPosts(userId).stream()
                .map(row -> row.getPostId()).filter(Objects::nonNull).toList();
        if (postIds.isEmpty()) {
            return "";
        }
        return postMapper.selectBatchIds(postIds).stream()
                .map(SysPost::getCode).filter(code -> code != null && !code.isBlank())
                .reduce((a, b) -> a + " " + b).orElse("");
    }

    private String joinRoleCodes(Long userId) {
        List<Long> roleIds = sysUserService.listRoleIds(userId);
        if (roleIds == null || roleIds.isEmpty()) {
            return "";
        }
        return roleMapper.selectBatchIds(roleIds).stream()
                .map(SysRole::getCode).filter(code -> code != null && !code.isBlank())
                .reduce((a, b) -> a + " " + b).orElse("");
    }

    private List<UserPostAssociation> toPostList(long[] postIds, Long fallbackDeptId) {
        List<UserPostAssociation> list = new ArrayList<>();
        for (long postId : postIds) {
            list.add(new UserPostAssociation(postId, fallbackDeptId));
        }
        return list;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
