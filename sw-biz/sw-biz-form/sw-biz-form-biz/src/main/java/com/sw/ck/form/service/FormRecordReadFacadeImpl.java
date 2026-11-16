package com.sw.ck.form.service;

import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.api.facade.FormRecordReadFacade;
import com.sw.ck.form.dynamic.ColumnValidation;
import com.sw.ck.form.dynamic.FieldType;

import org.springframework.stereotype.Service;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.extern.log4j.Log4j2;
/**
 * 表单记录读取 Facade 实现（P63）。
 * <p>
 * 数据经 {@link FormDataQueryService#getRecordDetailForSystem}（显式租户、全量投影、行 id）；
 * 多值列（USER/DEPT multiple、MULTISELECT、ATTACHMENT/IMAGE、DATASOURCE 摘要）按定义解码为
 * List/Map，日期值转 ISO 文本，保证跨模块消费方拿到稳定对象身份而非存储形状。
 * </p>
 */
@Service
@Log4j2
public class FormRecordReadFacadeImpl implements FormRecordReadFacade {

    @Autowired
    private FormDataQueryService queryService;
    @Autowired
    private FormDefService formDefService;
    @Autowired
    private FormFieldValidator formFieldValidator;
    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public Optional<FormRecordData> findRecord(Long tenantId, String formKey, String recordId) {
        if (tenantId == null || formKey == null || formKey.isBlank()
                || recordId == null || recordId.isBlank()) {
            return Optional.empty();
        }
        FormDefDTO formDef = formDefService.getFormDefByKey(formKey);
        if (formDef == null) {
            return Optional.empty();
        }
        Map<String, Object> raw;
        try {
            raw = queryService.getRecordDetailForSystem(tenantId, formKey, recordId);
        } catch (Exception e) {
            // 记录不存在/已删除按 empty 契约返回；基础设施异常不吞（查询服务已抛受控错误码）
            if (e instanceof BaseException base
                    && FormErrorCode.RECORD_NOT_FOUND.getCode() == base.getCode()) {
                return Optional.empty();
            }
            throw e;
        }
        Map<String, FormFieldValidator.FieldDef> fieldDefs =
                formFieldValidator.loadAndParseFieldDefs(formDef.getId(), Map.of());

        Map<String, Object> fields = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> tables = new LinkedHashMap<>();
        for (Map.Entry<String, FormFieldValidator.FieldDef> entry : fieldDefs.entrySet()) {
            FormFieldValidator.FieldDef def = entry.getValue();
            if ("TABLE".equals(def.type())) {
                List<Map<String, Object>> rows = raw.get(def.name()) instanceof List<?> list
                        ? decodeRows(def, list)
                        : List.of();
                tables.put(def.name(), rows);
                continue;
            }
            if ("LABEL".equals(def.type())) {
                continue;
            }
            Object value = raw.get(columnOf(def.name(), def.type()));
            fields.put(def.name(), decodeValue(def.type(), def.multiple(), value));
        }
        return Optional.of(new FormRecordData(formKey, recordId, fields, tables));
    }

    private List<Map<String, Object>> decodeRows(FormFieldValidator.FieldDef tableDef,
                                                 List<?> rawRows) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, FormFieldValidator.FieldDef> subDefs = new LinkedHashMap<>();
        if (tableDef.subFields() != null) {
            for (FormFieldValidator.FieldDef sub : tableDef.subFields()) {
                subDefs.put(sub.name(), sub);
            }
        }
        for (Object item : rawRows) {
            if (!(item instanceof Map<?, ?> rawRow)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (Map.Entry<String, FormFieldValidator.FieldDef> entry : subDefs.entrySet()) {
                FormFieldValidator.FieldDef sub = entry.getValue();
                Object value = rawRow.get(columnOf(sub.name(), sub.type()));
                row.put(sub.name(), decodeValue(sub.type(), sub.multiple(), value));
            }
            // 稳定行身份：保留子表行主键，来源追溯不以行序号代替
            row.put("id", rawRow.get("id"));
            rows.add(row);
        }
        return rows;
    }

    /** 多值/日期解码：JSON 数组串 → List、JSON 对象串 → Map、日期时间 → ISO 文本。 */
    private Object decodeValue(String type, boolean multiple, Object value) {
        if (value == null) {
            return null;
        }
        boolean listLike = "MULTISELECT".equals(type) || "ATTACHMENT".equals(type) || "IMAGE".equals(type)
                || (multiple && ("USER".equals(type) || "DEPT".equals(type)));
        if (listLike && value instanceof String text && text.startsWith("[")) {
            try {
                return objectMapper.readValue(text,
                        new TypeReference<List<Object>>() { });
            } catch (Exception e) {
                log.warn("Failed to decode list value for type {}: keep raw", type);
                return value;
            }
        }
        if ("DATASOURCE".equals(type) && value instanceof String text && text.startsWith("{")) {
            try {
                return objectMapper.readValue(text,
                        new TypeReference<Map<String, Object>>() { });
            } catch (Exception e) {
                log.warn("Failed to decode datasource value: keep raw");
                return value;
            }
        }
        if (value instanceof LocalDateTime || value instanceof LocalDate
                || value instanceof LocalTime || value instanceof Timestamp
                || value instanceof Date || value instanceof Time) {
            return String.valueOf(value);
        }
        return value;
    }

    private String columnOf(String name, String type) {
        return ColumnValidation.physicalColumnName(name,
                FieldType.valueOf(type));
    }
}
