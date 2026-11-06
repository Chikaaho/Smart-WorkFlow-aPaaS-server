package com.sw.ck.bpm.process.tool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.entity.InstanceStatusEnum;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.bpm.process.service.BpmProcessDefService;
import com.sw.ck.bpm.process.service.ProcessThemeService;
import com.sw.ck.bpm.api.facade.BpmRuntimeFacade;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 内部工具：发起指定已发布流程（0.2.4，通用能力，经工具白名单注册）。
 * <p>
 * 输入（JSON 字符串，与 M07 内部工具单字符串签名一致）：
 * {@code {"templateKey":"bpm_xxx","formData":{...},"idempotencyKey":"可选"}}。
 * 以当前执行身份（图提交人上下文）发起：镜像 IoT 触发发起链
 * （引擎发起 + 业务实例记录同一事务；formData 作为实例变量供分支条件与详情展示），
 * 不伪装系统身份。发起失败以 {@code success=false} 结果返回给 LLM，不上抛中断图执行。
 * </p>
 */
@Component("agentProcessStartTool")
public class AgentProcessStartTool {

    private static final Logger log = LoggerFactory.getLogger(AgentProcessStartTool.class);

    private final ObjectProvider<BpmRuntimeFacade> runtimeFacadeProvider;
    private final ObjectProvider<BpmProcessDefService> processDefServiceProvider;
    private final ObjectProvider<BpmInstanceService> instanceServiceProvider;
    private final ObjectProvider<ProcessThemeService> themeServiceProvider;
    private final ObjectProvider<BpmTaskFacade> taskFacadeProvider;
    private final ObjectProvider<PlatformTransactionManager> transactionManagerProvider;

    public AgentProcessStartTool(ObjectProvider<BpmRuntimeFacade> runtimeFacadeProvider,
                                 ObjectProvider<BpmProcessDefService> processDefServiceProvider,
                                 ObjectProvider<BpmInstanceService> instanceServiceProvider,
                                 ObjectProvider<ProcessThemeService> themeServiceProvider,
                                 ObjectProvider<BpmTaskFacade> taskFacadeProvider,
                                 ObjectProvider<PlatformTransactionManager> transactionManagerProvider) {
        this.runtimeFacadeProvider = runtimeFacadeProvider;
        this.processDefServiceProvider = processDefServiceProvider;
        this.instanceServiceProvider = instanceServiceProvider;
        this.themeServiceProvider = themeServiceProvider;
        this.taskFacadeProvider = taskFacadeProvider;
        this.transactionManagerProvider = transactionManagerProvider;
    }

    /**
     * 实例业务键。{@code sw_bpm_instance.business_key} 为 VARCHAR(36)：缺省用 UUID；
     * 显式幂等键原样使用，超长时按 SHA-256 取 33 位十六进制并以 {@code at-} 前缀收敛为
     * 36 字符——保持确定性幂等，又不会因前缀/长键插入失败（0.3.0 修复）。
     */
    static String businessKeyOf(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return UUID.randomUUID().toString();
        }
        if (idempotencyKey.length() <= 36) {
            return idempotencyKey;
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(idempotencyKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("at-");
            for (int i = 0; i < 17; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.substring(0, 36);
        } catch (java.security.NoSuchAlgorithmException e) {
            return UUID.randomUUID().toString();
        }
    }

    /** 内部工具统一签名：单字符串入参、字符串出参。 */
    public String execute(String inputJson) {
        JSONObject input;
        try {
            input = JSON.parseObject(inputJson == null ? "{}" : inputJson);
        } catch (Exception e) {
            return fail("输入不是合法 JSON: " + e.getMessage());
        }
        String templateKey = input == null ? null : input.getString("templateKey");
        JSONObject formDataJson = input == null ? null : input.getJSONObject("formData");
        if (templateKey == null || templateKey.isBlank()) {
            return fail("缺少 templateKey");
        }
        LoginUser loginUser = LoginUserHolder.get();
        if (loginUser == null || loginUser.getTenantId() == null) {
            return fail("执行身份缺失（租户上下文不可用）");
        }
        BpmRuntimeFacade runtimeFacade = runtimeFacadeProvider.getIfAvailable();
        BpmProcessDefService processDefService = processDefServiceProvider.getIfAvailable();
        PlatformTransactionManager transactionManager = transactionManagerProvider.getIfAvailable();
        if (runtimeFacade == null || processDefService == null || transactionManager == null) {
            return fail("流程运行时未装配");
        }
        BpmProcessDef def = processDefService.findByProcessKey(templateKey);
        if (def == null || !"PUBLISHED".equals(def.getStatus())
                || def.getProcessDefinitionId() == null || def.getProcessDefinitionId().isBlank()) {
            return fail("流程模板不存在或未发布: " + templateKey);
        }
        Map<String, Object> formData = new LinkedHashMap<>();
        if (formDataJson != null) {
            formData.putAll(formDataJson);
        }
        String idempotencyKey = input.getString("idempotencyKey");
        String businessKey = businessKeyOf(idempotencyKey);
        Map<String, Object> variables = new HashMap<>();
        variables.put("formData", formData);
        variables.put("submitter", String.valueOf(loginUser.getUserId()));
        // 与手动发起同契约：审批节点监听器从流程变量取 tenantId（0.2.4 缺陷修复）
        variables.put("tenantId", loginUser.getTenantId());
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        try {
            String processInstanceId = template.execute(status -> {
                String instanceId = runtimeFacade.startProcess(
                                templateKey, businessKey, variables, String.valueOf(loginUser.getTenantId()))
                        .orElseThrow(() -> new IllegalStateException("流程模板无已发布定义: " + templateKey));
                BpmInstanceService instanceService = instanceServiceProvider.getIfAvailable();
                if (instanceService != null) {
                    BpmInstance instance = new BpmInstance();
                    instance.setProcessInstanceId(instanceId);
                    instance.setProcessDefKey(templateKey);
                    instance.setBusinessKey(businessKey);
                    instance.setFormKey(def.getFormKey());
                    instance.setInitiatorId(loginUser.getUserId());
                    ProcessThemeService themeService = themeServiceProvider.getIfAvailable();
                    if (themeService != null) {
                        instance.setTheme(themeService.generate(templateKey, def.getThemeRule(),
                                loginUser.getUserId()));
                    }
                    boolean processActive = true;
                    BpmTaskFacade taskFacade = taskFacadeProvider.getIfAvailable();
                    if (taskFacade != null) {
                        java.util.Optional<Boolean> activeDecision =
                                taskFacade.isProcessActive(instanceId);
                        if (activeDecision.isPresent()) {
                            processActive = activeDecision.get();
                        }
                    }
                    instance.setStatus(processActive
                            ? InstanceStatusEnum.RUNNING.getCode()
                            : InstanceStatusEnum.APPROVED.getCode());
                    instanceService.save(instance);
                }
                return instanceId;
            });
            log.info("Agent 工具发起流程成功: templateKey={}, instanceId={}, operator={}",
                    templateKey, processInstanceId, loginUser.getUserId());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("processInstanceId", processInstanceId);
            return JSON.toJSONString(result);
        } catch (Exception e) {
            log.warn("Agent 工具发起流程失败: templateKey={}, error={}", templateKey, e.getMessage());
            return fail("发起失败: " + e.getMessage());
        }
    }

    private String fail(String error) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("error", error);
        return JSON.toJSONString(result);
    }
}
