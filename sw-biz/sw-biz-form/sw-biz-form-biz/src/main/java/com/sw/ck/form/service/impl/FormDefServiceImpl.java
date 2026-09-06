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
import com.sw.ck.form.service.FormDefService;
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

    public FormDefServiceImpl(FormDefMapper formDefMapper,
                              FormConfigMapper formConfigMapper,
                              FormSnapshotMapper formSnapshotMapper,
                              DynamicTableManager dynamicTableManager,
                              FormIdGenerator idGenerator,
                              ObjectMapper objectMapper) {
        this.formDefMapper = formDefMapper;
        this.formConfigMapper = formConfigMapper;
        this.formSnapshotMapper = formSnapshotMapper;
        this.dynamicTableManager = dynamicTableManager;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FormDefDTO createDraft(String formKey, String name, String logicalTableName, String description) {
        // —— 校验唯一性 ——
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
        entity.setTenantId(0L);
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
        config.setTenantId(0L);
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
            config.setTenantId(0L);
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

        // —— Step 3: 校验字段名白名单（复用 ColumnValidation） ——
        if (entity.getLogicalTableName() != null && !entity.getLogicalTableName().isBlank()) {
            ColumnValidation.validateColumnName(entity.getLogicalTableName());
        }
        Set<String> columnNames = new HashSet<>();
        for (FieldSpec field : fields) {
            if (field.getFieldType() == FieldType.TABLE) continue; // 不产生列
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
            subConfig.setTenantId(0L);
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
        snapshot.setTenantId(0L);
        snapshot.setDeleted(0);
        snapshot.setVersion(0L);
        formSnapshotMapper.insert(snapshot);

        log.info("Form published: id={}, formKey={}, physicalTable={}, version={}",
                formId, entity.getFormKey(), physicalTableName, entity.getFormVersion());
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
        if (!FormStatusEnum.PUBLISHED.getCode().equals(entity.getStatus())) {
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
            throw new BaseException(FormErrorCode.FORM_ALREADY_PUBLISHED.getCode(),
                    "已发布表单不能删除");
        }
        formDefMapper.deleteById(id);
        log.info("Form draft deleted: id={}", id);
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

        // —— 4. 列名 + 白名单校验 (跳过 TABLE) ——
        if (fieldType != FieldType.TABLE) {
            String physicalName = ColumnValidation.physicalColumnName(name, fieldType);
            try {
                ColumnValidation.validateColumnName(physicalName);
            } catch (IllegalArgumentException e) {
                throw new BaseException(FormErrorCode.INVALID_COLUMN_NAME,
                        "字段名 '" + physicalName + "' 不合法: " + e.getMessage());
            }
        }

        // —— 5. 类型特定约束 ——
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
            case MULTISELECT, ATTACHMENT, IMAGE, LABEL, EMAIL, PHONE, URL, RATE, SLIDER ->
                    throw new BaseException(FormErrorCode.FIELD_TYPE_DISABLED,
                            "FieldType " + fieldType + " is not enabled (disabled placeholder)");
        };
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
