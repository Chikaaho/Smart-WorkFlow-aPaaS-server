package com.sw.ck.notify.render;

import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 消息模板安全渲染服务（P36 / M05-F02-01）。
 *
 * <h3>渲染语义（方向 §3.2 唯一实现，预览与发送共用）</h3>
 * <ul>
 *   <li>仅支持 {@code ${变量名}} 简单占位符；变量名为 {@code [A-Za-z_][A-Za-z0-9_]*}；</li>
 *   <li>变量值按纯文本替换，不解释为 HTML/脚本/表达式（防模板注入）；</li>
 *   <li>缺失变量 → 抛 {@link TemplateRenderException} 并指出全部缺失项，
 *       不静默保留占位符、不替换空值；</li>
 *   <li>未被模板引用的额外变量不改变结果；</li>
 *   <li>标题与正文共用同一套语义。</li>
 * </ul>
 *
 * <p>非法占位符（如 ${1abc}、${a-b}、${}、嵌套表达式）在提取变量阶段即被
 * 判定为非法并拒绝——合法变量集之外出现的 {@code ${...}} 一律报错，
 * 保证「模板里不会残留未渲染的花括号片段」。</p>
 */
@Service
public class TemplateRenderService {

    /** 单变量值长度上限（I6 §3.4 超长发送前明确失败） */
    private static final int MAX_VARIABLE_LENGTH = 2000;

    /** ${...} 片段匹配（含非法形式，用于统一拒绝） */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]*)}");

    /** 合法变量名：字母或下划线开头，仅字母/数字/下划线 */
    private static final Pattern VALID_VAR_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * 渲染单个模板文本。
     *
     * @param template  模板文本（标题或正文）
     * @param variables 变量值表（key=变量名，value=纯文本值）
     * @return 渲染结果
     * @throws TemplateRenderException 存在非法占位符或缺失变量时
     */
    public String render(String template, Map<String, String> variables) {
        if (template == null || !template.contains("${")) {
            return template;
        }
        Set<String> missing = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String rawName = m.group(1);
            // 非法占位符（空名/非法字符/空白）：统一拒绝，不猜测意图
            if (rawName == null || rawName.isBlank() || !VALID_VAR_NAME.matcher(rawName).matches()) {
                throw new TemplateRenderException("非法占位符: ${" + rawName + "}");
            }
            String value = variables == null ? null : variables.get(rawName);
            if (value == null) {
                missing.add(rawName);
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            } else {
                // 方向 §3.4：变量超过长度限制在发送前明确失败
                if (value.length() > MAX_VARIABLE_LENGTH) {
                    throw new TemplateRenderException(
                            "变量 " + rawName + " 超过长度限制（" + MAX_VARIABLE_LENGTH + " 字符）");
                }
                // 变量值按字面文本替换（quoteReplacement 防 $ 与 \ 被二次解释）
                m.appendReplacement(sb, Matcher.quoteReplacement(value));
            }
        }
        m.appendTail(sb);
        if (!missing.isEmpty()) {
            throw new TemplateRenderException("缺少变量: " + String.join(", ", missing));
        }
        return sb.toString();
    }

    /**
     * 提取模板引用的全部合法变量名（供前端动态生成变量输入与后端校验复用）。
     *
     * @throws TemplateRenderException 存在非法占位符时
     */
    public Set<String> extractVariables(String template) {
        Set<String> names = new LinkedHashSet<>();
        if (template == null || !template.contains("${")) {
            return names;
        }
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) {
            String rawName = m.group(1);
            if (rawName == null || rawName.isBlank() || !VALID_VAR_NAME.matcher(rawName).matches()) {
                throw new TemplateRenderException("非法占位符: ${" + rawName + "}");
            }
            names.add(rawName);
        }
        return names;
    }
}
