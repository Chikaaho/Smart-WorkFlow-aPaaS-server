// package com.sw.ck.job.controller;

// import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
// import com.sw.ck.common.exception.BaseException;
// import com.sw.ck.common.page.PageParam;
// import com.sw.ck.common.page.PageResult;
// import com.sw.ck.common.response.R;
// import com.sw.ck.job.entity.JobInfo;
// import com.sw.ck.job.service.JobInfoService;
// import com.sw.ck.job.service.QuartzSchedulerService;
// import org.junit.jupiter.api.DisplayName;
// import org.junit.jupiter.api.Nested;
// import org.junit.jupiter.api.Test;
// import org.springframework.security.access.prepost.PreAuthorize;

// import java.time.LocalDateTime;
// import java.util.List;

// import static org.assertj.core.api.Assertions.assertThat;
// import static org.assertj.core.api.Assertions.assertThatThrownBy;
// import static org.mockito.ArgumentMatchers.any;
// import static org.mockito.ArgumentMatchers.anyLong;
// import static org.mockito.ArgumentMatchers.nullable;
// import static org.mockito.Mockito.*;

// /**
//  * {@link JobInfoController} 单元测试。
//  * <p>
//  * 纯 Mockito，不装载 Spring 上下文。覆盖 8 个端点的 happy path + 边界/异常路径。
//  * </p>
//  */
// @DisplayName("定时任务定义控制器测试")
// class JobInfoControllerTest {

//     private final JobInfoService jobInfoService = mock(JobInfoService.class);
//     private final QuartzSchedulerService quartzSchedulerService = mock(QuartzSchedulerService.class);
//     private final JobInfoController controller = new JobInfoController(jobInfoService, quartzSchedulerService);

//     @Test
//     @DisplayName("job 方法边界 → 每个写操作都有显式 permission")
//     void endpoints_shouldDeclareExplicitPermissions() throws NoSuchMethodException {
//         assertThat(JobInfoController.class.getMethod("create", JobInfo.class)
//                 .getAnnotation(PreAuthorize.class).value()).isEqualTo("@ss.hasPermi('job:create')");
//         assertThat(JobInfoController.class.getMethod("update", JobInfo.class)
//                 .getAnnotation(PreAuthorize.class).value()).isEqualTo("@ss.hasPermi('job:update')");
//         assertThat(JobInfoController.class.getMethod("delete", Long.class)
//                 .getAnnotation(PreAuthorize.class).value()).isEqualTo("@ss.hasPermi('job:delete')");
//         assertThat(JobInfoController.class.getMethod("pause", Long.class)
//                 .getAnnotation(PreAuthorize.class).value()).isEqualTo("@ss.hasPermi('job:pause')");
//         assertThat(JobInfoController.class.getMethod("resume", Long.class)
//                 .getAnnotation(PreAuthorize.class).value()).isEqualTo("@ss.hasPermi('job:resume')");
//         assertThat(JobInfoController.class.getMethod("trigger", Long.class)
//                 .getAnnotation(PreAuthorize.class).value()).isEqualTo("@ss.hasPermi('job:trigger')");
//     }

//     // ==================== 测试数据工厂 ====================

//     private JobInfo createJobInfo(Long id, String jobName, String status) {
//         JobInfo jobInfo = new JobInfo();
//         jobInfo.setId(id);
//         jobInfo.setJobName(jobName);
//         jobInfo.setJobGroup("DEFAULT");
//         jobInfo.setJobType("BEAN");
//         jobInfo.setCronExpression("0/30 * * * * ?");
//         jobInfo.setStatus(status);
//         jobInfo.setBeanName("testHandler");
//         jobInfo.setConcurrent(false);
//         jobInfo.setMisfirePolicy(0);
//         jobInfo.setCreateTime(LocalDateTime.now());
//         return jobInfo;
//     }

//     private JobInfo createCreateRequest() {
//         JobInfo jobInfo = new JobInfo();
//         jobInfo.setJobName("test-job");
//         jobInfo.setCronExpression("0/30 * * * * ?");
//         jobInfo.setJobGroup("DEFAULT");
//         jobInfo.setJobType("BEAN");
//         jobInfo.setBeanName("testHandler");
//         return jobInfo;
//     }

//     // ==================== POST /job/info/page ====================

//     @Nested
//     @DisplayName("分页查询")
//     class PageTests {

//         @Test
//         @DisplayName("分页查询 → 返回 PageResult 含 records + total")
//         void page_shouldReturnPageResult() {
//             PageResult<JobInfo> pageResult = PageResult.of(
//                     new Page<JobInfo>(1, 10).setRecords(List.of(
//                             createJobInfo(1L, "job-a", "NORMAL"),
//                             createJobInfo(2L, "job-b", "PAUSED"))).setTotal(2L));
//             when(jobInfoService.page(any(PageParam.class), nullable(JobInfo.class))).thenReturn(pageResult);

//             R<PageResult<JobInfo>> result = controller.page(1L, 10L, null);

//             assertThat(result.getCode()).isZero();
//             assertThat(result.getData().getRecords()).hasSize(2);
//             assertThat(result.getData().getTotal()).isEqualTo(2L);
//             verify(jobInfoService).page(any(PageParam.class), any());
//         }

//         @Test
//         @DisplayName("分页查询无数据 → 返回空 records, total=0")
//         void page_empty_shouldReturnEmptyPage() {
//             PageResult<JobInfo> empty = PageResult.of(
//                     new Page<JobInfo>(1, 10).setRecords(List.of()).setTotal(0L));
//             when(jobInfoService.page(any(PageParam.class), nullable(JobInfo.class))).thenReturn(empty);

//             R<PageResult<JobInfo>> result = controller.page(1L, 10L, null);

//             assertThat(result.getCode()).isZero();
//             assertThat(result.getData().getRecords()).isEmpty();
//             assertThat(result.getData().getTotal()).isZero();
//         }

//         @Test
//         @DisplayName("分页查询传参 pageNum=2, pageSize=5 → service 收到正确参数")
//         void page_shouldPassCorrectPageParams() {
//             PageResult<JobInfo> empty = PageResult.of(
//                     new Page<JobInfo>(2, 5).setRecords(List.of()).setTotal(0L));
//             when(jobInfoService.page(any(PageParam.class), nullable(JobInfo.class))).thenReturn(empty);

//             controller.page(2L, 5L, null);

//             verify(jobInfoService).page(argThat(p ->
//                     p.getPageNum() == 2 && p.getPageSize() == 5), nullable(JobInfo.class));
//         }
//     }

//     // ==================== GET /job/info/{id} ====================

//     @Nested
//     @DisplayName("按 ID 查询")
//     class GetByIdTests {

//         @Test
//         @DisplayName("任务存在 → 返回 JobInfo")
//         void getById_shouldReturnJobInfo() {
//             JobInfo jobInfo = createJobInfo(1L, "test-job", "NORMAL");
//             when(jobInfoService.getById(1L)).thenReturn(jobInfo);

//             R<JobInfo> result = controller.getById(1L);

//             assertThat(result.getCode()).isZero();
//             assertThat(result.getData().getJobName()).isEqualTo("test-job");
//             assertThat(result.getData().getStatus()).isEqualTo("NORMAL");
//             verify(jobInfoService).getById(1L);
//         }

//         @Test
//         @DisplayName("任务不存在 → 抛 BaseException(NOT_FOUND)")
//         void getById_notFound_shouldThrow() {
//             when(jobInfoService.getById(999L)).thenReturn(null);

//             assertThatThrownBy(() -> controller.getById(999L))
//                     .isInstanceOf(BaseException.class)
//                     .hasMessageContaining("不存在");
//             verify(jobInfoService).getById(999L);
//         }
//     }

//     // ==================== POST /job/info ====================

//     @Nested
//     @DisplayName("创建任务")
//     class CreateTests {

//         @Test
//         @DisplayName("创建成功 → 返回新 ID，已注册到 Quartz")
//         void create_shouldReturnIdAndRegisterToQuartz() {
//             JobInfo request = createCreateRequest();
//             request.setStatus("NORMAL");
//             when(jobInfoService.save(any(JobInfo.class))).thenAnswer(inv -> {
//                 JobInfo saved = inv.getArgument(0);
//                 saved.setId(100L);
//                 return true;
//             });

//             R<Long> result = controller.create(request);

//             assertThat(result.getCode()).isZero();
//             assertThat(result.getData()).isEqualTo(100L);
//             verify(jobInfoService).save(any(JobInfo.class));
//             verify(quartzSchedulerService).addJob(any(JobInfo.class));
//         }

//         @Test
//         @DisplayName("创建 PAUSED 状态任务 → 不注册到 Quartz")
//         void create_paused_shouldNotRegisterToQuartz() {
//             JobInfo request = createCreateRequest();
//             request.setStatus("PAUSED");
//             when(jobInfoService.save(any(JobInfo.class))).thenAnswer(inv -> {
//                 JobInfo saved = inv.getArgument(0);
//                 saved.setId(101L);
//                 return true;
//             });

//             R<Long> result = controller.create(request);

//             assertThat(result.getCode()).isZero();
//             verify(jobInfoService).save(any(JobInfo.class));
//             verify(quartzSchedulerService, never()).addJob(any());
//         }

//         @Test
//         @DisplayName("jobName 为空 → 抛 BaseException(PARAM_ERROR)")
//         void create_blankJobName_shouldThrow() {
//             JobInfo request = createCreateRequest();
//             request.setJobName("  ");

//             assertThatThrownBy(() -> controller.create(request))
//                     .isInstanceOf(BaseException.class)
//                     .hasMessageContaining("任务名称不能为空");
//             verify(jobInfoService, never()).save(any());
//         }

//         @Test
//         @DisplayName("cronExpression 为空 → 抛 BaseException(PARAM_ERROR)")
//         void create_blankCron_shouldThrow() {
//             JobInfo request = createCreateRequest();
//             request.setCronExpression(null);

//             assertThatThrownBy(() -> controller.create(request))
//                     .isInstanceOf(BaseException.class)
//                     .hasMessageContaining("Cron 表达式不能为空");
//             verify(jobInfoService, never()).save(any());
//         }

//         @Test
//         @DisplayName("Quartz 注册失败 → 不抛异常，任务已落库")
//         void create_quartzFailed_shouldNotThrow() {
//             JobInfo request = createCreateRequest();
//             request.setStatus("NORMAL");
//             when(jobInfoService.save(any(JobInfo.class))).thenAnswer(inv -> {
//                 JobInfo saved = inv.getArgument(0);
//                 saved.setId(102L);
//                 return true;
//             });
//             doThrow(new RuntimeException("Quartz error"))
//                     .when(quartzSchedulerService).addJob(any(JobInfo.class));

//             R<Long> result = controller.create(request);

//             // 不抛异常，创建成功
//             assertThat(result.getCode()).isZero();
//             assertThat(result.getData()).isEqualTo(102L);
//             verify(jobInfoService).save(any(JobInfo.class));
//             verify(quartzSchedulerService).addJob(any(JobInfo.class));
//         }

//         @Test
//         @DisplayName("创建时未指定 jobGroup/status/jobType → 使用默认值")
//         void create_shouldApplyDefaults() {
//             JobInfo request = new JobInfo();
//             request.setJobName("minimal-job");
//             request.setCronExpression("0 0 * * * ?");
//             when(jobInfoService.save(any(JobInfo.class))).thenAnswer(inv -> {
//                 JobInfo saved = inv.getArgument(0);
//                 saved.setId(103L);
//                 return true;
//             });

//             R<Long> result = controller.create(request);

//             assertThat(result.getCode()).isZero();
//             verify(jobInfoService).save(argThat(j ->
//                     "DEFAULT".equals(j.getJobGroup())
//                             && "NORMAL".equals(j.getStatus())
//                             && "BEAN".equals(j.getJobType())
//                             && Boolean.FALSE.equals(j.getConcurrent())
//                             && Integer.valueOf(0).equals(j.getMisfirePolicy())));
//         }
//     }

//     // ==================== PUT /job/info ====================

//     @Nested
//     @DisplayName("更新任务")
//     class UpdateTests {

//         @Test
//         @DisplayName("更新成功（NORMAL → NORMAL）→ 先移除再重新注册")
//         void update_shouldReregisterInQuartz() {
//             JobInfo existing = createJobInfo(1L, "old-name", "NORMAL");
//             JobInfo request = new JobInfo();
//             request.setId(1L);
//             request.setJobName("new-name");
//             request.setCronExpression("0 0/5 * * * ?");
//             request.setStatus("NORMAL");

//             when(jobInfoService.getById(1L))
//                     .thenReturn(existing) // 第一次：查 existing
//                     .thenReturn(request); // 第二次：查 updated
//             when(quartzSchedulerService.exists(existing)).thenReturn(true);

//             R<Void> result = controller.update(request);

//             assertThat(result.getCode()).isZero();
//             verify(quartzSchedulerService).exists(existing);
//             verify(quartzSchedulerService).removeJob(existing);
//             verify(jobInfoService).updateById(request);
//             verify(quartzSchedulerService).addJob(request);
//         }

//         @Test
//         @DisplayName("更新为 PAUSED → 移除 Quartz 注册，不重新注册")
//         void update_toPaused_shouldRemoveAndNotReRegister() {
//             JobInfo existing = createJobInfo(1L, "old-name", "NORMAL");
//             JobInfo request = new JobInfo();
//             request.setId(1L);
//             request.setJobName("old-name");
//             request.setStatus("PAUSED");

//             when(jobInfoService.getById(1L))
//                     .thenReturn(existing)
//                     .thenReturn(request);
//             when(quartzSchedulerService.exists(existing)).thenReturn(true);

//             R<Void> result = controller.update(request);

//             assertThat(result.getCode()).isZero();
//             verify(quartzSchedulerService).removeJob(existing);
//             verify(jobInfoService).updateById(request);
//             verify(quartzSchedulerService, never()).addJob(any());
//         }

//         @Test
//         @DisplayName("id 为 null → 抛 BaseException(PARAM_ERROR)")
//         void update_nullId_shouldThrow() {
//             JobInfo request = new JobInfo();
//             request.setJobName("no-id");

//             assertThatThrownBy(() -> controller.update(request))
//                     .isInstanceOf(BaseException.class)
//                     .hasMessageContaining("任务 ID 不能为空");
//         }

//         @Test
//         @DisplayName("任务不存在 → 抛 BaseException(NOT_FOUND)")
//         void update_notFound_shouldThrow() {
//             JobInfo request = new JobInfo();
//             request.setId(999L);
//             when(jobInfoService.getById(999L)).thenReturn(null);

//             assertThatThrownBy(() -> controller.update(request))
//                     .isInstanceOf(BaseException.class)
//                     .hasMessageContaining("不存在");
//         }
//     }

//     // ==================== DELETE /job/info/{id} ====================

//     @Nested
//     @DisplayName("删除任务")
//     class DeleteTests {

//         @Test
//         @DisplayName("删除成功 → 从 Quartz 移除 + 软删除")
//         void delete_shouldRemoveFromQuartzAndSoftDelete() {
//             JobInfo jobInfo = createJobInfo(1L, "test-job", "NORMAL");
//             when(jobInfoService.getById(1L)).thenReturn(jobInfo);
//             when(quartzSchedulerService.exists(jobInfo)).thenReturn(true);

//             R<Void> result = controller.delete(1L);

//             assertThat(result.getCode()).isZero();
//             verify(quartzSchedulerService).exists(jobInfo);
//             verify(quartzSchedulerService).removeJob(jobInfo);
//             verify(jobInfoService).removeById(1L);
//         }

//         @Test
//         @DisplayName("删除不存在的任务 → 幂等，返回成功")
//         void delete_notFound_shouldReturnOk() {
//             when(jobInfoService.getById(999L)).thenReturn(null);

//             R<Void> result = controller.delete(999L);

//             assertThat(result.getCode()).isZero();
//             verify(quartzSchedulerService, never()).removeJob(any());
//             verify(jobInfoService, never()).removeById(anyLong());
//         }
//     }

//     // ==================== POST /job/info/{id}/pause ====================

//     @Nested
//     @DisplayName("暂停任务")
//     class PauseTests {

//         @Test
//         @DisplayName("暂停成功 → Quartz pause + DB 状态改为 PAUSED")
//         void pause_shouldPauseQuartzAndUpdateDb() {
//             JobInfo jobInfo = createJobInfo(1L, "test-job", "NORMAL");
//             when(jobInfoService.getById(1L)).thenReturn(jobInfo);
//             when(quartzSchedulerService.exists(jobInfo)).thenReturn(true);

//             R<Void> result = controller.pause(1L);

//             assertThat(result.getCode()).isZero();
//             verify(quartzSchedulerService).pauseJob(jobInfo);
//             verify(jobInfoService).updateById(argThat(j ->
//                     "PAUSED".equals(j.getStatus())));
//         }

//         @Test
//         @DisplayName("暂停未注册的任务 → 跳过 Quartz pause，仅更新 DB")
//         void pause_notRegistered_shouldOnlyUpdateDb() {
//             JobInfo jobInfo = createJobInfo(1L, "test-job", "NORMAL");
//             when(jobInfoService.getById(1L)).thenReturn(jobInfo);
//             when(quartzSchedulerService.exists(jobInfo)).thenReturn(false);

//             R<Void> result = controller.pause(1L);

//             assertThat(result.getCode()).isZero();
//             verify(quartzSchedulerService, never()).pauseJob(any());
//             verify(jobInfoService).updateById(argThat(j ->
//                     "PAUSED".equals(j.getStatus())));
//         }

//         @Test
//         @DisplayName("暂停已暂停的任务 → 幂等，返回成功")
//         void pause_alreadyPaused_shouldReturnOk() {
//             JobInfo jobInfo = createJobInfo(1L, "test-job", "PAUSED");
//             when(jobInfoService.getById(1L)).thenReturn(jobInfo);

//             R<Void> result = controller.pause(1L);

//             assertThat(result.getCode()).isZero();
//             verify(quartzSchedulerService, never()).pauseJob(any());
//             verify(jobInfoService, never()).updateById(any());
//         }

//         @Test
//         @DisplayName("暂停不存在的任务 → 抛 BaseException(NOT_FOUND)")
//         void pause_notFound_shouldThrow() {
//             when(jobInfoService.getById(999L)).thenReturn(null);

//             assertThatThrownBy(() -> controller.pause(999L))
//                     .isInstanceOf(BaseException.class)
//                     .hasMessageContaining("不存在");
//         }
//     }

//     // ==================== POST /job/info/{id}/resume ====================

//     @Nested
//     @DisplayName("恢复任务")
//     class ResumeTests {

//         @Test
//         @DisplayName("恢复已注册的任务 → Quartz resume + DB 状态改为 NORMAL")
//         void resume_registered_shouldResumeQuartzAndUpdateDb() {
//             JobInfo jobInfo = createJobInfo(1L, "test-job", "PAUSED");
//             when(jobInfoService.getById(1L)).thenReturn(jobInfo);
//             when(quartzSchedulerService.exists(jobInfo)).thenReturn(true);

//             R<Void> result = controller.resume(1L);

//             assertThat(result.getCode()).isZero();
//             verify(quartzSchedulerService).resumeJob(jobInfo);
//             verify(jobInfoService).updateById(argThat(j ->
//                     "NORMAL".equals(j.getStatus())));
//         }

//         @Test
//         @DisplayName("恢复未注册的任务 → 重新注册到 Quartz")
//         void resume_notRegistered_shouldReRegister() {
//             JobInfo jobInfo = createJobInfo(1L, "test-job", "PAUSED");
//             when(jobInfoService.getById(1L)).thenReturn(jobInfo);
//             when(quartzSchedulerService.exists(jobInfo)).thenReturn(false);

//             R<Void> result = controller.resume(1L);

//             assertThat(result.getCode()).isZero();
//             verify(quartzSchedulerService, never()).resumeJob(any());
//             verify(quartzSchedulerService).addJob(jobInfo);
//         }

//         @Test
//         @DisplayName("恢复已恢复的任务 → 幂等，返回成功")
//         void resume_alreadyNormal_shouldReturnOk() {
//             JobInfo jobInfo = createJobInfo(1L, "test-job", "NORMAL");
//             when(jobInfoService.getById(1L)).thenReturn(jobInfo);

//             R<Void> result = controller.resume(1L);

//             assertThat(result.getCode()).isZero();
//             verify(quartzSchedulerService, never()).resumeJob(any());
//             verify(jobInfoService, never()).updateById(any());
//         }

//         @Test
//         @DisplayName("恢复不存在的任务 → 抛 BaseException(NOT_FOUND)")
//         void resume_notFound_shouldThrow() {
//             when(jobInfoService.getById(999L)).thenReturn(null);

//             assertThatThrownBy(() -> controller.resume(999L))
//                     .isInstanceOf(BaseException.class)
//                     .hasMessageContaining("不存在");
//         }
//     }

//     // ==================== POST /job/info/{id}/trigger ====================

//     @Nested
//     @DisplayName("手动触发")
//     class TriggerTests {

//         @Test
//         @DisplayName("手动触发成功 → 调用 quartzSchedulerService.triggerOnce")
//         void trigger_shouldCallService() {
//             JobInfo jobInfo = createJobInfo(1L, "test-job", "NORMAL");
//             when(jobInfoService.getById(1L)).thenReturn(jobInfo);

//             R<Void> result = controller.trigger(1L);

//             assertThat(result.getCode()).isZero();
//             verify(quartzSchedulerService).triggerOnce(jobInfo);
//         }

//         @Test
//         @DisplayName("手动触发不存在的任务 → 抛 BaseException(NOT_FOUND)")
//         void trigger_notFound_shouldThrow() {
//             when(jobInfoService.getById(999L)).thenReturn(null);

//             assertThatThrownBy(() -> controller.trigger(999L))
//                     .isInstanceOf(BaseException.class)
//                     .hasMessageContaining("不存在");
//         }
//     }
// }
