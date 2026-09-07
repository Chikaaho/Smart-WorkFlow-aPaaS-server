package com.sw.ck.bpm.process.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.dto.*;
import com.sw.ck.bpm.api.node.BpmNodeCapabilityDTO;
import com.sw.ck.bpm.api.node.BpmNodeRegistry;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.service.BpmProcessDefService;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.response.R;
import com.sw.ck.system.api.user.UserOptionDTO;
import com.sw.ck.system.api.user.UserQueryFacade;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 流程定义控制器 —— 图设计器后端。
 * <p>
 * 提供流程定义的 CRUD、草稿图保存、拓扑校验等端点。
 * 本刀（cut A）仅处理 DRAFT 状态的图模型存储与校验，不涉及发布/部署/Flowable。
 * </p>
 *
 * <h3>防腐</h3>
 * 本 Controller 不 import 任何 Flowable 类型；所有操作经 {@link BpmProcessDefService} 完成。
 */
@RestController
@RequestMapping("/workflow/defs")
public class BpmProcessDefController {

    private static final Logger log = LoggerFactory.getLogger(BpmProcessDefController.class);

    private final BpmProcessDefService bpmProcessDefService;
    private final ObjectMapper objectMapper;
    private final UserQueryFacade userQueryFacade;
    private final BpmNodeRegistry nodeRegistry;

    public BpmProcessDefController(BpmProcessDefService bpmProcessDefService,
                                   ObjectMapper objectMapper,
                                   UserQueryFacade userQueryFacade) {
        this(bpmProcessDefService, objectMapper, userQueryFacade, null);
    }

    @Autowired
    public BpmProcessDefController(BpmProcessDefService bpmProcessDefService,
                                   ObjectMapper objectMapper,
                                   UserQueryFacade userQueryFacade,
                                   BpmNodeRegistry nodeRegistry) {
        this.bpmProcessDefService = bpmProcessDefService;
        this.objectMapper = objectMapper;
        this.userQueryFacade = userQueryFacade;
        this.nodeRegistry = nodeRegistry;
    }

    /**
     * 创建流程定义（DRAFT 状态）。
     * <p>
     * 校验 formKey 表单存在（否则 2009），生成初始图（START → END）并入库。
     * </p>
     *
     * @param request 创建请求（name + formKey）
     * @return 流程定义 ID + 初始图
     */
    @Transactional
    @PreAuthorize("@ss.hasPermi('workflow:def:create')")
    @PostMapping
    public R<CreateProcessDefResponse> create(@Valid @RequestBody CreateProcessDefRequest request) {
        BpmProcessDef entity = bpmProcessDefService.createDef(
                request.getName(), request.getFormKey());
        ProcessGraph graph = parseGraph(entity.getGraphJson());
        log.info("流程定义已创建: id={}, processKey={}", entity.getId(), entity.getProcessKey());
        return R.ok(CreateProcessDefResponse.builder()
                .defId(entity.getId())
                .graph(graph)
                .build());
    }

    /**
     * 修改流程定义（仅 DRAFT 状态允许）。
     * <p>
     * name / formKey 均可选（null 表示不修改）；formKey 变更时校验表单存在。
     *
     * @param id      流程定义 ID
     * @param request 修改请求
     * @return 更新后的图（含同步后的 name / formKey）
     */
    @Transactional
    @PreAuthorize("@ss.hasPermi('workflow:def:save')")
    @PutMapping("/{id}")
    public R<ProcessGraph> updateDef(@PathVariable Long id,
                                     @RequestBody UpdateProcessDefRequest request) {
        bpmProcessDefService.updateDef(id, request.getName(), request.getFormKey());
        BpmProcessDef entity = bpmProcessDefService.getDef(id);
        log.info("流程定义已修改: id={}, name={}, formKey={}", id, entity.getName(), entity.getFormKey());
        return R.ok(parseGraph(entity.getGraphJson()));
    }

    /**
     * 保存草稿图 —— 无条件全量覆盖 graph_json。
     * <p>
     * status 保持 DRAFT，不跑校验拦截（允许存残图）。
     * </p>
     *
     * @param id    流程定义 ID
     * @param graph 图 JSON（ProcessGraph）
     */
    @Transactional
    @PreAuthorize("@ss.hasPermi('workflow:def:save')")
    @PutMapping("/{id}/graph")
    public R<Void> saveDraftGraph(@PathVariable Long id,
                                  @RequestBody ProcessGraph graph) {
        String graphJson = graph != null ? toJson(graph) : null;
        bpmProcessDefService.saveDraftGraph(id, graphJson);
        log.info("草稿图已保存: id={}", id);
        return R.ok();
    }

    /**
     * 校验图（按 ID 读取定义中的图进行校验）。
     * <p>
     * 跑全部 6 类校验规则，返回错误列表（空列表 = 通过）。
     * 独立端点，不改数据。
     * </p>
     */
    @PostMapping("/{id}/validate")
    public R<List<GraphValidationError>> validateGraph(@PathVariable Long id) {
        List<GraphValidationError> errors = bpmProcessDefService.validateGraph(id);
        log.debug("图校验（by id）: id={}, errorCount={}", id, errors.size());
        return R.ok(errors);
    }

    /**
     * 校验图（直接传入 ProcessGraph 进行校验）。
     * <p>
     * 供设计器实时校验使用，不依赖 DB 中的已存数据。
     * </p>
     */
    @PostMapping("/validate")
    public R<List<GraphValidationError>> validateGraph(@RequestBody ProcessGraph graph) {
        List<GraphValidationError> errors = bpmProcessDefService.validateGraph(graph);
        log.debug("图校验（direct）: processKey={}, errorCount={}",
                graph.getProcessKey(), errors.size());
        return R.ok(errors);
    }

    /**
     * 读取流程定义 + 图。
     *
     * @param id 流程定义 ID
     * @return ProcessGraph（设计器回显用）
     */
    @GetMapping("/{id}")
    public R<ProcessGraph> getDef(@PathVariable Long id) {
        BpmProcessDef entity = bpmProcessDefService.getDef(id);
        ProcessGraph graph = parseGraph(entity.getGraphJson());
        return R.ok(graph);
    }

    /**
     * 读取流程定义已部署的 BPMN XML。
     * <p>
     * 仅已发布（PUBLISHED）的流程定义有 BPMN XML 数据。
     * </p>
     *
     * @param id 流程定义 ID
     * @return 原始 BPMN XML 字符串
     */
    @GetMapping("/{id}/bpmn-xml")
    public R<String> getBpmnXml(@PathVariable Long id) {
        String bpmnXml = bpmProcessDefService.getBpmnXml(id);
        return R.ok(bpmnXml);
    }

    /**
     * 分页查询流程定义列表（不含 graph_json 大字段）。
     *
     * @param pageParam 分页参数
     * @param formKey   可选，按绑定表单 formKey 精确过滤（表单工作台"关联流程"用）
     */
    @PreAuthorize("@ss.hasPermi('workflow:def:view')")
    @GetMapping
    public R<PageResult<BpmProcessDef>> listDefs(PageParam pageParam,
                                                 @RequestParam(required = false) String formKey) {
        PageResult<BpmProcessDef> result = bpmProcessDefService.listDefs(pageParam, formKey);
        return R.ok(result);
    }

    /**
     * 软删流程定义。
     *
     * @param id 流程定义 ID
     */
    @Transactional
    @PreAuthorize("@ss.hasPermi('workflow:def:delete')")
    @DeleteMapping("/{id}")
    public R<Void> deleteDef(@PathVariable Long id) {
        bpmProcessDefService.deleteDef(id);
        log.info("流程定义已删除: id={}", id);
        return R.ok();
    }

    /**
     * 发布流程定义（DRAFT → PUBLISHED）。
     * <p>
     * 触发完整发布流水线：图校验 → 表单已发布检查 → process_key 冻结检查 →
     * BPMN 翻译 → Flowable 部署 → 回填 → 状态变更。
     * </p>
     *
     * @param id 流程定义 ID
     * @return 发布后的流程定义实体
     */
    @Transactional
    @PreAuthorize("@ss.hasPermi('workflow:def:publish')")
    @PostMapping("/{id}/publish")
    public R<BpmProcessDef> publish(@PathVariable Long id) {
        BpmProcessDef published = bpmProcessDefService.publish(id);
        log.info("流程定义已发布: id={}", id);
        return R.ok(published);
    }

    /**
     * 审批人候选列表（指定审批人配置用）。
     * <p>
     * 经 {@code UserQueryFacade}（sw-biz-system-api）查询正常状态用户，
     * 不直接访问 sys_user 表；仅返回 id/username/realName 脱敏字段。
     * 供流程定义编辑页配置单节点指定审批人。
     * </p>
     *
     * @param keyword 关键字（匹配用户名/姓名，可空）
     * @return 用户候选选项列表
     */
    @GetMapping("/approver-candidates")
    public R<List<UserOptionDTO>> approverCandidates(
            @RequestParam(required = false, defaultValue = "") String keyword) {
        return R.ok(userQueryFacade.searchActiveUsers(keyword, 50));
    }

    /**
     * 当前应用已完整贯通的节点能力清单。设计端、发布校验和翻译链消费同一注册结果。
     */
    @PreAuthorize("@ss.hasPermi('workflow:def:view')")
    @GetMapping("/node-capabilities")
    public R<List<BpmNodeCapabilityDTO>> nodeCapabilities() {
        if (nodeRegistry == null) {
            throw new IllegalStateException("BPM 节点注册结果未装配");
        }
        return R.ok(nodeRegistry.capabilities());
    }

    // ==================== 内部方法 ====================

    private ProcessGraph parseGraph(String graphJson) {
        if (graphJson == null || graphJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(graphJson, ProcessGraph.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse graph_json: {}", e.getMessage());
            return null;
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize graph", e);
        }
    }
}
