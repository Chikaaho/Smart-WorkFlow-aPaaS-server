package com.sw.ck.form.service;

import com.sw.ck.common.datascope.DataScopeFilter;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.form.api.dto.FormDataQueryRequest;
import com.sw.ck.form.dto.workspace.WorkspaceAggregateReq;
import com.sw.ck.form.dto.workspace.WorkspaceRowsReq;
import com.sw.ck.form.vo.FormDataRecordVO;
import com.sw.ck.form.vo.WorkspaceAggregateVO;
import com.sw.ck.form.vo.WorkspaceFieldsVO;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
/**
 * 表单数据查询服务——门面。
 *
 * <p>对已发布表单的动态宽表执行条件分页查询、工作台数据组件与单条记录查询。
 * 全部 SQL 经 {@link DynamicTableSql} 受控入口构造与执行：
 * 表名/列名在构造边界校验，{@code "deleted" = 0 AND "tenant_id" = ?} 由入口强制，
 * 值一律 {@code ?} 绑定，缺失即拒绝执行。</p>
 *
 * <h3>红线</h3>
 * <ul>
 *   <li>列名/表名过白名单（{@link DynamicTableSql#requireColumn(String, com.sw.ck.form.dynamic.FieldType)}）</li>
 *   <li>值一律 PreparedStatement ? 参数化绑定</li>
 *   <li>绝不 SELECT *，显式枚举列</li>
 *   <li>绝不复用 IPage（拦截器对裸 JdbcTemplate 失效）</li>
 * </ul>
 *
 * <p>
 * 实现按职责拆分至同包协作类（行为与拆分前一致，公开契约不变）：
 * {@link FormDataListQueryExecutor}（列表分页查询执行）、
 * {@link FormDataWorkspaceQueryService}（工作台聚合/列表/字段目录/记录数）、
 * {@link FormDataRecordDetailService}（单条详情/系统上下文读取/附件可读判定/
 * REFERENCE 存在性）、{@link FormDataFilterClauseBuilder}（过滤条件校验与 SQL
 * 子句构建）、{@link FormDataQuerySupport}（表名校验/definition 元数据/字段查看
 * 权限/数据范围解析）、{@link FilterClause} 与 {@link SubFieldMeta}（共享元数据
 * record）。本类无事务边界（纯读），公开方法签名与拆分前一致。
 * </p>
 */
@Service
public class FormDataQueryService {

    @Autowired
    private FormDataListQueryExecutor listQueryExecutor;
    @Autowired
    private FormDataWorkspaceQueryService workspaceQueryService;
    @Autowired
    private FormDataRecordDetailService recordDetailService;

    // ==================== 主入口 ====================

    /**
     * 查询表单数据（分页 + 过滤）。
     *
     * @param formKey 表单业务标识
     * @param request 查询请求（分页 + 过滤条件）
     * @return 分页结果
     */
    /**
     * 查询表单数据（分页 + 过滤），附带数据范围过滤（导出等有界消费方使用）。
     * <p>scopeFilter 为 none 时不追加条件；否则对 create_by 追加：
     * SELF → 等值；DEPT 家族 → create_by IN (SELECT id FROM sys_user WHERE dept_id IN (...))；
     * 空部门集 → 恒假（1 = 0）。</p>
     */
    public PageResult<Map<String, Object>> queryFormData(String formKey, FormDataQueryRequest request,
                                                         DataScopeFilter scopeFilter) {
        return listQueryExecutor.query(formKey, request, scopeFilter);
    }

    /**
     * 查询表单数据（分页 + 过滤），并允许调用方指定更大的单页上限（导出等有界消费方）。
     * <p>maxPageSize 仍为硬上限：请求超过该值时被钳制到该值。</p>
     */
    public PageResult<Map<String, Object>> queryFormData(String formKey, FormDataQueryRequest request,
                                                         DataScopeFilter scopeFilter, int maxPageSize) {
        return listQueryExecutor.query(formKey, request, scopeFilter, maxPageSize);
    }

    /**
     * 查询表单数据（分页 + 过滤），不做额外数据范围过滤（保持既有语义，租户边界恒生效）。
     */
    public PageResult<Map<String, Object>> queryFormData(String formKey, FormDataQueryRequest request) {
        return listQueryExecutor.query(formKey, request);
    }

    // ==================== 工作台数据组件（受控聚合 / 列表） ====================

    /**
     * 工作台字段目录：仅返回当前账号可见、可用于标量展示/聚合的字段。
     * 不返回子表、富文本、附件等字段，避免配置界面扩大读取范围。
     */
    public WorkspaceFieldsVO workspaceFields(String formKey) {
        return workspaceQueryService.workspaceFields(formKey);
    }

    /**
     * 工作台聚合查询：分组字段 + 1..5 个指标。
     * <p>沿用列表查询的可见性、租户、记录数据范围和字段查看权限；越权字段整请求拒绝，
     * 不静默剔除。所有列名经 {@link DynamicTableSql#requireColumn}，值全部参数绑定。</p>
     */
    public WorkspaceAggregateVO aggregateWorkspaceData(String formKey, WorkspaceAggregateReq req) {
        return workspaceQueryService.aggregateWorkspaceData(formKey, req);
    }

    /**
     * 工作台数据列表查询：显式列 + 过滤 + 排序 + 有界分页。
     * <p>列、过滤、排序字段均须当前账号可见；记录范围与普通表单列表同口径强制。</p>
     */
    public PageResult<FormDataRecordVO> queryWorkspaceRows(String formKey, WorkspaceRowsReq req) {
        return workspaceQueryService.queryWorkspaceRows(formKey, req);
    }

    /**
     * 工作台表单选择器的可见记录数：与列表查询同一可见性、租户与数据范围口径的 COUNT(*)。
     *
     * <p>仅供 sources 汇总展示；单个表单计数失败不阻断整个候选列表，按 0 记录并留日志。</p>
     */
    public long workspaceRecordCount(String formKey) {
        return workspaceQueryService.workspaceRecordCount(formKey);
    }

    // ==================== 单条记录详情查询 ====================

    /**
     * 查询单条记录详情（含子表行，供编辑回显）。
     * <p>
     * 返回主记录的 id + 审计列 + 业务列 + version（乐观锁用）+
     * 各 TABLE 子表的行列表。与列表查询不同，此处包含 version 字段。
     * </p>
     *
     * @param formKey  表单业务标识
     * @param recordId 主表记录 UUID
     * @return 主记录字段 + 子表行（key=TABLE字段逻辑名）
     */
    public Map<String, Object> getRecordDetail(String formKey, String recordId) {
        return recordDetailService.getRecordDetail(formKey, recordId);
    }

    /** 附件读取沿用用户详情投影；记录可读不代表能读取未关联或不可见字段中的文件。 */
    public boolean canCurrentUserAccessAttachment(String formKey, String recordId, String storageKey) {
        return recordDetailService.canCurrentUserAccessAttachment(formKey, recordId, storageKey);
    }

    /**
     * 系统上下文读取记录全量数据（P63 跨模块读接缝；流程引擎/调度侧无登录态）。
     * <p>
     * 与 {@link #getRecordDetail(String, String)} 的差异：显式租户、不做字段查看权限投影
     * 与数据范围（调用方为受信服务端上下文，对象可见性由流程侧既有授权承接）、
     * 子表行保留稳定行 {@code id}（来源行追溯）。
     * 裸 SQL 全部携带 {@code deleted=0 AND tenant_id=?}，表名仍走固定白名单校验。
     * </p>
     */
    public Map<String, Object> getRecordDetailForSystem(Long tenantId, String formKey, String recordId) {
        return recordDetailService.getRecordDetailForSystem(tenantId, formKey, recordId);
    }

    /**
     * 以与详情查询相同的租户、删除和表单可见性边界检查记录是否存在。
     * <p>
     * 该方法供跨模块 REFERENCE 契约校验使用；只查询常量列，不读取业务数据，
     * 记录 ID 作为参数绑定，物理表名仍经过固定白名单校验。
     * </p>
     */
    public boolean canCurrentUserAccessRecord(String formKey, String recordId) {
        return recordDetailService.canCurrentUserAccessRecord(formKey, recordId);
    }
}
