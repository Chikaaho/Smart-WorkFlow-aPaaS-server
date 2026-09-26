package com.sw.ck.bpm.process.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.bpm.api.dto.BpmDeployResult;
import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.GraphValidationError;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.facade.BpmDeployFacade;
import com.sw.ck.bpm.process.entity.BpmFormBinding;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.entity.BpmProcessDefVersion;
import com.sw.ck.bpm.process.mapper.BpmProcessDefMapper;
import com.sw.ck.bpm.process.service.BpmFormBindingService;
import com.sw.ck.bpm.process.service.BpmProcessDefService;
import com.sw.ck.bpm.process.validator.GraphValidator;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.api.form.FormDefinitionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 流程定义服务实现。
 */
@Service
public class BpmProcessDefServiceImpl implements BpmProcessDefService {

    private static final Logger log = LoggerFactory.getLogger(BpmProcessDefServiceImpl.class);

    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_PUBLISHED = "PUBLISHED";
    private static final String FORM_STATUS_PUBLISHED = "PUBLISHED";

    private final BpmProcessDefMapper mapper;
    private final com.sw.ck.bpm.process.mapper.BpmProcessDefVersionMapper versionMapper;
    private final com.sw.ck.bpm.process.service.NodeFunctionService nodeFunctionService;
    private final GraphValidator graphValidator;
    private final FormDefinitionService formDefinitionService;
    private final BpmDeployFacade bpmDeployFacade;
    private final BpmFormBindingService formBindingService;
    private final ObjectMapper objectMapper;

    public BpmProcessDefServiceImpl(BpmProcessDefMapper mapper,
                                    com.sw.ck.bpm.process.mapper.BpmProcessDefVersionMapper versionMapper,
                                    com.sw.ck.bpm.process.service.NodeFunctionService nodeFunctionService,
                                    GraphValidator graphValidator,
                                    FormDefinitionService formDefinitionService,
                                    BpmDeployFacade bpmDeployFacade,
                                    BpmFormBindingService formBindingService,
                                    ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.versionMapper = versionMapper;
        this.nodeFunctionService = nodeFunctionService;
        this.graphValidator = graphValidator;
        this.formDefinitionService = formDefinitionService;
        this.bpmDeployFacade = bpmDeployFacade;
        this.formBindingService = formBindingService;
        this.objectMapper = objectMapper;
    }

    @Override
    public BpmProcessDef findByProcessKey(String processKey) {
        LambdaQueryWrapper<BpmProcessDef> wrapper = Wrappers.<BpmProcessDef>lambdaQuery()
                .eq(BpmProcessDef::getProcessKey, processKey);
        return mapper.selectOne(wrapper);
    }

    @Override
    @Transactional
    public BpmProcessDef createDef(String name, String formKey) {
        // 校验表单存在（2009）
        if (!formExists(formKey)) {
            throw new BaseException(BpmErrorCode.GRAPH_FORM_NOT_FOUND);
        }

        // 生成 processKey
        String processKey = "bpm_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        // 生成初始图：START → END
        ProcessGraph initialGraph = buildInitialGraph(processKey, name, formKey);

        BpmProcessDef entity = new BpmProcessDef();
        entity.setProcessKey(processKey);
        entity.setName(name);
        entity.setFormKey(formKey);
        entity.setDefVersion(1);
        entity.setStatus(STATUS_DRAFT);
        entity.setGraphJson(toJson(initialGraph));

        mapper.insert(entity);
        log.info("Created process def: id={}, processKey={}", entity.getId(), processKey);
        return entity;
    }

    @Override
    @Transactional
    public BpmProcessDef updateDef(Long id, String name, String formKey) {
        BpmProcessDef entity = getExisting(id);
        if (!STATUS_DRAFT.equals(entity.getStatus())) {
            throw new BaseException(BpmErrorCode.PROCESS_DEF_PUBLISHED);
        }
        boolean changed = false;
        if (name != null && !name.isBlank() && !name.equals(entity.getName())) {
            entity.setName(name);
            changed = true;
        }
        if (formKey != null && !formKey.isBlank() && !formKey.equals(entity.getFormKey())) {
            if (!formExists(formKey)) {
                throw new BaseException(BpmErrorCode.GRAPH_FORM_NOT_FOUND);
            }
            entity.setFormKey(formKey);
            changed = true;
        }
        if (changed) {
            // 同步图内冗余的 name / formKey，保持元数据一致
            ProcessGraph graph = parseGraph(entity.getGraphJson());
            if (graph != null) {
                if (entity.getName() != null) {
                    graph.setName(entity.getName());
                }
                if (entity.getFormKey() != null) {
                    graph.setFormKey(entity.getFormKey());
                }
                entity.setGraphJson(toJson(graph));
            }
            mapper.updateById(entity);
            log.info("Updated process def: id={}, name={}, formKey={}", id, entity.getName(), entity.getFormKey());
        }
        return entity;
    }

    @Override
    @Transactional
    public void saveDraftGraph(Long id, String graphJson) {
        BpmProcessDef entity = getExisting(id);
        // 基本格式检查：草稿允许暂时不完整，但必须是可解析的图文档
        if (graphJson == null || graphJson.isBlank()
                || parseGraph(graphJson) == null) {
            throw new BaseException(BpmErrorCode.NODE_CONFIG_INVALID);
        }
        // 基本一致性检查（I3 §4.3 process_key 冻结）：图内 processKey 一律归一为
        // 定义行权威值；已发布定义的 key 冻结不被草稿图覆盖或漂移
        ProcessGraph incoming = parseGraph(graphJson);
        if (incoming.getProcessKey() == null
                || !incoming.getProcessKey().equals(entity.getProcessKey())) {
            incoming.setProcessKey(entity.getProcessKey());
            graphJson = toJson(incoming);
        }
        // 已发布定义再次编辑：形成新的草稿版本（版本单调递增；已发布冻结版本行不动）
        if (entity.getPublishedVersion() != null && entity.getDefVersion() != null
                && entity.getDefVersion() <= entity.getPublishedVersion()) {
            entity.setDefVersion(entity.getPublishedVersion() + 1);
            log.info("已发布定义再次编辑，草稿版本递增为 {}: defId={}", entity.getDefVersion(), id);
        }
        entity.setGraphJson(graphJson);
        mapper.updateById(entity);
        log.info("Saved draft graph: id={}, draftVersion={}", id, entity.getDefVersion());
    }

    @Override
    @Transactional
    public void markTemplateSource(Long id, Long templateId, Integer templateVersion) {
        BpmProcessDef entity = getExisting(id);
        // 仅 DRAFT 未登记时写入：历史与已发布定义的溯源关系不被改写
        if (!"DRAFT".equals(entity.getStatus()) || entity.getSourceTemplateId() != null) {
            return;
        }
        entity.setSourceTemplateId(templateId);
        entity.setSourceTemplateVersion(templateVersion);
        mapper.updateById(entity);
    }

    @Override
    public List<GraphValidationError> validateGraph(Long id) {
        BpmProcessDef entity = getExisting(id);
        ProcessGraph graph = parseGraph(entity.getGraphJson());
        if (graph == null || graph.getElements() == null) {
            return List.of(GraphValidationError.builder()
                    .errorCode(BpmErrorCode.GRAPH_MISSING_START.getCode())
                    .message("图数据为空")
                    .build());
        }
        return graphValidator.validate(graph.getElements(), entity.getFormKey());
    }

    @Override
    public List<GraphValidationError> validateGraph(ProcessGraph graph) {
        if (graph == null || graph.getElements() == null) {
            return List.of(GraphValidationError.builder()
                    .errorCode(BpmErrorCode.GRAPH_MISSING_START.getCode())
                    .message("图数据为空")
                    .build());
        }
        String formKey = graph.getFormKey();
        // 如果请求中没有 formKey，尝试从 DB 查
        return graphValidator.validate(graph.getElements(), formKey);
    }

    @Override
    public BpmProcessDef getDef(Long id) {
        return getExisting(id);
    }

    @Override
    public PageResult<BpmProcessDef> listDefs(PageParam pageParam, String formKey) {
        LambdaQueryWrapper<BpmProcessDef> wrapper = Wrappers.<BpmProcessDef>lambdaQuery()
                .orderByDesc(BpmProcessDef::getUpdateTime);
        if (formKey != null && !formKey.isBlank()) {
            wrapper.eq(BpmProcessDef::getFormKey, formKey.trim());
        }
        PageResult<BpmProcessDef> result = mapper.selectPage(pageParam, wrapper);
        // 列表不返回 graph_json 大字段
        if (result.getRecords() != null) {
            result.getRecords().forEach(r -> r.setGraphJson(null));
        }
        return result;
    }

    @Override
    @Transactional
    public void deleteDef(Long id) {
        BpmProcessDef entity = getExisting(id);
        // 安全删除：仅无发布版本的草稿可删除（已有发布/运行/历史引用的版本不可删）
        if (entity.getPublishedVersion() != null && entity.getPublishedVersion() > 0) {
            throw new BaseException(BpmErrorCode.VERSION_STATE_INVALID);
        }
        long referenceCount = formBindingService.lambdaQuery()
                .eq(BpmFormBinding::getProcessDefKey, entity.getProcessKey())
                .eq(BpmFormBinding::getActive, Boolean.TRUE)
                .count();
        if (referenceCount > 0) {
            throw new BaseException(BpmErrorCode.VERSION_STATE_INVALID);
        }
        mapper.deleteById(entity.getId());
        log.info("Soft-deleted process def: id={}", id);
    }

    @Override
    public String getBpmnXml(Long id) {
        BpmProcessDef def = getExisting(id);
        if (!STATUS_PUBLISHED.equals(def.getStatus()) || def.getProcessDefinitionId() == null) {
            throw new BaseException(BpmErrorCode.PROCESS_NOT_PUBLISHED);
        }
        // empty = 该 Flowable 流程定义不存在（原 IllegalStateException 缺失路径）：
        // 保持原有的“定义缺失即失败”对外行为，不静默返回空。
        return bpmDeployFacade.getBpmnXml(def.getProcessDefinitionId())
                .orElseThrow(() -> new IllegalStateException(
                        "流程定义资源不存在: processDefinitionId=" + def.getProcessDefinitionId()));
    }

    /**
     * 表单存在性判定（契约恒 present：formKey 空白亦给出明确 false 判定）；
     * empty 属契约破坏，显式失败而不伪装成“表单不存在”。
     */
    private boolean formExists(String formKey) {
        return formDefinitionService.formExists(formKey)
                .orElseThrow(() -> new IllegalStateException(
                        "表单存在性判定未返回结果: formKey=" + formKey));
    }

    @Override
    @Transactional
    public BpmProcessDef publish(Long id) {
        BpmProcessDef def = getExisting(id);

        // 解析图
        ProcessGraph graph = parseGraph(def.getGraphJson());
        if (graph == null || graph.getElements() == null || graph.getElements().isEmpty()) {
            throw new BaseException(BpmErrorCode.GRAPH_MISSING_START);
        }

        // ========== ② 发布门校验 ==========
        // 2a. 图拓扑校验（复现 GraphValidator 全规则）
        List<GraphValidationError> graphErrors = graphValidator.validate(graph.getElements(), graph.getFormKey());
        if (!graphErrors.isEmpty()) {
            // 将首条错误转为异常
            GraphValidationError first = graphErrors.get(0);
            throw new BaseException(first.getErrorCode(), first.getMessage());
        }

        // 2b. formKey 对应表单已发布（2100）
        String formKey = graph.getFormKey();
        if (formKey != null && !formKey.isBlank()) {
            // empty = 该 formKey 无表单定义（原 null 返回路径）：按“绑定表单不存在”拒绝发布
            java.util.Optional<FormDefDTO> formDefLookup = formDefinitionService.getFormDef(formKey);
            if (formDefLookup.isEmpty()) {
                throw new BaseException(BpmErrorCode.GRAPH_FORM_NOT_FOUND);
            }
            if (!FORM_STATUS_PUBLISHED.equals(formDefLookup.get().getStatus())) {
                throw new BaseException(BpmErrorCode.FORM_NOT_PUBLISHED);
            }
        }

        // 2c. process_key 冻结检查（2101）
        // 若该 def 已有 PUBLISHED 历史，检查 process_key 是否未变
        // 首次发布时 def.getStatus() == DRAFT，跳过此检查
        String newProcessKey = graph.getProcessKey();
        if (STATUS_PUBLISHED.equals(def.getStatus())) {
            // 已发布过的定义：process_key 不可变更
            if (!def.getProcessKey().equals(newProcessKey)) {
                throw new BaseException(BpmErrorCode.PROCESS_KEY_FROZEN);
            }
        }
        // 注意：查询已有历史需额外扫库，此处仅守住已 PUBLISHED 的当前记录
        // 若存在此前已发布的版本但删了，当前为 DRAFT 的新记录，则允许新 key

        // ========== ②c 节点函数发布期校验（I3 §4.9，零部署失败即拒绝） ==========
        List<GraphValidationError> functionErrors =
                nodeFunctionService.validateForPublish(graph, def.getTenantId());
        if (!functionErrors.isEmpty()) {
            GraphValidationError firstError = functionErrors.get(0);
            throw new BaseException(firstError.getErrorCode(), firstError.getMessage());
        }

        // ========== ③ 翻译 ==========
        // translateToBpmn 当前契约恒 present：图非法继续抛异常
        byte[] bpmnXml = bpmDeployFacade.translateToBpmn(graph)
                .orElseThrow(() -> new IllegalStateException(
                        "流程翻译未返回 BPMN XML: processKey=" + newProcessKey));

        // ========== ④ 部署 ==========
        String deploymentName = graph.getName() != null ? graph.getName() : newProcessKey;
        // deployModel 当前契约恒 present：部署失败继续抛异常
        BpmDeployResult deployResult = bpmDeployFacade.deployModel(bpmnXml, deploymentName)
                .orElseThrow(() -> new IllegalStateException(
                        "流程部署未返回部署结果: deploymentName=" + deploymentName));

        // ========== ⑤ 回填 + ⑥ 状态 ==========
        def.setDeploymentId(deployResult.getDeploymentId());
        def.setProcessDefinitionId(deployResult.getProcessDefinitionId());
        def.setStatus(STATUS_PUBLISHED);
        mapper.updateById(def);

        // 发布成功 → 落启用表单绑定（表单提交事件按 formKey 找到该流程发起实例）
        if (formKey != null && !formKey.isBlank()) {
            formBindingService.lambdaUpdate()
                    .eq(BpmFormBinding::getFormKey, formKey)
                    .eq(BpmFormBinding::getActive, Boolean.TRUE)
                    .set(BpmFormBinding::getActive, Boolean.FALSE)
                    .update();
            BpmFormBinding binding = new BpmFormBinding();
            binding.setFormKey(formKey);
            binding.setProcessDefKey(newProcessKey);
            binding.setActive(Boolean.TRUE);
            formBindingService.save(binding);
            log.info("Form binding activated: formKey={} -> processDefKey={}", formKey, newProcessKey);
        }

        // ========== ⑥ I3 冻结发布版本 ==========
        // 版本号单调递增：草稿版本 ≤ 已发布版本时视为新一轮编辑，冲到 published+1
        Integer frozenVersion = def.getDefVersion() == null ? 1 : def.getDefVersion();
        if (def.getPublishedVersion() != null && frozenVersion <= def.getPublishedVersion()) {
            frozenVersion = def.getPublishedVersion() + 1;
            def.setDefVersion(frozenVersion);
        }
        if (versionExists(id, frozenVersion)) {
            throw new BaseException(BpmErrorCode.VERSION_STATE_INVALID);
        }

        BpmProcessDefVersion frozen = new BpmProcessDefVersion();
        frozen.setDefId(id);
        frozen.setGraphVersion(frozenVersion);
        frozen.setStatus("PUBLISHED");
        frozen.setName(graph.getName() != null ? graph.getName() : def.getName());
        frozen.setFormKey(formKey);
        frozen.setFormVersion(resolveFormVersion(graph.getFormKey()));
        frozen.setFunctionVersions(resolveFunctionVersions(graph));
        frozen.setGraphJson(def.getGraphJson());
        frozen.setDeploymentId(deployResult.getDeploymentId());
        frozen.setProcessDefinitionId(deployResult.getProcessDefinitionId());
        try {
            com.sw.ck.security.holder.LoginUser loginUser = com.sw.ck.security.holder.LoginUserHolder.get();
            if (loginUser != null) {
                frozen.setPublishedBy(loginUser.getUserId());
            }
        } catch (Exception ignored) {
            // 非请求上下文（内部调用）允许为空
        }
        frozen.setPublishedAt(java.time.LocalDateTime.now());
        versionMapper.insert(frozen);

        // 只更新发布元数据列，避免把 graph_json 缺省值误写
        BpmProcessDef patch = new BpmProcessDef();
        patch.setId(id);
        patch.setDeploymentId(deployResult.getDeploymentId());
        patch.setProcessDefinitionId(deployResult.getProcessDefinitionId());
        patch.setStatus(STATUS_PUBLISHED);
        patch.setPublishedVersion(frozenVersion);
        patch.setDefVersion(frozenVersion);
        mapper.updateById(patch);

        log.info("Process def published: id={}, processKey={}, version={}, deploymentId={}, processDefinitionId={}",
                id, newProcessKey, frozenVersion, deployResult.getDeploymentId(),
                deployResult.getProcessDefinitionId());

        return def;
    }

    private boolean versionExists(Long defId, Integer graphVersion) {
        return versionMapper.selectCount(
                Wrappers.<BpmProcessDefVersion>lambdaQuery()
                        .eq(BpmProcessDefVersion::getDefId, defId)
                        .eq(BpmProcessDefVersion::getGraphVersion, graphVersion)) > 0;
    }

    private String resolveFormVersion(String formKey) {
        if (formKey == null || formKey.isBlank()) {
            return null;
        }
        try {
            // empty = 该 formKey 无表单定义（原 null 返回路径）：无版本可回填
            java.util.Optional<FormDefDTO> formDefLookup = formDefinitionService.getFormDef(formKey);
            if (formDefLookup.isEmpty()) {
                return null;
            }
            Integer formVersion = formDefLookup.get().getFormVersion();
            return formVersion == null ? null : String.valueOf(formVersion);
        } catch (Exception e) {
            log.warn("读取表单版本失败: formKey={}", formKey);
            return null;
        }
    }

    /**
     * 收集节点配置中引用的节点函数版本（funcKey → version），作为发布时冻结的清单 JSON。
     * 约定：节点 config.functions = [{key,version}...]。
     */
    private String resolveFunctionVersions(ProcessGraph graph) {
        Map<String, Object> mapping = new LinkedHashMap<>();
        if (graph.getElements() == null) return "{}";
        for (GraphElement element : graph.getElements()) {
            if (!"node".equals(element.getKind())
                    || element.getConfig() == null) {
                continue;
            }
            Object functions = element.getConfig().get("functions");
            if (functions instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> fn
                            && fn.get("key") != null && fn.get("version") != null) {
                        mapping.put(String.valueOf(fn.get("key")),
                                fn.get(("version")));
                    }
                }
            }
        }
        return toJson(mapping);
    }

    @Override
    public List<BpmProcessDefVersion> listVersions(Long defId) {
        getExisting(defId);
        return versionMapper.selectList(
                Wrappers.<BpmProcessDefVersion>lambdaQuery()
                        .eq(BpmProcessDefVersion::getDefId, defId)
                        .orderByDesc(BpmProcessDefVersion::getGraphVersion));
    }

    @Override
    public ProcessGraph getVersionGraph(Long defId, Integer graphVersion) {
        BpmProcessDefVersion version = getVersionRow(defId, graphVersion);
        return parseGraph(version.getGraphJson());
    }

    @Override
    @Transactional
    public void suspendVersion(Long defId, Integer graphVersion) {
        BpmProcessDefVersion version = requireLatestVersion(defId, graphVersion);
        // present = APPLIED（本次挂起）/ ALREADY_APPLIED（已挂起）；定义不存在继续由引擎抛异常
        bpmDeployFacade.suspendProcessDefinition(version.getProcessDefinitionId())
        .orElseThrow(() -> new IllegalStateException(
                "BpmDeployFacade#suspendProcessDefinition 契约恒 present，empty 属契约违约"));
        patchVersionStatus(version.getId(), "SUSPENDED");
        log.info("发布版本已挂起: defId={}, version={}", defId, graphVersion);
    }

    @Override
    @Transactional
    public void activateVersion(Long defId, Integer graphVersion) {
        BpmProcessDefVersion version = requireLatestVersion(defId, graphVersion);
        // present = APPLIED（本次激活）/ ALREADY_APPLIED（已激活）；定义不存在继续由引擎抛异常
        bpmDeployFacade.activateProcessDefinition(version.getProcessDefinitionId())
        .orElseThrow(() -> new IllegalStateException(
                "BpmDeployFacade#activateProcessDefinition 契约恒 present，empty 属契约违约"));
        patchVersionStatus(version.getId(), "PUBLISHED");
        log.info("发布版本已激活: defId={}, version={}", defId, graphVersion);
    }

    @Override
    @Transactional
    public void disableVersion(Long defId, Integer graphVersion) {
        BpmProcessDefVersion version = requireLatestVersion(defId, graphVersion);
        // DISABLED = 业务下线标记：同样挂起 Flowable 定义，历史回看不删除
        // present = APPLIED（本次挂起）/ ALREADY_APPLIED（已挂起）；定义不存在继续由引擎抛异常
        bpmDeployFacade.suspendProcessDefinition(version.getProcessDefinitionId())
        .orElseThrow(() -> new IllegalStateException(
                "BpmDeployFacade#suspendProcessDefinition 契约恒 present，empty 属契约违约"));
        patchVersionStatus(version.getId(), "DISABLED");
        log.info("发布版本已下线: defId={}, version={}", defId, graphVersion);
    }

    private BpmProcessDefVersion getVersionRow(Long defId, Integer graphVersion) {
        BpmProcessDefVersion version = versionMapper.selectOne(
                Wrappers.<BpmProcessDefVersion>lambdaQuery()
                        .eq(BpmProcessDefVersion::getDefId, defId)
                        .eq(BpmProcessDefVersion::getGraphVersion, graphVersion));
        if (version == null) {
            throw new BaseException(BpmErrorCode.PROCESS_DEF_NOT_FOUND);
        }
        return version;
    }

    private BpmProcessDefVersion requireLatestVersion(Long defId, Integer graphVersion) {
        BpmProcessDef def = getExisting(defId);
        BpmProcessDefVersion version = getVersionRow(defId, graphVersion);
        if (def.getPublishedVersion() == null
                || !def.getPublishedVersion().equals(graphVersion)) {
            // 只有当前最高发布版本可挂起/激活/下线，避免历史版本双活
            throw new BaseException(BpmErrorCode.VERSION_STATE_INVALID);
        }
        return version;
    }

    private void patchVersionStatus(Long id, String status) {
        BpmProcessDefVersion patch = new BpmProcessDefVersion();
        patch.setId(id);
        patch.setStatus(status);
        versionMapper.updateById(patch);
    }

    // ==================== 内部方法 ====================

    private BpmProcessDef getExisting(Long id) {
        BpmProcessDef entity = mapper.selectById(id);
        if (entity == null) {
            throw new BaseException(BpmErrorCode.PROCESS_DEF_NOT_FOUND);
        }
        return entity;
    }

    private ProcessGraph parseGraph(String graphJson) {
        if (graphJson == null || graphJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(graphJson, ProcessGraph.class);
        } catch (Exception e) {
            log.warn("Failed to parse graph_json: {}", e.getMessage());
            return null;
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize object", e);
        }
    }

    /**
     * 生成初始图：START 节点 → 一条边 → END 节点，开箱即合法。
     */
    private ProcessGraph buildInitialGraph(String processKey, String name, String formKey) {
        String startId = "node_start";
        String endId = "node_end";
        String edgeId = "edge_1";

        // 默认坐标（设计器画布可调整）
        Map<String, Object> startStyle = new LinkedHashMap<>();
        startStyle.put("x", 100);
        startStyle.put("y", 300);

        Map<String, Object> endStyle = new LinkedHashMap<>();
        endStyle.put("x", 700);
        endStyle.put("y", 300);

        GraphElement startNode = GraphElement.builder()
                .id(startId)
                .kind("node")
                .type("START")
                .style(startStyle)
                .config(Collections.emptyMap())
                .build();

        GraphElement endNode = GraphElement.builder()
                .id(endId)
                .kind("node")
                .type("END")
                .style(endStyle)
                .config(Collections.emptyMap())
                .build();

        GraphElement edge = GraphElement.builder()
                .id(edgeId)
                .kind("edge")
                .source(startId)
                .target(endId)
                .style(Collections.emptyMap())
                .config(Collections.emptyMap())
                .build();

        return ProcessGraph.builder()
                .processKey(processKey)
                .name(name)
                .formKey(formKey)
                .version(1)
                .elements(List.of(startNode, endNode, edge))
                .canvas(Collections.emptyMap())
                .build();
    }
    @Override
    public BpmProcessDef findById(Long id) {
        return mapper.selectById(id);
    }

    @Override
    @Transactional
    public BpmProcessDef changeIotAccess(Long id, boolean flag) {
        BpmProcessDef def = mapper.selectById(id);
        if (def == null) {
            throw new IllegalArgumentException("流程定义不存在: id=" + id);
        }
        if (flag && !"PUBLISHED".equals(def.getStatus())) {
            throw new IllegalStateException("仅已发布流程定义可开启 IoT 接入: " + def.getProcessKey());
        }
        BpmProcessDef patch = new BpmProcessDef();
        patch.setId(id);
        patch.setIotAccessEnabled(flag);
        mapper.updateById(patch);
        log.info("流程定义 IoT 接入开关已变更: id={}, processKey={}, enabled={}", id, def.getProcessKey(), flag);
        return mapper.selectById(id);
    }

    @Override
    @Transactional
    public BpmProcessDef setIotDeviceAction(Long id, String actionJson) {
        BpmProcessDef def = mapper.selectById(id);
        if (def == null) {
            throw new IllegalArgumentException("流程定义不存在: id=" + id);
        }
        if (actionJson != null && !actionJson.isBlank()) {
            com.alibaba.fastjson2.JSON.parseObject(actionJson);
        }
        BpmProcessDef patch = new BpmProcessDef();
        patch.setId(id);
        patch.setIotDeviceActionJson(actionJson);
        mapper.updateById(patch);
        return mapper.selectById(id);
    }

}
