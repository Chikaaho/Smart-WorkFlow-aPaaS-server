package com.sw.ck.bpm.process.dto;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Map;

/**
 * 普通视角分类聚合数量：key 为分类 ID（未分类为 0），value 为可见事项数。
 * <p>
 * 分类 ID 是动态字典键，沿用 {@link JsonValue} 直接序列化为
 * {@code {"0":1,"5":2}}，保持原 Map 响应形状不变。
 * </p>
 */
public class CategoryCountsDTO {

    private final Map<Long, Long> counts;

    public CategoryCountsDTO(Map<Long, Long> counts) {
        this.counts = counts == null ? Map.of() : counts;
    }

    @JsonValue
    public Map<Long, Long> getCounts() {
        return counts;
    }
}
