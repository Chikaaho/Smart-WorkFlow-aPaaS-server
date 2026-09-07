package com.sw.ck.bpm.process.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.facade.BpmDeployFacade;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.mapper.BpmProcessDefMapper;
import com.sw.ck.bpm.process.service.BpmFormBindingService;
import com.sw.ck.bpm.process.validator.GraphValidator;
import org.mybatis.spring.mapper.MapperScannerConfigurer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * P57 G6 实际存储证据：通过真实 MyBatis-Plus mapper 走 graph_json 写入，再用 JDBC 原始查询和实体回读双重核对。
 */
@SpringBootTest(
        classes = GraphJsonPersistenceIntegrationTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:p57_graph_json;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                "spring.sql.init.schema-locations=classpath:db/schema-process-def-h2.sql"
        })
@ActiveProfiles("test")
class GraphJsonPersistenceIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BpmProcessDefServiceImpl service;

    @Test
    void shouldPersistAndRereadGraphJsonWithoutShapeLoss() {
        long id = 57001L;
        String graphJson = "{\"processKey\":\"p57_graph_json\",\"name\":\"P57\","
                + "\"elements\":[{\"id\":\"s\",\"kind\":\"node\",\"type\":\"START\","
                + "\"config\":{\"opaque\":\"kept\"},\"style\":{\"x\":10}}]}";
        jdbcTemplate.update("""
                        INSERT INTO sw_bpm_process_def
                        (id, process_key, name, form_key, def_version, status, graph_json, tenant_id, deleted, version)
                        VALUES (?, 'p57_graph_json', 'P57', 'form_p57', 1, 'DRAFT', ?, 0, 0, 0)
                        """, id, graphJson);

        service.saveDraftGraph(id, graphJson);

        String rawStored = jdbcTemplate.queryForObject(
                "SELECT graph_json FROM sw_bpm_process_def WHERE id = ? AND tenant_id = 0 AND deleted = 0",
                String.class, id);
        BpmProcessDef reread = service.getDef(id);
        System.out.println("P57_GRAPH_INPUT=" + graphJson);
        System.out.println("P57_GRAPH_DB_RAW=" + rawStored);
        System.out.println("P57_GRAPH_SERVICE_RAW=" + reread.getGraphJson());
        System.out.println("P57_GRAPH_BYTE_EQUAL=" + graphJson.equals(rawStored)
                + ",DB_SERVICE_BYTE_EQUAL=" + rawStored.equals(reread.getGraphJson()));

        assertThat(rawStored).isEqualTo(graphJson);
        assertThat(reread.getGraphJson()).isEqualTo(graphJson);
        assertThat(rawStored).contains("opaque").contains("\"style\"");
    }

    @Configuration
    @EnableAutoConfiguration
    static class TestConfig {

        @Bean
        BpmProcessDefServiceImpl bpmProcessDefService(
                BpmProcessDefMapper mapper,
                ObjectMapper objectMapper) {
            return new BpmProcessDefServiceImpl(mapper, mock(GraphValidator.class),
                    mock(com.sw.ck.form.api.form.FormDefinitionService.class),
                    mock(BpmDeployFacade.class), mock(BpmFormBindingService.class), objectMapper);
        }

        @Bean
        static MapperScannerConfigurer mapperScannerConfigurer() {
            MapperScannerConfigurer configurer = new MapperScannerConfigurer();
            configurer.setBasePackage("com.sw.ck.bpm.process.mapper");
            return configurer;
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
