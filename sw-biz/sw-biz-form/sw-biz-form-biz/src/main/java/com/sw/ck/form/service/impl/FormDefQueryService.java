package com.sw.ck.form.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.form.api.vo.FormDefVO;
import com.sw.ck.form.api.vo.FormSnapshotDetailVO;
import com.sw.ck.form.api.vo.FormSnapshotVO;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.entity.FormConfigEntity;
import com.sw.ck.form.entity.FormDefEntity;
import com.sw.ck.form.entity.FormSnapshotEntity;
import com.sw.ck.form.entity.FormStatusEnum;
import com.sw.ck.form.mapper.FormConfigMapper;
import com.sw.ck.form.mapper.FormDefMapper;
import com.sw.ck.form.mapper.FormSnapshotMapper;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.api.user.UserQueryFacade;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.extern.log4j.Log4j2;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 表单定义查询/读取：单查与按 key 查、definition 读取、分页（含创建人展示名
 * 解析 V012-BUG-015）、版本快照列表与详情、当前用户可见性判定（发布状态 +
 * visibilityScope userIds，解析失败 fail-closed）。
 */
@Service
@Log4j2
class FormDefQueryService {

    @Autowired
    private FormDefMapper formDefMapper;
    @Autowired
    private FormConfigMapper formConfigMapper;
    @Autowired
    private FormSnapshotMapper formSnapshotMapper;
    @Autowired
    private ObjectMapper objectMapper;
    // ==== V012-BUG-015：创建人展示名解析（system 契约；兼容构造可为 null） ====
    @Autowired
    private UserQueryFacade userQueryFacade;

    FormDefVO getFormDef(String id) {
        FormDefEntity entity = formDefMapper.selectById(id);
        return entity != null ? toDTO(entity) : null;
    }

    FormDefVO getFormDefByKey(String formKey) {
        LambdaQueryWrapper<FormDefEntity> query = Wrappers.lambdaQuery(FormDefEntity.class)
                .eq(FormDefEntity::getFormKey, formKey);
        FormDefEntity entity = formDefMapper.selectOne(query);
        return entity != null ? toDTO(entity) : null;
    }

    String getDefinition(String formKey) {
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

    String getDefinitionById(String formId) {
        LambdaQueryWrapper<FormConfigEntity> configQuery = Wrappers.lambdaQuery(FormConfigEntity.class)
                .eq(FormConfigEntity::getFormId, formId)
                .isNull(FormConfigEntity::getParentTable);
        FormConfigEntity config = formConfigMapper.selectOne(configQuery);
        return config != null ? config.getDefinition() : null;
    }

    String getSnapshotDefinitionByKey(String formKey, Integer formVersion) {
        FormDefVO dto = getFormDefByKey(formKey);
        if (dto == null) {
            return null;
        }
        return getSnapshot(dto.getId(), formVersion).getDefinition();
    }

    PageResult<FormDefVO> pageFormDefs(PageParam pageParam, String keyword) {
        return pageFormDefs(pageParam, keyword, null);
    }

    /** BF20261013-020：按表单类型过滤的分页查询（formType 为空不过滤）。 */
    PageResult<FormDefVO> pageFormDefs(PageParam pageParam, String keyword, String formType) {
        // 构造查询条件：按 update_time 倒序 + 可选 name 模糊搜索 + 可选类型精确过滤
        LambdaQueryWrapper<FormDefEntity> wrapper = Wrappers.lambdaQuery(FormDefEntity.class)
                .orderByDesc(FormDefEntity::getUpdateTime);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(FormDefEntity::getName, keyword.trim());
        }
        if (formType != null && !formType.isBlank()) {
            wrapper.eq(FormDefEntity::getFormType, formType.trim());
        }
        // 走 MyBatis-Plus selectPage → @TableLogic / TenantLineHandler 自动生效
        PageResult<FormDefEntity> entityPage = formDefMapper.selectPage(pageParam, wrapper);
        // 转换 Entity → DTO
        List<FormDefVO> dtoList = entityPage.getRecords().stream()
                .map(FormDefQueryService::toDTO)
                .toList();
        resolveCreatorNames(entityPage.getRecords(), dtoList);
        PageResult<FormDefVO> result = new PageResult<>();
        result.setRecords(dtoList);
        result.setTotal(entityPage.getTotal());
        result.setPageNum(entityPage.getPageNum());
        result.setPageSize(entityPage.getPageSize());
        return result;
    }

    /**
     * 批量解析当前页创建人展示名（V012-BUG-015）：按 createBy 去重经
     * {@link UserQueryFacade#getUserDisplayNames} 查询后逐行回填。
     * 查询上下文缺失、失败或无解析结果时降级为 null，不阻断列表查询。
     */
    private void resolveCreatorNames(List<FormDefEntity> entities, List<FormDefVO> dtoList) {
        if (userQueryFacade == null) {
            return;
        }
        Set<Long> ids = new HashSet<>();
        for (FormDefEntity entity : entities) {
            if (entity.getCreateBy() != null) {
                ids.add(entity.getCreateBy());
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        Map<Long, String> names;
        try {
            names = userQueryFacade.getUserDisplayNames(ids).orElse(Map.of());
        } catch (Exception e) {
            log.warn("批量解析表单创建人展示名失败，创建人列降级为空: {}", e.getMessage());
            return;
        }
        for (int i = 0; i < entities.size(); i++) {
            Long createBy = entities.get(i).getCreateBy();
            if (createBy != null) {
                dtoList.get(i).setCreateByName(names.get(createBy));
            }
        }
    }

    List<FormSnapshotVO> listSnapshots(String formId) {
        FormDefEntity entity = formDefMapper.selectById(formId);
        if (entity == null) {
            throw new BaseException(FormErrorCode.FORM_NOT_FOUND);
        }
        LambdaQueryWrapper<FormSnapshotEntity> query = Wrappers.lambdaQuery(FormSnapshotEntity.class)
                .eq(FormSnapshotEntity::getFormId, formId)
                .orderByDesc(FormSnapshotEntity::getFormVersion);
        return formSnapshotMapper.selectList(query).stream()
                .map(s -> FormSnapshotVO.builder()
                        .formVersion(s.getFormVersion())
                        .createTime(s.getCreateTime())
                        .build())
                .toList();
    }

    FormSnapshotDetailVO getSnapshot(String formId, Integer formVersion) {
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
        return FormSnapshotDetailVO.builder()
                .formVersion(snapshot.getFormVersion())
                .createTime(snapshot.getCreateTime())
                .definition(snapshot.getDefinition())
                .build();
    }

    FormDefEntity getById(String id) {
        return formDefMapper.selectById(id);
    }

    List<FormDefVO> listPublishedForCurrentUser() {
        PageParam pageParam = new PageParam();
        pageParam.setPageNum(1);
        pageParam.setPageSize(200);
        LambdaQueryWrapper<FormDefEntity> wrapper = Wrappers.<FormDefEntity>lambdaQuery()
                .eq(FormDefEntity::getStatus, FormStatusEnum.PUBLISHED.getCode())
                .orderByDesc(FormDefEntity::getUpdateTime);
        PageResult<FormDefEntity> page = formDefMapper.selectPage(pageParam, wrapper);
        return page.getRecords().stream()
                .filter(this::isVisibleToCurrentUser)
                .map(FormDefQueryService::toDTO)
                .toList();
    }

    Set<String> filterVisibleFormKeys(Collection<String> formKeys) {
        if (formKeys == null || formKeys.isEmpty()) {
            return Set.of();
        }
        List<String> keys = formKeys.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (keys.isEmpty()) {
            return Set.of();
        }
        List<FormDefEntity> entities = formDefMapper.selectList(
                Wrappers.<FormDefEntity>lambdaQuery().in(FormDefEntity::getFormKey, keys));
        return entities.stream()
                .filter(this::isVisibleToCurrentUser)
                .map(FormDefEntity::getFormKey)
                .collect(Collectors.toSet());
    }

    boolean isCurrentUserVisible(String formKey) {
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

    /** Entity → DTO 映射（查询与草稿写入共用）。 */
    static FormDefVO toDTO(FormDefEntity entity) {
        return FormDefVO.builder()
                .id(entity.getId())
                .formKey(entity.getFormKey())
                .name(entity.getName())
                .logicalTableName(entity.getLogicalTableName())
                .status(entity.getStatus())
                .physicalTableName(entity.getPhysicalTableName())
                .formVersion(entity.getFormVersion())
                .description(entity.getDescription())
                .visibilityScope(entity.getVisibilityScope())
                .formType(entity.getFormType())
                .appId(entity.getAppId())
                .dirId(entity.getDirId())
                .createTime(entity.getCreateTime())
                .updateTime(entity.getUpdateTime())
                .build();
    }
}
