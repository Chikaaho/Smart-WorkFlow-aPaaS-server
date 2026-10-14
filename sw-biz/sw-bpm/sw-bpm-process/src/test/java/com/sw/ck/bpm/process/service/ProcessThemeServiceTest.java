package com.sw.ck.bpm.process.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BF20261013-041 流程编号（主题）规则文法：{SEQ} 位数补零与校验白名单。
 * 计数表用 H2 内存库落与生产同名的 sw_bpm_theme_seq。
 */
class ProcessThemeServiceTest {

    private ProcessThemeService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:theme_seq;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS sw_bpm_theme_seq ("
                + "process_key varchar(64) PRIMARY KEY, next_val bigint NOT NULL)");
        jdbcTemplate.update("DELETE FROM sw_bpm_theme_seq");
        service = new ProcessThemeService(jdbcTemplate);
    }

    @Test
    @DisplayName("指定位数时自增序号左补零（{SEQ:3} → 001、002）")
    void generate_whenSeqWithDigits_shouldPadWithZeros() {
        assertThat(service.generate("k_digits", "P{SEQ:3}", "fallback")).isEqualTo("P001");
        assertThat(service.generate("k_digits", "P{SEQ:3}", "fallback")).isEqualTo("P002");
    }

    @Test
    @DisplayName("未指定位数时不补零；同一规则内多次 {SEQ} 复用同一序号值")
    void generate_whenSeqWithoutDigits_shouldNotPadAndReuseSameValue() {
        assertThat(service.generate("k_plain", "{SEQ}", "fallback")).isEqualTo("1");
        assertThat(service.generate("k_plain", "{SEQ}-{SEQ:4}", "fallback")).isEqualTo("2-0002");
    }

    @Test
    @DisplayName("日期占位符与字面量按规则拼接")
    void generate_whenDatePlaceholders_shouldConcatLiteralAndDate() {
        assertThat(service.generate("k_date", "OA-{YYYYMMDD}-{SEQ:2}", "fallback"))
                .matches("OA-\\d{8}-01");
    }

    @Test
    @DisplayName("规则为空时回退流程定义名（历史定义兼容）")
    void generate_whenRuleBlank_shouldFallbackToDefName() {
        assertThat(service.generate("k_blank", "   ", "流程名")).isEqualTo("流程名");
    }

    @Test
    @DisplayName("校验：白名单通过；未知占位符、位数越界、空规则被拒")
    void validateRule_shouldWhitelistAndRejectIllegal() {
        assertThatCode(() -> service.validateRule("OA-{YYYYMMDD}-{SEQ:3}")).doesNotThrowAnyException();
        assertThatCode(() -> service.validateRule("{TIMESTAMP}{YYYYMMDDHHMMSS}{SEQ}")).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.validateRule("{FOO}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.validateRule("{SEQ:0}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.validateRule("{SEQ:11}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.validateRule("  ")).isInstanceOf(IllegalArgumentException.class);
    }
}
