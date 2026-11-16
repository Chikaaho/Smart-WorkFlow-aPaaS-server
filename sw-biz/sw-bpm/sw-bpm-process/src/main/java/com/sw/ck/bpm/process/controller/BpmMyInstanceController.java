package com.sw.ck.bpm.process.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.vo.BpmTaskVO;
import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.process.vo.ApprovalHistoryItemVO;
import com.sw.ck.bpm.process.vo.InstanceRelationVO;
import com.sw.ck.bpm.process.vo.MyInstanceCountVO;
import com.sw.ck.bpm.process.vo.MyInstanceDetailVO;
import com.sw.ck.bpm.process.vo.MyInstanceItemVO;
import com.sw.ck.bpm.process.vo.MyInstanceProgressNodeVO;
import com.sw.ck.bpm.process.vo.MyInstanceRelationInstanceVO;
import com.sw.ck.bpm.process.entity.ApprovalActionRecord;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.entity.InstanceStatusEnum;
import com.sw.ck.bpm.process.service.ApprovalActionService;
import com.sw.ck.bpm.process.service.ApprovalHistoryAssembler;
import com.sw.ck.bpm.process.service.ApprovalHistoryEnricher;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.bpm.process.service.BpmProcessDefService;
import com.sw.ck.bpm.process.service.InitiatorDisplay;
import com.sw.ck.bpm.process.service.ParticipantNameService;
import com.sw.ck.bpm.process.service.TaskActionService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.response.R;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.api.dept.DeptQueryFacade;
import com.sw.ck.system.api.user.UserQueryFacade;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.extern.log4j.Log4j2;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.sw.ck.common.value.DynamicData;
/**
 * 我发起的控制器（个人入口，非流程监控）。
 * <p>
 * 强制以当前登录用户为发起人查询（initiator_id = 当前用户，租户经拦截器），
 * 不信任客户端传入的查询人身份；普通用户无需管理监控权限。
 * </p>
 */
@RestController
@RequestMapping("/workflow/my/instances")
@Log4j2
public class BpmMyInstanceController {

    @Autowired
    private BpmInstanceService bpmInstanceService;
    @Autowired
    private BpmProcessDefService bpmProcessDefService;
    @Autowired
    private BpmTaskFacade bpmTaskFacade;
    @Autowired
    private TaskActionService taskActionService;
    @Autowired
    private ApprovalActionService approvalActionService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ParticipantNameService participantNameService;
    @Autowired
    private UserQueryFacade userQueryFacade;
    @Autowired
    private DeptQueryFacade deptQueryFacade;
    /** 流转记录装配（1.0.0 迁出共用；服务端 PDF 复用同口径）。 */
    @Autowired
    private ApprovalHistoryAssembler approvalHistoryAssembler;

    /**
     * 我发起的流程实例（分页，可选状态/关键字过滤）。
     *
     * @param keyword 匹配 businessKey 或 formKey（可选）
     */
    @GetMapping
    public R<PageResult<MyInstanceItemVO>> myInstances(PageParam pageParam,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(required = false) String keyword) {
        LoginUser loginUser = LoginUserHolder.get();
        var query = bpmInstanceService.lambdaQuery()
                .eq(BpmInstance::getInitiatorId, loginUser.getUserId())
                .eq(status != null && !status.isBlank(), BpmInstance::getStatus, status);
        if (keyword != null && !keyword.isBlank()) {
            query.and(wrapper -> wrapper
                    .like(BpmInstance::getBusinessKey, keyword)
                    .or().like(BpmInstance::getFormKey, keyword)
                    .or().like(BpmInstance::getProcessDefKey, keyword));
        }
        long total = query.count();
        List<BpmInstance> records = query
                .orderByDesc(BpmInstance::getCreateTime)
                .last("LIMIT " + pageParam.getPageSize()
                        + " OFFSET " + (pageParam.getPageNum() - 1) * pageParam.getPageSize())
                .list();
        PageResult<BpmInstance> page = new PageResult<>();
        page.setRecords(records);
        page.setTotal(total);
        page.setPageNum(pageParam.getPageNum());
        page.setPageSize(pageParam.getPageSize());
        log.debug("我发起的查询: userId={}, total={}", loginUser.getUserId(), total);
        List<MyInstanceItemVO> items = enrichInstanceItems(records);
        PageResult<MyInstanceItemVO> enrichedPage = new PageResult<>();
        enrichedPage.setRecords(items);
        enrichedPage.setTotal(total);
        enrichedPage.setPageNum(pageParam.getPageNum());
        enrichedPage.setPageSize(pageParam.getPageSize());
        return R.ok(enrichedPage);
    }

    /**
     * 提交后实例探测（用户态）：按表单键+业务键统计「当前用户为发起人」的实例数。
     *
     * <p>表单提交后的确认提示需要判断实例是否已真实创建；该判定不得依赖工作流监控
     * 权限（{@code workflow:monitor:view}），否则普通用户会得到假阴性。本端点沿用
     * 「我发起的」的强制发起人口径：只统计本人实例，他人实例恒为 0（不泄露存在性）。</p>
     */
    @GetMapping("/count")
    public R<MyInstanceCountVO> myInstanceCount(@RequestParam String formKey,
                                                 @RequestParam String businessKey) {
        LoginUser loginUser = LoginUserHolder.get();
        Long userId = loginUser == null ? null : loginUser.getUserId();
        long count = bpmInstanceService.countByFormKeyAndBusinessKeyAndInitiator(formKey, businessKey, userId);
        MyInstanceCountVO result = new MyInstanceCountVO();
        result.setCount(count);
        result.setFormKey(formKey);
        result.setBusinessKey(businessKey);
        return R.ok(result);
    }

    /**
     * 实例条目富化（V012-BUG-022）：流程名称（按定义键去重解析）与发起人展示名
     * （批量解析）；解析失败降级 null，不阻断列表。
     * 0.2.0（4:2）：补充 currentNode —— RUNNING 取活动任务名，APPROVED 取定义 END 节点名。
     */
    List<MyInstanceItemVO> enrichInstanceItems(List<BpmInstance> records) {
        Map<String, String> defNames = new HashMap<>();
        Map<String, String> endNodeNames = new HashMap<>();
        Set<Long> initiatorIds = new HashSet<>();
        for (BpmInstance record : records) {
            if (record.getProcessDefKey() != null) {
                defNames.computeIfAbsent(record.getProcessDefKey(), key -> {
                    BpmProcessDef processDef = bpmProcessDefService.findByProcessKey(key);
                    return processDef == null ? null : processDef.getName();
                });
                endNodeNames.computeIfAbsent(record.getProcessDefKey(), this::resolveEndNodeName);
            }
            if (record.getInitiatorId() != null) {
                initiatorIds.add(record.getInitiatorId());
            }
        }
        Map<Long, String> initiatorNames = userQueryFacade == null || initiatorIds.isEmpty()
                ? Map.of()
                : userQueryFacade.getUserDisplayNames(initiatorIds).orElse(Map.of());
        return records.stream().map(record -> {
            MyInstanceItemVO item = new MyInstanceItemVO();
            item.setId(record.getId());
            item.setProcessInstanceId(record.getProcessInstanceId());
            item.setProcessDefKey(record.getProcessDefKey());
            item.setProcessName(record.getProcessDefKey() == null ? null
                    : defNames.get(record.getProcessDefKey()));
            item.setTheme(record.getTheme());
            item.setBusinessKey(record.getBusinessKey());
            item.setFormKey(record.getFormKey());
            item.setInitiatorId(record.getInitiatorId());
            item.setInitiatorName(InitiatorDisplay.resolve(
                    record.getInitiatorId(), initiatorNames));
            item.setStatus(record.getStatus());
            item.setCurrentNode(resolveCurrentNode(record, endNodeNames));
            item.setCreateTime(record.getCreateTime());
            return item;
        }).collect(Collectors.toList());
    }

    /** 当前节点展示：RUNNING=活动任务名；APPROVED=定义 END 节点名；其余终态 null。 */
    private String resolveCurrentNode(BpmInstance record,
                                      Map<String, String> endNodeNames) {
        String status = record.getStatus();
        if (InstanceStatusEnum.RUNNING.getCode().equalsIgnoreCase(status)) {
            if (bpmTaskFacade == null || record.getProcessInstanceId() == null) {
                return null;
            }
            Optional<List<BpmTaskVO>> tasks =
                    bpmTaskFacade.queryByProcessInstance(record.getProcessInstanceId());
            if (tasks == null || tasks.isEmpty()) {
                return null;
            }
            return tasks.get().stream()
                    .map(BpmTaskVO::getName)
                    .filter(name -> name != null && !name.isBlank())
                    .findFirst().orElse(null);
        }
        if (InstanceStatusEnum.APPROVED.getCode().equalsIgnoreCase(status)
                && record.getProcessDefKey() != null) {
            return endNodeNames.get(record.getProcessDefKey());
        }
        return null;
    }

    /** 解析流程定义 END 节点名（config.name/label）；未命名或解析失败返回 null。 */
    private String resolveEndNodeName(String processDefKey) {
        if (objectMapper == null || processDefKey == null) {
            return null;
        }
        BpmProcessDef def = bpmProcessDefService.findByProcessKey(processDefKey);
        if (def == null || def.getGraphJson() == null || def.getGraphJson().isBlank()) {
            return null;
        }
        try {
            ProcessGraph graph =
                    objectMapper.readValue(def.getGraphJson(), ProcessGraph.class);
            if (graph == null || graph.getElements() == null) {
                return null;
            }
            for (GraphElement element : graph.getElements()) {
                if (!GraphElement.TYPE_END.equalsIgnoreCase(element.getType())
                        || element.getConfig() == null) {
                    continue;
                }
                Object name = element.getConfig().get("name");
                if (name == null) {
                    name = element.getConfig().get("label");
                }
                if (name != null && !name.toString().isBlank()) {
                    return name.toString();
                }
            }
        } catch (Exception ex) {
            log.warn("解析流程定义END节点名失败: processDefKey={}, reason={}", processDefKey, ex.getMessage());
        }
        return null;
    }

    /**
     * 我发起的实例详情：实例状态 + 当前进度（活动任务）+ 流转记录。
     */
    @GetMapping("/{id}")
    public R<MyInstanceDetailVO> myInstanceDetail(@PathVariable Long id) {
        LoginUser loginUser = LoginUserHolder.get();
        BpmInstance instance = bpmInstanceService.getById(id);
        if (instance == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "流程实例不存在");
        }
        if (!loginUser.getUserId().equals(instance.getInitiatorId())) {
            log.warn("我发起的详情越权拒绝: instanceId={}, initiator={}, currentUser={}",
                    id, instance.getInitiatorId(), loginUser.getUserId());
            throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(), "无权查看该流程实例");
        }

        String processName = null;
        if (instance.getProcessDefKey() != null) {
            BpmProcessDef processDef = bpmProcessDefService.findByProcessKey(instance.getProcessDefKey());
            processName = processDef == null ? null : processDef.getName();
        }

        // 当前进度：活动任务
        // empty = 实例标识缺失：无活动任务（响应字段保持原列表形状，不漂移为 Optional）
        List<BpmTaskVO> activeTasks = bpmTaskFacade
                .queryByProcessInstance(instance.getProcessInstanceId())
                .orElse(List.of());
        List<MyInstanceProgressNodeVO> progress = activeTasks.stream()
                .map(this::toProgressNode)
                .collect(Collectors.toList());

        MyInstanceDetailVO result = new MyInstanceDetailVO();
        result.setInstance(instance);
        result.setProcessName(processName);
        result.setFormKey(instance.getFormKey());
        result.setBusinessKey(instance.getBusinessKey());
        result.setStatus(instance.getStatus());
        result.setProgress(progress);
        result.setHistory(buildApprovalHistory(instance));
        Object formData = historicFormData(instance);
        result.setFormData(formData == null ? null : DynamicData.of(formData));
        return R.ok(result);
    }

    /** 活动任务 → 进度节点 DTO（null 展示字段按原契约转空串）。 */
    private MyInstanceProgressNodeVO toProgressNode(BpmTaskVO task) {
        MyInstanceProgressNodeVO node = new MyInstanceProgressNodeVO();
        node.setTaskId(task.getTaskId());
        node.setTaskName(task.getName() == null ? "" : task.getName());
        node.setNodeKey(task.getTaskDefinitionKey() == null ? "" : task.getTaskDefinitionKey());
        node.setAssignee(task.getAssignee() == null ? "" : task.getAssignee());
        return node;
    }

    /**
     * 实例业务表单数据（引擎历史变量中的 formData 最终快照，与抄送详情同源）。
     * <p>
     * Agent 工具、IoT 规则与开放 API 程序化发起的实例没有独立表单记录
     * （businessKey 不是表单记录 id），详情页无法按记录 id 回查——该字段作为
     * 回退数据源；手动发起实例的变量与表单记录同源，回退不改变可见内容。
     * 实例标识缺失或历史变量不可读时为 null（详情主链不因回退数据失败中断）。
     * </p>
     */
    Object historicFormData(BpmInstance instance) {
        if (instance.getProcessInstanceId() == null || instance.getProcessInstanceId().isBlank()) {
            return null;
        }
        Optional<Map<String, Object>> variables =
                bpmTaskFacade.getHistoricVariables(instance.getProcessInstanceId());
        return variables == null ? null : variables.map(v -> v.get("formData")).orElse(null);
    }

    /** 按表单键+业务键取最新实例（关系查询入口；独立成方法便于事务外单元测试与复用）。 */
    BpmInstance findLatestInstance(String formKey, String businessKey) {
        return bpmInstanceService.lambdaQuery()
                .eq(BpmInstance::getFormKey, formKey)
                .eq(BpmInstance::getBusinessKey, businessKey)
                .orderByDesc(BpmInstance::getCreateTime)
                .last("LIMIT 1")
                .one();
    }

    /**
     * 表单是否绑定已发布流程定义（与是否已有实例无关）。
     * <p>
     * 详情页据此决定是否渲染流程信息区；解析失败按未绑定处理，不阻断关系查询主链。
     * </p>
     */
    boolean formHasProcess(String formKey) {
        if (formKey == null || formKey.isBlank()) {
            return false;
        }
        try {
            return bpmProcessDefService.hasPublishedDefByFormKey(formKey);
        } catch (Exception ex) {
            log.warn("表单流程绑定判定失败，按未绑定处理: formKey={}, reason={}", formKey, ex.getMessage());
            return false;
        }
    }

    /**
     * 实例流转记录装配（我发起的详情与统一详情关系端点共用同一口径，避免两处漂移）：
     * 1.0.0 起实现迁至 {@link com.sw.ck.bpm.process.service.ApprovalHistoryAssembler}
     * （服务端 PDF 共用），控制器仅委托。
     */
    List<ApprovalHistoryItemVO> buildApprovalHistory(BpmInstance instance) {
        return approvalHistoryAssembler.build(instance);
    }

    /**
     * 意见字段展示视图（存量测试口径）：委托 {@link com.sw.ck.bpm.process.service.ApprovalHistoryEnricher}。
     */
    void resolveOpinionFields(List<ApprovalHistoryItemVO> history,
                              Map<String, ApprovalActionRecord> actionByTask) {
        ApprovalHistoryEnricher.enrichOpinionFields(
                history, actionByTask, objectMapper, taskActionService);
    }

    /** 冻结快照 JSON → 字段定义列表（保留包内入口，实现委托共享组件）。 */
    List<Map<String, Object>> opinionFieldDefinitions(String snapshotJson) {
        return ApprovalHistoryEnricher
                .opinionFieldDefinitions(snapshotJson, objectMapper);
    }

    /** 意见字段展示文本（保留包内入口，实现委托共享组件）。 */
    String opinionDisplayValue(String type, Object value, Map<Long, String> userNames) {
        return ApprovalHistoryEnricher
                .opinionDisplayValue(type, value, userNames);
    }

    /**
     * 0.1.7 #30：统一详情权限关系——按 formKey+businessKey 查询当前用户与实例的关系。
     * <p>
     * APPROVER=存在指派给当前用户的活动任务（附 pendingTaskId）；PARTICIPANT=发起人或
     * 历史任务经办人；NONE=与本实例无关系。NONE 时不回传实例任何信息（fail-closed，
     * 不向无关系者泄露实例存在性）。数据可见性仍由 form:data:query 行权限独立把关。
     * </p>
     * <p>
     * {@code formHasProcess} 表示该表单是否绑定已发布流程定义，与实例是否已产生无关，
     * 因此在实例查询之前写入——无实例提前返回的分支同样携带该标记。
     * </p>
     */
    @GetMapping("/relations")
    public R<InstanceRelationVO> instanceRelation(@RequestParam String formKey,
                                                   @RequestParam String businessKey) {
        LoginUser loginUser = LoginUserHolder.get();
        Long userId = loginUser.getUserId();
        InstanceRelationVO result = new InstanceRelationVO();

        // formHasProcess 与实例存在性无关：必须先于「无实例提前返回」赋值，
        // 否则尚未发起过的流程表单取不到该标记，流程信息区无法展示。
        result.setFormHasProcess(formHasProcess(formKey));

        BpmInstance instance = findLatestInstance(formKey, businessKey);
        if (instance == null) {
            result.setRelation(InstanceRelationVO.RELATION_NONE);
            return R.ok(result);
        }

        // empty = 实例标识缺失：按无活动任务/无历史处理，不漂移为 Optional
        List<BpmTaskVO> activeTasks = bpmTaskFacade
                .queryByProcessInstance(instance.getProcessInstanceId())
                .orElse(List.of());
        String pendingTaskId = activeTasks.stream()
                .filter(task -> userId != null && userId.toString().equals(task.getAssignee()))
                .map(BpmTaskVO::getTaskId)
                .findFirst()
                .orElse(null);

        boolean initiator = userId != null && userId.equals(instance.getInitiatorId());
        boolean historyParticipant = pendingTaskId != null || initiator;
        if (!historyParticipant) {
            List<BpmTaskVO> historyTasks = bpmTaskFacade
                    .queryHistoryByProcessInstance(instance.getProcessInstanceId())
                    .orElse(List.of());
            historyParticipant = historyTasks.stream()
                    .anyMatch(task -> userId != null && userId.toString().equals(task.getAssignee()));
        }

        String relation = pendingTaskId != null ? InstanceRelationVO.RELATION_APPROVER
                : historyParticipant ? InstanceRelationVO.RELATION_PARTICIPANT
                : InstanceRelationVO.RELATION_NONE;
        result.setRelation(relation);
        if (InstanceRelationVO.RELATION_NONE.equals(relation)) {
            return R.ok(result);
        }

        String processName = null;
        if (instance.getProcessDefKey() != null) {
            BpmProcessDef processDef = bpmProcessDefService.findByProcessKey(instance.getProcessDefKey());
            processName = processDef == null ? null : processDef.getName();
        }
        String initiatorName = InitiatorDisplay.resolve(
                instance.getInitiatorId(),
                userQueryFacade == null || instance.getInitiatorId() == null
                        ? Map.of()
                        : userQueryFacade
                                .getUserDisplayNames(Set.of(instance.getInitiatorId()))
                                .orElse(Map.of()));

        MyInstanceRelationInstanceVO instanceInfo = new MyInstanceRelationInstanceVO();
        instanceInfo.setId(instance.getId());
        instanceInfo.setProcessInstanceId(instance.getProcessInstanceId());
        instanceInfo.setProcessDefKey(instance.getProcessDefKey());
        instanceInfo.setProcessName(processName);
        instanceInfo.setTheme(instance.getTheme());
        instanceInfo.setStatus(instance.getStatus());
        instanceInfo.setInitiatorId(instance.getInitiatorId());
        instanceInfo.setInitiatorName(initiatorName);
        instanceInfo.setCreateTime(instance.getCreateTime());
        instanceInfo.setCurrentNodes(activeTasks.stream()
                .map(task -> task.getName() == null ? "" : task.getName())
                .filter(name -> !name.isEmpty())
                .collect(Collectors.toList()));
        instanceInfo.setCurrentNodeKeys(activeTasks.stream()
                .map(BpmTaskVO::getTaskDefinitionKey)
                .filter(key -> key != null && !key.isEmpty())
                .collect(Collectors.toList()));
        result.setPendingTaskId(pendingTaskId);
        result.setInstance(instanceInfo);
        // 有流程的表单详情：流程信息（外挂组件）与我的详情同装配口径
        result.setApprovalHistory(buildApprovalHistory(instance));
        // 程序化发起实例（Agent/IoT/API）无独立表单记录，详情页按此回退渲染表单数据
        Object relationFormData = historicFormData(instance);
        result.setFormData(relationFormData == null ? null : DynamicData.of(relationFormData));
        return R.ok(result);
    }

    /** 意见快照 JSON → Map；空/坏数据返回 null（不伪造意见）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseOpinionData(String opinionDataJson) {
        if (opinionDataJson == null || opinionDataJson.isBlank() || objectMapper == null) {
            return null;
        }
        try {
            return objectMapper.readValue(opinionDataJson, Map.class);
        } catch (Exception e) {
            log.warn("意见快照解析失败，按无意见展示: {}", e.getMessage());
            return null;
        }
    }
}
