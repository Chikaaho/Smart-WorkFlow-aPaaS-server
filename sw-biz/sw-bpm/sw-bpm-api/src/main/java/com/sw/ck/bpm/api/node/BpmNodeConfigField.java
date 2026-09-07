package com.sw.ck.bpm.api.node;

import java.util.Map;

/**
 * 节点配置字段描述，供设计端和发布前校验共同消费。
 *
 * @param key 字段名，支持以点号表达嵌套字段
 * @param label 面向设计器的字段名称
 * @param type 逻辑值类型，例如 string、object、array
 * @param required 是否必填
 * @param validation 额外校验约束，值为产品语义数据
 */
public record BpmNodeConfigField(
        String key,
        String label,
        String type,
        boolean required,
        Map<String, Object> validation) {
}
