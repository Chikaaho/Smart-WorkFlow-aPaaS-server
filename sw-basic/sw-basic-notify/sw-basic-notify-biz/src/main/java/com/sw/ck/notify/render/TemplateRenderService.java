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

    /* ==================== I6 §3.4 可判定类型契约 ==================== */

    /** 已声明的变量类型枚举：TEXT（默认纯文本）、NUMBER、DATE、EMAIL；ENUM 需携带可选值 */
    private static final Pattern NUMBER_VALUE = Pattern.compile("-?\\d{1,18}(\\.\\d{1,8})?");
    private static final Pattern DATE_VALUE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}([ T]\\d{2}:\\d{2}(:\\d{2})?)?");
    private static final Pattern EMAIL_VALUE = Pattern.compile("[^@\\s]{1,64}@[^@\\s]{3,253}\\.[A-Za-z]{2,24}");

    /** 类型声明条目（variables_allowed 条目扩展：{@code name} 或 {@code name:TYPE} 或 {@code name:ENUM(v1|v2)}）。 */
    private static final Pattern DECLARED_ENTRY = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)(:(TEXT|NUMBER|DATE|EMAIL|ENUM\\([^)]*\\)))?$");

    /**
     * 解析变量白名单/类型契约（I6 扩展语义，双端复用同源）。
     * <p>条目格式：{@code name}（未声明类型，等价 TEXT）、{@code name:NUMBER/DATE/EMAIL/TEXT}、
     * {@code name:ENUM(v1|v2)}；非法条目抛 {@link TemplateRenderException}。</p>
     *
     * @return LinkedHashMap 变量名 → 类型声明（可为 null 表示未声明）
     */
    public java.util.Map<String, String> parseContract(String variablesAllowed) {
        java.util.Map<String, String> contract = new java.util.LinkedHashMap<>();
        if (variablesAllowed == null || variablesAllowed.isBlank()) {
            return contract;
        }
        for (String entry : variablesAllowed.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Matcher m = DECLARED_ENTRY.matcher(trimmed);
            if (!m.matches()) {
                throw new TemplateRenderException("非法变量声明: " + trimmed);
            }
            // group(2) 含前导冒号（如 :NUMBER），解码后剥掉统一存纯类型名
            contract.put(m.group(1), m.group(2) == null ? null : m.group(2).substring(1));
        }
        return contract;
    }

    /**
     * 带契约渲染：未知变量（未登记白名单时）、类型不符在替换前明确失败（G2e-T）。
     * 与 {@link #render(String, Map)} 共用全部既有语义（缺失/非法占位符/长度上限）。
     */
    public String renderWithContract(String template, Map<String, String> variables, String variablesAllowed) {
        java.util.Map<String, String> contract = parseContract(variablesAllowed);
        requireContractCoversTemplate(template, contract);
        if (variables != null && !contract.isEmpty()) {
            for (Map.Entry<String, String> provided : variables.entrySet()) {
                if (!contract.containsKey(provided.getKey())) {
                    throw new TemplateRenderException("变量未登记白名单: " + provided.getKey());
                }
            }
        }
        if (variables != null) {
            for (Map.Entry<String, String> provided : variables.entrySet()) {
                validateValueAgainstContract(provided.getKey(), provided.getValue(),
                        contract.get(provided.getKey()));
            }
        }
        return render(template, variables);
    }

    /**
     * 模板内部引用的全部变量必须在契约白名单中（契约非空时）。
     */
    private void requireContractCoversTemplate(String template, java.util.Map<String, String> contract) {
        if (contract == null || contract.isEmpty()) {
            return;
        }
        for (String referenced : extractVariables(template)) {
            if (!contract.containsKey(referenced)) {
                throw new TemplateRenderException("模板引用了未登记白名单的变量: " + referenced);
            }
        }
    }

    /**
     * 单值契约校验（G2e-T）：
     * <ul>
     *   <li>NUMBER：{@code ^-?\d{1,18}(\.\d{1,8})?$}；</li>
     *   <li>DATE：{@code yyyy-MM-dd[ HH:mm[:ss]]}；</li>
     *   <li>EMAIL：非空白 + 单个 @ 合法形式；</li>
     *   <li>ENUM(a|b)：值必须命中枚举可选值；</li>
     *   <li>TEXT：仅继承长度上限。</li>
     * </ul>
     */
    private void validateValueAgainstContract(String name, String value, String declared) {
        if (declared == null) {
            return;
        }
        String type = declared.toUpperCase();
        if (type.equals("NUMBER")) {
            if (value == null || NUMBER_VALUE.matcher(value).matches() == false) {
                throw new TemplateRenderException("变量 " + name + " 类型不符（应为 NUMBER）");
            }
            return;
        }
        if (type.equals("DATE")) {
            if (value == null || DATE_VALUE.matcher(value).matches() == false) {
                throw new TemplateRenderException("变量 " + name + " 类型不符（应为 DATE）");
            }
            return;
        }
        if (type.equals("EMAIL")) {
            if (value == null || EMAIL_VALUE.matcher(value).matches() == false) {
                throw new TemplateRenderException("变量 " + name + " 类型不符（应为 EMAIL）");
            }
            return;
        }
        if (type.startsWith("ENUM(")) {
            Set<String> allowedOptions = new LinkedHashSet<>();
            for (String option : declared.substring(5, declared.length() - 1).split("\\|")) {
                if (!option.isBlank()) {
                    allowedOptions.add(option.trim());
                }
            }
            if (!allowedOptions.contains(value)) {
                throw new TemplateRenderException("变量 " + name + " 类型不符（应为枚举 " + declared + "）");
            }
            return;
        }
    }

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
