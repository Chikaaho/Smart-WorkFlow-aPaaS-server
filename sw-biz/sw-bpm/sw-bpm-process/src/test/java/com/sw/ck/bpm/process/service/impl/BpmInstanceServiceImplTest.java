package com.sw.ck.bpm.process.service.impl;

import com.sw.ck.bpm.process.dto.InstanceFilterDTO;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.mapper.BpmInstanceMapper;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.mapper.MapperScannerConfigurer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BpmInstanceServiceImpl} 集成测试。
 * <p>
 * Spring Boot + H2 上下文，真实 MyBatis-Plus {lambdaQuery()} 映射。
 * 使用 {@code application-test.yml} 配置 H2 数据源 + SQL 脚本初始化表结构。
 * </p>
 */
@SpringBootTest(classes = BpmInstanceServiceImplTest.TestConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
@DisplayName("BpmInstanceServiceImpl 集成测试")
class BpmInstanceServiceImplTest {

    @Autowired
    private BpmInstanceMapper mapper;

    @Autowired
    private BpmInstanceServiceImpl service;

    private BpmInstance createInstance(Long id, String status, String processDefKey, Long initiatorId) {
        BpmInstance inst = new BpmInstance();
        inst.setId(id);
        inst.setStatus(status);
        inst.setProcessDefKey(processDefKey);
        inst.setInitiatorId(initiatorId);
        inst.setProcessInstanceId("pi-" + id);
        inst.setBusinessKey("bk-" + id);
        inst.setFormKey("form_" + processDefKey);
        return inst;
    }

    @Nested
    @DisplayName("pageInstances 方法")
    class PageInstancesTests {

        @Test
        @DisplayName("正常：全量分页（无过滤条件），返回所有记录")
        void pageInstances_withoutFilter_shouldReturnAllRecords() {
            mapper.insert(createInstance(1L, "RUNNING", "process_a", 1L));
            mapper.insert(createInstance(2L, "APPROVED", "process_b", 2L));

            PageParam param = new PageParam();
            param.setPageNum(1);
            param.setPageSize(10);
            PageResult<BpmInstance> result = service.pageInstances(param, null);

            assertThat(result.getTotal()).isEqualTo(2L);
            assertThat(result.getRecords()).hasSize(2);
            assertThat(result.getPageNum()).isEqualTo(1);
            assertThat(result.getPageSize()).isEqualTo(10);
        }

        @Test
        @DisplayName("正常：按 status 过滤，只返回指定状态的记录")
        void pageInstances_withStatusFilter_shouldReturnFilteredRecords() {
            mapper.insert(createInstance(1L, "RUNNING", "process_a", 1L));
            mapper.insert(createInstance(2L, "APPROVED", "process_b", 2L));
            mapper.insert(createInstance(3L, "REJECTED", "process_c", 3L));

            InstanceFilterDTO filter = new InstanceFilterDTO();
            filter.setStatus("RUNNING");

            PageParam param = new PageParam();
            param.setPageNum(1);
            param.setPageSize(10);
            PageResult<BpmInstance> result = service.pageInstances(param, filter);

            assertThat(result.getTotal()).isEqualTo(1L);
            assertThat(result.getRecords()).hasSize(1);
            assertThat(result.getRecords().get(0).getStatus()).isEqualTo("RUNNING");
        }

        @Test
        @DisplayName("正常：按 processDefKey 过滤")
        void pageInstances_withProcessDefKeyFilter_shouldReturnFilteredRecords() {
            mapper.insert(createInstance(1L, "RUNNING", "process_a", 1L));
            mapper.insert(createInstance(2L, "APPROVED", "process_b", 2L));

            InstanceFilterDTO filter = new InstanceFilterDTO();
            filter.setProcessDefKey("process_a");

            PageParam param = new PageParam();
            param.setPageNum(1);
            param.setPageSize(10);
            PageResult<BpmInstance> result = service.pageInstances(param, filter);

            assertThat(result.getTotal()).isEqualTo(1L);
            assertThat(result.getRecords()).hasSize(1);
            assertThat(result.getRecords().get(0).getProcessDefKey()).isEqualTo("process_a");
        }

        @Test
        @DisplayName("正常：按 initiatorId 过滤")
        void pageInstances_withInitiatorFilter_shouldReturnFilteredRecords() {
            mapper.insert(createInstance(1L, "RUNNING", "process_a", 1L));
            mapper.insert(createInstance(2L, "APPROVED", "process_b", 2L));

            InstanceFilterDTO filter = new InstanceFilterDTO();
            filter.setInitiatorId(1L);

            PageParam param = new PageParam();
            param.setPageNum(1);
            param.setPageSize(10);
            PageResult<BpmInstance> result = service.pageInstances(param, filter);

            assertThat(result.getTotal()).isEqualTo(1L);
            assertThat(result.getRecords()).hasSize(1);
            assertThat(result.getRecords().get(0).getInitiatorId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("正常：filter 所有字段为 null，等价于全量分页")
        void pageInstances_filterWithAllNullFields_shouldMatchFullQuery() {
            mapper.insert(createInstance(1L, "RUNNING", "process_a", 1L));

            InstanceFilterDTO filter = new InstanceFilterDTO();

            PageParam param = new PageParam();
            param.setPageNum(1);
            param.setPageSize(10);
            PageResult<BpmInstance> result = service.pageInstances(param, filter);

            assertThat(result.getTotal()).isEqualTo(1L);
            assertThat(result.getRecords()).hasSize(1);
        }

        @Test
        @DisplayName("正常：分页越界，records 为空但 total 正确")
        void pageInstances_outOfRange_shouldReturnEmptyRecords() {
            mapper.insert(createInstance(1L, "RUNNING", "process_a", 1L));

            PageParam param = new PageParam();
            param.setPageNum(999);
            param.setPageSize(10);
            PageResult<BpmInstance> result = service.pageInstances(param, null);

            assertThat(result.getTotal()).isEqualTo(1L);
            assertThat(result.getRecords()).isEmpty();
            assertThat(result.getPageNum()).isEqualTo(999);
        }

        @Test
        @DisplayName("正常：按创建时间倒序排列")
        void pageInstances_shouldOrderByCreateTimeDesc() throws Exception {
            // 先插入旧记录，再插新记录确保 create_time 不同
            BpmInstance old = createInstance(1L, "RUNNING", "process_a", 1L);
            mapper.insert(old);
            Thread.sleep(10); // 确保时间戳不同
            BpmInstance recent = createInstance(2L, "APPROVED", "process_b", 2L);
            mapper.insert(recent);

            PageParam param = new PageParam();
            param.setPageNum(1);
            param.setPageSize(10);
            PageResult<BpmInstance> result = service.pageInstances(param, null);

            assertThat(result.getRecords()).hasSize(2);
            // 第一条应为最新创建的（create_time 倒序）
            assertThat(result.getRecords().get(0).getId()).isEqualTo(2L);
            assertThat(result.getRecords().get(1).getId()).isEqualTo(1L);
        }
    }

    // ==================== 测试上下文配置 ====================

    @Configuration
    @EnableAutoConfiguration
    static class TestConfig {

        // I5 fail-closed：测试种子与断言均在租户 0 语境；本测试上下文提供固定
        // 租户 0 的登录上下文（生产由 SecurityLoginContextProvider 从认证态读取）
        @Bean
        public com.sw.ck.common.security.LoginContextProvider i5TenantZeroLoginContextProvider() {
            return new com.sw.ck.common.security.LoginContextProvider() {
                @Override public Long getUserId() { return 0L; }
                @Override public Long getTenantId() { return 0L; }
                @Override public Long getDeptId() { return null; }
                @Override public com.sw.ck.common.datascope.DataScopeType getDataScopeType() {
                    return com.sw.ck.common.datascope.DataScopeType.ALL;
                }
                @Override public java.util.Set<Long> getCustomDeptIds() { return java.util.Set.of(); }
                @Override public boolean isSuperAdmin() { return false; }
            };
        }


        @Bean
        public BpmInstanceServiceImpl bpmInstanceServiceImpl(
                com.sw.ck.common.security.LoginContextProvider loginContextProvider,
                com.sw.ck.common.datascope.DeptScopeProvider deptScopeProvider) {
            return new BpmInstanceServiceImpl(loginContextProvider, deptScopeProvider);
        }

        @Bean
        public static MapperScannerConfigurer mapperScannerConfigurer() {
            MapperScannerConfigurer configurer = new MapperScannerConfigurer();
            configurer.setBasePackage("com.sw.ck.bpm.process.mapper");
            return configurer;
        }
    }
}
