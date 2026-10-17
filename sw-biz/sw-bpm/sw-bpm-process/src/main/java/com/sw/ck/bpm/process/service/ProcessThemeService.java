package com.sw.ck.bpm.process.service;

import com.sw.ck.bpm.api.expression.RestrictedExpressionEvaluator;
import com.sw.ck.system.api.user.UserQueryFacade;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 流程编号（主题）生成规则（V012-BUG-010；BF20261013-041/042 扩展；0.1.7 #37 要素化扩展）。
 * <p>
 * 规则文法：字面量 + 占位符 {@code {TIMESTAMP}}、{@code {YYYYMMDD}}、
 * {@code {YYYYMMDDHHMMSS}}、{@code {SEQ}} / {@code {SEQ:n}}（左补零到 n 位，n=1..10）、
 * {@code {INITIATOR}}（发起人姓名）；0.1.7 #37 起新增三类「要素」占位符——
 * {@code {DATE:pattern}} / {@code {TIME:pattern}}（pattern 取白名单，见 {@link #DATE_PATTERNS}
 * / {@link #TIME_PATTERNS}，杜绝任意格式串注入）、{@code {EXPR:表达式}}（受控表达式，
 * 复用流程分支条件同一解释器 {@link RestrictedExpressionEvaluator}，只读变量、不执行脚本）。
 * 字面量花括号以 {@code {{}} / {@code }}}
 * 转义——用户手写的 {@code {xxx}} 一律按普通字符串处理（前端保存时转义）。
 * {@code {SEQ}} 为每流程自增序号：计数表 {@code sw_bpm_theme_seq} 行锁读取后自增，
 * 与发起同事务提交——发起失败整体回滚，序号不产生空洞（失败重试不破坏既定序号语义）。
 * </p>
 */
@Service
public class ProcessThemeService {

    /** 默认编号规则：新建定义与「规则为空的历史定义」都按它生成——所有发起链路都必须有编号。 */
    public static final String DEFAULT_RULE = "{YYYYMMDD}-{SEQ:3}";

    /** IoT 等受控租户系统身份的发起人展示名（initiatorId=0，不伪装自然人）。 */
    public static final String SYSTEM_INITIATOR_NAME = "系统";

    /** {DATE:pattern} 支持的日期格式（白名单；新格式须同时进 {@link #DATE_PATTERNS} 与前端选项）。 */
    public static final Set<String> DATE_PATTERNS =
            Set.of("yyyyMMdd", "yyMMdd", "yyyy-MM-dd", "yyyy-MM", "yyyy-M-d");

    /** {TIME:pattern} 支持的时间格式（白名单）。 */
    public static final Set<String> TIME_PATTERNS = Set.of("HHmmss", "HH:mm:ss", "HH:mm", "H:mm");

    /** {EXPR:表达式} 引用 seq 时才分配自增号（避免没用序号也占号产生空洞）。 */
    private static final Pattern EXPR_SEQ_HINT = Pattern.compile("\\bseq\\b");

    /**
     * 物化扫描：转义对（{{ / }}）与合法占位符按出现顺序消费，
     * 保证 {{SEQ}} 这类转义后的字面量不会被当成变量替换。
     * 白名单：{TIMESTAMP}/{YYYYMMDD}/{YYYYMMDDHHMMSS}/{SEQ}/{SEQ:n}/{INITIATOR}
     * /{DATE:pattern}/{TIME:pattern}/{EXPR:表达式}
     * （BF20261013-041 加 {SEQ:n} 位数补零；BF20261013-042 加 {INITIATOR} 发起人；
     * 0.1.7 #37 加日期/时间格式与受控表达式要素）。
     */
    private static final Pattern MATERIALIZE =
            Pattern.compile("\\{\\{|\\}\\}|\\{(TIMESTAMP|YYYYMMDD|YYYYMMDDHHMMSS|SEQ(?::\\d{1,2})?|INITIATOR"
                    + "|DATE:[A-Za-z-]+|TIME:[A-Za-z:]+|EXPR:[^{}]*)\\}");

    /** 非法占位符：未被转义的 {..} 且不是白名单变量（{{ / }} 转义对不参与判定）。 */
    private static final Pattern ILLEGAL_PLACEHOLDER =
            Pattern.compile("(?<!\\{)\\{(?!\\{)(?!(?:TIMESTAMP|YYYYMMDD|YYYYMMDDHHMMSS|SEQ(?::\\d{1,2})?|INITIATOR"
                    + "|DATE:[A-Za-z-]+|TIME:[A-Za-z:]+|EXPR:[^{}]*)\\})[^{}]*\\}");

    private static final Pattern SEQ_PATTERN = Pattern.compile("\\{SEQ(?::(\\d{1,2}))?\\}");

    /** 自增序号位数上限（左补零），也是校验里拒绝 {SEQ:n} 的边界。 */
    private static final int SEQ_DIGITS_MAX = 10;

    private final JdbcTemplate jdbcTemplate;

    /** 可选：发起人展示名解析（缺省时数字身份回退为 ID 字符串）。 */
    private final UserQueryFacade userQueryFacade;

    public ProcessThemeService(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, null);
    }

    @Autowired
    public ProcessThemeService(JdbcTemplate jdbcTemplate,
                               ObjectProvider<UserQueryFacade> userQueryFacadeProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.userQueryFacade = userQueryFacadeProvider == null ? null : userQueryFacadeProvider.getIfAvailable();
    }

    /** 规则合法性校验：非空、无未知占位符、位数 1..10、日期/时间格式在白名单、表达式可解析、长度 ≤ 200。非法抛 IllegalArgumentException。 */
    public void validateRule(String rule) {
        if (rule == null || rule.isBlank()) {
            throw new IllegalArgumentException("主题生成规则不能为空");
        }
        if (rule.length() > 200) {
            throw new IllegalArgumentException("主题生成规则长度不能超过 200");
        }
        if (ILLEGAL_PLACEHOLDER.matcher(rule).find()) {
            throw new IllegalArgumentException(
                    "主题生成规则包含未知占位符，仅支持 {TIMESTAMP}/{YYYYMMDD}/{YYYYMMDDHHMMSS}/{SEQ}/{SEQ:n}/{INITIATOR}"
                            + "/{DATE:格式}/{TIME:格式}/{EXPR:表达式}，手写的花括号请用 {{ }} 转义");
        }
        Matcher seq = SEQ_PATTERN.matcher(rule);
        while (seq.find()) {
            String digits = seq.group(1);
            if (digits == null) {
                continue;
            }
            int n = Integer.parseInt(digits);
            if (n < 1 || n > SEQ_DIGITS_MAX) {
                throw new IllegalArgumentException(
                        "自增编号位数需在 1-" + SEQ_DIGITS_MAX + " 之间（如 {SEQ:3} 表示补零到 3 位）");
            }
        }
        validateFormatTokens(rule);
        validateExpressionTokens(rule);
    }

    /** 日期/时间要素：格式必须命中白名单（拒绝任意格式串，避免运行时 DateTimeFormatter 异常）。 */
    private void validateFormatTokens(String rule) {
        Matcher m = MATERIALIZE.matcher(rule);
        while (m.find()) {
            String token = m.group(1);
            if (token == null) {
                continue;
            }
            if (token.startsWith("DATE:") && !DATE_PATTERNS.contains(token.substring("DATE:".length()))) {
                throw new IllegalArgumentException(
                        "日期要素格式仅支持 " + String.join(" / ", DATE_PATTERNS.stream().sorted().toList()));
            }
            if (token.startsWith("TIME:") && !TIME_PATTERNS.contains(token.substring("TIME:".length()))) {
                throw new IllegalArgumentException(
                        "时间要素格式仅支持 " + String.join(" / ", TIME_PATTERNS.stream().sorted().toList()));
            }
        }
    }

    /** 自定义要素：表达式非空且能被受控解释器解析（保存即拦语法错误；运行期取不到的变量按空处理）。 */
    private void validateExpressionTokens(String rule) {
        Matcher m = MATERIALIZE.matcher(rule);
        while (m.find()) {
            String token = m.group(1);
            if (token == null || !token.startsWith("EXPR:")) {
                continue;
            }
            String expression = token.substring("EXPR:".length()).trim();
            if (expression.isEmpty()) {
                throw new IllegalArgumentException("自定义要素的表达式不能为空");
            }
            try {
                RestrictedExpressionEvaluator.value(expression, SAMPLE_EXPRESSION_VARIABLES);
            } catch (IllegalArgumentException parseError) {
                throw new IllegalArgumentException("自定义要素表达式不合法：" + parseError.getMessage());
            }
        }
    }

    /** 表达式变量样例（仅用于保存期语法校验；运行期用真实变量，见 {@link #expressionVariables}）。 */
    private static final Map<String, Object> SAMPLE_EXPRESSION_VARIABLES = Map.of(
            "initiatorName", "示例", "initiatorId", 1L, "seq", 1L,
            "yyyy", "2026", "yy", "26", "MM", "10", "dd", "17", "HH", "09", "mm", "30", "ss", "00");

    /**
     * 按规则生成编号（须在发起事务内调用）。
     * 规则为空时按 {@link #DEFAULT_RULE} 生成（历史定义兼容；所有发起链路都必须有编号）。
     *
     * @param processKey 流程标识（自增序号按它计数）
     * @param themeRule  规则；空/空白按默认规则
     * @param initiatorId 发起人用户 ID（{@code {INITIATOR}} 取展示名；0=系统身份）
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String generate(String processKey, String themeRule, Long initiatorId) {
        String effective = (themeRule == null || themeRule.isBlank()) ? DEFAULT_RULE : themeRule;
        LocalDateTime now = LocalDateTime.now();
        String initiatorName = resolveInitiatorName(initiatorId);
        Matcher m = MATERIALIZE.matcher(effective);
        StringBuilder out = new StringBuilder();
        int last = 0;
        boolean seqUsed = false;
        long seqValue = 0;
        while (m.find()) {
            out.append(effective, last, m.start());
            String matched = m.group();
            if ("{{".equals(matched)) {
                out.append('{');
            } else if ("}}".equals(matched)) {
                out.append('}');
            } else {
                String token = m.group(1);
                if (token.startsWith("SEQ")) {
                    if (!seqUsed) {
                        seqValue = nextSeq(processKey);
                        seqUsed = true;
                    }
                    out.append(formatSeq(seqValue, seqDigits(token)));
                } else if ("TIMESTAMP".equals(token)) {
                    out.append(now);
                } else if ("YYYYMMDD".equals(token)) {
                    out.append(now.format(DateTimeFormatter.BASIC_ISO_DATE));
                } else if ("YYYYMMDDHHMMSS".equals(token)) {
                    out.append(now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
                } else if ("INITIATOR".equals(token)) {
                    out.append(initiatorName);
                } else if (token.startsWith("DATE:") || token.startsWith("TIME:")) {
                    out.append(now.format(DateTimeFormatter.ofPattern(token.substring(token.indexOf(':') + 1))));
                } else if (token.startsWith("EXPR:")) {
                    String expression = token.substring("EXPR:".length()).trim();
                    if (EXPR_SEQ_HINT.matcher(expression).find() && !seqUsed) {
                        seqValue = nextSeq(processKey);
                        seqUsed = true;
                    }
                    out.append(evaluateExpression(expression, now, initiatorId, initiatorName,
                            seqUsed ? seqValue : 0L));
                } else {
                    throw new IllegalStateException("未支持的占位符");
                }
            }
            last = m.end();
        }
        out.append(effective.substring(last));
        return out.toString();
    }

    /**
     * 发起人展示名：0 = 受控租户系统身份（IoT 自动发起）；命中用户取展示名（real_name 优先）；
     * 查不到或 facade 缺失时回退为 ID 字符串（不伪造姓名）。
     */
    private String resolveInitiatorName(Long initiatorId) {
        if (initiatorId == null) {
            return "";
        }
        if (initiatorId == 0L) {
            return SYSTEM_INITIATOR_NAME;
        }
        if (userQueryFacade != null) {
            Map<Long, String> names = userQueryFacade.getUserDisplayNames(List.of(initiatorId)).orElse(null);
            if (names != null && names.get(initiatorId) != null) {
                return names.get(initiatorId);
            }
        }
        return String.valueOf(initiatorId);
    }

    /** {SEQ} / {SEQ:n} 的位数：未指定（不补零）返回 0。 */
    private static int seqDigits(String token) {
        int colon = token.indexOf(':');
        return colon < 0 ? 0 : Integer.parseInt(token.substring(colon + 1));
    }

    /**
     * 自定义要素表达式求值（0.1.7 #37）：只读变量、不执行脚本；
     * 引用不到的变量按空串处理（受控解释器对缺失路径返回 empty，不抛异常）。
     */
    private static String evaluateExpression(String expression, LocalDateTime now, Long initiatorId,
                                             String initiatorName, long seq) {
        Object value = RestrictedExpressionEvaluator
                .value(expression, expressionVariables(now, initiatorId, initiatorName, seq))
                .orElse(null);
        return value == null ? "" : String.valueOf(value);
    }

    /** 表达式可读变量：发起人（展示名/ID）、自增序号、日期时间的各段（补零字符串）。 */
    private static Map<String, Object> expressionVariables(LocalDateTime now, Long initiatorId,
                                                           String initiatorName, long seq) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("initiatorName", initiatorName == null ? "" : initiatorName);
        variables.put("initiatorId", initiatorId == null ? 0L : initiatorId);
        variables.put("seq", seq);
        variables.put("yyyy", pad4(now.getYear()));
        variables.put("yy", two(now.getYear() % 100));
        variables.put("MM", two(now.getMonthValue()));
        variables.put("dd", two(now.getDayOfMonth()));
        variables.put("HH", two(now.getHour()));
        variables.put("mm", two(now.getMinute()));
        variables.put("ss", two(now.getSecond()));
        return variables;
    }

    private static String two(int value) {
        return String.format("%02d", value);
    }

    private static String pad4(int value) {
        return String.format("%04d", value);
    }

    /** 自增序号展示值：指定位数时左补零（{SEQ:3} 的 1 → "001"），否则原样输出。 */
    private static String formatSeq(long value, int digits) {
        return digits <= 0 ? Long.toString(value) : String.format("%0" + digits + "d", value);
    }

    /**
     * 自增序号（行锁串行化；同一事务内多次 {SEQ} 复用同一值）。
     * 首次使用插入计数行并返回 1。
     */
    private long nextSeq(String processKey) {
        Long current = jdbcTemplate.query(
                "select next_val from sw_bpm_theme_seq where process_key = ? for update",
                rs -> rs.next() ? rs.getLong(1) : null,
                processKey);
        if (current == null) {
            jdbcTemplate.update("insert into sw_bpm_theme_seq (process_key, next_val) values (?, 2)", processKey);
            return 1L;
        }
        jdbcTemplate.update("update sw_bpm_theme_seq set next_val = ? where process_key = ?", current + 1, processKey);
        return current;
    }
}
