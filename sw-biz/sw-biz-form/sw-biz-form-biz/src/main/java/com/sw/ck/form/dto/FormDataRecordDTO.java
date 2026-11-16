package com.sw.ck.form.dto;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Map;

/**
 * 动态表单记录/详情/工作台行。
 * <p>
 * 字段完全由表单定义与记录数据决定，使用 {@link JsonValue} 直接序列化原始 Map，
 * 保持列表、详情与导出消费方看到的 JSON 形状不变。
 * </p>
 */
public class FormDataRecordDTO {

    private final Map<String, Object> values;

    public FormDataRecordDTO(Map<String, Object> values) {
        this.values = values == null ? Map.of() : values;
    }

    @JsonValue
    public Map<String, Object> getValues() {
        return values;
    }
}
