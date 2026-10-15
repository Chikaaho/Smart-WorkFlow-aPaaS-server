package com.sw.ck.bpm.process.service;

import com.sw.ck.system.api.user.UserQueryFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BF20261013-041/042 流程编号（主题）规则文法：{SEQ:n} 位数补零、{INITIATOR} 发起人、
 * 字面量花括号转义（手写 {xxx} 当普通字符串）、空规则回退默认规则。
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

    private static ProcessThemeService serviceWithName(Long userId, String displayName) {
        UserQueryFacade facade = mock(UserQueryFacade.class);
        when(facade.getUserDisplayNames(List.of(userId)))
                .thenReturn(Optional.of(Map.of(userId, displayName)));
        return withFacade(facade);
    }

    @SuppressWarnings("unchecked")
    private static ProcessThemeService withFacade(UserQueryFacade facade) {
        ObjectProvider<UserQueryFacade> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(facade);
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:theme_seq;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
        return new ProcessThemeService(new JdbcTemplate(dataSource), provider);
    }

    @Test
    @DisplayName("指定位数时自增序号左补零（{SEQ:3} → 001、002）")
    void generate_whenSeqWithDigits_shouldPadWithZeros() {
        assertThat(service.generate("k_digits", "P{SEQ:3}", 1L)).isEqualTo("P001");
        assertThat(service.generate("k_digits", "P{SEQ:3}", 1L)).isEqualTo("P002");
    }

    @Test
    @DisplayName("未指定位数时不补零；同一规则内多次 {SEQ} 复用同一序号值")
    void generate_whenSeqWithoutDigits_shouldNotPadAndReuseSameValue() {
        assertThat(service.generate("k_plain", "{SEQ}", 1L)).isEqualTo("1");
        assertThat(service.generate("k_plain", "{SEQ}-{SEQ:4}", 1L)).isEqualTo("2-0002");
    }

    @Test
    @DisplayName("日期占位符与字面量按规则拼接")
    void generate_whenDatePlaceholders_shouldConcatLiteralAndDate() {
        assertThat(service.generate("k_date", "OA-{YYYYMMDD}-{SEQ:2}", 1L))
                .matches("OA-\\d{8}-01");
    }

    @Test
    @DisplayName("BF042：规则为空按默认规则生成（所有发起链路都有编号，不再回退流程名）")
    void generate_whenRuleBlank_shouldUseDefaultRule() {
        assertThat(service.generate("k_blank", "   ", 1L)).matches("\\d{8}-001");
        assertThat(service.generate("k_blank", null, 1L)).matches("\\d{8}-002");
    }

    @Test
    @DisplayName("BF044：{INITIATOR} 取发起人展示名；系统身份(0)=系统；查不到回退 ID")
    void generate_whenInitiatorToken_shouldUseDisplayName() {
        // 无 facade：非 0 的发起人回退 ID 字符串
        assertThat(service.generate("k_init", "P-{INITIATOR}", 7L)).isEqualTo("P-7");
        // 系统身份（IoT 等受控租户自动发起）
        assertThat(service.generate("k_init", "P-{INITIATOR}", 0L)).isEqualTo("P-系统");
        // 有 facade：取展示名（real_name 优先由 facade 保证）
        ProcessThemeService named = serviceWithName(7L, "张三");
        assertThat(named.generate("k_init_named", "P-{INITIATOR}", 7L)).isEqualTo("P-张三");
    }

    @Test
    @DisplayName("BF042：转义花括号按字面量输出，且不消费自增序号（手写 {xxx} 是普通字符串）")
    void generate_whenEscapedBraces_shouldKeepLiteral() {
        assertThat(service.generate("k_esc", "单号{{临时}}-{SEQ:3}", 1L)).isEqualTo("单号{临时}-001");
        // {{SEQ:3}} 是字面量，不占用序号：下列调用才拿到 001
        assertThat(service.generate("k_esc2", "{{SEQ:3}}", 1L)).isEqualTo("{SEQ:3}");
        assertThat(service.generate("k_esc2", "{SEQ:3}", 1L)).isEqualTo("001");
    }

    @Test
    @DisplayName("校验：白名单（含发起人/转义花括号）通过；未知占位符、位数越界、空规则被拒")
    void validateRule_shouldWhitelistAndRejectIllegal() {
        assertThatCode(() -> service.validateRule("OA-{YYYYMMDD}-{SEQ:3}")).doesNotThrowAnyException();
        assertThatCode(() -> service.validateRule("{TIMESTAMP}{YYYYMMDDHHMMSS}{SEQ}")).doesNotThrowAnyException();
        assertThatCode(() -> service.validateRule("JRZX_{INITIATOR}_的流程_{SEQ:3}")).doesNotThrowAnyException();
        // 手写花括号已转义：不再按未知占位符拒绝
        assertThatCode(() -> service.validateRule("单号{{临时}}")).doesNotThrowAnyException();
        assertThatCode(() -> service.validateRule("{{SEQ}}")).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.validateRule("{FOO}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.validateRule("{SEQ:0}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.validateRule("{SEQ:11}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.validateRule("  ")).isInstanceOf(IllegalArgumentException.class);
    }
}
