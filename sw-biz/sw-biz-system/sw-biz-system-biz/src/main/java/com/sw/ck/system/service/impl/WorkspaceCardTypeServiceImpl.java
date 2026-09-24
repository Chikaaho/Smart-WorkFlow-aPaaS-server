package com.sw.ck.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.service.BaseServiceImpl;
import com.sw.ck.system.entity.SysWorkspaceCardType;
import com.sw.ck.system.mapper.SysWorkspaceCardTypeMapper;
import com.sw.ck.system.service.WorkspaceCardTypeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** 工作台卡片类型维护服务。 */
@Service
public class WorkspaceCardTypeServiceImpl extends BaseServiceImpl<SysWorkspaceCardTypeMapper, SysWorkspaceCardType>
        implements WorkspaceCardTypeService {

    private static final Pattern TYPE_CODE = Pattern.compile("[a-z][a-z0-9_-]{1,63}");
    private static final Set<String> RENDERER_KEYS = Set.of(
            "stats", "todo", "favorites", "activity", "efficiency", "drafts", "messages");
    private static final int MAX_METADATA_BYTES = 32 * 1024;

    private final ObjectMapper objectMapper;

    public WorkspaceCardTypeServiceImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<SysWorkspaceCardType> listAvailable() {
        return lambdaQuery()
                .eq(SysWorkspaceCardType::getStatus, 0)
                .orderByAsc(SysWorkspaceCardType::getDefaultOrder)
                .orderByAsc(SysWorkspaceCardType::getId)
                .list();
    }

    @Override
    public List<SysWorkspaceCardType> listAll() {
        return lambdaQuery()
                .orderByAsc(SysWorkspaceCardType::getDefaultOrder)
                .orderByAsc(SysWorkspaceCardType::getId)
                .list();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(SysWorkspaceCardType cardType) {
        validate(cardType);
        ensureTypeCodeAvailable(cardType.getTypeCode(), null);
        save(cardType);
        return cardType.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(SysWorkspaceCardType cardType) {
        if (cardType == null || cardType.getId() == null) {
            throw param("卡片类型 ID 不能为空");
        }
        validate(cardType);
        ensureTypeCodeAvailable(cardType.getTypeCode(), cardType.getId());
        updateById(cardType);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        if (id == null) {
            throw param("卡片类型 ID 不能为空");
        }
        removeById(id);
    }

    private void ensureTypeCodeAvailable(String typeCode, Long excludedId) {
        LambdaQueryWrapper<SysWorkspaceCardType> query = new LambdaQueryWrapper<SysWorkspaceCardType>()
                .eq(SysWorkspaceCardType::getTypeCode, typeCode);
        if (excludedId != null) {
            query.ne(SysWorkspaceCardType::getId, excludedId);
        }
        if (count(query) > 0) {
            throw param("卡片类型编码已存在");
        }
    }

    private void validate(SysWorkspaceCardType cardType) {
        if (cardType == null) {
            throw param("卡片类型不能为空");
        }
        if (cardType.getTypeCode() == null || !TYPE_CODE.matcher(cardType.getTypeCode()).matches()) {
            throw param("卡片类型编码必须为 2-64 位小写字母、数字、下划线或短横线");
        }
        if (cardType.getDisplayName() == null || cardType.getDisplayName().isBlank()) {
            throw param("卡片名称不能为空");
        }
        if (cardType.getRendererKey() == null || !RENDERER_KEYS.contains(cardType.getRendererKey())) {
            throw param("不支持的卡片渲染器");
        }
        if (cardType.getDefaultSpan() == null || (cardType.getDefaultSpan() != 1 && cardType.getDefaultSpan() != 2)) {
            throw param("卡片默认宽度仅支持 1 或 2");
        }
        if (cardType.getDefaultOrder() == null || cardType.getDefaultOrder() < 1) {
            throw param("卡片默认排序必须为正整数");
        }
        if (cardType.getStatus() == null || (cardType.getStatus() != 0 && cardType.getStatus() != 1)) {
            throw param("卡片状态只能为 0 或 1");
        }
        String metadata = cardType.getMetadataJson();
        if (metadata == null || metadata.isBlank()) {
            cardType.setMetadataJson("{}");
            return;
        }
        if (metadata.getBytes(StandardCharsets.UTF_8).length > MAX_METADATA_BYTES) {
            throw param("卡片元数据超过 32KB 上限");
        }
        try {
            JsonNode node = objectMapper.readTree(metadata);
            if (node == null || !node.isObject()) {
                throw param("卡片元数据必须是 JSON 对象");
            }
        } catch (BaseException e) {
            throw e;
        } catch (Exception e) {
            throw param("卡片元数据 JSON 格式无效");
        }
    }

    private BaseException param(String message) {
        return new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), message);
    }
}
