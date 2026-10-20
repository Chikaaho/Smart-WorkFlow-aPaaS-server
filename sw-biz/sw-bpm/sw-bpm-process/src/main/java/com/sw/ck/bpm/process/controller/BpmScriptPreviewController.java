package com.sw.ck.bpm.process.controller;

import com.sw.ck.bpm.api.script.BpmScriptEvaluatePort;
import com.sw.ck.bpm.api.script.ScriptFunctionContext;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.response.R;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.api.dept.DeptDirectoryRow;
import com.sw.ck.system.api.dept.DeptQueryFacade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 流程脚本模拟运行（只读，无副作用）。
 * <p>
 * 流程中的表达式是**计算值**的适配器：脚本以 {@code return} 返回结果。本接口用与运行期
 * 相同的脚本引擎（GraalJS + {@code #} 函数语法糖）执行脚本，函数数据用真实组织目录 +
 * 按声明类型生成的示例变量，因此"模拟运行"与运行期语义一致，而不是另一套本地解释器。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/workflow/scripts")
public class BpmScriptPreviewController {

    private final BpmScriptEvaluatePort scriptEvaluatePort;
    private final DeptQueryFacade deptQueryFacade;

    @Value("${sw.bpm.script.timeout-ms:5000}")
    private long timeoutMs = BpmScriptEvaluatePort.DEFAULT_TIMEOUT_MS;

    public BpmScriptPreviewController(BpmScriptEvaluatePort scriptEvaluatePort,
                                      DeptQueryFacade deptQueryFacade) {
        this.scriptEvaluatePort = scriptEvaluatePort;
        this.deptQueryFacade = deptQueryFacade;
    }

    /**
     * 模拟执行流程脚本。
     * <p>请求体：{@code {"script":"...","variables":[{"name":"var_x","type":"DEPT_SET"}]}}。</p>
     */
    @PreAuthorize("@ss.hasPermi('workflow:def:design')")
    @PostMapping("/preview")
    public R<Map<String, Object>> preview(@RequestBody(required = false) Map<String, Object> request) {
        Long tenantId = LoginUserHolder.get() == null ? null : LoginUserHolder.get().getTenantId();
        if (tenantId == null) {
            throw new BaseException(CommonErrorCode.UNAUTHORIZED);
        }
        String script = request == null ? null : str(request.get("script"));
        if (script == null || script.isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "script 不能为空");
        }
        List<DeptDirectoryRow> directory = deptQueryFacade.listDeptDirectory(tenantId).orElse(List.of());
        Map<String, Object> samples = sampleVariables(request.get("variables"), directory);
        ScriptFunctionContext functionContext = new ScriptFunctionContext(
                parentMap(directory), leaderMap(directory), samples);
        BpmScriptEvaluatePort.ScriptOutcome outcome = scriptEvaluatePort
                .run(script, samples, timeoutMs, functionContext)
                .orElseThrow(() -> new BaseException(CommonErrorCode.SYSTEM_ERROR));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", outcome.kind());
        result.put("value", outcome.value());
        result.put("typeName", outcome.typeName());
        result.put("errorMessage", outcome.errorMessage());
        result.put("durationMs", outcome.durationMs());
        result.put("samples", samples);
        return R.ok(result);
    }

    /** 按声明类型生成示例变量：部门/用户取真实目录（保证组织函数可解析），其余为文本示例。 */
    private Map<String, Object> sampleVariables(Object rawVariables, List<DeptDirectoryRow> directory) {
        Map<String, Object> samples = new LinkedHashMap<>();
        Set<String> deptIds = new LinkedHashSet<>();
        for (DeptDirectoryRow row : directory) {
            if (row.leaderId() != null && row.leaderId() > 0) {
                deptIds.add(String.valueOf(row.deptId()));
            }
        }
        List<String> sampleDepts = deptIds.stream().limit(2).toList();
        List<String> sampleUsers = directory.stream()
                .filter(row -> row.leaderId() != null && row.leaderId() > 0)
                .map(row -> String.valueOf(row.leaderId()))
                .distinct().limit(2).toList();
        if (!(rawVariables instanceof List<?> list)) {
            return samples;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> declaration)) {
                continue;
            }
            String name = str(declaration.get("name"));
            if (name == null || name.isBlank()) {
                continue;
            }
            String type = Optional.ofNullable(str(declaration.get("type"))).orElse("").toUpperCase();
            if (type.contains("DEPT")) {
                samples.put(name, new ArrayList<>(sampleDepts));
            } else if (type.contains("USER")) {
                samples.put(name, new ArrayList<>(sampleUsers));
            } else {
                samples.put(name, "示例值");
            }
        }
        return samples;
    }

    private Map<Long, Long> parentMap(List<DeptDirectoryRow> directory) {
        Map<Long, Long> map = new LinkedHashMap<>();
        for (DeptDirectoryRow row : directory) {
            if (row.deptId() != null) {
                map.put(row.deptId(), row.parentId() == null ? 0L : row.parentId());
            }
        }
        return map;
    }

    private Map<Long, Long> leaderMap(List<DeptDirectoryRow> directory) {
        Map<Long, Long> map = new LinkedHashMap<>();
        for (DeptDirectoryRow row : directory) {
            if (row.deptId() != null && row.leaderId() != null && row.leaderId() > 0) {
                map.put(row.deptId(), row.leaderId());
            }
        }
        return map;
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
