package com.sw.ck.agent.orchestration;

import com.sw.ck.agent.dto.graph.GraphElement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图节点 config 读取工具（自 AgentGraphInterpreter 拆出，行为不变）。
 * <p>
 * 承载节点/出边 config 键的宽松读取语义：Number 与十进制数字字符串均可解析为
 * 数值 id（graph_json 落库后 Long 一律是字符串形态，见
 * {@link AgentGraphInterpreter#parseConfigLong} 的历史说明）；缺失/非 String/空白
 * 一律按"未配置"处理。方法均为无状态静态工具，供解释器主循环与各节点执行类共用。
 * </p>
 */
final class AgentGraphNodeConfigs {

    private AgentGraphNodeConfigs() {
    }

    static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /**
     * 读取节点 config 中的 String 型可选键（与 {@link #requireConfigString} 不同：
     * 缺失/非 String 返回 null 而非抛错，供 systemPrompt / userPromptTemplate 等可选契约使用）。
     */
    static String configString(GraphElement node, String key) {
        Map<String, Object> cfg = node.getConfig();
        if (cfg == null) {
            return null;
        }
        Object v = cfg.get(key);
        if (!(v instanceof String s)) {
            return null;
        }
        return s;
    }

    static Long configLong(GraphElement node, String key) {
        if (node.getConfig() == null) return null;
        Object value = node.getConfig().get(key);
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    static Integer configInteger(GraphElement node, String key) {
        Long value = configLong(node, key);
        return value == null ? null : value.intValue();
    }

    /** config 读取正整数（缺失/非法用默认值；最小 1）。 */
    public static int configInt(GraphElement node, String key, int defaultValue) {
        Long parsed = parseConfigLong(node.getConfig() == null ? null : node.getConfig().get(key));
        return parsed == null ? defaultValue : (int) Math.max(1L, Math.min(parsed, 50L));
    }

    /** Double 配置读取（Number/数字字符串；null/非法 → 默认值）。 */
    public static double configDouble(GraphElement node, String key, double defaultValue) {
        Object value = node.getConfig() == null ? null : node.getConfig().get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /** config 读取字符串列表（支持 List&lt;String&gt; 与逗号分隔字符串；空/缺失返回空列表）。 */
    public static List<String> configStringList(GraphElement node, String key) {
        Object value = node.getConfig() == null ? null : node.getConfig().get(key);
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s && !s.isBlank()) {
                    result.add(s.trim());
                }
            }
        } else if (value instanceof String s && !s.isBlank()) {
            for (String part : s.split(",")) {
                if (!part.isBlank()) {
                    result.add(part.trim());
                }
            }
        }
        return result;
    }

    static List<Map<String, Object>> configMapList(GraphElement node, String key) {
        if (node.getConfig() == null) return List.of();
        Object raw = node.getConfig().get(key);
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((k, v) -> {
                    if (k != null) copy.put(String.valueOf(k), v);
                });
                result.add(copy);
            }
        }
        return result;
    }

    static List<Long> configLongList(GraphElement node, String key) {
        if (node.getConfig() == null) return List.of();
        Object raw = node.getConfig().get(key);
        if (!(raw instanceof List<?> list)) return List.of();
        List<Long> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Number number) result.add(number.longValue());
            else if (item instanceof String text && !text.isBlank()) {
                try {
                    result.add(Long.parseLong(text.trim()));
                } catch (NumberFormatException ignore) {
                    // 非法项忽略（发布校验负责拦截）
                }
            }
        }
        return result;
    }

    static List<String> configStringValues(GraphElement node, String key) {
        if (node.getConfig() == null) return List.of();
        Object raw = node.getConfig().get(key);
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null && !String.valueOf(item).isBlank()) result.add(String.valueOf(item).trim());
        }
        return result;
    }

    /**
     * 解析节点 config 中的数值 id（{@code agentModelConfigId} 等）。
     * <p>
     * 接受 {@link Number} 与十进制数字字符串：项目全局 Jackson 对 Long 启用
     * ToStringSerializer（防 JS 精度丢失），graph_json 落库后 id 一律是字符串形态；
     * 仅收 Number 会让任何含 LLM 节点的已发布图都无法执行（F1 阶段 1 真实联调发现）。
     * 空白或非数字 → null（由调用方决定错误语义）。
     * </p>
     */
    public static Long parseConfigLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** 读取节点 config 中的 Long 型必填键（缺失/非数值 → 运行时错误，防御性兜底） */
    static Long requireConfigId(GraphElement node, String key) {
        Map<String, Object> config = node.getConfig();
        Long parsed = config == null ? null : parseConfigLong(config.get(key));
        if (parsed == null) {
            throw new AgentGraphInterpreter.GraphExecutionException(
                    AgentGraphInterpreter.ERROR_CATEGORY_TOPOLOGY_INVALID,
                    "节点缺少 " + key + ": " + node.getId());
        }
        return parsed;
    }

    /** 读取节点 config 中的 String 型必填键（缺失/空白 → 运行时错误，防御性兜底） */
    static String requireConfigString(GraphElement node, String key) {
        Map<String, Object> config = node.getConfig();
        if (config == null || !(config.get(key) instanceof String s) || s.isBlank()) {
            throw new AgentGraphInterpreter.GraphExecutionException(
                    AgentGraphInterpreter.ERROR_CATEGORY_TOPOLOGY_INVALID,
                    "节点缺少 " + key + ": " + node.getId());
        }
        return s;
    }

    /**
     * 读取边的条件关键词（执行契约键 {@code config.keyword}）：无 config / 键缺失 /
     * 值非 String / 空串均视为"无关键词"（该边为默认边候选）。
     */
    public static String keywordOf(GraphElement edge) {
        Map<String, Object> config = edge.getConfig();
        if (config == null) {
            return null;
        }
        Object keyword = config.get(AgentGraphInterpreter.CONFIG_KEY_KEYWORD);
        if (!(keyword instanceof String s) || s.isBlank()) {
            return null;
        }
        return s;
    }

    /**
     * LOOP 节点迭代上限：config.maxIterations（Number → int；
     * 缺失/非数值 = 默认 {@link AgentGraphInterpreter#DEFAULT_MAX_ITERATIONS}；&lt;1 由执行前校验拦截，若被
     * 直接构造绕过校验，首次到达（计数 1）即触发"循环迭代次数超限"，fail-fast 不静默）。
     */
    static int maxIterationsOf(GraphElement node) {
        Map<String, Object> config = node.getConfig();
        if (config == null) {
            return AgentGraphInterpreter.DEFAULT_MAX_ITERATIONS;
        }
        Long parsed = parseConfigLong(config.get(AgentGraphInterpreter.CONFIG_KEY_MAX_ITERATIONS));
        if (parsed == null) {
            return AgentGraphInterpreter.DEFAULT_MAX_ITERATIONS;
        }
        return parsed.intValue();
    }
}
