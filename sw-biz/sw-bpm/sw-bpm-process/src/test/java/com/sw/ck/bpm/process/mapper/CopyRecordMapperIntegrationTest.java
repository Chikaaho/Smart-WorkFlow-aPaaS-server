package com.sw.ck.bpm.process.mapper;

import com.sw.ck.bpm.process.dto.CopyItemDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.mapper.MapperScannerConfigurer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 抄送查询聚焦集成测试：验证实例精确筛选与同时间次键分页。
 * <p>
 * 两条记录故意使用完全相同的 create_time，分页只能依赖 id DESC 的唯一次键；
 * 每次重复读取均须得到相同顺序，且 page1/page2/page3 无重漏。
 * </p>
 */
@SpringBootTest(
        classes = CopyRecordMapperIntegrationTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:bpm_copy_query_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                "spring.sql.init.schema-locations=classpath:db/schema-h2.sql,classpath:db/schema-copy-h2.sql"
        }
)
@ActiveProfiles("test")
@DisplayName("抄送查询 R2a/R2b 聚焦集成测试")
class CopyRecordMapperIntegrationTest {

    private static final Long TENANT_ID = 0L;
    private static final String RECIPIENT_ID = "recipient-1";
    private static final LocalDateTime SAME_CREATE_TIME = LocalDateTime.of(2026, 9, 7, 12, 30);

    @Autowired
    private CopyRecordMapper mapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM sw_bpm_copy_record");
        jdbcTemplate.update("DELETE FROM sw_bpm_instance");

        insertInstance(101L, "pi-a", "bk-a");
        insertInstance(102L, "pi-b", "bk-b");
        insertCopy(2001L, "pi-a", "node-a");
        insertCopy(2002L, "pi-b", "node-b");
    }

    @Test
    @DisplayName("R2a：processInstanceId 精确命中单实例，不存在实例返回空")
    void processInstanceFilterMustBeExact() {
        assertThat(mapper.countMyCopies(TENANT_ID, RECIPIENT_ID, "pi-a", null, null, null))
                .isEqualTo(1L);
        assertThat(mapper.selectMyCopies(TENANT_ID, RECIPIENT_ID, "pi-a", null, null, null, 10, 0))
                .extracting(CopyItemDTO::getProcessInstanceId)
                .containsExactly("pi-a");

        assertThat(mapper.countMyCopies(TENANT_ID, RECIPIENT_ID, "pi-missing", null, null, null))
                .isZero();
        assertThat(mapper.selectMyCopies(TENANT_ID, RECIPIENT_ID, "pi-missing", null, null, null, 10, 0))
                .isEmpty();
    }

    @Test
    @DisplayName("R2b：同时间按 id DESC 稳定分页，重复读取无重漏")
    void sameTimestampPaginationMustBeStable() {
        List<String> firstReadPage1 = processIds(1, 0);
        List<String> firstReadPage2 = processIds(1, 1);
        List<String> firstReadPage3 = processIds(1, 2);
        List<String> secondReadPage1 = processIds(1, 0);
        List<String> secondReadPage2 = processIds(1, 1);

        assertThat(mapper.countMyCopies(TENANT_ID, RECIPIENT_ID, null, null, null, null)).isEqualTo(2L);
        assertThat(firstReadPage1).containsExactly("pi-b");
        assertThat(firstReadPage2).containsExactly("pi-a");
        assertThat(firstReadPage3).isEmpty();
        assertThat(secondReadPage1).isEqualTo(firstReadPage1);
        assertThat(secondReadPage2).isEqualTo(firstReadPage2);
        assertThat(List.of(firstReadPage1, firstReadPage2).stream().flatMap(List::stream).toList())
                .containsExactly("pi-b", "pi-a");
    }

    private List<String> processIds(int limit, int offset) {
        return mapper.selectMyCopies(TENANT_ID, RECIPIENT_ID, null, null, null, null, limit, offset)
                .stream()
                .map(CopyItemDTO::getProcessInstanceId)
                .toList();
    }

    private void insertInstance(Long id, String processInstanceId, String businessKey) {
        jdbcTemplate.update("""
                        INSERT INTO sw_bpm_instance
                        (id, process_instance_id, process_def_key, business_key, form_key,
                         initiator_id, status, create_by, tenant_id)
                        VALUES (?, ?, 'copy-def', ?, 'copy-form', 1, 'RUNNING', 1, ?)
                        """,
                id, processInstanceId, businessKey, TENANT_ID);
    }

    private void insertCopy(Long id, String processInstanceId, String nodeKey) {
        jdbcTemplate.update("""
                        INSERT INTO sw_bpm_copy_record
                        (id, create_time, create_by, update_time, update_by, deleted, tenant_id,
                         version, process_instance_id, node_key, task_id, recipient_id, delivery_status)
                        VALUES (?, ?, 1, ?, 1, 0, ?, 0, ?, ?, NULL, ?, 'SUCCESS')
                        """,
                id, SAME_CREATE_TIME, SAME_CREATE_TIME, TENANT_ID, processInstanceId, nodeKey, RECIPIENT_ID);
    }

    @Configuration
    @EnableAutoConfiguration
    static class TestConfig {

        @Bean
        public static MapperScannerConfigurer mapperScannerConfigurer() {
            MapperScannerConfigurer configurer = new MapperScannerConfigurer();
            configurer.setBasePackage("com.sw.ck.bpm.process.mapper");
            return configurer;
        }
    }
}
