package com.sw.ck.bpm.engine.script;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * BF20261013-021：脚本 `#函数名(...)` 语法糖编译器（与 Web 侧 {@code script-sugar.ts} 同语义）。
 * <p>
 * 业务写法 {@code var value = #获取流程变量(var_dept_ids)} 编译为普通 JS 调用
 * {@code var value = 获取流程变量("var_dept_ids")}：
 * <ul>
 *   <li>{@code #} 只作标记，编译时剔除——JS 标识符本身允许中文，宿主函数名不变；</li>
 *   <li>参数为<b>裸标识符</b>时按“名字字符串”处理（自动加引号）；已带引号的字符串、
 *       数字、表达式原样透传（{@code #获取流程变量("" + name)} 可表达动态名）；</li>
 *   <li>参数里的嵌套 {@code #调用} 递归编译；字符串/注释/模板字符串内的 {@code #} 不动；
 *       模板字符串 {@code ${...}} 内按代码处理；</li>
 *   <li>{@code true/false/null/undefined} 是字面量，不加引号。</li>
 * </ul>
 * 语法错误（{@code #} 后缺函数名/缺括号/括号不闭合）抛出带定位说明的
 * {@link IllegalArgumentException}，由调用方归入 SCRIPT_ERROR 诊断。
 * </p>
 */
public final class ScriptSugar {

    private static final Set<String> KEYWORD_LITERALS = Set.of("true", "false", "null", "undefined");

    private ScriptSugar() {
    }

    /** 编译语法糖；无 {@code #} 时原样返回。 */
    public static String transpile(String script) {
        if (script == null || script.isEmpty() || script.indexOf('#') < 0) {
            return script;
        }
        StringBuilder out = new StringBuilder(script.length() + 16);
        int end = scanCode(script, 0, out, null);
        if (end != script.length()) {
            throw new IllegalArgumentException("语法糖解析异常：存在未闭合的结构（位置 " + end + "）");
        }
        return out.toString();
    }

    /**
     * 代码区扫描：原样搬移普通字符，处理字符串/注释/模板字符串，遇到 {@code #} 调用做替换。
     *
     * @param stopAt  非 null 时表示当前处于某个花括号内（模板字符串 ${}），遇到它即返回
     * @return 消费到的下标（stopAt != null 时为该字符下标；否则为串尾）
     */
    private static int scanCode(String src, int start, StringBuilder out, Character stopAt) {
        int i = start;
        int n = src.length();
        int braceDepth = 0;
        while (i < n) {
            char c = src.charAt(i);
            if (stopAt != null && c == stopAt) {
                if (braceDepth == 0) {
                    return i;
                }
                braceDepth--;
                out.append(c);
                i++;
                continue;
            }
            if (stopAt != null && c == '{') {
                braceDepth++;
                out.append(c);
                i++;
                continue;
            }
            if (c == '\'' || c == '"') {
                i = copyQuoted(src, i, c, out);
                continue;
            }
            if (c == '`') {
                i = copyTemplate(src, i, out);
                continue;
            }
            if (c == '/' && i + 1 < n) {
                char next = src.charAt(i + 1);
                if (next == '/') {
                    int lineEnd = src.indexOf('\n', i);
                    int stop = lineEnd < 0 ? n : lineEnd;
                    out.append(src, i, stop);
                    i = stop;
                    continue;
                }
                if (next == '*') {
                    int close = src.indexOf("*/", i + 2);
                    if (close < 0) {
                        throw new IllegalArgumentException("脚本注释未闭合（位置 " + i + "）");
                    }
                    out.append(src, i, close + 2);
                    i = close + 2;
                    continue;
                }
            }
            if (c == '#') {
                i = translateCall(src, i, out);
                continue;
            }
            out.append(c);
            i++;
        }
        if (stopAt != null) {
            throw new IllegalArgumentException("模板字符串插值未闭合（缺失 '" + stopAt + "'）");
        }
        return i;
    }

    /** 复制普通字符串字面量（' 或 "）。返回下一处理位置。 */
    private static int copyQuoted(String src, int start, char quote, StringBuilder out) {
        int i = start + 1;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) {
                out.append(src, start, i + 1);
                return i + 1;
            }
            if (c == '\n') {
                break;
            }
            i++;
        }
        throw new IllegalArgumentException("字符串字面量未闭合（位置 " + start + "）");
    }

    /** 复制模板字符串，凡 ${...} 内部按代码递归编译。返回下一处理位置。 */
    private static int copyTemplate(String src, int start, StringBuilder out) {
        out.append('`');
        int i = start + 1;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\\') {
                out.append(src, i, Math.min(i + 2, n));
                i += 2;
                continue;
            }
            if (c == '`') {
                out.append('`');
                return i + 1;
            }
            if (c == '$' && i + 1 < n && src.charAt(i + 1) == '{') {
                out.append("${");
                int close = scanCode(src, i + 2, out, '}');
                out.append('}');
                i = close + 1;
                continue;
            }
            out.append(c);
            i++;
        }
        throw new IllegalArgumentException("模板字符串未闭合（位置 " + start + "）");
    }

    /**
     * 处理一处 {@code #函数名(args)}：剔除 #、递归编译参数、裸标识符加引号。
     */
    private static int translateCall(String src, int hashIndex, StringBuilder out) {
        int i = hashIndex + 1;
        int n = src.length();
        int nameStart = i;
        while (i < n && isIdentifierPart(src.charAt(i))) {
            i++;
        }
        if (i == nameStart || !isIdentifierStart(src.charAt(nameStart))) {
            throw new IllegalArgumentException("语法糖 # 后应为函数名（位置 " + hashIndex + "）");
        }
        String name = src.substring(nameStart, i);
        while (i < n && (src.charAt(i) == ' ' || src.charAt(i) == '\t')) {
            i++;
        }
        if (i >= n || src.charAt(i) != '(') {
            throw new IllegalArgumentException("语法糖 #" + name + " 缺少参数括号（位置 " + hashIndex + "）");
        }
        int open = i;
        int close = findMatchingParen(src, open);
        String argsSrc = src.substring(open + 1, close);
        List<String> args = splitTopLevel(argsSrc);
        out.append(name).append('(');
        for (int k = 0; k < args.size(); k++) {
            if (k > 0) {
                out.append(',');
            }
            String arg = args.get(k).trim();
            String compiled = transpile(arg);
            if (isBareName(compiled) && !KEYWORD_LITERALS.contains(compiled)) {
                out.append('"').append(compiled).append('"');
            } else {
                out.append(compiled);
            }
        }
        out.append(')');
        return close + 1;
    }

    /** 找到与 open 处 '(' 配对的 ')'（跳过字符串/注释/模板字符串内容）。 */
    private static int findMatchingParen(String src, int open) {
        int depth = 0;
        int i = open;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\'' || c == '"') {
                i = skipQuoted(src, i, c);
                continue;
            }
            if (c == '`') {
                i = skipTemplate(src, i);
                continue;
            }
            if (c == '/' && i + 1 < n) {
                char next = src.charAt(i + 1);
                if (next == '/') {
                    int lineEnd = src.indexOf('\n', i);
                    i = lineEnd < 0 ? n : lineEnd;
                    continue;
                }
                if (next == '*') {
                    int close = src.indexOf("*/", i + 2);
                    if (close < 0) {
                        throw new IllegalArgumentException("脚本注释未闭合（位置 " + i + "）");
                    }
                    i = close + 2;
                    continue;
                }
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
            i++;
        }
        throw new IllegalArgumentException("语法糖函数调用括号未闭合（位置 " + open + "）");
    }

    private static int skipQuoted(String src, int start, char quote) {
        int i = start + 1;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) {
                return i + 1;
            }
            if (c == '\n') {
                break;
            }
            i++;
        }
        throw new IllegalArgumentException("字符串字面量未闭合（位置 " + start + "）");
    }

    private static int skipTemplate(String src, int start) {
        int i = start + 1;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '`') {
                return i + 1;
            }
            if (c == '$' && i + 1 < n && src.charAt(i + 1) == '{') {
                int depth = 1;
                i += 2;
                while (i < n && depth > 0) {
                    char inner = src.charAt(i);
                    if (inner == '\'' || inner == '"') {
                        i = skipQuoted(src, i, inner);
                        continue;
                    }
                    if (inner == '{') {
                        depth++;
                    } else if (inner == '}') {
                        depth--;
                    }
                    i++;
                }
                continue;
            }
            i++;
        }
        throw new IllegalArgumentException("模板字符串未闭合（位置 " + start + "）");
    }

    /** 顶层逗号切分（括号/方括号/花括号/字符串/注释内的逗号不切）。 */
    static List<String> splitTopLevel(String src) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\'' || c == '"') {
                int next = skipQuoted(src, i, c);
                current.append(src, i, next);
                i = next;
                continue;
            }
            if (c == '`') {
                int next = skipTemplate(src, i);
                current.append(src, i, next);
                i = next;
                continue;
            }
            if (c == '/' && i + 1 < n && (src.charAt(i + 1) == '/' || src.charAt(i + 1) == '*')) {
                if (src.charAt(i + 1) == '/') {
                    int lineEnd = src.indexOf('\n', i);
                    int stop = lineEnd < 0 ? n : lineEnd;
                    current.append(src, i, stop);
                    i = stop;
                    continue;
                }
                int close = src.indexOf("*/", i + 2);
                if (close < 0) {
                    throw new IllegalArgumentException("脚本注释未闭合（位置 " + i + "）");
                }
                current.append(src, i, close + 2);
                i = close + 2;
                continue;
            }
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
                i++;
                continue;
            }
            current.append(c);
            i++;
        }
        parts.add(current.toString());
        if (parts.size() == 1 && parts.get(0).trim().isEmpty()) {
            return List.of();
        }
        return parts;
    }

    private static boolean isBareName(String text) {
        if (text.isEmpty() || !isIdentifierStart(text.charAt(0))) {
            return false;
        }
        for (int i = 1; i < text.length(); i++) {
            if (!isIdentifierPart(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    static boolean isIdentifierStart(char c) {
        return c == '_' || c == '$' || Character.isLetter(c);
    }

    static boolean isIdentifierPart(char c) {
        return c == '_' || c == '$' || Character.isLetterOrDigit(c);
    }
}
