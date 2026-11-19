package com.sw.ck.form.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.bundle.FormAppBundleDatasourcePort;
import com.sw.ck.form.api.bundle.FormAppBundleProcessPort;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.bundle.FormAppBundleDocument;
import com.sw.ck.form.bundle.FormBundleImportReport;
import com.sw.ck.form.bundle.FormBundlePreviewResult;
import com.sw.ck.form.dynamic.FieldType;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 应用整包导入导出的共享支撑：JSON 编解码、跨模块端口探测、报告/预检 DTO
 * 小工厂与文本小工具。供导出、预检、导入、改写、残留校验各协作类共用，
 * 本类不持有任何业务状态。
 */
@Service
class FormBundleSupport {

    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ObjectProvider<FormAppBundleProcessPort> processPort;
    @Autowired
    private ObjectProvider<FormAppBundleDatasourcePort> datasourcePort;

    // ==================================================================================
    // JSON 编解码
    // ==================================================================================

    Map<String, String> parseOpinionResolutions(String opinionKeysJson) {
        if (opinionKeysJson == null || opinionKeysJson.isBlank()) {
            return Map.of();
        }
        try {
            List<Map<String, String>> list = objectMapper.readValue(opinionKeysJson,
                    new TypeReference<List<Map<String, String>>>() { });
            Map<String, String> result = new LinkedHashMap<>();
            for (Map<String, String> item : list) {
                String formKey = item.get("formKey");
                String newKey = item.get("newKey");
                if (formKey != null && newKey != null && !newKey.isBlank()) {
                    result.put(formKey.trim(), newKey.trim());
                }
            }
            return result;
        } catch (IOException e) {
            throw new BaseException(FormErrorCode.BUNDLE_INVALID.getCode(), "意见表单标识解决参数格式无效");
        }
    }

    byte[] serializeDocument(FormAppBundleDocument document) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(document);
        } catch (IOException e) {
            throw new BaseException(FormErrorCode.BUNDLE_IMPORT_FAILED.getCode(), "导出包序列化失败");
        }
    }

    String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new BaseException(FormErrorCode.BUNDLE_IMPORT_FAILED.getCode(), "JSON 序列化失败");
        }
    }

    Map<String, Object> parseObjectOrNull(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json,
                    new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (IOException e) {
            return null;
        }
    }

    Map<String, Object> parseObjectOrEmpty(String json) {
        Map<String, Object> value = parseObjectOrNull(json);
        return value == null ? new LinkedHashMap<>() : value;
    }

    // ==================================================================================
    // 跨模块端口探测
    // ==================================================================================

    /** 数据源按名称匹配（流程/表单模块未装配端口时视为未匹配）。 */
    Optional<Long> findDatasourceIdByName(String name) {
        FormAppBundleDatasourcePort port = datasourcePort.getIfAvailable();
        return port == null ? Optional.empty() : port.findDatasourceIdByName(name);
    }

    /** 流程分类按名称匹配（流程模块未装配端口时视为未匹配）。 */
    Optional<Long> findCategoryIdByName(String name) {
        FormAppBundleProcessPort port = processPort.getIfAvailable();
        return port == null ? Optional.empty() : port.findCategoryIdByName(name);
    }

    // ==================================================================================
    // 引用收集小工具
    // ==================================================================================

    void collectDsBindingKeys(Map<String, Object> definition, Set<String> sink) {
        if (definition == null) {
            return;
        }
        if (definition.get("fields") instanceof List<?> fields) {
            for (Object fieldObj : fields) {
                collectDsBindingKeysOfField(fieldObj, sink);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void collectDsBindingKeysOfField(Object fieldObj, Set<String> sink) {
        if (!(fieldObj instanceof Map<?, ?> fieldRaw)) {
            return;
        }
        Map<String, Object> field = (Map<String, Object>) fieldRaw;
        if (FieldType.DATASOURCE.name().equalsIgnoreCase(text(field.get("type")))
                && field.get("dsBinding") instanceof Map<?, ?> binding
                && text(binding.get("queryKey")) != null) {
            sink.add(text(binding.get("queryKey")));
        }
        if (field.get("subFields") instanceof List<?> subFields) {
            for (Object sub : subFields) {
                collectDsBindingKeysOfField(sub, sink);
            }
        }
    }

    void collectDictCodes(Map<String, Object> definition, Set<String> sink) {
        if (definition == null) {
            return;
        }
        if (definition.get("fields") instanceof List<?> fields) {
            for (Object fieldObj : fields) {
                collectDictCodesOfField(fieldObj, sink);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void collectDictCodesOfField(Object fieldObj, Set<String> sink) {
        if (!(fieldObj instanceof Map<?, ?> fieldRaw)) {
            return;
        }
        Map<String, Object> field = (Map<String, Object>) fieldRaw;
        if (FieldType.DICT.name().equalsIgnoreCase(text(field.get("type"))) && text(field.get("dictType")) != null) {
            sink.add(text(field.get("dictType")));
        }
        if (field.get("subFields") instanceof List<?> subFields) {
            for (Object sub : subFields) {
                collectDictCodesOfField(sub, sink);
            }
        }
    }

    // ==================================================================================
    // 报告/预检 DTO 小工厂
    // ==================================================================================

    FormBundlePreviewResult.ExternalRefPreview externalRef(String kind, String value,
                                                           String location, boolean matched,
                                                           String matchedTo) {
        return FormBundlePreviewResult.ExternalRefPreview.builder()
                .kind(kind).value(value).location(location).matched(matched).matchedTo(matchedTo)
                .build();
    }

    FormBundleImportReport.ClearedRef cleared(String kind, String value, String location) {
        return FormBundleImportReport.ClearedRef.builder()
                .kind(kind).originalValue(value).location(location).build();
    }

    FormBundleImportReport.ReusedObject reused(String value, String name, Long id) {
        return FormBundleImportReport.ReusedObject.builder().value(value).name(name).id(id).build();
    }

    // ==================================================================================
    // 文本小工具
    // ==================================================================================

    boolean hasValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Collection<?> collection) {
            return !collection.isEmpty();
        }
        return !String.valueOf(value).isBlank();
    }

    String valueText(Object value) {
        if (value instanceof Collection<?> collection) {
            return joinValues(collection);
        }
        return value == null ? null : String.valueOf(value);
    }

    String joinValues(Collection<?> values) {
        return values.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }

    String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    String randomHex(int length) {
        StringBuilder sb = new StringBuilder(length);
        while (sb.length() < length) {
            sb.append(UUID.randomUUID().toString().replace("-", ""));
        }
        return sb.substring(0, length);
    }
}
