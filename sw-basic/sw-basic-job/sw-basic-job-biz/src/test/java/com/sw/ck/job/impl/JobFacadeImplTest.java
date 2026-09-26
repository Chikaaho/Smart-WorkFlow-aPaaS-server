package com.sw.ck.job.impl;

import com.sw.ck.job.dto.JobInfoDTO;
import com.sw.ck.job.entity.JobInfo;
import com.sw.ck.job.service.JobInfoService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * {@link JobFacadeImpl} 单元测试。
 * <p>
 * 验证 Facade 薄封装 + Entity→DTO 转换逻辑的正确性。
 * </p>
 */
@DisplayName("任务门面实现测试")
class JobFacadeImplTest {

    private final JobInfoService jobInfoService = mock(JobInfoService.class);
    private final JobFacadeImpl jobFacade = new JobFacadeImpl(jobInfoService);

    // ==================== 测试数据工厂 ====================

    private JobInfo createJobInfo(Long id, String jobName) {
        JobInfo entity = new JobInfo();
        entity.setId(id);
        entity.setJobName(jobName);
        entity.setJobGroup("DEFAULT");
        entity.setJobType("BEAN");
        entity.setCronExpression("0/30 * * * * ?");
        entity.setStatus("NORMAL");
        entity.setConcurrent(false);
        entity.setMisfirePolicy(0);
        entity.setDescription("测试任务");
        entity.setBeanName("testHandler");
        entity.setFlowDefKey(null);
        entity.setLastFireTime(LocalDateTime.now().minusMinutes(10));
        entity.setNextFireTime(LocalDateTime.now().plusMinutes(20));
        entity.setCreateTime(LocalDateTime.now().minusDays(1));
        return entity;
    }

    // ==================== getById ====================

    @Nested
    @DisplayName("getById")
    class GetByIdTests {

        @Test
        @DisplayName("任务存在 → 返回 present DTO，字段逐项映射正确")
        void getById_shouldReturnDTO() {
            JobInfo entity = createJobInfo(1L, "test-job");
            when(jobInfoService.getById(1L)).thenReturn(entity);

            Optional<JobInfoDTO> dto = jobFacade.getById(1L);

            assertThat(dto).isPresent();
            JobInfoDTO value = dto.orElseThrow();
            assertThat(value.getId()).isEqualTo(1L);
            assertThat(value.getJobName()).isEqualTo("test-job");
            assertThat(value.getJobGroup()).isEqualTo("DEFAULT");
            assertThat(value.getJobType()).isEqualTo("BEAN");
            assertThat(value.getCronExpression()).isEqualTo("0/30 * * * * ?");
            assertThat(value.getStatus()).isEqualTo("NORMAL");
            assertThat(value.getConcurrent()).isFalse();
            assertThat(value.getMisfirePolicy()).isEqualTo(0);
            assertThat(value.getDescription()).isEqualTo("测试任务");
            assertThat(value.getBeanName()).isEqualTo("testHandler");
            assertThat(value.getFlowDefKey()).isNull();
            assertThat(value.getLastFireTime()).isNotNull();
            assertThat(value.getNextFireTime()).isNotNull();
            assertThat(value.getCreateTime()).isNotNull();
            verify(jobInfoService).getById(1L);
        }

        @Test
        @DisplayName("任务不存在 → 返回 empty")
        void getById_notFound_shouldReturnEmpty() {
            when(jobInfoService.getById(999L)).thenReturn(null);

            Optional<JobInfoDTO> dto = jobFacade.getById(999L);

            assertThat(dto).isEmpty();
            verify(jobInfoService).getById(999L);
        }
    }

    // ==================== getByJobName ====================

    @Nested
    @DisplayName("getByJobName")
    class GetByJobNameTests {

        @Test
        @DisplayName("任务存在 → 返回 present DTO")
        void getByJobName_shouldReturnDTO() {
            JobInfo entity = createJobInfo(2L, "cron-cleanup");
            when(jobInfoService.getByJobName("cron-cleanup")).thenReturn(entity);

            Optional<JobInfoDTO> dto = jobFacade.getByJobName("cron-cleanup");

            assertThat(dto).isPresent();
            assertThat(dto.orElseThrow().getId()).isEqualTo(2L);
            assertThat(dto.orElseThrow().getJobName()).isEqualTo("cron-cleanup");
            verify(jobInfoService).getByJobName("cron-cleanup");
        }

        @Test
        @DisplayName("任务不存在 → 返回 empty")
        void getByJobName_notFound_shouldReturnEmpty() {
            when(jobInfoService.getByJobName("ghost-job")).thenReturn(null);

            Optional<JobInfoDTO> dto = jobFacade.getByJobName("ghost-job");

            assertThat(dto).isEmpty();
            verify(jobInfoService).getByJobName("ghost-job");
        }

        @Test
        @DisplayName("FLOW 类型任务 → DTO 含 flowDefKey")
        void getByJobName_flowType_shouldContainFlowDefKey() {
            JobInfo entity = createJobInfo(3L, "flow-trigger");
            entity.setJobType("FLOW");
            entity.setBeanName(null);
            entity.setFlowDefKey("approval_flow_v1");
            when(jobInfoService.getByJobName("flow-trigger")).thenReturn(entity);

            JobInfoDTO dto = jobFacade.getByJobName("flow-trigger").orElseThrow();

            assertThat(dto.getJobType()).isEqualTo("FLOW");
            assertThat(dto.getBeanName()).isNull();
            assertThat(dto.getFlowDefKey()).isEqualTo("approval_flow_v1");
        }
    }
}
