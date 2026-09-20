package com.sw.ck.form.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.security.holder.LoginUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 字段级查看/编辑权限评估（I2，方向 §4.6）。
 *
 * <h3>契约</h3>
 * <ul>
 *   <li>definition 顶层 {@code fieldPermissions}: {@code {"字段名": {"view": [主体...], "edit": [主体...]}}}</li>
 *   <li>主体语法：{@code role:<角色code>}、{@code user:<用户ID>}、{@code dept:<部门ID>}；
 *       列表缺省/为空 = 不设限（对齐既有暗态 gating 语义：配置为空即放行）。</li>
 *   <li>超管（{@code LoginUser.superAdmin}）直接放行；服务端是唯一权威，前端隐藏只是 UX。</li>
 *   <li>编辑拒绝：构造请求提交/更新无 edit 权字段 → FIELD_EDIT_DENIED，整请求拒绝（非静默剥离）。</li>
 *   <li>查看拒绝：查询/详情投影在服务端剔除无 view 权字段，敏感值不出响应。</li>
 * </ul>
 */
@Component
public class FieldPermissionService {

    private static final Logger log = LoggerFactory.getLogger(FieldPermissionService.class);

    private final ObjectMapper objectMapper;

    public FieldPermissionService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public enum Action { VIEW, EDIT }

    /**
     * 解析 definition 中全部字段的权限配置。
     *
     * @return 字段名 → (action → 主体集合)；无配置的字段不出现在结果中 = 不设限
     */
    public Map<String, Map<Action, Set<String>>> parse(String definitionJson) {
        Map<String, Map<Action, Set<String>>> result = new LinkedHashMap<>();
        if (definitionJson == null || definitionJson.isBlank()) {
            return result;
        }
        try {
            JsonNode root = objectMapper.readTree(definitionJson);
            JsonNode perms = root == null ? null : root.get("fieldPermissions");
            if (perms == null || !perms.isObject()) {
                return result;
            }
            Iterator<Map.Entry<String, JsonNode>> it = perms.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                String fieldName = entry.getKey();
                Map<Action, Set<String>> byAction = new EnumMap<>(Action.class);
                for (Action action : Action.values()) {
                    JsonNode subjects = entry.getValue().get(action.name().toLowerCase(Locale.ROOT));
                    if (subjects != null && subjects.isArray() && !subjects.isEmpty()) {
                        Set<String> set = new LinkedHashSet<>();
                        subjects.forEach(s -> set.add(s.asText()));
                        byAction.put(action, set);
                    }
                }
                if (!byAction.isEmpty()) {
                    result.put(fieldName, byAction);
                }
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to parse fieldPermissions: {}", e.getMessage());
            throw new BaseException(FormErrorCode.DEFINITION_INVALID, "fieldPermissions 解析失败");
        }
    }

    /** 当前身份对指定字段是否具备查看权。 */
    public boolean canView(LoginUser user, String fieldName, Map<String, Map<Action, Set<String>>> perms) {
        return check(user, fieldName, Action.VIEW, perms);
    }

    /** 当前身份对指定字段是否具备编辑权。 */
    public boolean canEdit(LoginUser user, String fieldName, Map<String, Map<Action, Set<String>>> perms) {
        return check(user, fieldName, Action.EDIT, perms);
    }

    /**
     * 返回当前身份在查询投影中必须剔除的字段集合（无 view 权的已配置字段）。
     */
    public Set<String> viewDeniedFields(LoginUser user, Map<String, Map<Action, Set<String>>> perms) {
        Set<String> denied = new LinkedHashSet<>();
        for (Map.Entry<String, Map<Action, Set<String>>> e : perms.entrySet()) {
            if (!check(user, e.getKey(), Action.VIEW, perms)) {
                denied.add(e.getKey());
            }
        }
        return denied;
    }

    /**
     * 编辑写入闸门：载荷中出现当前身份无 edit 权的已配置字段 → 整请求拒绝。
     *
     * @param payload 提交/更新载荷（字段名 → 值）
     */
    public void assertEditablePayload(LoginUser user, Map<String, Map<Action, Set<String>>> perms,
                                      Map<String, Object> payload) {
        assertEditablePayload(user, perms, payload, java.util.Map.of());
    }

    /**
     * 同上，但用字段显示名生成用户可读提示（P61 阶段 C：不向用户暴露字段键）。
     *
     * @param fieldLabels 字段键 → 显示名；未收录的字段键回退为键本身
     */
    public void assertEditablePayload(LoginUser user, Map<String, Map<Action, Set<String>>> perms,
                                      Map<String, Object> payload, Map<String, String> fieldLabels) {
        for (String fieldName : payload.keySet()) {
            Map<Action, Set<String>> byAction = perms.get(fieldName);
            if (byAction != null && byAction.containsKey(Action.EDIT)
                    && !check(user, fieldName, Action.EDIT, perms)) {
                String display = fieldLabels == null ? fieldName
                        : fieldLabels.getOrDefault(fieldName, fieldName);
                throw new BaseException(FormErrorCode.FIELD_EDIT_DENIED,
                        "您没有编辑字段「" + display + "」的权限");
            }
        }
    }

    /**
     * 评估 definition 顶层 actionPermissions。缺省或空列表与字段权限保持一致，表示不设限；
     * 已声明的非空主体列表必须命中当前身份，否则拒绝。
     */
    public boolean canAction(LoginUser user, String action, String definitionJson) {
        if (action == null || action.isBlank()) {
            return false;
        }
        if (definitionJson == null || definitionJson.isBlank()) {
            return true;
        }
        try {
            JsonNode root = objectMapper.readTree(definitionJson);
            JsonNode actionPermissions = root == null ? null : root.get("actionPermissions");
            if (actionPermissions == null || !actionPermissions.isObject()) {
                return true;
            }
            JsonNode subjects = actionPermissions.get(action);
            if (subjects == null) {
                return true;
            }
            if (!subjects.isArray()) {
                log.warn("actionPermissions.{} is not an array", action);
                return false;
            }
            if (subjects.isEmpty()) {
                return true;
            }
            if (user == null || user.isSuperAdmin()) {
                return user != null;
            }
            for (JsonNode subject : subjects) {
                if (matchSubject(user, subject.asText())) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("Failed to parse actionPermissions: {}", e.getMessage());
            return false;
        }
    }

    private boolean check(LoginUser user, String fieldName, Action action,
                          Map<String, Map<Action, Set<String>>> perms) {
        Map<Action, Set<String>> byAction = perms.get(fieldName);
        if (byAction == null || !byAction.containsKey(action)) {
            return true; // 未配置 = 不设限
        }
        if (user == null) {
            return false;
        }
        if (user.isSuperAdmin()) {
            return true;
        }
        Set<String> subjects = byAction.get(action);
        if (subjects.isEmpty()) {
            return true;
        }
        for (String subject : subjects) {
            if (matchSubject(user, subject)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchSubject(LoginUser user, String subject) {
        int sep = subject.indexOf(':');
        if (sep <= 0) {
            return false;
        }
        String kind = subject.substring(0, sep);
        String value = subject.substring(sep + 1);
        return switch (kind) {
            case "role" -> user.getRoles() != null && user.getRoles().contains(value);
            case "user" -> user.getUserId() != null && value.equals(String.valueOf(user.getUserId()));
            case "dept" -> user.getDeptId() != null && value.equals(String.valueOf(user.getDeptId()));
            default -> false;
        };
    }
}
