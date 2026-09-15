package com.sw.ck.form.service;

import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.exception.FormErrorCode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * 公式引擎（I2）：definition 中 FORMULA 字段 expression 的唯一解析、依赖校验与求值实现。
 *
 * <h3>契约（方向 §4.2）</h3>
 * <ul>
 *   <li>仅允许：数字字面量、{@code ${fieldName}} 字段引用、+ - * / %、括号、
 *       白名单函数 ABS / ROUND(x,scale) / MIN / MAX / DAYS(end,start)（日期差天数）。</li>
 *   <li>禁止任意脚本、反射、外部输入；表达式在发布前经 {@link #validateDependencies} 校验：
 *       未知字段与循环依赖在保存/发布前拒绝。</li>
 *   <li>空值语义：任一被引用字段为空 → 结果为空（不猜测 0）。</li>
 *   <li>精度：中间运算 scale 12、除法 {@link MathContext#DECIMAL32} 等价精度，
 *       最终结果 scale 6（对齐 NUMBER 列 NUMERIC(20,6)）；除数为 0 抛错。</li>
 *   <li>客户端提交的公式值不消费：正式值一律由服务端按冻结 definition 重算。</li>
 * </ul>
 */
@Component
public class FormulaEngine {

    /** 结果统一 scale（对齐动态宽表 NUMERIC(20,6)） */
    public static final int RESULT_SCALE = 6;

    // ==================== 引用提取与依赖校验 ====================

    /**
     * 提取表达式中全部 {@code ${field}} 引用。
     *
     * @throws BaseException FORMULA_INVALID 当出现非法引用形式（如 {@code ${}}、嵌套花括号）
     */
    public Set<String> extractRefs(String expression) {
        Set<String> refs = new LinkedHashSet<>();
        if (expression == null || expression.isBlank()) {
            throw new BaseException(FormErrorCode.FORMULA_INVALID, "公式表达式为空");
        }
        int i = 0;
        while ((i = expression.indexOf("${", i)) >= 0) {
            int end = expression.indexOf('}', i);
            if (end < 0) {
                throw new BaseException(FormErrorCode.FORMULA_INVALID,
                        "公式引用缺少闭合 '}'（位置 " + i + "）");
            }
            String ref = expression.substring(i + 2, end);
            if (!ref.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                throw new BaseException(FormErrorCode.FORMULA_INVALID,
                        "非法字段引用 '${" + ref + "}'");
            }
            refs.add(ref);
            i = end + 1;
        }
        return refs;
    }

    /**
     * 发布前依赖校验：未知字段与 FORMULA 间循环依赖拒绝。
     *
     * @param expressions FORMULA 字段名 → 表达式
     * @param knownFields 全部已登记字段名（含 FORMULA 字段自身）
     */
    public void validateDependencies(Map<String, String> expressions, Set<String> knownFields) {
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : expressions.entrySet()) {
            // 语法校验（空值全量穿透：REF 解析为 null，逐 token 结构校验；I2 E3b）。
            // 常量除零在此一并拒绝——任何运行期都必然失败的表达式不得发布。
            validateSyntax(e.getValue());
            for (String ref : extractRefs(e.getValue())) {
                if (!knownFields.contains(ref)) {
                    throw new BaseException(FormErrorCode.FORMULA_UNKNOWN_FIELD,
                            "公式字段 '" + e.getKey() + "' 引用了未定义字段 '" + ref + "'");
                }
            }
            graph.put(e.getKey(), extractRefs(e.getValue()));
        }
        // DFS 环检测（仅沿 FORMULA→FORMULA 边）
        for (String start : graph.keySet()) {
            Deque<String> path = new ArrayDeque<>();
            Set<String> visiting = new HashSet<>();
            if (hasCycle(start, graph, visiting, path)) {
                throw new BaseException(FormErrorCode.FORMULA_CYCLE,
                        "公式字段存在循环依赖: " + String.join(" → ", path));
            }
        }
    }

    /**
     * 表达式语法校验：以空值集驱动解析器，只验结构（记号/括号/运算符/函数），
     * 不消费任何字段值。非法表达式在发布前拒绝（1209），不带病上线。
     */
    public void validateSyntax(String expression) {
        List<Tok> tokens = new Lexer(expression).tokenize();
        Map<String, Object> nullValues = new java.util.HashMap<>();
        for (Tok tok : tokens) {
            if (tok.type() == TokType.REF) {
                nullValues.put(tok.text(), null);
            }
        }
        Parser parser = new Parser(tokens, nullValues);
        parser.parseExpression();
        parser.expectEnd();
    }

    private boolean hasCycle(String node, Map<String, Set<String>> graph,
                             Set<String> visiting, Deque<String> path) {
        if (visiting.contains(node)) {
            path.push(node);
            return true;
        }
        Set<String> refs = graph.get(node);
        if (refs == null || refs.isEmpty()) {
            return false;
        }
        visiting.add(node);
        path.push(node);
        for (String ref : refs) {
            if (graph.containsKey(ref) && hasCycle(ref, graph, visiting, path)) {
                return true;
            }
        }
        path.pop();
        visiting.remove(node);
        return false;
    }

    // ==================== 求值 ====================

    /**
     * 按冻结 definition 的 expression 与当前记录字段值求值。
     *
     * @param expression  公式表达式
     * @param fieldValues 字段名 → 值（NUMBER/DATE/TEXT 等原始值）
     * @return 结果（scale 6）；任一被引用字段为空 → null
     * @throws BaseException FORMULA_INVALID 语法/运算错误（含除零）
     */
    public BigDecimal evaluate(String expression, Map<String, Object> fieldValues) {
        Lexer lexer = new Lexer(expression);
        Parser parser = new Parser(lexer.tokenize(), fieldValues);
        BigDecimal result = parser.parseExpression();
        parser.expectEnd();
        if (result == null) {
            return null;
        }
        return result.setScale(RESULT_SCALE, RoundingMode.HALF_UP);
    }

    // ==================== 词法 ====================

    private enum TokType { NUMBER, REF, OP, LPAREN, RPAREN, COMMA, FUNC, END }

    private record Tok(TokType type, String text, BigDecimal num) {}

    private final class Lexer {
        private final String src;
        private int pos;

        Lexer(String src) {
            this.src = Objects.requireNonNull(src, "expression");
        }

        List<Tok> tokenize() {
            List<Tok> tokens = new ArrayList<>();
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (Character.isWhitespace(c)) {
                    pos++;
                    continue;
                }
                if (c == '$' && pos + 1 < src.length() && src.charAt(pos + 1) == '{') {
                    int end = src.indexOf('}', pos);
                    if (end < 0) {
                        throw new BaseException(FormErrorCode.FORMULA_INVALID, "公式引用缺少闭合 '}'");
                    }
                    tokens.add(new Tok(TokType.REF, src.substring(pos + 2, end), null));
                    pos = end + 1;
                    continue;
                }
                if (Character.isDigit(c) || c == '.') {
                    int start = pos;
                    while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
                        pos++;
                    }
                    String num = src.substring(start, pos);
                    try {
                        tokens.add(new Tok(TokType.NUMBER, num, new BigDecimal(num)));
                    } catch (NumberFormatException e) {
                        throw new BaseException(FormErrorCode.FORMULA_INVALID, "非法数字: " + num);
                    }
                    continue;
                }
                if (Character.isLetter(c)) {
                    int start = pos;
                    while (pos < src.length() && Character.isLetterOrDigit(src.charAt(pos))) {
                        pos++;
                    }
                    tokens.add(new Tok(TokType.FUNC, src.substring(start, pos).toUpperCase(Locale.ROOT), null));
                    continue;
                }
                switch (c) {
                    case '+', '-', '*', '/', '%' -> {
                        tokens.add(new Tok(TokType.OP, String.valueOf(c), null));
                        pos++;
                    }
                    case '(' -> {
                        tokens.add(new Tok(TokType.LPAREN, "(", null));
                        pos++;
                    }
                    case ')' -> {
                        tokens.add(new Tok(TokType.RPAREN, ")", null));
                        pos++;
                    }
                    case ',' -> {
                        tokens.add(new Tok(TokType.COMMA, ",", null));
                        pos++;
                    }
                    default -> throw new BaseException(FormErrorCode.FORMULA_INVALID,
                            "公式包含非法字符: '" + c + "'");
                }
            }
            tokens.add(new Tok(TokType.END, "", null));
            return tokens;
        }
    }

    // ==================== 递归下降解析求值 ====================

    private final class Parser {
        private final List<Tok> tokens;
        private final Map<String, Object> fieldValues;
        private int idx;

        Parser(List<Tok> tokens, Map<String, Object> fieldValues) {
            this.tokens = tokens;
            this.fieldValues = fieldValues == null ? Map.of() : fieldValues;
        }

        BigDecimal parseExpression() {
            BigDecimal left = parseTerm();
            while (peek().type() == TokType.OP && ("+".equals(peek().text()) || "-".equals(peek().text()))) {
                String op = next().text();
                BigDecimal right = parseTerm();
                if (left == null || right == null) {
                    left = null;
                } else {
                    left = "+".equals(op) ? left.add(right) : left.subtract(right);
                }
            }
            return left;
        }

        private BigDecimal parseTerm() {
            BigDecimal left = parseFactor();
            while (peek().type() == TokType.OP && ("*".equals(peek().text()) || "/".equals(peek().text()) || "%".equals(peek().text()))) {
                String op = next().text();
                BigDecimal right = parseFactor();
                if (left == null || right == null) {
                    left = null;
                } else if ("/".equals(op)) {
                    if (right.compareTo(BigDecimal.ZERO) == 0) {
                        throw new BaseException(FormErrorCode.FORMULA_INVALID, "公式除数为 0");
                    }
                    left = left.divide(right, MathContext.DECIMAL32);
                } else if ("%".equals(op)) {
                    if (right.compareTo(BigDecimal.ZERO) == 0) {
                        throw new BaseException(FormErrorCode.FORMULA_INVALID, "公式取模除数为 0");
                    }
                    left = left.remainder(right);
                } else {
                    left = left.multiply(right);
                }
            }
            return left;
        }

        private BigDecimal parseFactor() {
            Tok tok = next();
            return switch (tok.type()) {
                case OP -> {
                    if ("-".equals(tok.text())) {
                        BigDecimal v = parseFactor();
                        yield v == null ? null : v.negate();
                    }
                    if ("+".equals(tok.text())) {
                        yield parseFactor();
                    }
                    throw new BaseException(FormErrorCode.FORMULA_INVALID, "非法运算符: " + tok.text());
                }
                case NUMBER -> tok.num();
                case REF -> resolveRef(tok.text());
                case LPAREN -> {
                    BigDecimal v = parseExpression();
                    if (next().type() != TokType.RPAREN) {
                        throw new BaseException(FormErrorCode.FORMULA_INVALID, "缺少右括号");
                    }
                    yield v;
                }
                case FUNC -> parseFunctionCall(tok.text());
                default -> throw new BaseException(FormErrorCode.FORMULA_INVALID,
                        "意外的记号: '" + tok.text() + "'");
            };
        }

        private BigDecimal parseFunctionCall(String fn) {
            if (next().type() != TokType.LPAREN) {
                throw new BaseException(FormErrorCode.FORMULA_INVALID, "函数 " + fn + " 缺少 '('");
            }
            List<BigDecimal> args = new ArrayList<>();
            if (peek().type() != TokType.RPAREN) {
                args.add(parseExpression());
                while (peek().type() == TokType.COMMA) {
                    next();
                    args.add(parseExpression());
                }
            }
            if (next().type() != TokType.RPAREN) {
                throw new BaseException(FormErrorCode.FORMULA_INVALID, "函数 " + fn + " 缺少 ')'");
            }
            return switch (fn) {
                case "ABS" -> requireArgs(fn, args, 1) ? (args.get(0) == null ? null : args.get(0).abs()) : null;
                case "ROUND" -> {
                    if (args.size() != 2) {
                        throw new BaseException(FormErrorCode.FORMULA_INVALID, "ROUND 需要 2 个参数");
                    }
                    BigDecimal v = args.get(0);
                    BigDecimal scale = args.get(1);
                    if (v == null || scale == null) {
                        yield null;
                    }
                    yield v.setScale(scale.intValueExact(), RoundingMode.HALF_UP);
                }
                case "MIN" -> extreme(args, true);
                case "MAX" -> extreme(args, false);
                case "DAYS" -> {
                    if (args.size() != 2) {
                        throw new BaseException(FormErrorCode.FORMULA_INVALID, "DAYS 需要 2 个参数");
                    }
                    yield args.get(0) == null || args.get(1) == null
                            ? null
                            : args.get(0).subtract(args.get(1));
                }
                default -> throw new BaseException(FormErrorCode.FORMULA_INVALID,
                        "不在白名单的函数: " + fn);
            };
        }

        /** DAYS 引用日期字段：解析阶段把 DATE 引用转为 epoch 天数，这里只做差值。 */
        private BigDecimal extreme(List<BigDecimal> args, boolean min) {
            BigDecimal best = null;
            for (BigDecimal a : args) {
                if (a == null) {
                    return null;
                }
                if (best == null || (min ? a.compareTo(best) < 0 : a.compareTo(best) > 0)) {
                    best = a;
                }
            }
            return best;
        }

        private boolean requireArgs(String fn, List<BigDecimal> args, int n) {
            if (args.size() != n) {
                throw new BaseException(FormErrorCode.FORMULA_INVALID, fn + " 需要 " + n + " 个参数");
            }
            return true;
        }

        private BigDecimal resolveRef(String fieldName) {
            if (!fieldValues.containsKey(fieldName)) {
                throw new BaseException(FormErrorCode.FORMULA_UNKNOWN_FIELD,
                        "公式引用了未提供的字段: '" + fieldName + "'");
            }
            Object raw = fieldValues.get(fieldName);
            if (raw == null || (raw instanceof String s && s.isBlank())) {
                return null;
            }
            if (raw instanceof BigDecimal bd) {
                return bd;
            }
            if (raw instanceof Number n) {
                return new BigDecimal(n.toString());
            }
            if (raw instanceof String s) {
                // 日期字符串 → epoch 天（供 DAYS）；其余尝试数字
                String trimmed = s.trim();
                try {
                    return new BigDecimal(trimmed);
                } catch (NumberFormatException ignored) {
                    try {
                        LocalDate date = LocalDate.parse(trimmed.length() > 10 ? trimmed.substring(0, 10) : trimmed);
                        return BigDecimal.valueOf(date.toEpochDay());
                    } catch (DateTimeParseException e) {
                        throw new BaseException(FormErrorCode.FORMULA_INVALID,
                                "字段 '" + fieldName + "' 的值 '" + trimmed + "' 不能参与数值运算");
                    }
                }
            }
            throw new BaseException(FormErrorCode.FORMULA_INVALID,
                    "字段 '" + fieldName + "' 的值类型不能参与数值运算");
        }

        void expectEnd() {
            if (peek().type() != TokType.END) {
                throw new BaseException(FormErrorCode.FORMULA_INVALID,
                        "表达式存在多余内容: '" + peek().text() + "'");
            }
        }

        private Tok peek() {
            return tokens.get(idx);
        }

        private Tok next() {
            return tokens.get(idx++);
        }
    }
}
