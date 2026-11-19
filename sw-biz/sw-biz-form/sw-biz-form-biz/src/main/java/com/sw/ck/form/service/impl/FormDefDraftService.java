package com.sw.ck.form.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.form.api.vo.FormDefVO;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.entity.FormConfigEntity;
import com.sw.ck.form.entity.FormDefEntity;
import com.sw.ck.form.entity.FormIdGenerator;
import com.sw.ck.form.entity.FormStatusEnum;
import com.sw.ck.form.mapper.FormConfigMapper;
import com.sw.ck.form.mapper.FormDefMapper;
import com.sw.ck.form.service.FormAppMembershipValidator;

import java.time.LocalDateTime;

import lombok.extern.log4j.Log4j2;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 表单草稿写入：创建（含表单类型 BF20261013-020 与应用归属校验）、草稿元数据
 * 更新、改名（0.1.7 #4：不动 formKey/定义/物理列）、definition 保存（colSpan
 * 布局 / 字段类型 / 事件 / 集成清单四道闸门先行，见 {@link FormDefDefinitionValidator}）。
 */
@Service
@Log4j2
class FormDefDraftService {

    @Autowired
    private FormDefMapper formDefMapper;
    @Autowired
    private FormConfigMapper formConfigMapper;
    @Autowired
    private FormIdGenerator idGenerator;
    // ==== 应用中心：表单归属目标校验（兼容构造可为 null，带归属路径 fail-closed） ====
    @Autowired
    private FormAppMembershipValidator appMembershipValidator;
    @Autowired
    private FormDefDefinitionValidator definitionValidator;

    FormDefVO createDraft(String formKey, String name, String logicalTableName, String description) {
        return createDraft(formKey, name, logicalTableName, description, null);
    }

    /** BF20261013-020：带表单类型的草稿创建（空白类型按业务表单）。 */
    FormDefVO createDraft(String formKey, String name, String logicalTableName, String description,
            String formType) {
        return createDraft(formKey, name, logicalTableName, description, formType, null, null);
    }

    /** 应用中心：带表单类型与归属的草稿创建（审批意见表单不参与应用归属）。 */
    FormDefVO createDraft(String formKey, String name, String logicalTableName, String description,
            String formType, String appId, String dirId) {
        // —— 校验唯一性（经租户拦截器按当前租户过滤；V83 后跨租户同名 formKey 合法） ——
        LambdaQueryWrapper<FormDefEntity> keyQuery = Wrappers.lambdaQuery(FormDefEntity.class)
                .eq(FormDefEntity::getFormKey, formKey);
        if (formDefMapper.selectCount(keyQuery) > 0) {
            throw new BaseException(FormErrorCode.FORM_KEY_DUPLICATE, "表单标识 '" + formKey + "' 已存在");
        }

        // —— 归属校验（应用中心）：目标应用须存在且启用、目录须属于该应用；失败不创建 ——
        String normalizedType = "APPROVAL_COMMENT".equals(formType) ? "APPROVAL_COMMENT" : "BUSINESS";
        String normalizedAppId = trimToNull(appId);
        String normalizedDirId = trimToNull(dirId);
        if ("APPROVAL_COMMENT".equals(normalizedType)) {
            normalizedAppId = null;
            normalizedDirId = null;
        } else if (normalizedAppId != null) {
            if (appMembershipValidator == null) {
                throw new IllegalStateException("归属校验组件未装配，拒绝写入应用归属");
            }
            appMembershipValidator.validateTarget(normalizedAppId, normalizedDirId);
        }

        // —— 创建草稿 ——
        FormDefEntity entity = new FormDefEntity();
        entity.setId(idGenerator.generate());
        entity.setFormKey(formKey);
        entity.setName(name);
        entity.setLogicalTableName(logicalTableName);
        entity.setDescription(description);
        entity.setStatus(FormStatusEnum.DRAFT.getCode());
        // BF20261013-020：表单类型元数据（空白/未知按业务表单）
        entity.setFormType(normalizedType);
        entity.setAppId(normalizedAppId);
        entity.setDirId(normalizedDirId);
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
        return FormDefQueryService.toDTO(entity);
    }

    FormDefVO updateDraft(String id, String name, String logicalTableName, String description) {
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

        return FormDefQueryService.toDTO(entity);
    }

    void renameFormDef(String id, String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "表单名称不能为空");
        }
        if (trimmed.length() > 200) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "表单名称不能超过 200 字");
        }
        FormDefEntity entity = formDefMapper.selectById(id);
        if (entity == null) {
            throw new BaseException(FormErrorCode.FORM_NOT_FOUND);
        }
        // 0.1.7 #4：改名不动 formKey/定义/物理列，全部状态可改
        entity.setName(trimmed);
        entity.setUpdateTime(LocalDateTime.now());
        formDefMapper.updateById(entity);
        log.info("Renamed form def: id={}, name={}", id, trimmed);
    }

    void saveConfig(String formId, String definition) {
        FormDefEntity entity = formDefMapper.selectById(formId);
        if (entity == null) {
            throw new BaseException(FormErrorCode.FORM_NOT_FOUND);
        }

        // colSpan 是跨前端设计器、预览和填写页的持久化布局契约；保存入口也必须设防，
        // 不能只依赖浏览器控件限制。缺省值兼容历史 definition，异常值直接拒绝入库。
        definitionValidator.validateLayoutDefinition(definition);

        // 字段类型权威校验（Z8/G14a）：config 保存层即拒绝未启用类型，与 publish 的
        // validFieldTypes 校验同口径——禁止类型不得依赖 publish 兜底。
        definitionValidator.validateFieldTypes(definition);
        definitionValidator.validateFormEvents(definition);
        definitionValidator.validateFormIntegrations(definition);

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

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
