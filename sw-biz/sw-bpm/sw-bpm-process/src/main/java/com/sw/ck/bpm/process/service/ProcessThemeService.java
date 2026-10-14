package com.sw.ck.bpm.process.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 流程主题生成规则（V012-BUG-010）。
 * <p>
 * 规则文法：字面量 + 占位符 {@code {TIMESTAMP}}、{@code {YYYYMMDD}}、
 * {@code {YYYYMMDDHHMMSS}}、{@code {SEQ}}。{SEQ} 为每流程自增序号：计数表
 * {@code sw_bpm_theme_seq} 行锁读取后自增，与发起同事务提交——发起失败整体
 * 回滚，序号不产生空洞（失败重试不破坏既定序号语义）。
 * </p>
 */
@Service
public class ProcessThemeService {

    /**
     * 规则合法占位符（其余 {..} 记法一律拒绝，避免歧义）。
     * BF20261013-041：{SEQ} 支持指定位数 {@code {SEQ:3}}（左补零到 n 位，n=1..10），
     * 供前端「自增编号几位数」选择带入。
     */
    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\{(TIMESTAMP|YYYYMMDD|YYYYMMDDHHMMSS|SEQ(?::\\d{1,2})?)\\}");
    private static final Pattern ILLEGAL_PLACEHOLDER =
            Pattern.compile("\\{(?!(?:TIMESTAMP|YYYYMMDD|YYYYMMDDHHMMSS|SEQ(?::\\d{1,2})?)\\})[^{}]*\\}");
    private static final Pattern SEQ_PATTERN = Pattern.compile("\\{SEQ(?::(\\d{1,2}))?\\}");

    /** 自增序号位数上限（左补零），也是校验里拒绝 {SEQ:n} 的边界。 */
    private static final int SEQ_DIGITS_MAX = 10;

    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public ProcessThemeService(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 规则合法性校验：非空、无未知占位符、长度 ≤ 200。非法抛 IllegalArgumentException。 */
    public void validateRule(String rule) {
        if (rule == null || rule.isBlank()) {
            throw new IllegalArgumentException("主题生成规则不能为空");
        }
        if (rule.length() > 200) {
            throw new IllegalArgumentException("主题生成规则长度不能超过 200");
        }
        if (ILLEGAL_PLACEHOLDER.matcher(rule).find()) {
            throw new IllegalArgumentException(
                    "主题生成规则包含未知占位符，仅支持 {TIMESTAMP}/{YYYYMMDD}/{YYYYMMDDHHMMSS}/{SEQ}/{SEQ:n}");
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
    }

    /**
     * 按规则生成主题（须在发起事务内调用）。规则为空时回退流程定义名（历史定义兼容）。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String generate(String processKey, String themeRule, String fallbackName) {
        if (themeRule == null || themeRule.isBlank()) {
            return fallbackName;
        }
        String now = LocalDateTime.now().toString();
        Matcher m = PLACEHOLDER.matcher(themeRule);
        StringBuilder out = new StringBuilder();
        int last = 0;
        boolean seqUsed = false;
        long seqValue = 0;
        while (m.find()) {
            out.append(themeRule, last, m.start());
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
                out.append(LocalDateTime.now().format(DateTimeFormatter.BASIC_ISO_DATE));
            } else if ("YYYYMMDDHHMMSS".equals(token)) {
                out.append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
            } else {
                throw new IllegalStateException("未支持的占位符");
            }
            last = m.end();
        }
        out.append(themeRule.substring(last));
        return out.toString();
    }

    /** {SEQ} / {SEQ:n} 的位数：未指定（不补零）返回 0。 */
    private static int seqDigits(String token) {
        int colon = token.indexOf(':');
        return colon < 0 ? 0 : Integer.parseInt(token.substring(colon + 1));
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
