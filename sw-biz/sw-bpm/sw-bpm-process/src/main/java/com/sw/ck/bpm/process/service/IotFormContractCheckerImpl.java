package com.sw.ck.bpm.process.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.api.form.FormDefinitionService;
import com.sw.ck.iot.api.IotFormContractChecker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 表单契约校验实现（G3）：映射字段必须存在于流程模板绑定的已发布表单，
 * required 字段的 source 必须可解析；模板未发布/未开 IoT 接入直接拒绝。
 */
@Service
public class IotFormContractCheckerImpl implements IotFormContractChecker {

    private final BpmProcessDefService processDefService;
    private final ObjectProvider<FormDefinitionService> formDefinitionServiceProvider;

    public IotFormContractCheckerImpl(BpmProcessDefService processDefService,
                                      ObjectProvider<FormDefinitionService> formDefinitionServiceProvider) {
        this.processDefService = processDefService;
        this.formDefinitionServiceProvider = formDefinitionServiceProvider;
    }

    @Override
    public Optional<List<String>> checkMapping(String processTemplateKey, String formMappingJson) {
        List<String> errors = new ArrayList<>();
        // empty = 无法裁决（流程模板 key 空白）：适用条件不存在，由调用方 fail closed
        if (processTemplateKey == null || processTemplateKey.isBlank()) {
            return Optional.empty();
        }
        BpmProcessDef def = processDefService.findByProcessKey(processTemplateKey);
        if (def == null || !"PUBLISHED".equals(def.getStatus())) {
            errors.add("流程模板未发布: " + processTemplateKey);
            return Optional.of(errors);
        }
        if (!Boolean.TRUE.equals(def.getIotAccessEnabled())) {
            errors.add("流程模板未开启 IoT 接入: " + processTemplateKey);
            return Optional.of(errors);
        }
        FormDefinitionService formService = formDefinitionServiceProvider.getIfAvailable();
        if (formService == null) {
            // 基础设施缺失属真实错误，继续抛出而不是伪装成一条校验错误
            throw new IllegalStateException("表单服务未装配，无法执行表单契约校验: " + processTemplateKey);
        }
        // empty = 该 formKey 无表单定义（原 null 返回路径）：与未发布同判
        Optional<FormDefDTO> formLookup = formService.getFormDef(def.getFormKey());
        if (formLookup.isEmpty()
                || !"PUBLISHED".equals(String.valueOf(formLookup.orElseThrow().getStatus()))) {
            errors.add("绑定表单未发布: " + def.getFormKey());
            return Optional.of(errors);
        }
        // 字段类型/枚举/引用契约（G3a）：name → {type, options}
        // empty = 该 formKey 无表单定义：无字段契约可提取（上方存在性校验已兜住）
        java.util.Map<String, com.alibaba.fastjson2.JSONObject> fieldTypes =
                formService.getFormDefinition(def.getFormKey())
                        .map(this::extractFieldContracts)
                        .orElseGet(java.util.LinkedHashMap::new);
        Set<String> formFields = fieldTypes.keySet();
        JSONArray mappings;
        try {
            mappings = JSON.parseArray(formMappingJson);
        } catch (Exception e) {
            errors.add("表单映射 JSON 非法");
            return Optional.of(errors);
        }
        if (mappings == null || mappings.isEmpty()) {
            errors.add("表单映射为空");
            return Optional.of(errors);
        }
        for (int i = 0; i < mappings.size(); i++) {
            JSONObject mapping = mappings.getJSONObject(i);
            String field = mapping.getString("field");
            if (field == null || field.isBlank()) {
                errors.add("映射[" + i + "] 缺少 field");
                continue;
            }
            if (!formFields.contains(field)) {
                errors.add("字段不存在于已发布表单: " + field);
            }
            if (mapping.getBooleanValue("required")
                    && (mapping.getString("source") == null || mapping.getString("source").isBlank())
                    && mapping.get("fixed") == null) {
                errors.add("必填字段缺少来源: " + field);
            }
            // 类型/枚举/引用契约：固定值必须与字段类型/枚举域匹配；REFERENCE 必须给出引用对象标识
            com.alibaba.fastjson2.JSONObject contract = fieldTypes.get(field);
            if (contract != null && mapping.get("fixed") != null) {
                String type = contract.getString("type");
                Object fixed = mapping.get("fixed");
                if ("NUMBER".equals(type) && !(fixed instanceof Number)
                        && !isNumericString(String.valueOf(fixed))) {
                    errors.add("字段 '" + field + "' 为 NUMBER，固定值非数值: " + fixed);
                } else if ("BOOL".equals(type) && !(fixed instanceof Boolean)
                        && !"true".equalsIgnoreCase(String.valueOf(fixed))
                        && !"false".equalsIgnoreCase(String.valueOf(fixed))) {
                    errors.add("字段 '" + field + "' 为 BOOL，固定值非布尔: " + fixed);
                } else if ("DATE".equals(type) && !String.valueOf(fixed).matches("\\d{4}-\\d{2}-\\d{2}.*")) {
                    errors.add("字段 '" + field + "' 为 DATE，固定值非日期: " + fixed);
                } else if ("DICT".equals(type)) {
                    com.alibaba.fastjson2.JSONArray options = contract.getJSONArray("options");
                    if (options != null && !options.isEmpty()
                            && !options.contains(String.valueOf(fixed))) {
                        errors.add("字段 '" + field + "' 枚举不含值: " + fixed);
                    }
                } else if ("REFERENCE".equals(type)) {
                    // 引用契约：记录 ID 可能是 UUID，不能用数字格式代替存在性与权限校验。
                    String targetFormKey = contract.getString("targetFormId");
                    String recordId = fixed == null ? null : String.valueOf(fixed).trim();
                    if (targetFormKey == null || targetFormKey.isBlank()) {
                        errors.add("字段 '" + field + "' 为 REFERENCE，缺少 targetFormId");
                    } else {
                        // empty = formKey/recordId 空白无法裁决：fail closed，按不可访问处理；
                        // present 判定为 false 同样是拒绝（原 false 判定语义）
                        Optional<Boolean> accessDecision =
                                formService.canCurrentUserAccessRecord(targetFormKey, recordId);
                        boolean accessible = accessDecision.isPresent() && accessDecision.get();
                        if (recordId == null || recordId.isBlank() || !accessible) {
                            errors.add("字段 '" + field + "' 引用对象不存在或当前用户无权访问: "
                                    + targetFormKey + "/" + recordId);
                        }
                    }
                }
            }
        }
        return Optional.of(errors);
    }

    private boolean isNumericString(String text) {
        try {
            Double.parseDouble(text);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private Set<String> extractFieldNames(String json) {
        return extractFieldContracts(json).keySet();
    }

    /**
     * 字段契约提取：name → {type, options}（枚举域来自 field options 数组）。
     */
    private java.util.Map<String, com.alibaba.fastjson2.JSONObject> extractFieldContracts(String json) {
        java.util.Map<String, com.alibaba.fastjson2.JSONObject> contracts = new java.util.LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return contracts;
        }
        try {
            JSONObject root = JSON.parseObject(json);
            JSONArray fields = root.getJSONArray("fields");
            if (fields != null) {
                for (int i = 0; i < fields.size(); i++) {
                    JSONObject node = fields.getJSONObject(i);
                    String name = node.getString("name");
                    if (name == null) {
                        continue;
                    }
                    JSONObject contract = new JSONObject();
                    contract.put("type", node.getString("type"));
                    Object options = node.get("options");
                    if (options instanceof JSONArray arr) {
                        java.util.List<String> values = new ArrayList<>();
                        for (int j = 0; j < arr.size(); j++) {
                            Object opt = arr.get(j);
                            if (opt instanceof JSONObject obj && obj.getString("value") != null) {
                                values.add(obj.getString("value"));
                            } else if (opt != null) {
                                values.add(String.valueOf(opt));
                            }
                        }
                        contract.put("options", values);
                    }
                    contract.put("targetFormId", node.getString("targetFormId"));
                    contracts.put(name, contract);
                }
            }
        } catch (Exception ignore) {
            // 定义非法时按空字段集处理，由上层报错
        }
        return contracts;
    }
}
