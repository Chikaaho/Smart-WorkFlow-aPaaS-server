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

    /** 规则合法占位符（其余 {..} 记法一律拒绝，避免歧义）。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(TIMESTAMP|YYYYMMDD|YYYYMMDDHHMMSS|SEQ)\\}");
    private static final Pattern ILLEGAL_PLACEHOLDER = Pattern.compile("\\{(?!TIMESTAMP|YYYYMMDD|YYYYMMDDHHMMSS|SEQ\\})[^{}]*\\}");
    private static final Pattern SEQ_PATTERN = Pattern.compile("\\{SEQ\\}");

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
            throw new IllegalArgumentException("主题生成规则包含未知占位符，仅支持 {TIMESTAMP}/{YYYYMMDD}/{YYYYMMDDHHMMSS}/{SEQ}");
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
            switch (m.group(1)) {
                case "TIMESTAMP" -> out.append(now);
                case "YYYYMMDD" -> out.append(LocalDateTime.now().format(DateTimeFormatter.BASIC_ISO_DATE));
                case "YYYYMMDDHHMMSS" -> out.append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
                case "SEQ" -> {
                    if (!seqUsed) {
                        seqValue = nextSeq(processKey);
                        seqUsed = true;
                    }
                    out.append(seqValue);
                }
                default -> throw new IllegalStateException("未支持的占位符");
            }
            last = m.end();
        }
        out.append(themeRule.substring(last));
        return out.toString();
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
