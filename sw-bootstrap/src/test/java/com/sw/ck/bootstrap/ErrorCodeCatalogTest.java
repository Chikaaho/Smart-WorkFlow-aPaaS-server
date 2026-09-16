package com.sw.ck.bootstrap;

import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.exception.ErrorCode;
import com.sw.ck.common.response.R;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.openapi.api.exception.OpenApiErrorCode;
import com.sw.ck.system.security.AuthErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P61 阶段 A：errorKey 契约与数值码兼容目录的常驻回归测试。
 *
 * <p>本测试是错误码目录的机器守护者，与 {@code docs/governance/error-code-catalog.md} 配对。
 * 它跨模块聚合全部 {@link ErrorCode} 枚举，钉死三件事：</p>
 * <ol>
 *   <li><b>errorKey 全局唯一</b>——新增重复键必须在此失败，不得靠人工核对。</li>
 *   <li><b>数值冲突集合精确等于已登记集合</b>——新增数值重复（新错误复用冲突值）必须在此失败。</li>
 *   <li><b>成功响应字节形状不变</b>——{@code R.ok()} 不得出现 {@code errorKey}/{@code eventRef}，
 *       保证 0.1.0 旧调用方看到的成功响应完全一致。</li>
 * </ol>
 *
 * <p>放在 sw-bootstrap 是因为只有启动模块同时可见全部 5 个枚举（依赖方向铁律下，
 * sw-common 不可见业务模块枚举）。本测试不启动 Spring 上下文。</p>
 */
class ErrorCodeCatalogTest {

    /** catalog §3 登记的数值冲突集合；新增一项都必须同步更新目录与测试。 */
    private static final Set<Integer> REGISTERED_DUPLICATE_CODES =
            Set.of(2101, 2102, 2103, 2104, 2415);

    /** catalog §4 保留既有通用数值码的语义键。 */
    private static final List<String> RETAINED_GENERIC_CODE_KEYS = List.of(
            BpmErrorCode.DRAFT_NO_ACTIVE_BINDING,
            BpmErrorCode.DRAFT_BINDING_AMBIGUOUS,
            BpmErrorCode.DRAFT_NOT_EDITABLE,
            BpmErrorCode.DRAFT_BINDING_CHANGED);

    private static final List<ErrorCode[]> ALL_ENUMS = List.of(
            CommonErrorCode.values(),
            AuthErrorCode.values(),
            FormErrorCode.values(),
            BpmErrorCode.values(),
            OpenApiErrorCode.values());

    private static List<ErrorCode> allConstants() {
        List<ErrorCode> all = new ArrayList<>();
        for (ErrorCode[] constants : ALL_ENUMS) {
            all.addAll(List.of(constants));
        }
        return all;
    }

    @Test
    @DisplayName("errorKey 全局唯一：无重复键，且均为 <domain>.<semantic> 形式")
    void errorKeys_shouldBeGloballyUniqueAndWellFormed() {
        Map<String, String> owner = new LinkedHashMap<>();
        List<String> duplicates = new ArrayList<>();
        List<String> malformed = new ArrayList<>();

        for (ErrorCode code : allConstants()) {
            String key = code.getErrorKey();
            String where = code.getClass().getSimpleName() + "." + code;
            if (key == null || key.isBlank()) {
                malformed.add(where + " 缺失 errorKey");
                continue;
            }
            if (!key.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")) {
                malformed.add(where + " errorKey 形式非法: " + key);
            }
            String previous = owner.putIfAbsent(key, where);
            if (previous != null) {
                duplicates.add(key + " 被 " + previous + " 与 " + where + " 同时占用");
            }
        }

        assertTrue(malformed.isEmpty(), "errorKey 缺失或形式非法: " + malformed);
        assertTrue(duplicates.isEmpty(), "errorKey 必须全局唯一，发现重复: " + duplicates);
        assertEquals(127, allConstants().size(),
                "ErrorCode 常量总数变化时必须同步 docs/governance/error-code-catalog.md §2");
    }

    @Test
    @DisplayName("数值码冲突集合精确等于已登记集合（新增冲突值必须同步登记）")
    void duplicateNumericCodes_shouldMatchRegisteredRegistry() {
        Map<Integer, Set<String>> byCode = new LinkedHashMap<>();
        for (ErrorCode code : allConstants()) {
            byCode.computeIfAbsent(code.getCode(), k -> new LinkedHashSet<>())
                    .add(code.getErrorKey());
        }

        Set<Integer> actualDuplicates = new TreeSet<>();
        for (Map.Entry<Integer, Set<String>> entry : byCode.entrySet()) {
            if (entry.getValue().size() > 1) {
                actualDuplicates.add(entry.getKey());
            }
        }

        assertEquals(new TreeSet<>(REGISTERED_DUPLICATE_CODES), actualDuplicates,
                "数值码重复集合与 catalog §3 登记不一致：新错误不得复用冲突值，"
                        + "若确需重复必须同时更新目录与本测试的 REGISTERED_DUPLICATE_CODES");
        assertEquals(122, byCode.size(),
                "唯一数值码数量变化时必须同步 docs/governance/error-code-catalog.md §2");
    }

    @Test
    @DisplayName("冲突数值确实跨语义：每个冲突值的 errorKey 互不相同")
    void conflictingCodes_shouldBeDisambiguatedByDistinctErrorKeys() {
        Map<Integer, Set<String>> byCode = new LinkedHashMap<>();
        for (ErrorCode code : allConstants()) {
            byCode.computeIfAbsent(code.getCode(), k -> new LinkedHashSet<>())
                    .add(code.getErrorKey());
        }
        for (Integer conflicting : REGISTERED_DUPLICATE_CODES) {
            Set<String> keys = byCode.get(conflicting);
            assertTrue(keys != null && keys.size() > 1,
                    "冲突数值 " + conflicting + " 必须由不同 errorKey 消歧");
        }
    }

    @Test
    @DisplayName("保留既有通用数值码的语义键已登记且唯一")
    void retainedGenericCodeKeys_shouldBeRegisteredAndUnique() {
        assertEquals(RETAINED_GENERIC_CODE_KEYS.size(),
                new LinkedHashSet<>(RETAINED_GENERIC_CODE_KEYS).size(),
                "保留语义键不得重复");
        for (String key : RETAINED_GENERIC_CODE_KEYS) {
            assertFalse(key.isBlank(), "保留语义键不得为空");
            assertTrue(key.startsWith("bpm."), "保留语义键必须带业务命名空间: " + key);
        }
    }

    @Test
    @DisplayName("BaseException 携带 ErrorCode 的 errorKey；原始整数码构造时为 null")
    void baseException_shouldCarryErrorKey() {
        BaseException fromEnum = new BaseException(AuthErrorCode.PASSWORD_ERROR);
        assertEquals(AuthErrorCode.PASSWORD_ERROR.getErrorKey(), fromEnum.getErrorKey());
        assertEquals(2104, fromEnum.getCode());

        BaseException withMessage = new BaseException(FormErrorCode.VERSION_CONFLICT, "自定义文案");
        assertEquals(FormErrorCode.VERSION_CONFLICT.getErrorKey(), withMessage.getErrorKey());
        assertEquals(1508, withMessage.getCode());
        assertEquals("自定义文案", withMessage.getMessage());

        BaseException rawInt = new BaseException(400, "原始码文案");
        assertNull(rawInt.getErrorKey(),
                "原始整数码构造无 errorKey；新增代码应优先使用 ErrorCode 常量");

        BaseException explicitKey = new BaseException(400, BpmErrorCode.DRAFT_NO_ACTIVE_BINDING, "文案");
        assertEquals(BpmErrorCode.DRAFT_NO_ACTIVE_BINDING, explicitKey.getErrorKey());
        assertEquals(400, explicitKey.getCode());
    }

    @Test
    @DisplayName("成功响应字节形状与 0.1.0 一致：不出现 errorKey / eventRef")
    void okResponse_shouldNotEmitNewFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        Map<String, Object> payload = new HashMap<>();
        payload.put("id", 7);

        String withData = mapper.writeValueAsString(R.ok(payload));
        assertEquals("{\"code\":0,\"msg\":\"success\",\"data\":{\"id\":7}}", withData);

        String withoutData = mapper.writeValueAsString(R.ok());
        assertEquals("{\"code\":0,\"msg\":\"success\",\"data\":null}", withoutData);
    }

    @Test
    @DisplayName("失败响应在提供语义标识与事件引用时同时外显两者")
    void failResponse_shouldEmitErrorKeyAndEventRef() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        String json = mapper.writeValueAsString(R.fail(
                AuthErrorCode.CAPTCHA_ERROR.getCode(),
                AuthErrorCode.CAPTCHA_ERROR.getErrorKey(),
                AuthErrorCode.CAPTCHA_ERROR.getMessage(),
                "web-abc123"));

        assertTrue(json.contains("\"code\":2101"), json);
        assertTrue(json.contains("\"errorKey\":\"auth.captcha_mismatch\""), json);
        assertTrue(json.contains("\"eventRef\":\"web-abc123\""), json);
    }

    @Test
    @DisplayName("旧式失败构造不引入新字段：仅 code/msg/data 出现（与 0.1.0 形状一致）")
    void legacyFailResponse_shouldStayUnchanged() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        String json = mapper.writeValueAsString(R.fail(1401, "必填字段缺失"));

        assertEquals("{\"code\":1401,\"msg\":\"必填字段缺失\",\"data\":null}", json);
        assertFalse(json.contains("errorKey"), "旧式失败构造不得凭空出现 errorKey：" + json);
        assertFalse(json.contains("eventRef"), "旧式失败构造不得凭空出现 eventRef：" + json);
    }
}
