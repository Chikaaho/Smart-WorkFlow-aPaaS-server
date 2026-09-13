package com.sw.ck.form.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.api.dto.FormSnapshotDTO;
import com.sw.ck.form.api.dto.FormSnapshotDetailDTO;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.dynamic.ColumnValidation;
import com.sw.ck.form.dynamic.DynamicTableManager;
import com.sw.ck.form.dynamic.FieldSpec;
import com.sw.ck.form.dynamic.FieldType;
import com.sw.ck.form.dynamic.FormTableSpec;
import com.sw.ck.form.entity.*;
import com.sw.ck.form.mapper.FormConfigMapper;
import com.sw.ck.form.mapper.FormDefMapper;
import com.sw.ck.form.mapper.FormSnapshotMapper;
import com.sw.ck.form.service.FieldPermissionService;
import com.sw.ck.form.service.FormDefService;
import com.sw.ck.form.service.FormExtDataService;
import com.sw.ck.form.service.FormVisibilityRules;
import com.sw.ck.form.service.FormulaEngine;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 表单定义管理服务实现。
 */
@Service
public class FormDefServiceImpl implements FormDefService {

    private static final Logger log = LoggerFactory.getLogger(FormDefServiceImpl.class);

    private final FormDefMapper formDefMapper;
    private final FormConfigMapper formConfigMapper;
    private final FormSnapshotMapper formSnapshotMapper;
    private final DynamicTableManager dynamicTableManager;
    private final FormIdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final FormVisibilityRules visibilityRules;
    // ==== I2 新增协作对象（可空：兼容既有测试构造；仅新契约路径使用） ====
    private final FormulaEngine formulaEngine;
    private final FieldPermissionService fieldPermissionService;
    private final FormExtDataService extDataService;
    private final com.sw.ck.form.mapper.FormListConfigMapper listConfigMapper;
    private final com.sw.ck.form.mapper.FormLifecycleAuditMapper lifecycleAuditMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public FormDefServiceImpl(FormDefMapper formDefMapper,
                              FormConfigMapper formConfigMapper,
                              FormSnapshotMapper formSnapshotMapper,
                              DynamicTableManager dynamicTableManager,
                              FormIdGenerator idGenerator,
                              ObjectMapper objectMapper,
                              FormVisibilityRules visibilityRules,
                              FormulaEngine formulaEngine,
                              FieldPermissionService fieldPermissionService,
                              @org.springframework.beans.factory.annotation.Autowired(required = false)
                              FormExtDataService extDataService,
                              com.sw.ck.form.mapper.FormListConfigMapper listConfigMapper,
                              com.sw.ck.form.mapper.FormLifecycleAuditMapper lifecycleAuditMapper) {
        this.formDefMapper = formDefMapper;
        this.formConfigMapper = formConfigMapper;
        this.formSnapshotMapper = formSnapshotMapper;
        this.dynamicTableManager = dynamicTableManager;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
        this.visibilityRules = visibilityRules;
        this.formulaEngine = formulaEngine;
        this.fieldPermissionService = fieldPermissionService;
        this.extDataService = extDataService;
        this.listConfigMapper = listConfigMapper;
        this.lifecycleAuditMapper = lifecycleAuditMapper;
    }

    /** 兼容既有测试构造（无显隐规则注入时使用默认实现；I2 协作对象用默认/空实现）。 */
    public FormDefServiceImpl(FormDefMapper formDefMapper,
                              FormConfigMapper formConfigMapper,
                              FormSnapshotMapper formSnapshotMapper,
                              DynamicTableManager dynamicTableManager,
                              FormIdGenerator idGenerator,
                              ObjectMapper objectMapper) {
        this(formDefMapper, formConfigMapper, formSnapshotMapper, dynamicTableManager,
                idGenerator, objectMapper, new FormVisibilityRules(objectMapper),
                new FormulaEngine(), new FieldPermissionService(objectMapper),
                null, null, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FormDefDTO createDraft(String formKey, String name, String logicalTableName, String description) {
        // —— 校验唯一性（经租户拦截器按当前租户过滤；V83 后跨租户同名 formKey 合法） ——
        LambdaQueryWrapper<FormDefEntity> keyQuery = Wrappers.lambdaQuery(FormDefEntity.class)
                .eq(FormDefEntity::getFormKey, formKey);
        if (formDefMapper.selectCount(keyQuery) > 0) {
            throw new BaseException(FormErrorCode.FORM_KEY_DUPLICATE, "表单标识 '" + formKey + "' 已存在");
        }

        // —— 创建草稿 ——
        FormDefEntity entity = new FormDefEntity();
        entity.setId(idGenerator.generate());
        entity.setFormKey(formKey);
        entity.setName(name);
        entity.setLogicalTableName(logicalTableName);
        entity.setDescription(description);
        entity.setStatus(FormStatusEnum.DRAFT.getCode());
        entity.setFormVersion(1);
        entity.setCreateTime(LocalDateTime.now());
        entity.setUpdateTime(LocalDateTime.now());
        entity.setDeleted(0);
        entity.setVersion(0L);
        formDefMapper.insert(entity);

        // —— 创建空白 config ——
        FormConfigEntity config = new FormConfigEntity();
        config.setId(idGenerator.generate());
        config.setFormId(entity.getId());
        config.setDefinition("{}");
        config.setCreateTime(LocalDateTime.now());
        config.setUpdateTime(LocalDateTime.now());
        config.setDeleted(0);
        config.setVersion(0L);
        formConfigMapper.insert(config);

        log.info("Created form draft: id={}, formKey={}", entity.getId(), formKey);
        return toDTO(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FormDefDTO updateDraft(String id, String name, String logicalTableName, String description) {
        FormDefEntity entity = formDefMapper.selectById(id);
        if (entity == null) {
            throw new BaseException(FormErrorCode.FORM_NOT_FOUND);
        }
        if (!FormStatusEnum.DRAFT.getCode().equals(entity.getStatus())) {
            throw new BaseException(FormErrorCode.FORM_ALREADY_PUBLISHED, "已发布的表单不能修改元数据");
        }

        entity.setName(name);
        entity.setLogicalTableName(logicalTableName);
        entity.setDescription(description);
        entity.setUpdateTime(LocalDateTime.now());
        formDefMapper.updateById(entity);

        return toDTO(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveConfig(String formId, String definition) {
        FormDefEntity entity = formDefMapper.selectById(formId);
        if (entity == null) {
            throw new BaseException(FormErrorCode.FORM_NOT_FOUND);
        }

        // colSpan 是跨前端设计器、预览和填写页的持久化布局契约；保存入口也必须设防，
        // 不能只依赖浏览器控件限制。缺省值兼容历史 definition，异常值直接拒绝入库。
        validateLayoutDefinition(definition);

        // 字段类型权威校验（Z8/G14a）：config 保存层即拒绝未启用类型，与 publish 的
        // validFieldTypes 校验同口径——禁止类型不得依赖 publish 兜底。
        validateFieldTypes(definition);

        LambdaQueryWrapper<FormConfigEntity> query = Wrappers.lambdaQuery(FormConfigEntity.class)
                .eq(FormConfigEntity::getFormId, formId)
                .isNull(FormConfigEntity::getParentTable);
        FormConfigEntity config = formConfigMapper.selectOne(query);
        if (config == null) {
            // 创建新的 config 记录
            config = new FormConfigEntity();
            config.setId(idGenerator.generate());
            config.setFormId(formId);
            config.setDefinition(definition);
            config.setCreateTime(LocalDateTime.now());
            config.setUpdateTime(LocalDateTime.now());
            config.setDeleted(0);
            config.setVersion(0L);
            formConfigMapper.insert(config);
        } else {
            config.setDefinition(definition);
            config.setUpdateTime(LocalDateTime.now());
            formConfigMapper.updateById(config);
        }

        log.info("Saved form config: formId={}", formId);
    }

    @Override
    public void publish(String formId) {
        // —— Step 1: 加载并校验状态 ——
        FormDefEntity entity = formDefMapper.selectById(formId);
        if (entity == null) {
            throw new BaseException(FormErrorCode.FORM_NOT_FOUND);
        }
        if (!FormStatusEnum.DRAFT.getCode().equals(entity.getStatus())) {
            throw new BaseException(FormErrorCode.FORM_ALREADY_PUBLISHED, "表单已发布，不能重复发布");
        }

        // —— Step 2: 加载 config.definition 并解析校验字段（唯一字段真源） ——
        LambdaQueryWrapper<FormConfigEntity> configQuery = Wrappers.lambdaQuery(FormConfigEntity.class)
                .eq(FormConfigEntity::getFormId, formId)
                .isNull(FormConfigEntity::getParentTable);
        FormConfigEntity config = formConfigMapper.selectOne(configQuery);
        String definitionJson = (config != null) ? config.getDefinition() : "{}";

        List<FieldSpec> fields = parseAndValidateFieldsFromDefinition(definitionJson);

        // —— Step 2b: 显隐规则校验（v0.0.2：字段存在/op/logic/无环依赖） ——
        Set<String> fieldNames = fields.stream()
                .map(FieldSpec::getFieldName)
                .collect(java.util.stream.Collectors.toSet());
        visibilityRules.parseAndValidate(definitionJson, fieldNames);

        // —— Step 2c（I2）: 公式依赖 + 字段权限配置 + 外部数据源绑定校验 ——
        validateFormulaDependencies(definitionJson, fieldNames);
        fieldPermissionService.parse(definitionJson);
        validateDatasourceBindings(definitionJson);

        // —— Step 3: 校验字段名白名单（复用 ColumnValidation） ——
        if (entity.getLogicalTableName() != null && !entity.getLogicalTableName().isBlank()) {
            ColumnValidation.validateColumnName(entity.getLogicalTableName());
        }
        Set<String> columnNames = new HashSet<>();
        for (FieldSpec field : fields) {
            if (field.getFieldType() == FieldType.TABLE) continue; // 不产生列
            if (field.getFieldType() == FieldType.LABEL) continue; // v0.0.2：说明文字不产生列
            String physicalName = field.getPhysicalColumnName();
            try {
                ColumnValidation.validateColumnName(physicalName);
            } catch (IllegalArgumentException e) {
                throw new BaseException(FormErrorCode.INVALID_COLUMN_NAME,
                        "字段名不合法: '" + physicalName + "' — " + e.getMessage());
            }
            if (!columnNames.add(physicalName)) {
                throw new BaseException(FormErrorCode.DUPLICATE_COLUMN, "字段名重复: '" + physicalName + "'");
            }
        }

        // —— Step 4: 创建物理表（DDL 不可回滚，因此校验先行） ——
        FormTableSpec tableSpec = new FormTableSpec(false, fields);
        Map<String, String> subTableNameSink = new HashMap<>();
        String physicalTableName;
        try {
            physicalTableName = dynamicTableManager.createFormTable(tableSpec, subTableNameSink);
        } catch (Exception e) {
            log.error("Failed to create physical table for form: {}", formId, e);
            throw new BaseException(FormErrorCode.PUBLISH_FAILED, "创建动态宽表失败: " + e.getMessage());
        }
        log.info("Physical table created: {} for form: {}", physicalTableName, formId);

        // —— Step 4a: 序列化子表映射 ——
        String subTableMappingJson = null;
        if (!subTableNameSink.isEmpty()) {
            try {
                subTableMappingJson = objectMapper.writeValueAsString(subTableNameSink);
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize sub-table mapping, skip: {}", e.getMessage());
            }
        }

        // —— Step 5: 回填表单元数据 + sw_form_config 的 table_name/parent_table ——
        entity.setPhysicalTableName(physicalTableName);
        entity.setStatus(FormStatusEnum.PUBLISHED.getCode());
        entity.setFormVersion(entity.getFormVersion() == null ? 1 : entity.getFormVersion() + 1);
        entity.setSubTableMapping(subTableMappingJson);
        entity.setUpdateTime(LocalDateTime.now());
        formDefMapper.updateById(entity);

        // 回填主表单的 sw_form_config 行：table_name = physicalTableName
        if (config != null) {
            config.setTableName(physicalTableName);
            // parent_table 对主表单留空
            formConfigMapper.updateById(config);
        }

        // 回填每个 TABLE 子表的 sw_form_config 行：parent_table = 主表 table_name
        for (Map.Entry<String, String> entry : subTableNameSink.entrySet()) {
            String subFieldName = entry.getKey();
            String subTableName = entry.getValue();
            FormConfigEntity subConfig = new FormConfigEntity();
            subConfig.setId(idGenerator.generate());
            subConfig.setFormId(formId);
            subConfig.setTableName(subTableName);
            subConfig.setParentTable(physicalTableName);
            // 子表 definition = 该 TABLE 字段的 subFields 序列化
            String subDefinition = buildSubTableDefinition(fields, subFieldName);
            subConfig.setDefinition(subDefinition);
            subConfig.setCreateTime(LocalDateTime.now());
            subConfig.setUpdateTime(LocalDateTime.now());
            subConfig.setDeleted(0);
            subConfig.setVersion(0L);
            formConfigMapper.insert(subConfig);
        }

        // —— Step 6: 存快照 ——
        FormSnapshotEntity snapshot = new FormSnapshotEntity();
        snapshot.setId(idGenerator.generate());
        snapshot.setFormId(formId);
        snapshot.setFormVersion(entity.getFormVersion());
        snapshot.setDefinition(definitionJson);
        snapshot.setCreateTime(LocalDateTime.now());
        snapshot.setUpdateTime(LocalDateTime.now());
        snapshot.setDeleted(0);
        snapshot.setVersion(0L);
        formSnapshotMapper.insert(snapshot);

        log.info("Form published: id={}, formKey={}, physicalTable={}, version={}",
                formId, entity.getFormKey(), physicalTableName, entity.getFormVersion());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publishNewVersion(String formId, String definition) {
        FormDefEntity entity = formDefMapper.selectById(formId);
        if (entity == null) {
            throw new BaseException(FormErrorCode.FORM_NOT_FOUND);
        }
        if (!FormStatusEnum.PUBLISHED.getCode().equals(entity.getStatus())
                && !FormStatusEnum.DISABLED.getCode().equals(entity.getStatus())) {
            throw new BaseException(FormErrorCode.FORM_NOT_PUBLISHED,
                    "仅已发布或已停用表单可发布新版本");
        }
        if (entity.getPhysicalTableName() == null || entity.getPhysicalTableName().isBlank()) {
            throw new BaseException(FormErrorCode.PUBLISH_FAILED, "表单缺少既有物理表，不能发布新版本");
        }

        validateLayoutDefinition(definition);
        List<FieldSpec> fields = parseAndValidateFieldsFromDefinition(definition);
        Set<String> fieldNames = fields.stream()
                .map(FieldSpec::getFieldName)
                .collect(java.util.stream.Collectors.toSet());
        visibilityRules.parseAndValidate(definition, fieldNames);
        validateFormulaDependencies(definition, fieldNames);
        fieldPermissionService.parse(definition);
        validateDatasourceBindings(definition);

        // 先完成全部定义校验，再执行不可回滚的增量 DDL；新版本只允许复用原表。
        for (FieldSpec field : fields) {
            if (field.getFieldType() == FieldType.TABLE) {
                throw new BaseException(FormErrorCode.DEFINITION_INVALID,
                        "新版本暂不支持新增或变更 TABLE 字段");
            }
            if (field.getFieldType() != FieldType.LABEL) {
                dynamicTableManager.addColumn(entity.getPhysicalTableName(), field);
            }
        }

        FormConfigEntity config = formConfigMapper.selectOne(
                Wrappers.lambdaQuery(FormConfigEntity.class)
                        .eq(FormConfigEntity::getFormId, formId)
                        .isNull(FormConfigEntity::getParentTable));
        if (config == null) {
            throw new BaseException(FormErrorCode.CONFIG_NOT_FOUND, "表单配置不存在，不能发布新版本");
        }
        config.setDefinition(definition);
        config.setUpdateTime(LocalDateTime.now());
        formConfigMapper.updateById(config);

        int newVersion = entity.getFormVersion() == null ? 1 : entity.getFormVersion() + 1;
        entity.setFormVersion(newVersion);
        entity.setStatus(FormStatusEnum.PUBLISHED.getCode());
        entity.setUpdateTime(LocalDateTime.now());
        formDefMapper.updateById(entity);

        FormSnapshotEntity snapshot = new FormSnapshotEntity();
        snapshot.setId(idGenerator.generate());
        snapshot.setFormId(formId);
        snapshot.setFormVersion(newVersion);
        snapshot.setDefinition(definition);
        snapshot.setCreateTime(LocalDateTime.now());
        snapshot.setUpdateTime(LocalDateTime.now());
        snapshot.setDeleted(0);
        snapshot.setVersion(0L);
        formSnapshotMapper.insert(snapshot);

        log.info("Form new version published: id={}, formKey={}, physicalTable={}, version={}",
                formId, entity.getFormKey(), entity.getPhysicalTableName(), newVersion);
    }

    @Override
    public FormDefDTO getFormDef(String id) {
        FormDefEntity entity = formDefMapper.selectById(id);
        return entity != null ? toDTO(entity) : null;
    }

    @Override
    public FormDefDTO getFormDefByKey(String formKey) {
        LambdaQueryWrapper<FormDefEntity> query = Wrappers.lambdaQuery(FormDefEntity.class)
                .eq(FormDefEntity::getFormKey, formKey);
        FormDefEntity entity = formDefMapper.selectOne(query);
        return entity != null ? toDTO(entity) : null;
    }

    @Override
    public String getDefinition(String formKey) {
        LambdaQueryWrapper<FormDefEntity> defQuery = Wrappers.lambdaQuery(FormDefEntity.class)
                .eq(FormDefEntity::getFormKey, formKey);
        FormDefEntity entity = formDefMapper.selectOne(defQuery);
        if (entity == null) {
            return null;
        }
        LambdaQueryWrapper<FormConfigEntity> configQuery = Wrappers.lambdaQuery(FormConfigEntity.class)
                .eq(FormConfigEntity::getFormId, entity.getId())
                .isNull(FormConfigEntity::getParentTable);
        FormConfigEntity config = formConfigMapper.selectOne(configQuery);
        return config != null ? config.getDefinition() : null;
    }

    @Override
    public String getDefinitionById(String formId) {
        LambdaQueryWrapper<FormConfigEntity> configQuery = Wrappers.lambdaQuery(FormConfigEntity.class)
                .eq(FormConfigEntity::getFormId, formId)
                .isNull(FormConfigEntity::getParentTable);
        FormConfigEntity config = formConfigMapper.selectOne(configQuery);
        return config != null ? config.getDefinition() : null;
    }

    @Override
    public PageResult<FormDefDTO> pageFormDefs(PageParam pageParam, String keyword) {
        // 构造查询条件：按 update_time 倒序 + 可选 name 模糊搜索
        LambdaQueryWrapper<FormDefEntity> wrapper = Wrappers.lambdaQuery(FormDefEntity.class)
                .orderByDesc(FormDefEntity::getUpdateTime);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(FormDefEntity::getName, keyword.trim());
        }
        // 走 MyBatis-Plus selectPage → @TableLogic / TenantLineHandler 自动生效
        PageResult<FormDefEntity> entityPage = formDefMapper.selectPage(pageParam, wrapper);
        // 转换 Entity → DTO
        List<FormDefDTO> dtoList = entityPage.getRecords().stream()
                .map(this::toDTO)
                .toList();
        PageResult<FormDefDTO> result = new PageResult<>();
        result.setRecords(dtoList);
        result.setTotal(entityPage.getTotal());
        result.setPageNum(entityPage.getPageNum());
        result.setPageSize(entityPage.getPageSize());
        return result;
    }

    @Override
    public List<FormSnapshotDTO> listSnapshots(String formId) {
        FormDefEntity entity = formDefMapper.selectById(formId);
        if (entity == null) {
            throw new BaseException(FormErrorCode.FORM_NOT_FOUND);
        }
        LambdaQueryWrapper<FormSnapshotEntity> query = Wrappers.lambdaQuery(FormSnapshotEntity.class)
                .eq(FormSnapshotEntity::getFormId, formId)
                .orderByDesc(FormSnapshotEntity::getFormVersion);
        return formSnapshotMapper.selectList(query).stream()
                .map(s -> FormSnapshotDTO.builder()
                        .formVersion(s.getFormVersion())
                        .createTime(s.getCreateTime())
                        .build())
                .toList();
    }

    @Override
    public FormSnapshotDetailDTO getSnapshot(String formId, Integer formVersion) {
        FormDefEntity entity = formDefMapper.selectById(formId);
        if (entity == null) {
            throw new BaseException(FormErrorCode.FORM_NOT_FOUND);
        }
        LambdaQueryWrapper<FormSnapshotEntity> query = Wrappers.lambdaQuery(FormSnapshotEntity.class)
                .eq(FormSnapshotEntity::getFormId, formId)
                .eq(FormSnapshotEntity::getFormVersion, formVersion);
        FormSnapshotEntity snapshot = formSnapshotMapper.selectOne(query);
        if (snapshot == null) {
            throw new BaseException(FormErrorCode.SNAPSHOT_NOT_FOUND,
                    "表单版本快照不存在: version=" + formVersion);
        }
        return FormSnapshotDetailDTO.builder()
                .formVersion(snapshot.getFormVersion())
                .createTime(snapshot.getCreateTime())
                .definition(snapshot.getDefinition())
                .build();
    }

    @Override
    public FormDefEntity getById(String id) {
        return formDefMapper.selectById(id);
    }

    @Override
    public List<FormDefDTO> listPublishedForCurrentUser() {
        PageParam pageParam = new PageParam();
        pageParam.setPageNum(1);
        pageParam.setPageSize(200);
        LambdaQueryWrapper<FormDefEntity> wrapper = Wrappers.<FormDefEntity>lambdaQuery()
                .eq(FormDefEntity::getStatus, FormStatusEnum.PUBLISHED.getCode())
                .orderByDesc(FormDefEntity::getUpdateTime);
        PageResult<FormDefEntity> page = formDefMapper.selectPage(pageParam, wrapper);
        return page.getRecords().stream()
                .filter(this::isVisibleToCurrentUser)
                .map(this::toDTO)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateVisibility(String formId, Collection<Long> userIds) {
        FormDefEntity entity = formDefMapper.selectById(formId);
        if (entity == null) {
            throw new BaseException(FormErrorCode.FORM_NOT_FOUND);
        }
        List<Long> normalized = userIds == null ? List.of() : userIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (normalized.stream().anyMatch(id -> id <= 0)) {
            throw new BaseException(com.sw.ck.common.exception.CommonErrorCode.PARAM_ERROR.getCode(),
                    "可见范围用户 ID 无效");
        }
        try {
            entity.setVisibilityScope(normalized.isEmpty()
                    ? null
                    : objectMapper.writeValueAsString(Map.of("userIds", normalized)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化表单可见范围失败", e);
        }
        formDefMapper.updateById(entity);
        log.info("Updated form visibility: formId={}, userCount={}", formId, normalized.size());
    }

    @Override
    public boolean isCurrentUserVisible(String formKey) {
        FormDefEntity entity = formDefMapper.selectOne(Wrappers.<FormDefEntity>lambdaQuery()
                .eq(FormDefEntity::getFormKey, formKey));
        return entity != null && isVisibleToCurrentUser(entity);
    }

    private boolean isVisibleToCurrentUser(FormDefEntity entity) {
        if (!FormStatusEnum.PUBLISHED.getCode().equals(entity.getStatus())
                && !FormStatusEnum.DISABLED.getCode().equals(entity.getStatus())) {
            return false;
        }
        LoginUser loginUser = LoginUserHolder.get();
        if (loginUser == null || loginUser.getUserId() == null) {
            return false;
        }
        String scope = entity.getVisibilityScope();
        if (scope == null || scope.isBlank()) {
            return true;
        }
        try {
            JsonNode root = objectMapper.readTree(scope);
            JsonNode ids = root == null ? null : root.get("userIds");
            if (ids == null || !ids.isArray()) {
                return false;
            }
            for (JsonNode id : ids) {
                if (loginUser.getUserId().toString().equals(id.asText())) {
                    return true;
                }
            }
            return false;
        } catch (JsonProcessingException e) {
            log.error("Invalid form visibility scope: formKey={}", entity.getFormKey(), e);
            return false;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDraft(String id) {
        FormDefEntity entity = formDefMapper.selectById(id);
        if (entity == null) {
            throw new BaseException(FormErrorCode.FORM_NOT_FOUND);
        }
        if (!"DRAFT".equals(entity.getStatus())) {
            auditLifecycle(id, "DELETE_DENIED", "状态 " + entity.getStatus() + " 不允许删除");
            throw new BaseException(FormErrorCode.FORM_ALREADY_PUBLISHED.getCode(),
                    "已发布/已停用表单不能删除");
        }
        formDefMapper.deleteById(id);
        auditLifecycle(id, "DELETE", "草稿删除");
        log.info("Form draft deleted: id={}", id);
    }

    // ==================== I2：生命周期 / 列表配置 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void disable(String id, String reason) {
        FormDefEntity entity = formDefMapper.selectById(id);
        if (entity == null) {
            throw new BaseException(FormErrorCode.FORM_NOT_FOUND);
        }
        if (!FormStatusEnum.PUBLISHED.getCode().equals(entity.getStatus())) {
            auditLifecycle(id, "DISABLE_DENIED", "状态 " + entity.getStatus() + " 不允许停用");
            throw new BaseException(FormErrorCode.FORM_ALREADY_DRAFT.getCode(),
                    "仅已发布表单可停用");
        }
        entity.setStatus(FormStatusEnum.DISABLED.getCode());
        entity.setUpdateTime(LocalDateTime.now());
        formDefMapper.updateById(entity);
        auditLifecycle(id, "DISABLE", reason);
        log.info("Form disabled: id={}, reason={}", id, reason);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void enable(String id, String reason) {
        FormDefEntity entity = formDefMapper.selectById(id);
        if (entity == null) {
            throw new BaseException(FormErrorCode.FORM_NOT_FOUND);
        }
        if (!FormStatusEnum.DISABLED.getCode().equals(entity.getStatus())) {
            auditLifecycle(id, "ENABLE_DENIED", "状态 " + entity.getStatus() + " 不允许启用");
            throw new BaseException(FormErrorCode.FORM_ALREADY_PUBLISHED.getCode(),
                    "仅已停用表单可启用");
        }
        entity.setStatus(FormStatusEnum.PUBLISHED.getCode());
        entity.setUpdateTime(LocalDateTime.now());
        formDefMapper.updateById(entity);
        auditLifecycle(id, "ENABLE", reason);
        log.info("Form enabled: id={}, reason={}", id, reason);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveListConfig(String formId, String configJson) {
        FormDefEntity entity = formDefMapper.selectById(formId);
        if (entity == null) {
            throw new BaseException(FormErrorCode.FORM_NOT_FOUND);
        }
        validateListConfig(configJson);
        LambdaQueryWrapper<com.sw.ck.form.entity.FormListConfigEntity> query =
                Wrappers.lambdaQuery(com.sw.ck.form.entity.FormListConfigEntity.class)
                        .eq(com.sw.ck.form.entity.FormListConfigEntity::getFormId, formId);
        com.sw.ck.form.entity.FormListConfigEntity config = listConfigMapper.selectOne(query);
        java.time.LocalDateTime now = LocalDateTime.now();
        if (config == null) {
            config = new com.sw.ck.form.entity.FormListConfigEntity();
            config.setId(idGenerator.generate());
            config.setFormId(formId);
            config.setConfigJson(configJson);
            config.setCreateTime(now);
            config.setUpdateTime(now);
            config.setDeleted(0);
            config.setVersion(0L);
            listConfigMapper.insert(config);
        } else {
            config.setConfigJson(configJson);
            config.setUpdateTime(now);
            listConfigMapper.updateById(config);
        }
        log.info("Saved form list config: formId={}", formId);
    }

    @Override
    public String getListConfig(String formId) {
        LambdaQueryWrapper<com.sw.ck.form.entity.FormListConfigEntity> query =
                Wrappers.lambdaQuery(com.sw.ck.form.entity.FormListConfigEntity.class)
                        .eq(com.sw.ck.form.entity.FormListConfigEntity::getFormId, formId);
        com.sw.ck.form.entity.FormListConfigEntity config = listConfigMapper.selectOne(query);
        return config != null ? config.getConfigJson() : null;
    }

    /**
     * 列表配置 JSON 结构校验（服务端权威；无权字段不得借配置进入响应）。
     * 允许形状：{"columns":[{"name":..,"label":..,"width":..}..],
     *            "filters":[{"name":..,"op":..}..],
     *            "defaultSort":{"name":..,"desc":true|false},
     *            "actions":["view","edit","delete","export"...]}
     */
    private void validateListConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            throw new BaseException(FormErrorCode.LIST_CONFIG_INVALID, "列表配置不能为空");
        }
        try {
            JsonNode root = objectMapper.readTree(configJson);
            if (root == null || !root.isObject()) {
                throw new BaseException(FormErrorCode.LIST_CONFIG_INVALID, "列表配置必须是 JSON 对象");
            }
            JsonNode columns = root.get("columns");
            if (columns == null || !columns.isArray() || columns.isEmpty()) {
                throw new BaseException(FormErrorCode.LIST_CONFIG_INVALID, "columns 必须是非空数组");
            }
            Set<String> seen = new HashSet<>();
            for (JsonNode col : columns) {
                String name = col.path("name").asText("");
                if (name.isBlank() || !seen.add(name)) {
                    throw new BaseException(FormErrorCode.LIST_CONFIG_INVALID,
                            "columns 存在缺失或重复的字段名: '" + name + "'");
                }
            }
            JsonNode actions = root.get("actions");
            if (actions != null) {
                if (!actions.isArray()) {
                    throw new BaseException(FormErrorCode.LIST_CONFIG_INVALID, "actions 必须是数组");
                }
                Set<String> allowed = Set.of("view", "edit", "delete", "export", "import", "start-flow");
                for (JsonNode action : actions) {
                    if (!allowed.contains(action.asText())) {
                        throw new BaseException(FormErrorCode.LIST_CONFIG_INVALID,
                                "不允许的列表动作: '" + action.asText() + "'");
                    }
                }
            }
            JsonNode filters = root.get("filters");
            if (filters != null && !filters.isArray()) {
                throw new BaseException(FormErrorCode.LIST_CONFIG_INVALID, "filters 必须是数组");
            }
            JsonNode sort = root.get("defaultSort");
            if (sort != null && !sort.isObject()) {
                throw new BaseException(FormErrorCode.LIST_CONFIG_INVALID, "defaultSort 必须是对象");
            }
        } catch (JsonProcessingException e) {
            throw new BaseException(FormErrorCode.LIST_CONFIG_INVALID, "列表配置 JSON 解析失败");
        }
    }

    private void auditLifecycle(String formId, String action, String reason) {
        if (lifecycleAuditMapper == null) {
            log.info("Form lifecycle (audit mapper absent): formId={}, action={}, reason={}",
                    formId, action, reason);
            return;
        }
        try {
            com.sw.ck.form.entity.FormLifecycleAuditEntity audit =
                    new com.sw.ck.form.entity.FormLifecycleAuditEntity();
            audit.setId(idGenerator.generate());
            audit.setFormId(formId);
            audit.setAction(action);
            audit.setReason(reason);
            var loginUser = com.sw.ck.security.holder.LoginUserHolder.get();
            audit.setOperatorId(loginUser == null ? null : loginUser.getUserId());
            audit.setTenantId(loginUser == null || loginUser.getTenantId() == null
                    ? 0L : loginUser.getTenantId());
            audit.setDeleted(0);
            audit.setVersion(0L);
            java.time.LocalDateTime now = LocalDateTime.now();
            audit.setCreateTime(now);
            audit.setUpdateTime(now);
            lifecycleAuditMapper.insert(audit);
        } catch (Exception e) {
            log.error("Failed to write lifecycle audit: formId={}, action={}", formId, action, e);
        }
    }

    /**
     * I2 发布校验：FORMULA 依赖（未知字段/循环）与 fieldPermissions 结构。
     */
    private void validateFormulaDependencies(String definitionJson, Set<String> fieldNames) {
        try {
            JsonNode root = objectMapper.readTree(definitionJson);
            JsonNode fieldsArray = root.isArray() ? root : root.get("fields");
            if (fieldsArray == null || !fieldsArray.isArray()) {
                return;
            }
            Map<String, String> expressions = new LinkedHashMap<>();
            for (JsonNode field : fieldsArray) {
                if ("FORMULA".equals(field.path("type").asText())) {
                    expressions.put(field.path("name").asText(), field.path("expression").asText());
                }
            }
            if (!expressions.isEmpty()) {
                formulaEngine.validateDependencies(expressions, fieldNames);
            }
        } catch (JsonProcessingException e) {
            throw new BaseException(FormErrorCode.DEFINITION_INVALID, "definition JSON 解析失败");
        }
    }

    /**
     * I2 发布校验：DATASOURCE 字段的 dsBinding 必须命中启用中的查询契约。
     */
    private void validateDatasourceBindings(String definitionJson) {
        try {
            JsonNode root = objectMapper.readTree(definitionJson);
            JsonNode fieldsArray = root.isArray() ? root : root.get("fields");
            if (fieldsArray == null || !fieldsArray.isArray()) {
                return;
            }
            boolean hasBinding = false;
            for (JsonNode field : fieldsArray) {
                JsonNode binding = field.path("dsBinding");
                if (!binding.isObject()) {
                    continue;
                }
                hasBinding = true;
                if (extDataService == null) {
                    throw new BaseException(FormErrorCode.EXT_QUERY_NOT_FOUND,
                            "外部数据源服务未装配，无法发布 DATASOURCE 字段");
                }
                extDataService.validateBinding(
                        binding.path("queryKey").asText(),
                        binding.hasNonNull("version") ? binding.get("version").intValue() : null,
                        binding.path("valueField").asText(),
                        binding.path("displayField").asText());
            }
            if (hasBinding) {
                log.info("Datasource bindings validated at publish");
            }
        } catch (JsonProcessingException e) {
            throw new BaseException(FormErrorCode.DEFINITION_INVALID, "definition JSON 解析失败");
        }
    }

    // ==================== 内部方法 ====================

    private FormDefDTO toDTO(FormDefEntity entity) {
        return FormDefDTO.builder()
                .id(entity.getId())
                .formKey(entity.getFormKey())
                .name(entity.getName())
                .logicalTableName(entity.getLogicalTableName())
                .status(entity.getStatus())
                .physicalTableName(entity.getPhysicalTableName())
                .formVersion(entity.getFormVersion())
                .description(entity.getDescription())
                .visibilityScope(entity.getVisibilityScope())
                .createTime(entity.getCreateTime())
                .updateTime(entity.getUpdateTime())
                .build();
    }

    // ==================== definition 解析校验闸门（唯一闸门） ====================

    /**
     * 从 definition JSON 解析字段并执行全栈校验。
     * <p>
     * 期望格式：{@code {"fields": [...]}} 或顶层数组（兼容旧格式）。
     * definition 是唯一字段真源——不再接受外部 fieldSpecs 入参。
     * </p>
     *
     * <h3>校验项（任一不过抛对应 FormErrorCode）</h3>
     * <ol>
     *   <li>type 字面量 ∈ FieldType 且 enabled=true</li>
     *   <li>列名过 ColumnValidation（白名单正则/保留字/前缀/长度）</li>
     *   <li>同级重复列名拒绝</li>
     *   <li>DICT 必须带 dictType</li>
     *   <li>REFERENCE 必须带 targetFormId</li>
     *   <li>TABLE 必须带 subFields；subFields 内不得再含 TABLE（禁递归）</li>
     *   <li>subFields 内字段过全部上述校验</li>
     * </ol>
     *
     * @param definitionJson config.definition JSON
     * @return 校验通过的字段规格列表
     * @throws BaseException 校验失败
     */
    List<FieldSpec> parseAndValidateFieldsFromDefinition(String definitionJson) {
        if (definitionJson == null || definitionJson.isBlank() || "{}".equals(definitionJson.trim())) {
            throw new BaseException(FormErrorCode.DEFINITION_INVALID, "表单 definition 为空，不能发布");
        }
        try {
            JsonNode root = objectMapper.readTree(definitionJson);
            if (root == null || root.isNull()) {
                throw new BaseException(FormErrorCode.DEFINITION_INVALID, "definition JSON 不能为 null");
            }
            JsonNode fieldsArray = root.get("fields");
            if (fieldsArray == null || !fieldsArray.isArray()) {
                // 兼容旧格式：顶层数组
                if (root.isArray()) {
                    fieldsArray = root;
                } else {
                    throw new BaseException(FormErrorCode.DEFINITION_INVALID,
                            "definition 中缺少 fields 数组");
                }
            }
            if (fieldsArray.isEmpty()) {
                throw new BaseException(FormErrorCode.DEFINITION_INVALID, "definition 的 fields 数组为空");
            }
            List<FieldSpec> fields = new ArrayList<>();
            for (JsonNode node : fieldsArray) {
                validateLayoutFieldNode(node);
                fields.add(parseFieldNodeFromDefinition(node, false));
            }
            return fields;
        } catch (JsonProcessingException e) {
            throw new BaseException(FormErrorCode.DEFINITION_INVALID,
                    "definition JSON 解析失败: " + e.getMessage());
        }
    }

    /**
     * 从 definition JSON 的单字段节点解析为 FieldSpec（含全栈校验）。
     *
     * @param node        JSON 节点
     * @param isSubField  是否在 TABLE subFields 内（用于递归禁止检查）
     * @return FieldSpec
     * @throws BaseException 校验失败
     */
    private FieldSpec parseFieldNodeFromDefinition(JsonNode node, boolean isSubField) {
        validateLayoutFieldNode(node);

        // —— 1. name ——
        if (!node.has("name") || node.get("name").asText().isBlank()) {
            throw new BaseException(FormErrorCode.FIELD_ATTR_MISSING, "字段缺少 name");
        }
        String name = node.get("name").asText();

        // —— 2. type ——
        if (!node.has("type") || node.get("type").asText().isBlank()) {
            throw new BaseException(FormErrorCode.FIELD_TYPE_UNKNOWN, "字段 '" + name + "' 缺少 type");
        }
        String typeStr = node.get("type").asText();
        FieldType fieldType;
        try {
            fieldType = FieldType.valueOf(typeStr);
        } catch (IllegalArgumentException e) {
            throw new BaseException(FormErrorCode.FIELD_TYPE_UNKNOWN,
                    "字段 '" + name + "' 的类型 '" + typeStr + "' 不在 FieldType 枚举中");
        }

        // —— 3. enabled 检查 ——
        if (!fieldType.isEnabled()) {
            throw new BaseException(FormErrorCode.FIELD_TYPE_DISABLED,
                    "字段 '" + name + "' 的类型 '" + typeStr + "' (disabled)，v1 不支持发布");
        }

        // —— 4. 列名 + 白名单校验 (跳过 TABLE / LABEL：均不产生物理列) ——
        if (fieldType != FieldType.TABLE && fieldType != FieldType.LABEL) {
            String physicalName = ColumnValidation.physicalColumnName(name, fieldType);
            try {
                ColumnValidation.validateColumnName(physicalName);
            } catch (IllegalArgumentException e) {
                throw new BaseException(FormErrorCode.INVALID_COLUMN_NAME,
                        "字段名 '" + physicalName + "' 不合法: " + e.getMessage());
            }
        }

        // —— 5. 类型特定约束 ——
        validateDefaultValueType(name, fieldType, node);
        return switch (fieldType) {
            case TEXT -> FieldSpec.text(name);
            case RICH_TEXT -> FieldSpec.richText(name);
            case NUMBER -> FieldSpec.number(name);
            case DATE -> FieldSpec.date(name);
            case BOOL -> FieldSpec.bool(name);
            case DICT -> {
                if (!node.has("dictType") || node.get("dictType").asText().isBlank()) {
                    throw new BaseException(FormErrorCode.FIELD_ATTR_MISSING,
                            "DICT 字段 '" + name + "' 必须带 dictType");
                }
                yield FieldSpec.dict(name, node.get("dictType").asText());
            }
            case REFERENCE -> {
                if (!node.has("targetFormId") || node.get("targetFormId").asText().isBlank()) {
                    throw new BaseException(FormErrorCode.FIELD_ATTR_MISSING,
                            "REFERENCE 字段 '" + name + "' 必须带 targetFormId");
                }
                yield FieldSpec.ref(name, node.get("targetFormId").asText());
            }
            case MULTISELECT -> FieldSpec.multiselect(name);
            case ATTACHMENT -> FieldSpec.attachment(name);
            case IMAGE -> FieldSpec.image(name);
            case LABEL -> FieldSpec.label(name);
            case TIME -> FieldSpec.time(name);
            case USER -> FieldSpec.user(name);
            case DEPT -> FieldSpec.dept(name);
            case FORMULA -> {
                if (!node.has("expression") || node.get("expression").asText().isBlank()) {
                    throw new BaseException(FormErrorCode.FIELD_ATTR_MISSING,
                            "FORMULA 字段 '" + name + "' 必须带 expression");
                }
                yield FieldSpec.formula(name);
            }
            case DATASOURCE -> {
                JsonNode binding = node.get("dsBinding");
                if (binding == null || !binding.isObject()
                        || binding.path("queryKey").asText("").isBlank()
                        || binding.path("valueField").asText("").isBlank()
                        || binding.path("displayField").asText("").isBlank()) {
                    throw new BaseException(FormErrorCode.FIELD_ATTR_MISSING,
                            "DATASOURCE 字段 '" + name + "' 必须带 dsBinding{queryKey,version,valueField,displayField}");
                }
                JsonNode version = binding.get("version");
                if (version != null && !version.canConvertToInt()) {
                    throw new BaseException(FormErrorCode.FIELD_ATTR_MISSING,
                            "DATASOURCE 字段 '" + name + "' 的 dsBinding.version 必须是整数");
                }
                yield FieldSpec.datasource(name);
            }
            case TABLE -> {
                // —— 递归禁止（C: TABLE 套 TABLE 硬拦截） ——
                if (isSubField) {
                    throw new BaseException(FormErrorCode.FIELD_NESTED_TABLE,
                            "TABLE 字段 '" + name + "' 不能嵌套在另一个 TABLE 的 subFields 中（禁止递归）");
                }
                if (!node.has("subFields") || !node.get("subFields").isArray()
                        || node.get("subFields").isEmpty()) {
                    throw new BaseException(FormErrorCode.FIELD_ATTR_MISSING,
                            "TABLE 字段 '" + name + "' 必须带 subFields 且非空");
                }
                List<FieldSpec> subFields = new ArrayList<>();
                for (JsonNode subNode : node.get("subFields")) {
                    // 子字段必过全部校验 isSubField=true
                    subFields.add(parseFieldNodeFromDefinition(subNode, true));
                }
                yield FieldSpec.table(name, subFields);
            }
            // disabled 占位成员 — 已在 enabled 检查中拦截，不会到达此处
            case EMAIL, PHONE, URL, RATE, SLIDER ->
                    throw new BaseException(FormErrorCode.FIELD_TYPE_DISABLED,
                            "FieldType " + fieldType + " is not enabled (disabled placeholder)");
        };
    }

    /**
     * 校验静态默认值与字段类型匹配（v0.0.2）。
     * <p>
     * 仅做类型匹配静态校验：TEXT/RICH_TEXT/DICT/DATE → 文本；NUMBER → 数值；
     * BOOL → 布尔；MULTISELECT → 字符串数组；ATTACHMENT/IMAGE → 数组；
     * LABEL/LINK 类非输入字段不允许默认值。类型不符按 1206 ATTR_MISSING 语义拒绝发布。
     * </p>
     */
    private void validateDefaultValueType(String name, FieldType fieldType, JsonNode node) {
        JsonNode defaultValue = node.get("defaultValue");
        if (defaultValue == null || defaultValue.isNull()) {
            return;
        }
        boolean valid = switch (fieldType) {
            case TEXT, RICH_TEXT, DICT, DATE, TIME, USER, DEPT -> defaultValue.isTextual() || defaultValue.isNumber();
            case NUMBER -> defaultValue.isNumber();
            case BOOL -> defaultValue.isBoolean();
            case MULTISELECT, ATTACHMENT, IMAGE -> defaultValue.isArray();
            case LABEL, REFERENCE, TABLE, FORMULA, DATASOURCE -> false;
            default -> defaultValue.isTextual();
        };
        if (!valid) {
            throw new BaseException(FormErrorCode.FIELD_ATTR_MISSING,
                    "字段 '" + name + "' 的默认值与类型 " + fieldType + " 不匹配");
        }
    }

    /**
     * 校验并拒绝 definition 中的非法 24 列布局值。
     * <p>
     * colSpan 缺省表示旧 definition，交给前端按字段类型补默认值；一旦携带就必须是
     * 1—24 的整数。校验递归覆盖 TABLE 的 subFields，避免不同入口各自解释值域。
     * </p>
     */
    private void validateLayoutDefinition(String definitionJson) {
        if (definitionJson == null || definitionJson.isBlank() || "{}".equals(definitionJson.trim())) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(definitionJson);
            if (root == null || root.isNull()) {
                throw new BaseException(FormErrorCode.DEFINITION_INVALID, "definition JSON 不能为 null");
            }
            JsonNode fieldsArray = root.isArray() ? root : root.get("fields");
            if (fieldsArray == null || !fieldsArray.isArray()) {
                return;
            }
            for (JsonNode fieldNode : fieldsArray) {
                validateLayoutFieldNode(fieldNode);
            }
        } catch (JsonProcessingException e) {
            throw new BaseException(FormErrorCode.DEFINITION_INVALID,
                    "definition JSON 解析失败: " + e.getMessage());
        }
    }

    /**
     * 字段类型权威校验（Z8/G14a）：config 保存层拒绝未知与未启用类型。
     * 未知类型用 FIELD_TYPE_UNKNOWN(1204)，未启用类型用 FIELD_TYPE_DISABLED(1205)，
     * 与 publish 的 validFieldTypes 校验同口径（ColumnValidation 权威目录）。
     */
    private void validateFieldTypes(String definitionJson) {
        if (definitionJson == null || definitionJson.isBlank() || "{}".equals(definitionJson.trim())) {
            return;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(definitionJson);
        } catch (JsonProcessingException e) {
            throw new BaseException(FormErrorCode.DEFINITION_INVALID,
                    "definition JSON 解析失败: " + e.getMessage());
        }
        JsonNode fieldsArray = (root == null || root.isNull()) ? null
                : (root.isArray() ? root : root.get("fields"));
        if (fieldsArray == null || !fieldsArray.isArray()) {
            return;
        }
        for (JsonNode fieldNode : fieldsArray) {
            String type = fieldNode.path("type").asText("");
            if (type.isBlank()) {
                throw new BaseException(FormErrorCode.FIELD_TYPE_UNKNOWN,
                        "字段缺少 type: " + fieldNode.path("name").asText(""));
            }
            com.sw.ck.form.dynamic.FieldType fieldType;
            try {
                fieldType = com.sw.ck.form.dynamic.FieldType.valueOf(type);
            } catch (IllegalArgumentException e) {
                throw new BaseException(FormErrorCode.FIELD_TYPE_UNKNOWN,
                        "字段类型未知: " + type);
            }
            if (!fieldType.isEnabled()) {
                throw new BaseException(FormErrorCode.FIELD_TYPE_DISABLED,
                        "字段类型暂不允许发布: " + type);
            }
        }
    }

    private void validateLayoutFieldNode(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new BaseException(FormErrorCode.DEFINITION_INVALID, "definition 字段必须是对象");
        }

        JsonNode colSpan = node.get("colSpan");
        if (colSpan != null && (!colSpan.isIntegralNumber()
                || colSpan.intValue() < 1 || colSpan.intValue() > 24)) {
            String name = node.path("name").asText("<unknown>");
            throw new BaseException(FormErrorCode.DEFINITION_INVALID,
                    "字段 '" + name + "' 的 colSpan 必须是 1—24 的整数");
        }

        JsonNode subFields = node.get("subFields");
        if (subFields != null && subFields.isArray()) {
            for (JsonNode subField : subFields) {
                validateLayoutFieldNode(subField);
            }
        }
    }

    /**
     * 构建子表的 definition JSON（从父表单 fields 中提取指定 TABLE 字段的 subFields）。
     */
    private String buildSubTableDefinition(List<FieldSpec> masterFields, String tableFieldName) {
        for (FieldSpec f : masterFields) {
            if (f.getFieldType() == FieldType.TABLE && f.getFieldName().equals(tableFieldName)) {
                try {
                    return objectMapper.writeValueAsString(f.getSubFields());
                } catch (JsonProcessingException e) {
                    log.warn("Failed to serialize sub-table definition for '{}': {}", tableFieldName, e.getMessage());
                    return "[]";
                }
            }
        }
        return "[]";
    }
}
