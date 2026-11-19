package com.sw.ck.form.service.impl;

import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.form.api.vo.FormDefVO;
import com.sw.ck.form.api.vo.FormSnapshotVO;
import com.sw.ck.form.api.vo.FormSnapshotDetailVO;
import com.sw.ck.form.dynamic.FieldSpec;
import com.sw.ck.form.entity.FormDefEntity;
import com.sw.ck.form.service.FormDefService;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
/**
 * 表单定义管理服务实现——门面。
 *
 * <p>
 * 实现按职责拆分至同包协作类（行为与拆分前一致，公开契约不变）：
 * {@link FormDefDraftService}（草稿创建/元数据更新/改名/definition 保存）、
 * {@link FormDefPublishService}（首发布与同表新版本发布编排）、
 * {@link FormDefQueryService}（定义/快照/分页/可见性查询）、
 * {@link FormDefLifecycleService}（停用/启用/软删/发起可见范围/列表配置与
 * 生命周期审计）、{@link FormDefDefinitionValidator}（definition 解析校验
 * 唯一闸门：布局/类型/事件/集成/公式依赖/数据源绑定）。
 * {@code @Transactional} 事务边界保留在本门面的公开方法上（经 Spring 代理
 * 调用，语义与拆分前一致）；{@code publish} 沿用拆分前无事务标注的设计
 * （DDL 不可回滚，校验先行）。
 * </p>
 */
@Service
public class FormDefServiceImpl implements FormDefService {

    @Autowired
    private FormDefDraftService draftService;
    @Autowired
    private FormDefPublishService publishService;
    @Autowired
    private FormDefQueryService queryService;
    @Autowired
    private FormDefLifecycleService lifecycleService;
    @Autowired
    private FormDefDefinitionValidator definitionValidator;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FormDefVO createDraft(String formKey, String name, String logicalTableName, String description) {
        return draftService.createDraft(formKey, name, logicalTableName, description);
    }

    /** BF20261013-020：带表单类型的草稿创建（空白类型按业务表单）。 */
    public FormDefVO createDraft(String formKey, String name, String logicalTableName, String description,
            String formType) {
        return draftService.createDraft(formKey, name, logicalTableName, description, formType);
    }

    /** 应用中心：带表单类型与归属的草稿创建（审批意见表单不参与应用归属）。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public FormDefVO createDraft(String formKey, String name, String logicalTableName, String description,
            String formType, String appId, String dirId) {
        return draftService.createDraft(formKey, name, logicalTableName, description, formType, appId, dirId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FormDefVO updateDraft(String id, String name, String logicalTableName, String description) {
        return draftService.updateDraft(id, name, logicalTableName, description);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void renameFormDef(String id, String name) {
        draftService.renameFormDef(id, name);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveConfig(String formId, String definition) {
        draftService.saveConfig(formId, definition);
    }

    @Override
    public void publish(String formId) {
        publishService.publish(formId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publishNewVersion(String formId, String definition) {
        publishService.publishNewVersion(formId, definition);
    }

    @Override
    public FormDefVO getFormDef(String id) {
        return queryService.getFormDef(id);
    }

    @Override
    public FormDefVO getFormDefByKey(String formKey) {
        return queryService.getFormDefByKey(formKey);
    }

    @Override
    public String getDefinition(String formKey) {
        return queryService.getDefinition(formKey);
    }

    @Override
    public String getDefinitionById(String formId) {
        return queryService.getDefinitionById(formId);
    }

    @Override
    public String getSnapshotDefinitionByKey(String formKey, Integer formVersion) {
        return queryService.getSnapshotDefinitionByKey(formKey, formVersion);
    }

    @Override
    public PageResult<FormDefVO> pageFormDefs(PageParam pageParam, String keyword) {
        return queryService.pageFormDefs(pageParam, keyword);
    }

    /** BF20261013-020：按表单类型过滤的分页查询（formType 为空不过滤）。 */
    public PageResult<FormDefVO> pageFormDefs(PageParam pageParam, String keyword, String formType) {
        return queryService.pageFormDefs(pageParam, keyword, formType);
    }

    @Override
    public List<FormSnapshotVO> listSnapshots(String formId) {
        return queryService.listSnapshots(formId);
    }

    @Override
    public FormSnapshotDetailVO getSnapshot(String formId, Integer formVersion) {
        return queryService.getSnapshot(formId, formVersion);
    }

    @Override
    public FormDefEntity getById(String id) {
        return queryService.getById(id);
    }

    @Override
    public List<FormDefVO> listPublishedForCurrentUser() {
        return queryService.listPublishedForCurrentUser();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateVisibility(String formId, Collection<Long> userIds) {
        lifecycleService.updateVisibility(formId, userIds);
    }

    @Override
    public boolean isCurrentUserVisible(String formKey) {
        return queryService.isCurrentUserVisible(formKey);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDraft(String id) {
        lifecycleService.deleteDraft(id);
    }

    // ==================== I2：生命周期 / 列表配置 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void disable(String id, String reason) {
        lifecycleService.disable(id, reason);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void enable(String id, String reason) {
        lifecycleService.enable(id, reason);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveListConfig(String formId, String configJson) {
        lifecycleService.saveListConfig(formId, configJson);
    }

    @Override
    public String getListConfig(String formId) {
        return lifecycleService.getListConfig(formId);
    }

    @Override
    public Set<String> filterVisibleFormKeys(Collection<String> formKeys) {
        return queryService.filterVisibleFormKeys(formKeys);
    }

    // ==================== 包内契约保持（原包可见方法，委托校验闸门） ====================

    /**
     * 字段键 → 设计者可读显示名（R3b）：显隐规则校验失败时向设计者展示 label 而非内部键。
     */
    Map<String, String> collectFieldDisplay(String definitionJson) {
        return definitionValidator.collectFieldDisplay(definitionJson);
    }

    /**
     * 从 definition JSON 解析字段并执行全栈校验（唯一闸门，详见
     * {@link FormDefDefinitionValidator#parseAndValidateFieldsFromDefinition}）。
     */
    List<FieldSpec> parseAndValidateFieldsFromDefinition(String definitionJson) {
        return definitionValidator.parseAndValidateFieldsFromDefinition(definitionJson);
    }
}
