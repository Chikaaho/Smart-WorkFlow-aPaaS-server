package com.sw.ck.form.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.entity.FormDefEntity;
import com.sw.ck.form.entity.FormIdGenerator;
import com.sw.ck.form.entity.FormLifecycleAuditEntity;
import com.sw.ck.form.entity.FormListConfigEntity;
import com.sw.ck.form.entity.FormStatusEnum;
import com.sw.ck.form.mapper.FormDefMapper;
import com.sw.ck.form.mapper.FormLifecycleAuditMapper;
import com.sw.ck.form.mapper.FormListConfigMapper;
import com.sw.ck.security.holder.LoginUserHolder;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import lombok.extern.log4j.Log4j2;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 表单生命周期与列表配置（I2）：停用/启用（状态闸门 + 生命周期审计）、软删
 * （0.1.7 #21：任何状态可删，物理表/快照/数据保留）、发起可见范围设置、
 * 管理员列表配置保存/读取（结构服务端权威校验）。生命周期审计失败不阻断主流程。
 */
@Service
@Log4j2
class FormDefLifecycleService {

    @Autowired
    private FormDefMapper formDefMapper;
    @Autowired
    private FormIdGenerator idGenerator;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private FormListConfigMapper listConfigMapper;
    @Autowired
    private FormLifecycleAuditMapper lifecycleAuditMapper;

    void updateVisibility(String formId, Collection<Long> userIds) {
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
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(),
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

    /**
     * 0.1.7 #21：表单任何状态均可删除（软删）。物理表/快照/数据全部保留（deleted=1），
     * 列表/详情/填报入口经 @TableLogic 自动过滤；删除粒度只区分表单（可删）与流程定义（禁删）。
     */
    void deleteDraft(String id) {
        FormDefEntity entity = formDefMapper.selectById(id);
        if (entity == null) {
            throw new BaseException(FormErrorCode.FORM_NOT_FOUND);
        }
        formDefMapper.deleteById(id);
        auditLifecycle(id, "DELETE", "表单删除（软删，状态 " + entity.getStatus() + "）");
        log.info("Form soft-deleted: id={}, status={}", id, entity.getStatus());
    }

    // ==================== I2：生命周期 / 列表配置 ====================

    void disable(String id, String reason) {
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

    void enable(String id, String reason) {
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

    void saveListConfig(String formId, String configJson) {
        FormDefEntity entity = formDefMapper.selectById(formId);
        if (entity == null) {
            throw new BaseException(FormErrorCode.FORM_NOT_FOUND);
        }
        validateListConfig(configJson);
        LambdaQueryWrapper<FormListConfigEntity> query =
                Wrappers.lambdaQuery(FormListConfigEntity.class)
                        .eq(FormListConfigEntity::getFormId, formId);
        FormListConfigEntity config = listConfigMapper.selectOne(query);
        LocalDateTime now = LocalDateTime.now();
        if (config == null) {
            config = new FormListConfigEntity();
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

    String getListConfig(String formId) {
        LambdaQueryWrapper<FormListConfigEntity> query =
                Wrappers.lambdaQuery(FormListConfigEntity.class)
                        .eq(FormListConfigEntity::getFormId, formId);
        FormListConfigEntity config = listConfigMapper.selectOne(query);
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
            FormLifecycleAuditEntity audit =
                    new FormLifecycleAuditEntity();
            audit.setId(idGenerator.generate());
            audit.setFormId(formId);
            audit.setAction(action);
            audit.setReason(reason);
            var loginUser = LoginUserHolder.get();
            audit.setOperatorId(loginUser == null ? null : loginUser.getUserId());
            audit.setTenantId(loginUser == null || loginUser.getTenantId() == null
                    ? 0L : loginUser.getTenantId());
            audit.setDeleted(0);
            audit.setVersion(0L);
            LocalDateTime now = LocalDateTime.now();
            audit.setCreateTime(now);
            audit.setUpdateTime(now);
            lifecycleAuditMapper.insert(audit);
        } catch (Exception e) {
            log.error("Failed to write lifecycle audit: formId={}, action={}", formId, action, e);
        }
    }
}
