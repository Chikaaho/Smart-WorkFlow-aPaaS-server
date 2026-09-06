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
     * 删除表单草稿（逻辑删除，仅 DRAFT 状态允许）。
     * <p>
     * 已发布表单已建物理宽表，禁止删除；需删除时先走后续的独立下线流程。
     *
     * @param id 表单 ID
     */
    void deleteDraft(String id);
}
