package com.sw.ck.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.system.entity.SysUserWorkspace;
import com.sw.ck.system.mapper.SysUserWorkspaceMapper;
import com.sw.ck.system.service.UserWorkspaceService;
import com.sw.ck.system.entity.SysWorkspaceCardType;
import com.sw.ck.system.service.WorkspaceCardTypeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

/** 用户工作台布局服务实现。 */
@Service
public class UserWorkspaceServiceImpl implements UserWorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(UserWorkspaceServiceImpl.class);

    private static final int MAX_LAYOUT_BYTES = 64 * 1024;
    private static final int MAX_FAVORITES = 20;

    private final SysUserWorkspaceMapper workspaceMapper;
    private final ObjectMapper objectMapper;
    private final WorkspaceCardTypeService cardTypeService;

    public UserWorkspaceServiceImpl(SysUserWorkspaceMapper workspaceMapper,
                                    ObjectMapper objectMapper,
                                    WorkspaceCardTypeService cardTypeService) {
        this.workspaceMapper = workspaceMapper;
        this.objectMapper = objectMapper;
        this.cardTypeService = cardTypeService;
    }

    @Override
    public Map<String, Object> getLayout() {
        Long userId = currentUserId();
        List<SysWorkspaceCardType> cardTypes = cardTypeService.listAvailable();
        SysUserWorkspace row = workspaceMapper.selectOne(Wrappers.lambdaQuery(SysUserWorkspace.class)
                .eq(SysUserWorkspace::getUserId, userId));
        if (row == null || row.getLayoutJson() == null || row.getLayoutJson().isBlank()) {
            return Map.of("custom", false, "cardTypes", cardTypes, "layout", defaultLayout(cardTypes));
        }
        try {
            JsonNode node = objectMapper.readTree(row.getLayoutJson());
            Map<String, Object> layout = objectMapper.convertValue(node, Map.class);
            return Map.of("custom", true, "cardTypes", cardTypes,
                    "layout", normalizeLayout(layout, cardTypes));
        } catch (Exception e) {
            // 失效配置回落为可用首页（方向 §3.1）
            log.warn("工作台布局解析失败，回落默认布局: userId={}, {}", userId, e.getMessage());
            return Map.of("custom", false, "cardTypes", cardTypes, "layout", defaultLayout(cardTypes));
        }
    }

    @Override
    @Transactional
    public void saveLayout(Map<String, Object> layout) {
        Long userId = currentUserId();
        List<SysWorkspaceCardType> cardTypes = cardTypeService.listAvailable();
        validate(layout, cardTypes);
        String json;
        try {
            json = objectMapper.writeValueAsString(layout);
        } catch (JsonProcessingException e) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "布局序列化失败");
        }
        if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_LAYOUT_BYTES) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "布局配置超过 64KB 上限");
        }
        SysUserWorkspace row = workspaceMapper.selectOne(Wrappers.lambdaQuery(SysUserWorkspace.class)
                .eq(SysUserWorkspace::getUserId, userId));
        if (row == null) {
            row = new SysUserWorkspace();
            row.setUserId(userId);
            row.setLayoutJson(json);
            row.setCreateTime(LocalDateTime.now());
            row.setUpdateTime(LocalDateTime.now());
            row.setDeleted(0);
            row.setVersion(0L);
            workspaceMapper.insert(row);
        } else {
            row.setLayoutJson(json);
            row.setUpdateTime(LocalDateTime.now());
            workspaceMapper.updateById(row);
        }
        log.info("工作台布局已保存: userId={}", userId);
    }

    @Override
    @Transactional
    public void resetLayout() {
        Long userId = currentUserId();
        // layoutJson 需显式置空：updateById 默认忽略 null 字段
        workspaceMapper.update(null, Wrappers.<SysUserWorkspace>lambdaUpdate()
                .set(SysUserWorkspace::getLayoutJson, null)
                .eq(SysUserWorkspace::getUserId, userId));
        log.info("工作台布局已恢复默认: userId={}", userId);
    }

    // ==================== 内部方法 ====================

    @SuppressWarnings("unchecked")
    private void validate(Map<String, Object> layout, List<SysWorkspaceCardType> cardTypes) {
        if (layout == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "布局不能为空");
        }
        Object cards = layout.get("cards");
        if (!(cards instanceof java.util.List<?>)) {
            // 兼容 0.0.2 已保存的 components 形状，落盘时仍保留原请求，读取时统一转 cards。
            cards = layout.get("components");
            if (!(cards instanceof java.util.List<?>)) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "布局缺少 cards");
            }
        }
        java.util.List<?> list = (java.util.List<?>) cards;
        Set<String> availableCodes = cardTypes.stream()
                .map(SysWorkspaceCardType::getTypeCode)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> seen = new HashSet<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> card)) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "卡片项必须是对象");
            }
            Object rawTypeCode = card.containsKey("typeCode") ? card.get("typeCode") : card.get("key");
            if (!(rawTypeCode instanceof String typeCode) || typeCode.isBlank()) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "卡片项必须含 typeCode");
            }
            if (!availableCodes.contains(typeCode)) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "未知或停用卡片类型: " + typeCode);
            }
            if (!seen.add(typeCode)) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "卡片类型重复: " + typeCode);
            }
            Object span = card.get("span");
            if (span != null
                    && (!(span instanceof Number n) || (n.intValue() != 1 && n.intValue() != 2))) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(),
                        "卡片宽度仅支持 1（半宽）或 2（整行）");
            }
            Object order = card.get("order");
            if (order != null && (!(order instanceof Number n) || n.intValue() < 1)) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "卡片排序必须为正整数");
            }
            Object metadata = card.get("metadata");
            if (metadata != null && !(metadata instanceof Map<?, ?>)) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "卡片元数据必须是 JSON 对象");
            }
        }
        Object favorites = layout.get("favoriteItemKeys");
        if (favorites != null) {
            if (!(favorites instanceof java.util.List<?> favList)
                    || favList.stream().anyMatch(f -> !(f instanceof String))) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "常用事项必须为字符串列表");
            }
            if (favList.size() > MAX_FAVORITES) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(),
                        "常用事项不能超过 " + MAX_FAVORITES + " 个");
            }
        }
    }

    private Map<String, Object> normalizeLayout(Map<String, Object> layout,
                                                 List<SysWorkspaceCardType> cardTypes) {
        Object rawCards = layout.get("cards");
        if (!(rawCards instanceof List<?>)) {
            rawCards = layout.get("components");
        }
        Set<String> availableCodes = cardTypes.stream()
                .map(SysWorkspaceCardType::getTypeCode)
                .collect(java.util.stream.Collectors.toSet());
        List<Map<String, Object>> cards = new java.util.ArrayList<>();
        if (rawCards instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> raw)) {
                    continue;
                }
                Map<String, Object> card = new LinkedHashMap<>();
                Object typeCode = raw.get("typeCode");
                if (!(typeCode instanceof String)) {
                    typeCode = raw.get("key");
                }
                if (!(typeCode instanceof String)) {
                    continue;
                }
                if (!availableCodes.contains(typeCode)) {
                    continue;
                }
                card.put("typeCode", typeCode);
                card.put("visible", raw.containsKey("visible") ? raw.get("visible") : true);
                card.put("order", raw.containsKey("order") ? raw.get("order") : cards.size() + 1);
                card.put("span", raw.containsKey("span") ? raw.get("span") : 1);
                Object metadata = raw.get("metadata");
                card.put("metadata", metadata instanceof Map<?, ?> ? metadata : new HashMap<>());
                cards.add(card);
            }
        }
        if (cards.isEmpty()) {
            return defaultLayout(cardTypes);
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("cards", cards);
        Object favorites = layout.get("favoriteItemKeys");
        normalized.put("favoriteItemKeys", favorites instanceof List<?> ? favorites : List.of());
        return normalized;
    }

    private Map<String, Object> defaultLayout(List<SysWorkspaceCardType> cardTypes) {
        List<Map<String, Object>> cards = new java.util.ArrayList<>();
        for (SysWorkspaceCardType type : cardTypes) {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("typeCode", type.getTypeCode());
            card.put("visible", true);
            card.put("order", type.getDefaultOrder());
            card.put("span", type.getDefaultSpan());
            card.put("metadata", new HashMap<>());
            cards.add(card);
        }
        Map<String, Object> layout = new LinkedHashMap<>();
        layout.put("cards", cards);
        layout.put("favoriteItemKeys", java.util.List.of());
        return layout;
    }

    private Long currentUserId() {
        var loginUser = com.sw.ck.security.holder.LoginUserHolder.get();
        if (loginUser == null) {
            throw new BaseException(CommonErrorCode.UNAUTHORIZED.getCode(), "未登录");
        }
        return loginUser.getUserId();
    }
}
