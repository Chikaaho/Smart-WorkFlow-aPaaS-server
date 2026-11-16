package com.sw.ck.form.vo;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Map;
import com.sw.ck.common.value.DynamicData;

/**
 * 动态表单记录/详情/工作台行。
 * <p>
 * 字段完全由表单定义与记录数据决定，使用 {@link JsonValue} 直接序列化原始 Map，
 * 保持列表、详情与导出消费方看到的 JSON 形状不变。
 * </p>
 */
public class FormDataRecordVO {

    private final DynamicData values;

    public FormDataRecordVO(Map<String, Object> values) {
        this.values = DynamicData.of(values == null ? Map.of() : values);
    }

    @JsonValue
    public Object getValues() {
        return values.getValue();
    }
}
