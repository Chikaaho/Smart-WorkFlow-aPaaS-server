package com.sw.ck.form.service;

import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.api.dto.FormSnapshotDTO;
import com.sw.ck.form.api.dto.FormSnapshotDetailDTO;
import com.sw.ck.form.entity.FormDefEntity;

import java.util.List;
import java.util.Collection;

/**
 * 表单定义管理服务。
 */
public interface FormDefService {

    /**
     * 创建表单草稿。
     * 只写 sw_form_def + sw_form_config，不碰物理表。
     *
     * @param formKey         表单业务标识
     * @param name            表单名称
     * @param logicalTableName 用户自定义逻辑表名
     * @param description     表单描述
     * @return 创建的草稿 DTO
     */
    FormDefDTO createDraft(String formKey, String name, String logicalTableName, String description);

    /**
     * 创建表单草稿（BF20261013-020：带表单类型）。
     *
     * @param formType BUSINESS 业务表单 / APPROVAL_COMMENT 审批意见表单；空白按 BUSINESS
     */
    FormDefDTO createDraft(String formKey, String name, String logicalTableName, String description,
            String formType);

    /**
     * 创建表单草稿（应用中心：带归属）。
     * <p>
     * appId 非空时校验目标为当前租户启用应用、目录属于该应用（校验失败不创建）；
     * APPROVAL_COMMENT 表单不参与应用归属，忽略传入归属。
     * </p>
     *
     * @param appId 归属应用 ID（可空=不归属）
     * @param dirId 归属目录 ID（可空=直接归属应用）
     */
    FormDefDTO createDraft(String formKey, String name, String logicalTableName, String description,
            String formType, String appId, String dirId);

    /**
     * 批量判定表单对当前用户是否可见（应用中心表单过滤用）。
     * <p>
     * 逐 formKey 语义与 {@link #isCurrentUserVisible(String)} 一致（未发布不可见、
     * 可见范围为空的定义语义、解析失败 fail-closed）；一次查询后内存判定。
     * </p>
     *
     * @param formKeys 待判定的 formKey 集合
     * @return 其中对当前用户可见的 formKey 子集
     */
    java.util.Set<String> filterVisibleFormKeys(java.util.Collection<String> formKeys);

    /**
     * 更新表单草稿元数据。
     *
     * @param id         表单 ID
     * @param name       表单名称
     * @param logicalTableName 逻辑表名
     * @param description 描述
     * @return 更新后的 DTO
     */
    FormDefDTO updateDraft(String id, String name, String logicalTableName, String description);

    /**
     * 仅改名（0.1.7 #4）：不动 formKey、定义与物理列；草稿/已发布/停用均可改。
     */
    void renameFormDef(String id, String name);

    /**
     * 保存表单配置（definition JSON）。
     *
     * @param formId     表单 ID
     * @param definition 表单配置 JSON
     */
    void saveConfig(String formId, String definition);

    /**
     * 发布表单草稿。
     * <p>
     * 字段定义从该表单已存的 {@code sw_form_config.definition.fields} 派生建表，
     * 不再接受外部 fieldSpecs 入参（definition 是唯一字段真源）。
     * </p>
     * <p>
     * 事务边界：
     * <ol>
     *   <li>加载 config.definition 并解析校验字段</li>
     *   <li>校验：逻辑表名 + 所有字段名列名过白名单</li>
     *   <li>创建动态宽表（DynamicTableManager.createFormTable）</li>
     *   <li>回填 physical_table_name / table_name / parent_table → status=PUBLISHED</li>
     *   <li>存一版 definition 到 sw_form_snapshot</li>
     * </ol>
     * DDL 在多数数据库不可回滚，因此校验先行，建表成功后改元数据，
     * 避免半成品状态。
     * </p>
     *
     * @param formId 表单 ID
     */
    void publish(String formId);

    /**
     * 在同一已发布表单上发布新版本。
     * <p>保留现有物理表与历史快照，仅为新增字段增量加列，并递增 formVersion。</p>
     */
    void publishNewVersion(String formId, String definition);

    /**
     * 根据 ID 获取表单定义 DTO。
     */
    FormDefDTO getFormDef(String id);

    /**
     * 根据 formKey 获取表单定义 DTO。
     */
    FormDefDTO getFormDefByKey(String formKey);

    /**
     * 根据 formKey 获取表单定义 JSON（渲染接口）。
     *
     * @param formKey 表单业务标识
     * @return definition JSON
     */
    String getDefinition(String formKey);

    /**
     * 根据 ID 获取表单定义 JSON（渲染接口）。
     *
     * @param formId 表单 ID
     * @return definition JSON
     */
    String getDefinitionById(String formId);

    /**
     * 分页查询表单定义列表。
     * <p>
     * 只返元数据（id/formKey/name/status/时间等），不返 definition JSON，
     * 避免列表体积过大。按 update_time 倒序排列。
     * 多租户/逻辑删除走 MyBatis-Plus 拦截器自动过滤，不手写条件。
     * </p>
     *
     * @param pageParam 分页参数（pageNum/pageSize）
     * @param keyword   可选，对 name 模糊搜索（LIKE），为空不加该条件
     * @return 分页结果，每行 FormDefDTO（含 id/formKey/name/status/createTime/updateTime 等）
     */
    PageResult<FormDefDTO> pageFormDefs(PageParam pageParam, String keyword);

    /**
     * 分页查询（BF20261013-020：按表单类型过滤）。
     *
     * @param formType 可选，BUSINESS / APPROVAL_COMMENT；为空不过滤
     */
    PageResult<FormDefDTO> pageFormDefs(PageParam pageParam, String keyword, String formType);

    /**
     * 根据 ID 获取表单定义实体。
     */
    FormDefEntity getById(String id);

    /** 查询当前租户内当前用户可见的已发布表单。 */
    List<FormDefDTO> listPublishedForCurrentUser();

    /** 更新业务发起可见范围；空集合表示当前租户内全部用户。 */
    void updateVisibility(String formId, Collection<Long> userIds);

    /** 判断当前用户是否可通过业务入口发起该已发布表单。 */
    boolean isCurrentUserVisible(String formKey);

    /**
     * 查询表单历史版本快照列表（版本号倒序）。
     * <p>
     * 只读：返回版本元数据（formVersion + createTime），不含 definition JSON。
     * 表单不存在抛 FORM_NOT_FOUND；无任何快照返回空列表。
     * 多租户/逻辑删除走 MyBatis-Plus 拦截器自动过滤。
     * </p>
     *
     * @param formId 表单 ID
     * @return 快照列表，按 formVersion 倒序
     */
    List<FormSnapshotDTO> listSnapshots(String formId);

    /**
     * 读取指定版本的快照详情（只读预览）。
     * <p>
     * 返回该版本的完整 definition JSON；表单或快照不存在抛对应错误码。
     * 不提供任何回写路径。
     * </p>
     *
     * @param formId 表单 ID
     * @param formVersion 快照版本号
     * @return 快照详情（含 definition）
     */
    FormSnapshotDetailDTO getSnapshot(String formId, Integer formVersion);

    /**
     * 删除表单（逻辑删除，0.1.7 #21 起任何状态均可删除）。
     * <p>
     * 物理表/快照/数据全部保留（deleted=1），列表与填报入口经 @TableLogic 自动过滤；
     * 删除粒度只区分表单（可软删）与流程定义（禁删）。
     *
     * @param id 表单 ID
     */
    void deleteDraft(String id);

    // ==================== I2 生命周期与列表配置 ====================

    /**
     * 停用已发布表单（I2，方向 §4.4）。
     * <p>
     * 停用后禁止新的绑定、填报、正式提交和流程发起；既有运行实例、审批查看
     * 和历史记录继续按冻结快照可读。动作落 sw_form_lifecycle_audit 审计。
     * </p>
     *
     * @param id     表单 ID
     * @param reason 停用原因（可空）
     */
    void disable(String id, String reason);

    /** 重新启用已停用表单（动作落审计）。 */
    void enable(String id, String reason);

    /**
     * 保存表单列表展示配置（I2，方向 §4.5）。
     *
     * @param formId     表单 ID（须已发布）
     * @param configJson 列表配置 JSON（columns/filters/defaultSort/actions）
     */
    void saveListConfig(String formId, String configJson);

    /**
     * 读取表单列表展示配置；未配置返回 null（消费方可继续用派生默认）。
     */
    String getListConfig(String formId);
}
