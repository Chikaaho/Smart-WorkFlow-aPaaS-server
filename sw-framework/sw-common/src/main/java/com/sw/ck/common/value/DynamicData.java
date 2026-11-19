package com.sw.ck.common.value;

import com.fasterxml.jackson.annotation.JsonValue;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 动态载荷收敛封装（表单数据、流程变量、脚本样本等结构由运行时定义的数据）。
 * <p>
 * 用途是把响应契约里「裸 Map / 裸 Object」收敛成一个有名字的类型：
 * 接口签名与 DTO 字段统一声明 {@code DynamicData}，Swagger 即可生成具名 schema
 * 并挂说明，不再出现 schema 为空的 {@code Object} 或裸 {@code Map}。
 * </p>
 * <p>
 * 序列化经 {@link JsonValue} 直接展开内部值，JSON 形状与原先的 Map/Object 完全一致，
 * 前端零改动。内部值允许为 null（复杂值序列化为 {@code null}）。
 * </p>
 */
public class DynamicData implements Serializable {

    private static final long serialVersionUID = 1L;

    private Object value;

    private DynamicData(Object value) {
        this.value = value;
    }

    /** 唯一构造入口；入参可为 Map、List、标量或 null。 */
    public static DynamicData of(Object value) {
        return new DynamicData(value);
    }

    /** 空对象载荷。 */
    public static DynamicData empty() {
        return new DynamicData(new LinkedHashMap<String, Object>());
    }

    /** 仅当内部值为 Map 时返回其自身，其它形态返回空 Map（读取侧安全兜底）。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> asMap() {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @JsonValue
    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }
}
