// package com.sw.ck.bpm.process.controller;

// import com.sw.ck.bpm.api.vo.BpmTaskVO;
// import com.sw.ck.bpm.api.facade.BpmTaskFacade;
// import com.sw.ck.bpm.process.vo.ApprovalHistoryItemVO;
// import com.sw.ck.bpm.process.vo.InstanceRelationVO;
// import com.sw.ck.bpm.process.vo.MyInstanceCountVO;
// import com.sw.ck.bpm.process.vo.MyInstanceDetailVO;
// import com.sw.ck.bpm.process.vo.MyInstanceProgressNodeVO;
// import com.sw.ck.bpm.process.vo.MyInstanceRelationInstanceVO;
// import com.sw.ck.bpm.process.entity.ApprovalActionRecord;
// import com.sw.ck.bpm.process.entity.BpmInstance;
// import com.sw.ck.bpm.process.entity.BpmProcessDef;
// import com.sw.ck.bpm.process.service.ApprovalActionService;
// import com.sw.ck.bpm.process.service.BpmInstanceService;
// import com.sw.ck.bpm.process.service.BpmProcessDefService;
// import com.sw.ck.bpm.process.service.TaskActionService;
// import com.sw.ck.common.config.jackson.JacksonLongToStringConfig;
// import com.sw.ck.common.exception.BaseException;
// import com.sw.ck.common.response.R;
// import com.sw.ck.security.holder.LoginUser;
// import com.sw.ck.security.holder.LoginUserHolder;
// import com.sw.ck.system.api.user.UserQueryFacade;

// import com.fasterxml.jackson.core.type.TypeReference;
// import com.fasterxml.jackson.databind.ObjectMapper;
// import org.junit.jupiter.api.AfterEach;
// import org.junit.jupiter.api.BeforeEach;
// import org.junit.jupiter.api.DisplayName;
// import org.junit.jupiter.api.Test;
// import org.mockito.Mockito;

// import java.util.Date;
// import java.util.List;
// import java.util.Map;
// import java.util.Optional;

// import static org.assertj.core.api.Assertions.assertThat;
// import static org.assertj.core.api.Assertions.assertThatThrownBy;
// import static org.mockito.ArgumentMatchers.any;
// import static org.mockito.ArgumentMatchers.anyCollection;
// import static org.mockito.Mockito.mock;
// import static org.mockito.Mockito.when;

// /**
//  * {@link BpmMyInstanceController} 单元测试（myInstanceDetail）。
//  * <p>
//  * 覆盖：实例不存在 404、initiator 非本人 FORBIDDEN、本人访问返回进度与流转记录。
//  * </p>
//  */
// @DisplayName("我发起的控制器测试")
// class BpmMyInstanceControllerTest {

//     private final BpmInstanceService bpmInstanceService = mock(BpmInstanceService.class);
//     private final BpmProcessDefService bpmProcessDefService = mock(BpmProcessDefService.class);
//     private final BpmTaskFacade bpmTaskFacade = mock(BpmTaskFacade.class);
//     private final TaskActionService taskActionService = mock(TaskActionService.class);
//     private final ApprovalActionService approvalActionService =
//             mock(ApprovalActionService.class);

//     private final BpmMyInstanceController controller = new BpmMyInstanceController(
//             bpmInstanceService, bpmProcessDefService, bpmTaskFacade, taskActionService,
//             null, null, null, null);

//     @BeforeEach
//     void setUp() {
//         LoginUser loginUser = new LoginUser();
//         loginUser.setUserId(2L);
//         loginUser.setTenantId(1L);
//         LoginUserHolder.set(loginUser);
//         when(taskActionService.resolveUserNames(any())).thenReturn(Map.of(3L, "张三"));
//     }

//     @AfterEach
//     void tearDown() {
//         LoginUserHolder.clear();
//     }

//     private BpmInstance instance(Long initiatorId) {
//         BpmInstance instance = new BpmInstance();
//         instance.setId(1L);
//         instance.setInitiatorId(initiatorId);
//         instance.setProcessInstanceId("pi-001");
//         instance.setProcessDefKey("leave_flow");
//         instance.setFormKey("leave_form");
//         instance.setBusinessKey("rec-001");
//         instance.setStatus("RUNNING");
//         return instance;
//     }

//     private BpmTaskVO task(String taskId, boolean finished) {
//         BpmTaskVO task = new BpmTaskVO();
//         task.setTaskId(taskId);
//         task.setName("审批");
//         task.setTaskDefinitionKey("node_approve");
//         task.setAssignee("3");
//         task.setProcessInstanceId("pi-001");
//         task.setCreateTime(new Date());
//         if (finished) {
//             task.setEndTime(new Date());
//         }
//         return task;
//     }

//     @Test
//     @DisplayName("V012-BUG-022：我发起的列表富化流程名称与发起人展示名（解析失败降级 null）")
//     void enrichInstanceItems_shouldFillProcessAndInitiatorNames() {
//         UserQueryFacade userQueryFacade =
//                 mock(UserQueryFacade.class);
//         BpmMyInstanceController enriched = new BpmMyInstanceController(
//                 bpmInstanceService, bpmProcessDefService, bpmTaskFacade, taskActionService,
//                 null, null, null, userQueryFacade);
//         BpmInstance row = instance(2L);
//         row.setTheme("测试1-20260928-1");
//         BpmProcessDef def = new BpmProcessDef();
//         def.setName("测试1");
//         when(bpmProcessDefService.findByProcessKey("leave_flow")).thenReturn(def);
//         when(userQueryFacade.getUserDisplayNames(any()))
//                 .thenReturn(Optional.of(Map.of(2L, "系统管理员")));

//         var items = enriched.enrichInstanceItems(List.of(row));

//         assertThat(items).hasSize(1);
//         assertThat(items.get(0).getProcessName()).isEqualTo("测试1");
//         assertThat(items.get(0).getInitiatorName()).isEqualTo("系统管理员");
//         assertThat(items.get(0).getTheme()).isEqualTo("测试1-20260928-1");
//     }

//     @Test
//     @DisplayName("实例不存在 → NOT_FOUND")
//     void myInstanceDetail_shouldReturn404WhenMissing() {
//         when(bpmInstanceService.getById(1L)).thenReturn(null);

//         assertThatThrownBy(() -> controller.myInstanceDetail(1L))
//                 .isInstanceOf(BaseException.class)
//                 .hasMessageContaining("不存在");
//     }

//     @Test
//     @DisplayName("initiator 非本人 → FORBIDDEN")
//     void myInstanceDetail_shouldRejectNonInitiator() {
//         when(bpmInstanceService.getById(1L)).thenReturn(instance(999L));

//         assertThatThrownBy(() -> controller.myInstanceDetail(1L))
//                 .isInstanceOf(BaseException.class)
//                 .hasMessageContaining("无权查看");
//     }

//     @Test
//     @DisplayName("0.2.0（4:2）：当前节点 —— RUNNING 取活动任务名，APPROVED 取定义 END 节点名")
//     void enrichInstanceItems_shouldFillCurrentNode() {
//         BpmMyInstanceController enriched = new BpmMyInstanceController(
//                 bpmInstanceService, bpmProcessDefService, bpmTaskFacade, taskActionService,
//                 null, new ObjectMapper(), null, null);
//         BpmProcessDef def = new BpmProcessDef();
//         def.setName("请假流程");
//         def.setGraphJson("{\"elements\":[{\"kind\":\"node\",\"type\":\"START\",\"config\":{}},"
//                 + "{\"kind\":\"node\",\"type\":\"END\",\"config\":{\"name\":\"归档\"}}]}");
//         when(bpmProcessDefService.findByProcessKey("leave_flow")).thenReturn(def);
//         when(bpmTaskFacade.queryByProcessInstance("pi-001"))
//                 .thenReturn(Optional.of(List.of(task("t1", false))));

//         BpmInstance running = instance(2L);
//         BpmInstance approved = instance(2L);
//         approved.setStatus("APPROVED");

//         var items = enriched.enrichInstanceItems(List.of(running, approved));

//         assertThat(items.get(0).getCurrentNode()).isEqualTo("审批");
//         assertThat(items.get(1).getCurrentNode()).isEqualTo("归档");
//     }

//     @Test
//     @DisplayName("本人访问 → 返回 instance/progress/history（含 assigneeName 富化）")
//     void myInstanceDetail_shouldReturnProgressAndHistoryForOwner() {
//         when(bpmInstanceService.getById(1L)).thenReturn(instance(2L));
//         BpmProcessDef def = new BpmProcessDef();
//         def.setName("请假流程");
//         def.setProcessKey("leave_flow");
//         when(bpmProcessDefService.findByProcessKey("leave_flow")).thenReturn(def);
//         when(bpmTaskFacade.queryByProcessInstance("pi-001")).thenReturn(Optional.of(List.of(task("t1", false))));
//         when(bpmTaskFacade.queryHistoryByProcessInstance("pi-001")).thenReturn(Optional.of(List.of(task("t2", true))));

//         R<MyInstanceDetailVO> resp = controller.myInstanceDetail(1L);

//         MyInstanceDetailVO result = resp.getData();
//         assertThat(result.getProcessName()).isEqualTo("请假流程");
//         assertThat(result.getFormKey()).isEqualTo("leave_form");
//         assertThat(result.getBusinessKey()).isEqualTo("rec-001");
//         assertThat(result.getStatus()).isEqualTo("RUNNING");

//         List<MyInstanceProgressNodeVO> progress = result.getProgress();
//         assertThat(progress).hasSize(1);
//         MyInstanceProgressNodeVO node = progress.get(0);
//         assertThat(node.getTaskId()).isEqualTo("t1");
//         assertThat(node.getTaskName()).isEqualTo("审批");
//         assertThat(node.getNodeKey()).isEqualTo("node_approve");
//         assertThat(node.getAssignee()).isEqualTo("3");

//         List<ApprovalHistoryItemVO> history = result.getHistory();
//         assertThat(history).hasSize(1);
//         assertThat(history.get(0).getTaskId()).isEqualTo("t2");
//         assertThat(history.get(0).getAssigneeName()).isEqualTo("张三");
//         assertThat(history.get(0).getEndTime()).isNotNull();
//     }

//     @Test
//     @DisplayName("0.3.0 返修 G2：详情回传引擎 formData 快照——程序化发起实例（无表单记录）详情页据此回退渲染")
//     void myInstanceDetail_shouldReturnFormDataFromHistoricVariables() {
//         when(bpmInstanceService.getById(1L)).thenReturn(instance(2L));
//         when(bpmProcessDefService.findByProcessKey("leave_flow")).thenReturn(null);
//         when(bpmTaskFacade.queryByProcessInstance("pi-001")).thenReturn(Optional.of(List.of()));
//         when(bpmTaskFacade.queryHistoryByProcessInstance("pi-001")).thenReturn(Optional.of(List.of()));
//         when(bpmTaskFacade.getHistoricVariables("pi-001"))
//                 .thenReturn(Optional.of(Map.of(
//                         "formData", Map.of("activityAssessment", "NO_EXPLANATION"),
//                         "submitter", "agent")));

//         R<MyInstanceDetailVO> resp = controller.myInstanceDetail(1L);

//         assertThat(resp.getData().getFormData())
//                 .isEqualTo(Map.of("activityAssessment", "NO_EXPLANATION"));
//     }

//     @Test
//     @DisplayName("0.3.0 返修 G2 反向：历史变量缺失 formData 或查询未返回时不伪造，详情主链不中断")
//     void myInstanceDetail_shouldReturnNullFormDataWithoutVariables() {
//         when(bpmInstanceService.getById(1L)).thenReturn(instance(2L));
//         when(bpmProcessDefService.findByProcessKey("leave_flow")).thenReturn(null);
//         when(bpmTaskFacade.queryByProcessInstance("pi-001")).thenReturn(Optional.of(List.of()));
//         when(bpmTaskFacade.queryHistoryByProcessInstance("pi-001")).thenReturn(Optional.of(List.of()));
//         when(bpmTaskFacade.getHistoricVariables("pi-001"))
//                 .thenReturn(Optional.of(Map.of("submitter", "agent")));

//         assertThat(controller.myInstanceDetail(1L).getData().getFormData()).isNull();
//         assertThat(controller.historicFormData(instance(2L))).isNull();
//     }

//     @Test
//     @DisplayName("A1/C1 详情合并动作记录：已办理节点回显真实动作/结果/意见，未办理节点不伪造动作")
//     void myInstanceDetail_shouldMergeActionRecordsIntoHistory() {
//         BpmMyInstanceController enriched = new BpmMyInstanceController(
//                 bpmInstanceService, bpmProcessDefService, bpmTaskFacade, taskActionService,
//                 approvalActionService, new ObjectMapper(), null, null);

//         when(bpmInstanceService.getById(1L)).thenReturn(instance(2L));
//         when(bpmProcessDefService.findByProcessKey("leave_flow")).thenReturn(null);
//         when(bpmTaskFacade.queryByProcessInstance("pi-001")).thenReturn(Optional.of(List.of()));
//         // 终审节点（已完成）+ 一个无动作记录节点（如取消成员）
//         when(bpmTaskFacade.queryHistoryByProcessInstance("pi-001"))
//                 .thenReturn(Optional.of(List.of(task("t-done", true), task("t-cancelled", true))));

//         ApprovalActionRecord handled =
//                 new ApprovalActionRecord();
//         handled.setTaskId("t-done");
//         handled.setAction("APPROVE");
//         handled.setSettlementStatus("APPROVED");
//         handled.setOpinionData("{\"comment\":\"同意\"}");
//         when(approvalActionService.findByProcessInstanceId("pi-001"))
//                 .thenReturn(List.of(handled));

//         R<MyInstanceDetailVO> resp = enriched.myInstanceDetail(1L);

//         List<ApprovalHistoryItemVO> history = resp.getData().getHistory();
//         assertThat(history).hasSize(2);
//         ApprovalHistoryItemVO done = history.stream().filter(h -> "t-done".equals(h.getTaskId()))
//                 .findFirst().orElseThrow();
//         assertThat(done.getAction()).isEqualTo("APPROVE");
//         assertThat(done.getApprovalResult()).isEqualTo("APPROVED");
//         assertThat(done.getOpinionData()).containsEntry("comment", "同意");

//         // 反向：无动作记录的节点不伪造动作/结果
//         ApprovalHistoryItemVO cancelled = history.stream()
//                 .filter(h -> "t-cancelled".equals(h.getTaskId())).findFirst().orElseThrow();
//         assertThat(cancelled.getAction()).isNull();
//         assertThat(cancelled.getApprovalResult()).isNull();
//         assertThat(cancelled.getOpinionData()).isNull();
//     }

//     @Test
//     @DisplayName("0.1.7 #30 统一详情关系：审批人返回 pendingTaskId/节点键/外挂流程信息，历史与我的详情同口径")
//     void instanceRelation_shouldReturnAttachedProcessInfoForApprover() {
//         BpmTaskVO active = task("t-active", false);
//         active.setAssignee("2"); // 当前登录用户（setUp 中 userId=2）
//         when(bpmTaskFacade.queryByProcessInstance("pi-001"))
//                 .thenReturn(Optional.of(List.of(active)));
//         when(bpmTaskFacade.queryHistoryByProcessInstance("pi-001"))
//                 .thenReturn(Optional.of(List.of(task("t-done", true))));
//         when(bpmTaskFacade.getHistoricVariables("pi-001"))
//                 .thenReturn(Optional.of(Map.of("formData", Map.of("k", "v"))));
//         BpmMyInstanceController spy = Mockito.spy(controller);
//         Mockito.doReturn(instance(999L))
//                 .when(spy).findLatestInstance("leave_form", "rec-001");

//         InstanceRelationVO result = spy.instanceRelation("leave_form", "rec-001").getData();

//         assertThat(result.getRelation()).isEqualTo("APPROVER");
//         assertThat(result.getPendingTaskId()).isEqualTo("t-active");
//         assertThat(result.getFormData()).isEqualTo(Map.of("k", "v"));
//         MyInstanceRelationInstanceVO instanceInfo = result.getInstance();
//         assertThat(instanceInfo.getProcessInstanceId()).isEqualTo("pi-001");
//         assertThat(instanceInfo.getProcessDefKey()).isEqualTo("leave_flow");
//         assertThat(instanceInfo.getCurrentNodeKeys()).containsExactly("node_approve");
//         List<ApprovalHistoryItemVO> history = result.getApprovalHistory();
//         assertThat(history).hasSize(1);
//         assertThat(history.get(0).getTaskId()).isEqualTo("t-done");
//         assertThat(history.get(0).getAssigneeName()).isEqualTo("张三");
//     }

//     @Test
//     @DisplayName("0.1.7 #30 统一详情关系：无关系 fail-closed——不回传实例与流程信息")
//     void instanceRelation_shouldFailClosedForStranger() {
//         when(bpmTaskFacade.queryByProcessInstance("pi-001"))
//                 .thenReturn(Optional.of(List.of(task("t-active", false))));
//         when(bpmTaskFacade.queryHistoryByProcessInstance("pi-001"))
//                 .thenReturn(Optional.of(List.of(task("t-done", true))));
//         BpmMyInstanceController spy = Mockito.spy(controller);
//         Mockito.doReturn(instance(999L))
//                 .when(spy).findLatestInstance("leave_form", "rec-001");

//         InstanceRelationVO result = spy.instanceRelation("leave_form", "rec-001").getData();

//         assertThat(result.getRelation()).isEqualTo("NONE");
//         assertThat(toJsonMap(result))
//                 .doesNotContainKeys("instance", "approvalHistory", "pendingTaskId", "formData");
//     }

//     @Test
//     @DisplayName("0.1.7 #30 统一详情关系：无实例（表单独立存在）→ NONE，不查询任何流程数据")
//     void instanceRelation_shouldReturnNoneWhenFormStandsAlone() {
//         BpmMyInstanceController spy = Mockito.spy(controller);
//         Mockito.doReturn(null)
//                 .when(spy).findLatestInstance("leave_form", "rec-001");

//         InstanceRelationVO result = spy.instanceRelation("leave_form", "rec-001").getData();

//         assertThat(result.getRelation()).isEqualTo("NONE");
//         assertThat(toJsonMap(result)).doesNotContainKeys("instance", "approvalHistory");
//         Mockito.verifyNoInteractions(bpmTaskFacade);
//     }

//     /**
//      * formHasProcess 与实例存在性无关：表单已绑定已发布流程但尚未发起时，
//      * 提前返回路径也必须带上 true，否则前端无法渲染流程信息区。
//      */
//     @Test
//     @DisplayName("formHasProcess：无实例但表单绑定已发布流程 → true 且 relation=NONE")
//     void instanceRelation_shouldReturnFormHasProcessWithoutInstance() {
//         when(bpmProcessDefService.hasPublishedDefByFormKey("leave_form")).thenReturn(true);
//         BpmMyInstanceController spy = Mockito.spy(controller);
//         Mockito.doReturn(null)
//                 .when(spy).findLatestInstance("leave_form", "rec-001");

//         InstanceRelationVO result = spy.instanceRelation("leave_form", "rec-001").getData();

//         assertThat(result.isFormHasProcess()).isTrue();
//         assertThat(result.getRelation()).isEqualTo("NONE");
//         assertThat(toJsonMap(result)).doesNotContainKeys("instance", "approvalHistory");
//     }

//     /** 已有实例的表单同样要带 formHasProcess，与实例存在性解耦。 */
//     @Test
//     @DisplayName("formHasProcess：已有实例的表单仍返回 true")
//     void instanceRelation_shouldReturnFormHasProcessWithInstance() {
//         when(bpmProcessDefService.hasPublishedDefByFormKey("leave_form")).thenReturn(true);
//         when(bpmTaskFacade.queryByProcessInstance("pi-001"))
//                 .thenReturn(Optional.of(List.of(task("t-active", false))));
//         when(bpmTaskFacade.queryHistoryByProcessInstance("pi-001"))
//                 .thenReturn(Optional.of(List.of()));
//         BpmMyInstanceController spy = Mockito.spy(controller);
//         Mockito.doReturn(instance(2L))
//                 .when(spy).findLatestInstance("leave_form", "rec-001");

//         InstanceRelationVO result = spy.instanceRelation("leave_form", "rec-001").getData();

//         assertThat(result.isFormHasProcess()).isTrue();
//         assertThat(result.getRelation()).isEqualTo("PARTICIPANT");
//     }

//     /** 反向断言：纯数据表单（无任何已发布流程定义绑定）恒为 false，不因有实例而变 true。 */
//     @Test
//     @DisplayName("formHasProcess：纯数据表单即使有实例也恒为 false")
//     void instanceRelation_shouldReturnFalseForPureDataForm() {
//         when(bpmProcessDefService.hasPublishedDefByFormKey("pure_form")).thenReturn(false);
//         when(bpmTaskFacade.queryByProcessInstance("pi-001"))
//                 .thenReturn(Optional.of(List.of()));
//         when(bpmTaskFacade.queryHistoryByProcessInstance("pi-001"))
//                 .thenReturn(Optional.of(List.of()));
//         BpmMyInstanceController spy = Mockito.spy(controller);
//         Mockito.doReturn(instance(2L))
//                 .when(spy).findLatestInstance("pure_form", "rec-001");

//         InstanceRelationVO result = spy.instanceRelation("pure_form", "rec-001").getData();

//         assertThat(result.isFormHasProcess()).isFalse();
//         assertThat(result.getRelation()).isEqualTo("PARTICIPANT");
//     }

//     /** 判定异常不得中断关系查询主链：按未绑定降级。 */
//     @Test
//     @DisplayName("formHasProcess：绑定判定异常时降级为 false，不阻断关系查询")
//     void instanceRelation_shouldDegradeToFalseOnLookupFailure() {
//         when(bpmProcessDefService.hasPublishedDefByFormKey("leave_form"))
//                 .thenThrow(new IllegalStateException("db down"));
//         BpmMyInstanceController spy = Mockito.spy(controller);
//         Mockito.doReturn(null)
//                 .when(spy).findLatestInstance("leave_form", "rec-001");

//         InstanceRelationVO result = spy.instanceRelation("leave_form", "rec-001").getData();

//         assertThat(result.isFormHasProcess()).isFalse();
//         assertThat(result.getRelation()).isEqualTo("NONE");
//     }

//     /**
//      * 提交后实例探测（用户态）：统计本人实例数，且不依赖工作流监控权限。
//      * 反向断言：发起人 = 当前登录用户强制传入，不接受客户端指定他人身份。
//      */
//     @Test
//     @DisplayName("提交探测：按表单键+业务键统计本人实例数，发起人取当前登录用户")
//     void myInstanceCount_shouldCountOwnInstancesOnly() {
//         when(bpmInstanceService.countByFormKeyAndBusinessKeyAndInitiator("leave_form", "rec-001", 2L))
//                 .thenReturn(1L);

//         MyInstanceCountVO result = controller.myInstanceCount("leave_form", "rec-001").getData();

//         assertThat(result.getCount()).isEqualTo(1L);
//         assertThat(result.getFormKey()).isEqualTo("leave_form");
//         assertThat(result.getBusinessKey()).isEqualTo("rec-001");
//     }

//     /** 反向断言：未登录恒为 0（发起人缺失不落入「无发起人条件」的全量统计）。 */
//     @Test
//     @DisplayName("提交探测：未登录恒为 0")
//     void myInstanceCount_shouldFailClosedWithoutUser() {
//         LoginUserHolder.clear();

//         MyInstanceCountVO anonymous = controller.myInstanceCount("leave_form", "rec-001").getData();

//         assertThat(anonymous.getCount()).isEqualTo(0L);
//     }

//     @Test
//     @DisplayName("历史意见字段展示视图：中文标签 + 人员姓名/本地时间/多选拼接")
//     void resolveOpinionFields_buildsDisplayValues() {
//         ObjectMapper mapper = new ObjectMapper();
//         BpmMyInstanceController withMapper = new BpmMyInstanceController(
//                 bpmInstanceService, bpmProcessDefService, bpmTaskFacade, taskActionService,
//                 null, mapper, null, null);
//         when(taskActionService.resolveUserNames(any()))
//                 .thenReturn(Map.of(9L, "赵磊"));

//         ApprovalHistoryItemVO item = new ApprovalHistoryItemVO();
//         item.setTaskId("t1");
//         item.setOpinionData(Map.of(
//                 "factResult", "属实",
//                 "fieldHandler", "9",
//                 "arrivalTime", "2026-11-07 21:58:00",
//                 "measures", List.of("疏导", "增派安保")));
//         ApprovalActionRecord action =
//                 new ApprovalActionRecord();
//         action.setTaskId("t1");
//         action.setOpinionFormSnapshot("{\"opinionForm\":{\"fields\":["
//                 + "{\"key\":\"factResult\",\"type\":\"RADIO\",\"label\":\"事实核实结论\"},"
//                 + "{\"key\":\"fieldHandler\",\"type\":\"USER\",\"label\":\"现场处置人\"},"
//                 + "{\"key\":\"arrivalTime\",\"type\":\"DATETIME\",\"label\":\"到场核实时间\"},"
//                 + "{\"key\":\"measures\",\"type\":\"CHECKBOX\",\"label\":\"处置措施\"}]}}");

//         withMapper.resolveOpinionFields(List.of(item), Map.of("t1", action));

//         assertThat(item.getOpinionFields()).hasSize(4);
//         assertThat(item.getOpinionFields().get(0).getLabel()).isEqualTo("事实核实结论");
//         assertThat(item.getOpinionFields().get(0).getDisplayValue()).isEqualTo("属实");
//         assertThat(item.getOpinionFields().get(1).getDisplayValue()).isEqualTo("赵磊");
//         assertThat(item.getOpinionFields().get(2).getDisplayValue()).isEqualTo("2026-11-07 21:58");
//         assertThat(item.getOpinionFields().get(3).getDisplayValue()).isEqualTo("疏导、增派安保");
//     }

//     @Test
//     @DisplayName("旧记录缺意见快照时不生成字段视图（不虚构标签）")
//     void resolveOpinionFields_skipsWhenSnapshotMissing() {
//         ObjectMapper mapper = new ObjectMapper();
//         BpmMyInstanceController withMapper = new BpmMyInstanceController(
//                 bpmInstanceService, bpmProcessDefService, bpmTaskFacade, taskActionService,
//                 null, mapper, null, null);
//         ApprovalHistoryItemVO item = new ApprovalHistoryItemVO();
//         item.setTaskId("t1");
//         item.setOpinionData(Map.of("factResult", "属实"));
//         ApprovalActionRecord action =
//                 new ApprovalActionRecord();
//         action.setTaskId("t1");
//         action.setOpinionFormSnapshot("{\"opinionForm\":{}}");

//         withMapper.resolveOpinionFields(List.of(item), Map.of("t1", action));

//         assertThat(item.getOpinionFields()).isNull();
//     }

//     @Test
//     @DisplayName("JSON 契约：count 保持字符串化，formKey/businessKey 回显不变")
//     void myInstanceCountJson_keepsContractKeysAndStringifiesLong() {
//         MyInstanceCountVO dto = new MyInstanceCountVO();
//         dto.setCount(1L);
//         dto.setFormKey("leave_form");
//         dto.setBusinessKey("rec-001");

//         ObjectMapper mapper = new ObjectMapper();
//         mapper.registerModule(new JacksonLongToStringConfig().longToStringModule());
//         Map<String, Object> json = mapper.convertValue(dto, new TypeReference<Map<String, Object>>() { });

//         assertThat(json).containsEntry("count", "1")
//                 .containsEntry("formKey", "leave_form")
//                 .containsEntry("businessKey", "rec-001");
//     }

//     @Test
//     @DisplayName("JSON 契约：详情顶层 key 与 progress 节点 key 保持不变")
//     void myInstanceDetailJson_keepsContractKeys() {
//         MyInstanceDetailVO dto = new MyInstanceDetailVO();
//         dto.setInstance(instance(2L));
//         dto.setProcessName("请假流程");
//         dto.setFormKey("leave_form");
//         dto.setBusinessKey("rec-001");
//         dto.setStatus("RUNNING");
//         MyInstanceProgressNodeVO node = new MyInstanceProgressNodeVO();
//         node.setTaskId("t1");
//         node.setTaskName("审批");
//         node.setNodeKey("node_approve");
//         node.setAssignee("3");
//         dto.setProgress(List.of(node));
//         dto.setHistory(List.of());

//         Map<String, Object> json = toJsonMap(dto);

//         assertThat(json).containsKeys("instance", "processName", "formKey", "businessKey",
//                 "status", "progress", "history", "formData");
//         @SuppressWarnings("unchecked")
//         Map<String, Object> progress = ((List<Map<String, Object>>) json.get("progress")).get(0);
//         assertThat(progress).containsKeys("taskId", "taskName", "nodeKey", "assignee");
//     }

//     @Test
//     @DisplayName("JSON 契约：NONE 关系省略实例/历史/pendingTaskId/formData，保留 formHasProcess")
//     void instanceRelationJson_omitsNullKeysForNone() {
//         InstanceRelationVO dto = new InstanceRelationVO();
//         dto.setFormHasProcess(false);
//         dto.setRelation("NONE");

//         Map<String, Object> json = toJsonMap(dto);

//         assertThat(json).containsEntry("formHasProcess", false)
//                 .containsEntry("relation", "NONE")
//                 .doesNotContainKeys("instance", "approvalHistory", "pendingTaskId", "formData");
//     }

//     private static Map<String, Object> toJsonMap(Object value) {
//         return new ObjectMapper().convertValue(value, new TypeReference<Map<String, Object>>() { });
//     }
// }
