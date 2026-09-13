package com.sw.ck.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.system.entity.SysUserWorkspace;
import com.sw.ck.system.mapper.SysUserWorkspaceMapper;
import com.sw.ck.system.service.UserWorkspaceService;
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
import java.util.Set;

/** 用户工作台布局服务实现。 */
@Service
public class UserWorkspaceServiceImpl implements UserWorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(UserWorkspaceServiceImpl.class);

    /** 组件键白名单（v0.0.2 首批四组件；I4 §3.7 扩展草稿与消息入口；I4 G4a 补「我的已办」） */
    private static final Set<String> COMPONENT_KEYS = Set.of("todo", "myProcessed", "myInitiated", "cc", "favoriteItems", "drafts", "messages");
    private static final int MAX_LAYOUT_BYTES = 64 * 1024;
    private static final int MAX_FAVORITES = 20;

    private final SysUserWorkspaceMapper workspaceMapper;
    private final ObjectMapper objectMapper;

    public UserWorkspaceServiceImpl(SysUserWorkspaceMapper workspaceMapper, ObjectMapper objectMapper) {
        this.workspaceMapper = workspaceMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public Map<String, Object> getLayout() {
        Long userId = currentUserId();
        SysUserWorkspace row = workspaceMapper.selectOne(Wrappers.lambdaQuery(SysUserWorkspace.class)
                .eq(SysUserWorkspace::getUserId, userId));
        if (row == null || row.getLayoutJson() == null || row.getLayoutJson().isBlank()) {
            return Map.of("custom", false, "layout", defaultLayout());
        }
        try {
            JsonNode node = objectMapper.readTree(row.getLayoutJson());
            Map<String, Object> layout = objectMapper.convertValue(node, Map.class);
            return Map.of("custom", true, "layout", layout);
        } catch (Exception e) {
            // 失效配置回落为可用首页（方向 §3.1）
            log.warn("工作台布局解析失败，回落默认布局: userId={}, {}", userId, e.getMessage());
            return Map.of("custom", false, "layout", defaultLayout());
        }
    }

    @Override
    @Transactional
    public void saveLayout(Map<String, Object> layout) {
        Long userId = currentUserId();
        validate(layout);
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
            row.setTenantId(0L);
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
    private void validate(Map<String, Object> layout) {
        if (layout == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "布局不能为空");
        }
        Object components = layout.get("components");
        if (!(components instanceof java.util.List<?> list)) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "布局缺少 components");
        }
        Set<String> seen = new HashSet<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> component)
                    || !(component.get("key") instanceof String key)) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "组件项必须含 key");
            }
            if (!COMPONENT_KEYS.contains(key)) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "未知组件: " + key);
            }
            if (!seen.add(key)) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "组件重复: " + key);
            }
            Object span = component.get("span");
            if (span != null
                    && (!(span instanceof Number n) || (n.intValue() != 1 && n.intValue() != 2))) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(),
                        "组件宽度仅支持 1（半宽）或 2（整行）");
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> defaultLayout() {
        Map<String, Object> todo = new LinkedHashMap<>();
        todo.put("key", "todo");
        todo.put("visible", true);
        todo.put("order", 1);
        todo.put("span", 1);
        Map<String, Object> myProcessed = new LinkedHashMap<>();
        myProcessed.put("key", "myProcessed");
        myProcessed.put("visible", true);
        myProcessed.put("order", 2);
        myProcessed.put("span", 1);
        Map<String, Object> myInitiated = new LinkedHashMap<>();
        myInitiated.put("key", "myInitiated");
        myInitiated.put("visible", true);
        myInitiated.put("order", 3);
        myInitiated.put("span", 1);
        Map<String, Object> cc = new LinkedHashMap<>();
        cc.put("key", "cc");
        cc.put("visible", true);
        cc.put("order", 4);
        cc.put("span", 1);
        Map<String, Object> favoriteItems = new LinkedHashMap<>();
        favoriteItems.put("key", "favoriteItems");
        favoriteItems.put("visible", true);
        favoriteItems.put("order", 5);
        favoriteItems.put("span", 1);
        Map<String, Object> drafts = new LinkedHashMap<>();
        drafts.put("key", "drafts");
        drafts.put("visible", true);
        drafts.put("order", 6);
        drafts.put("span", 1);
        Map<String, Object> messages = new LinkedHashMap<>();
        messages.put("key", "messages");
        messages.put("visible", true);
        messages.put("order", 7);
        messages.put("span", 1);
        Map<String, Object> layout = new LinkedHashMap<>();
        layout.put("components", java.util.List.of(todo, myProcessed, myInitiated, cc, favoriteItems, drafts, messages));
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
