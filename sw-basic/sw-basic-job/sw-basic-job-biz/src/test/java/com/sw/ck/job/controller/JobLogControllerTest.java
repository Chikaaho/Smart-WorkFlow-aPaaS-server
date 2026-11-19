// package com.sw.ck.job.controller;

// import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
// import com.sw.ck.common.exception.BaseException;
// import com.sw.ck.common.page.PageParam;
// import com.sw.ck.common.page.PageResult;
// import com.sw.ck.common.response.R;
// import com.sw.ck.job.entity.JobLog;
// import com.sw.ck.job.enums.ExecStatus;
// import com.sw.ck.job.service.JobLogService;
// import org.junit.jupiter.api.DisplayName;
// import org.junit.jupiter.api.Nested;
// import org.junit.jupiter.api.Test;

// import java.time.LocalDateTime;
// import java.util.List;

// import static org.assertj.core.api.Assertions.assertThat;
// import static org.assertj.core.api.Assertions.assertThatThrownBy;
// import static org.mockito.ArgumentMatchers.any;
// import static org.mockito.ArgumentMatchers.eq;
// import static org.mockito.Mockito.*;

// /**
//  * {@link JobLogController} 单元测试。
//  * <p>
//  * 纯 Mockito，不装载 Spring 上下文。覆盖 2 个端点的 happy path + 边界/异常路径。
//  * </p>
//  */
// @DisplayName("定时任务执行日志控制器测试")
// class JobLogControllerTest {

//     private final JobLogService jobLogService = mock(JobLogService.class);
//     private final JobLogController controller = new JobLogController(jobLogService);

//     // ==================== 测试数据工厂 ====================

//     private JobLog createJobLog(Long id, Long jobId, ExecStatus status) {
//         JobLog jobLog = new JobLog();
//         jobLog.setId(id);
//         jobLog.setJobId(jobId);
//         jobLog.setExecStatus(status.name());
//         jobLog.setTriggerType("AUTO");
//         jobLog.setStartTime(LocalDateTime.now().minusMinutes(5));
//         jobLog.setEndTime(LocalDateTime.now());
//         jobLog.setDuration(5000L);
//         return jobLog;
//     }

//     // ==================== POST /job/log/page ====================

//     @Nested
//     @DisplayName("分页查询")
//     class PageTests {

//         @Test
//         @DisplayName("按 jobId 分页 → 返回 PageResult")
//         void page_shouldReturnPageResult() {
//             PageResult<JobLog> pageResult = PageResult.of(
//                     new Page<JobLog>(1, 10).setRecords(List.of(
//                             createJobLog(1L, 100L, ExecStatus.SUCCESS),
//                             createJobLog(2L, 100L, ExecStatus.FAILED))).setTotal(2L));
//             when(jobLogService.page(any(PageParam.class), eq(100L))).thenReturn(pageResult);

//             R<PageResult<JobLog>> result = controller.page(100L, 1L, 10L);

//             assertThat(result.getCode()).isZero();
//             assertThat(result.getData().getRecords()).hasSize(2);
//             assertThat(result.getData().getTotal()).isEqualTo(2L);
//             verify(jobLogService).page(any(PageParam.class), eq(100L));
//         }

//         @Test
//         @DisplayName("无日志 → 返回空 records, total=0")
//         void page_empty_shouldReturnEmptyPage() {
//             PageResult<JobLog> empty = PageResult.of(
//                     new Page<JobLog>(1, 10).setRecords(List.of()).setTotal(0L));
//             when(jobLogService.page(any(PageParam.class), eq(100L))).thenReturn(empty);

//             R<PageResult<JobLog>> result = controller.page(100L, 1L, 10L);

//             assertThat(result.getCode()).isZero();
//             assertThat(result.getData().getRecords()).isEmpty();
//             assertThat(result.getData().getTotal()).isZero();
//         }

//         @Test
//         @DisplayName("jobId 为 null → 抛 BaseException(PARAM_ERROR)")
//         void page_nullJobId_shouldThrow() {
//             assertThatThrownBy(() -> controller.page(null, 1L, 10L))
//                     .isInstanceOf(BaseException.class)
//                     .hasMessageContaining("任务 ID 不能为空");
//             verify(jobLogService, never()).page(any(), anyLong());
//         }
//     }

//     // ==================== GET /job/log/{id} ====================

//     @Nested
//     @DisplayName("按 ID 查询")
//     class GetByIdTests {

//         @Test
//         @DisplayName("日志存在 → 返回 JobLog")
//         void getById_shouldReturnJobLog() {
//             JobLog jobLog = createJobLog(1L, 100L, ExecStatus.SUCCESS);
//             when(jobLogService.getById(1L)).thenReturn(jobLog);

//             R<JobLog> result = controller.getById(1L);

//             assertThat(result.getCode()).isZero();
//             assertThat(result.getData().getJobId()).isEqualTo(100L);
//             assertThat(result.getData().getExecStatus()).isEqualTo("SUCCESS");
//             verify(jobLogService).getById(1L);
//         }

//         @Test
//         @DisplayName("日志不存在 → 抛 BaseException(NOT_FOUND)")
//         void getById_notFound_shouldThrow() {
//             when(jobLogService.getById(999L)).thenReturn(null);

//             assertThatThrownBy(() -> controller.getById(999L))
//                     .isInstanceOf(BaseException.class)
//                     .hasMessageContaining("不存在");
//             verify(jobLogService).getById(999L);
//         }
//     }
// }
