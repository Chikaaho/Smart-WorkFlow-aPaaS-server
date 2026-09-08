package com.sw.ck.iot.controller;

import com.sw.ck.common.response.R;
import com.sw.ck.iot.entity.IotScript;
import com.sw.ck.iot.entity.IotScriptExec;
import com.sw.ck.iot.entity.IotScriptVersion;
import com.sw.ck.iot.script.ScriptRunResult;
import com.sw.ck.iot.service.IotScriptService;
import com.sw.ck.security.holder.LoginUserHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import java.util.Map;

/**
 * 受控脚本控制器：编辑、校验、试运行、发布、版本、停用、执行日志。
 */
@RestController
@PreAuthorize("@ss.hasPermi('iot:script:manage')")
@RequestMapping("/iot/scripts")
public class IotScriptController {

    private final IotScriptService scriptService;

    public IotScriptController(IotScriptService scriptService) {
        this.scriptService = scriptService;
    }

    @GetMapping
    public R<List<IotScript>> list() {
        return R.ok(scriptService.list());
    }

    @GetMapping("/{id}")
    public R<IotScript> detail(@PathVariable Long id) {
        return R.ok(scriptService.getById(id));
    }

    @GetMapping("/{id}/versions")
    public R<List<IotScriptVersion>> versions(@PathVariable Long id) {
        return R.ok(scriptService.versions(id));
    }

    @PostMapping
    public R<IotScript> create(@RequestBody ScriptRequest request) {
        IotScript script = new IotScript();
        script.setCode(request.getCode());
        script.setName(request.getName());
        script.setLanguage(request.getLanguage());
        script.setTriggerType(request.getTriggerType() == null ? "MESSAGE" : request.getTriggerType());
        script.setBindingJson(request.getBindingJson());
        script.setInputSchema(request.getInputSchema());
        script.setOutputSchema(request.getOutputSchema());
        script.setTimeoutMs(request.getTimeoutMs() == null ? 5000 : request.getTimeoutMs());
        return R.ok(scriptService.create(script, request.getSourceCode()));
    }

    @PutMapping("/{id}")
    public R<IotScript> updateDraft(@PathVariable Long id, @RequestBody ScriptRequest request) {
        IotScript patch = new IotScript();
        patch.setName(request.getName());
        patch.setTriggerType(request.getTriggerType());
        patch.setBindingJson(request.getBindingJson());
        patch.setInputSchema(request.getInputSchema());
        patch.setOutputSchema(request.getOutputSchema());
        patch.setTimeoutMs(request.getTimeoutMs());
        return R.ok(scriptService.updateDraft(id, patch, request.getSourceCode()));
    }

    /**
     * 语法/安全校验当前草稿。
     */
    @PostMapping("/{id}/validate")
    public R<ScriptRunResult> validate(@PathVariable Long id) {
        return R.ok(scriptService.validate(id));
    }

    /**
     * 试运行（默认不产生真实副作用）。
     */
    @PostMapping("/{id}/dry-run")
    public R<IotScriptExec> dryRun(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> input) {
        return R.ok(scriptService.dryRun(id,
                LoginUserHolder.get().getTenantId(), LoginUserHolder.get().getUserId(),
                input));
    }

    @PostMapping("/{id}/publish")
    public R<IotScript> publish(@PathVariable Long id) {
        return R.ok(scriptService.publish(id));
    }

    @PostMapping("/{id}/disable")
    public R<Void> disable(@PathVariable Long id) {
        scriptService.disable(id);
        return R.ok();
    }

    @GetMapping("/{id}/execs")
    public R<List<IotScriptExec>> execs(@PathVariable Long id) {
        return R.ok(scriptService.listExecs(id));
    }

    public static class ScriptRequest {
        private String code;
        private String name;
        private String language;
        private String triggerType;
        private String bindingJson;
        private String inputSchema;
        private String outputSchema;
        private String sourceCode;
        private Integer timeoutMs;

        public String getCode() {
            return code;
        }

        public String getName() {
            return name;
        }

        public String getLanguage() {
            return language;
        }

        public String getTriggerType() {
            return triggerType;
        }

        public String getBindingJson() {
            return bindingJson;
        }

        public String getInputSchema() {
            return inputSchema;
        }

        public String getOutputSchema() {
            return outputSchema;
        }

        public String getSourceCode() {
            return sourceCode;
        }

        public Integer getTimeoutMs() {
            return timeoutMs;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public void setName(String name) {
            this.name = name;
        }

        public void setLanguage(String language) {
            this.language = language;
        }

        public void setTriggerType(String triggerType) {
            this.triggerType = triggerType;
        }

        public void setBindingJson(String bindingJson) {
            this.bindingJson = bindingJson;
        }

        public void setInputSchema(String inputSchema) {
            this.inputSchema = inputSchema;
        }

        public void setOutputSchema(String outputSchema) {
            this.outputSchema = outputSchema;
        }

        public void setSourceCode(String sourceCode) {
            this.sourceCode = sourceCode;
        }

        public void setTimeoutMs(Integer timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }
}
