package com.sw.ck.bpm.api.expression;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 流程配置的最小受控表达式解释器。
 * <p>只读取传入 Map，支持路径、布尔值、比较、&&/||、exists 和括号；不执行 Java/JavaScript。</p>
 */
public final class RestrictedExpressionEvaluator {

    private RestrictedExpressionEvaluator() {
    }

    public static Object value(String expression, Map<String, Object> variables) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("表达式不能为空");
        }
        String text = expression.trim();
        validateBalancedSyntax(text);
        if (isWrapped(text)) {
            return value(text.substring(1, text.length() - 1), variables);
        }
        List<String> orParts = splitTopLevel(text, "||");
        if (orParts.size() > 1) {
            return orParts.stream().anyMatch(part -> truthy(value(part, variables)));
        }
        List<String> andParts = splitTopLevel(text, "&&");
        if (andParts.size() > 1) {
            return andParts.stream().allMatch(part -> truthy(value(part, variables)));
        }
        if (text.startsWith("exists(") && text.endsWith(")")) {
            return resolve(text.substring(7, text.length() - 1).trim(), variables) != null;
        }
        if (text.startsWith("not(") && text.endsWith(")")) {
            return !truthy(value(text.substring(4, text.length() - 1), variables));
        }
        for (String operator : List.of("!=", ">=", "<=", "==", ">", "<")) {
            int index = findTopLevel(text, operator);
            if (index >= 0) {
                Object left = value(text.substring(0, index), variables);
                Object right = value(text.substring(index + operator.length()), variables);
                return compare(left, right, operator);
            }
        }
        return resolve(text, variables);
    }

    public static boolean matches(String expression, Map<String, Object> variables) {
        return truthy(value(expression, variables));
    }

    public static List<String> values(String expression, Map<String, Object> variables) {
        Object result = value(expression, variables);
        if (result == null) return List.of();
        if (result instanceof Collection<?> collection) {
            return collection.stream().filter(item -> item != null)
                    .map(String::valueOf).filter(item -> !item.isBlank()).distinct().toList();
        }
        String text = String.valueOf(result).trim();
        return text.isBlank() ? List.of() : List.of(text);
    }

    private static Object resolve(String raw, Map<String, Object> variables) {
        String text = raw.trim();
        if ((text.startsWith("'") && text.endsWith("'"))
                || (text.startsWith("\"") && text.endsWith("\""))) {
            return text.substring(1, text.length() - 1);
        }
        if ("true".equalsIgnoreCase(text)) return true;
        if ("false".equalsIgnoreCase(text)) return false;
        if ("null".equalsIgnoreCase(text)) return null;
        if (text.matches("-?[0-9]+(\\.[0-9]+)?")) return new BigDecimal(text);
        if (text.startsWith("[") && text.endsWith("]")) {
            List<String> result = new ArrayList<>();
            for (String item : splitTopLevel(text.substring(1, text.length() - 1), ",")) {
                if (!item.isBlank()) result.add(String.valueOf(resolve(item, variables)));
            }
            return result;
        }
        String path = text;
        if (path.startsWith("variable(")) {
            path = path.substring(9, path.length() - 1).trim();
        }
        if ((path.startsWith("'") && path.endsWith("'"))
                || (path.startsWith("\"") && path.endsWith("\""))) {
            path = path.substring(1, path.length() - 1);
        }
        Object current = variables;
        if (path.startsWith("form.")) {
            current = variables.get("formData");
            path = path.substring(5);
        } else if (path.startsWith("data.")) {
            current = variables.get("formData");
            path = path.substring(5);
        } else if (path.startsWith("variables.")) {
            path = path.substring(10);
        }
        for (String part : path.split("\\.")) {
            if (part.isBlank()) return null;
            if (current instanceof Map<?, ?> map) current = map.get(part);
            else return null;
        }
        return current;
    }

    private static boolean compare(Object left, Object right, String operator) {
        if ("==".equals(operator)) return equal(left, right);
        if ("!=".equals(operator)) return !equal(left, right);
        if (left == null || right == null) return false;
        int compared;
        boolean numericOperand = left instanceof Number || right instanceof Number;
        try {
            compared = new BigDecimal(String.valueOf(left)).compareTo(new BigDecimal(String.valueOf(right)));
        } catch (NumberFormatException ignored) {
            if (numericOperand) {
                throw new IllegalArgumentException("表达式比较类型不匹配");
            }
            compared = String.valueOf(left).compareTo(String.valueOf(right));
        }
        return switch (operator) {
            case ">" -> compared > 0;
            case ">=" -> compared >= 0;
            case "<" -> compared < 0;
            case "<=" -> compared <= 0;
            default -> false;
        };
    }

    private static boolean equal(Object left, Object right) {
        if (left == null || right == null) return left == right;
        return String.valueOf(left).equals(String.valueOf(right));
    }

    private static boolean truthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Collection<?> collection) return !collection.isEmpty();
        if (value instanceof Number number) return number.doubleValue() != 0;
        return !String.valueOf(value).isBlank() && !"false".equalsIgnoreCase(String.valueOf(value));
    }

    private static boolean isWrapped(String value) {
        return value.length() >= 2 && value.charAt(0) == '(' && value.charAt(value.length() - 1) == ')'
                && matchingAtEnd(value);
    }

    private static boolean matchingAtEnd(String value) {
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\'' || c == '"') quoted = !quoted;
            if (quoted) continue;
            if (c == '(') depth++;
            if (c == ')' && --depth == 0 && i != value.length() - 1) return false;
        }
        return depth == 0;
    }

    private static int findTopLevel(String value, String operator) {
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i <= value.length() - operator.length(); i++) {
            char c = value.charAt(i);
            if (c == '\'' || c == '"') quoted = !quoted;
            if (quoted) continue;
            if (c == '(' || c == '[') depth++;
            if (c == ')' || c == ']') depth--;
            if (depth == 0 && value.startsWith(operator, i)) return i;
        }
        return -1;
    }

    private static List<String> splitTopLevel(String value, String delimiter) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i <= value.length() - delimiter.length(); i++) {
            char c = value.charAt(i);
            if (c == '\'' || c == '"') quoted = !quoted;
            if (quoted) continue;
            if (c == '(' || c == '[') depth++;
            if (c == ')' || c == ']') depth--;
            if (depth == 0 && value.startsWith(delimiter, i)) {
                parts.add(value.substring(start, i).trim());
                start = i + delimiter.length();
                i += delimiter.length() - 1;
            }
        }
        parts.add(value.substring(start).trim());
        return parts;
    }

    private static void validateBalancedSyntax(String value) {
        int parentheses = 0;
        int brackets = 0;
        boolean quoted = false;
        char quote = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c == '\'' || c == '"')) {
                if (quoted && c == quote) quoted = false;
                else if (!quoted) { quoted = true; quote = c; }
                continue;
            }
            if (quoted) continue;
            if (c == '(') parentheses++;
            if (c == ')' && --parentheses < 0) throw new IllegalArgumentException("括号不匹配");
            if (c == '[') brackets++;
            if (c == ']' && --brackets < 0) throw new IllegalArgumentException("方括号不匹配");
        }
        if (quoted || parentheses != 0 || brackets != 0) {
            throw new IllegalArgumentException("表达式语法不完整");
        }
    }
}
