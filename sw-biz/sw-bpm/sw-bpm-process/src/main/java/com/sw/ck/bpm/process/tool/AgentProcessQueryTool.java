package com.sw.ck.bpm.process.tool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.security.holder.LoginUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 内部工具：查询近期流程实例记录（0.2.4，通用能力，经工具白名单注册）。
 * <p>
 * 输入（JSON 字符串）：{@code {"windowMinutes":1440,"keyword":"","limit":10}}，
 * 全部可选。按当前租户、创建时间倒序返回近期实例摘要（实例 id、流程模板、
 * 状态、发起人、创建时间、业务键；keyword 对流程模板 key 与业务键做包含匹配）。
 * 租户隔离经租户拦截器按执行上下文生效；上限 20 条。查询失败以
 * {@code success=false} 返回，不静默当作"无记录"。
 * </p>
 */
@Component("agentProcessQueryTool")
public class AgentProcessQueryTool {

    private static final Logger log = LoggerFactory.getLogger(AgentProcessQueryTool.class);

    private static final int MAX_LIMIT = 20;

    private final ObjectProvider<BpmInstanceService> instanceServiceProvider;

    public AgentProcessQueryTool(ObjectProvider<BpmInstanceService> instanceServiceProvider) {
        this.instanceServiceProvider = instanceServiceProvider;
    }

    /** 内部工具统一签名：单字符串入参、字符串出参。 */
    public String execute(String inputJson) {
        JSONObject input;
        try {
            input = JSON.parseObject(inputJson == null ? "{}" : inputJson);
        } catch (Exception e) {
            return fail("输入不是合法 JSON: " + e.getMessage());
        }
        BpmInstanceService instanceService = instanceServiceProvider.getIfAvailable();
        if (instanceService == null) {
            return fail("流程运行时未装配");
        }
        if (LoginUserHolder.get() == null || LoginUserHolder.get().getTenantId() == null) {
            return fail("执行身份缺失（租户上下文不可用）");
        }
        int limit = input == null || input.getIntValue("limit") <= 0
                ? 10 : Math.min(input.getIntValue("limit"), MAX_LIMIT);
        int windowMinutes = input == null || input.getIntValue("windowMinutes") <= 0
                ? 1440 : input.getIntValue("windowMinutes");
        String keyword = input == null ? null : input.getString("keyword");
        try {
            LocalDateTime since = LocalDateTime.now().minusMinutes(windowMinutes);
            LambdaQueryWrapper<BpmInstance> query = new LambdaQueryWrapper<BpmInstance>()
                    .ge(BpmInstance::getCreateTime, since)
                    .orderByDesc(BpmInstance::getCreateTime)
                    .last("LIMIT " + limit);
            if (keyword != null && !keyword.isBlank()) {
                query.and(q -> q.like(BpmInstance::getProcessDefKey, keyword)
                        .or().like(BpmInstance::getBusinessKey, keyword));
            }
            List<BpmInstance> records = instanceService.list(query);
            List<Map<String, Object>> items = records.stream().map(instance -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("processInstanceId", instance.getProcessInstanceId());
                item.put("processDefKey", instance.getProcessDefKey());
                item.put("status", instance.getStatus());
                item.put("initiatorId", instance.getInitiatorId());
                item.put("businessKey", instance.getBusinessKey());
                item.put("createTime", instance.getCreateTime() == null
                        ? null : instance.getCreateTime().toString());
                return item;
            }).toList();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("count", items.size());
            result.put("records", items);
            return JSON.toJSONString(result);
        } catch (Exception e) {
            log.warn("Agent 工具查询流程记录失败: error={}", e.getMessage());
            return fail("查询失败: " + e.getMessage());
        }
    }

    private String fail(String error) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("error", error);
        return JSON.toJSONString(result);
    }
}
