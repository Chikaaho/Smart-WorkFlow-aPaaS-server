package com.sw.ck.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.agent.dto.AgentGraphDefDTO;
import com.sw.ck.agent.dto.graph.GraphElement;
import com.sw.ck.agent.dto.graph.ProcessGraph;
import com.sw.ck.agent.entity.AgentGraphDef;
import com.sw.ck.agent.mapper.AgentGraphDefMapper;
import com.sw.ck.agent.service.AgentGraphDefService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.service.BaseServiceImpl;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 图定义 Service 实现（M07-F02 Step7 图定义 CRUD + 版本 + 发布骨架）。
 * <p>
 * 纯存储+管理，无任何执行语义：<b>不解释节点/边含义</b>——{@code graph_json} 内
 * 节点/边的 {@code config}/{@code style} 为不透明 Map 原样透传（DTO 类注释禁令），
 * 发布门仅做"图可解析 + elements 非空 + graphKey 冻结检查"最小校验，完整拓扑校验
 * 与解释执行留 Step8。
 * </p>
 * <p>
 * 发布状态机（方案 §4-A）：版本递增取 sw-biz-form 语义（每次发布 defVersion + 1，
 * 对齐 FormDefServiceImpl L217 原文），key 冻结取 sw-bpm 语义（已 PUBLISHED 后
 * graph_key 不可变更，对齐 BpmProcessDefServiceImpl L195-204 2101 检查），允许重复
 * 发布（agent 图发布不建物理资源，无 form 的"不可重复发布"理由）。
 * </p>
 */
@Service
public class AgentGraphDefServiceImpl
        extends BaseServiceImpl<AgentGraphDefMapper, AgentGraphDef>
        implements AgentGraphDefService {

    /** 状态常量（varchar + String，不建 enum 类，D52 决策） */
    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_PUBLISHED = "PUBLISHED";

    /** graphKey 前缀（对齐 sw-bpm "bpm_" 前缀先例） */
    private static final String GRAPH_KEY_PREFIX = "agent_";

    private final ObjectMapper objectMapper;

    public AgentGraphDefServiceImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ==================== 创建 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(String name) {
        if (name == null || name.isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "图名称不能为空");
        }
        // graphKey 服务端生成：agent_ 前缀 + UUID 短串（对齐 sw-bpm bpm_ 前缀先例）
        String graphKey = GRAPH_KEY_PREFIX
                + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        ProcessGraph initialGraph = buildInitialGraph(graphKey, name);

        AgentGraphDef entity = new AgentGraphDef();
        entity.setGraphKey(graphKey);
        entity.setName(name);
        entity.setDefVersion(1);
        entity.setStatus(STATUS_DRAFT);
        entity.setGraphJson(toJson(initialGraph));
        try {
            save(entity);
        } catch (DuplicateKeyException e) {
            // 并发竞态兜底：唯一索引 uk_sw_agent_graph_key (tenant_id, graph_key) 冲突
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "图 key 冲突：" + graphKey);
        }
        return entity.getId();
    }

    // ==================== 草稿保存 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveDraftGraph(Long id, ProcessGraph graph) {
        AgentGraphDef entity = requireEntity(id);
        entity.setGraphJson(toJson(graph));
        // status 保持 DRAFT，不跑校验（允许存残图，对齐 sw-bpm saveDraftGraph 先例）
        updateById(entity);
    }

    // ==================== 查询 ====================

    @Override
    public ProcessGraph getGraph(Long id) {
        AgentGraphDef entity = requireEntity(id);
        return parseGraph(entity.getGraphJson());
    }

    @Override
    public PageResult<AgentGraphDefDTO> pageDefs(PageParam pageParam) {
        LambdaQueryWrapper<AgentGraphDef> wrapper = Wrappers.<AgentGraphDef>lambdaQuery()
                .orderByDesc(AgentGraphDef::getUpdateTime, AgentGraphDef::getId);
        Page<AgentGraphDef> page = page(new Page<>(pageParam.getPageNum(), pageParam.getPageSize()), wrapper);
        // DTO 不含 graph_json 大字段（编译期防线），列表剥离大字段（对齐 sw-bpm listDefs 先例）
        return PageResult.of(page.convert(this::toDTO));
    }

    // ==================== 删除 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        // 逻辑删除（@TableLogic），幂等：不存在也直接返回成功（对齐 AgentModelConfigService.delete）
        removeById(id);
    }

    // ==================== 发布 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentGraphDefDTO publish(Long id) {
        AgentGraphDef entity = requireEntity(id);

        // —— ① 发布门：图可解析且 elements 非空（最小校验，完整拓扑校验留 Step8） ——
        ProcessGraph graph = parseGraph(entity.getGraphJson());
        if (graph == null || graph.getElements() == null || graph.getElements().isEmpty()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "图数据为空，无法发布");
        }

        // —— ② graph_key 冻结检查（对齐 sw-bpm 2101 PROCESS_KEY_FROZEN 语义） ——
        // 已发布过的定义：graphKey 不可变更；首次发布（DRAFT）无发布历史，跳过检查
        if (STATUS_PUBLISHED.equals(entity.getStatus())) {
            if (graph.getGraphKey() != null && !entity.getGraphKey().equals(graph.getGraphKey())) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR, "graphKey 已冻结，不可变更");
            }
        }

        // —— ③ 版本递增 + 状态（版本递增取 sw-biz-form 语义：每次发布 +1） ——
        entity.setDefVersion(entity.getDefVersion() == null ? 1 : entity.getDefVersion() + 1);
        entity.setStatus(STATUS_PUBLISHED);
        updateById(entity);

        return toDTO(entity);
    }

    // ==================== 内部辅助 ====================

    /**
     * 按 id + 租户加载（baseMapper.selectById 经租户拦截器自动过滤 tenant_id），
     * 不存在抛 NOT_FOUND。
     */
    private AgentGraphDef requireEntity(Long id) {
        if (id == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "id 不能为空");
        }
        AgentGraphDef entity = baseMapper.selectById(id);
        if (entity == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND);
        }
        return entity;
    }

    /**
     * 生成初始图：START 节点 → 一条边 → END 节点，开箱即合法（对齐 sw-bpm buildInitialGraph 先例）。
     */
    private ProcessGraph buildInitialGraph(String graphKey, String name) {
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
                .graphKey(graphKey)
                .name(name)
                .version(1)
                .elements(List.of(startNode, endNode, edge))
                .canvas(Collections.emptyMap())
                .build();
    }

    private ProcessGraph parseGraph(String graphJson) {
        if (graphJson == null || graphJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(graphJson, ProcessGraph.class);
        } catch (Exception e) {
            // 注：ServiceImpl 基类的 log 为 MyBatis Log 接口（org.apache.ibatis.logging.Log），
            // 不支持 {} 占位符，拼接消息
            log.warn("Failed to parse graph_json: " + e.getMessage());
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

    private AgentGraphDefDTO toDTO(AgentGraphDef entity) {
        AgentGraphDefDTO dto = new AgentGraphDefDTO();
        dto.setId(entity.getId());
        dto.setGraphKey(entity.getGraphKey());
        dto.setName(entity.getName());
        dto.setDefVersion(entity.getDefVersion());
        dto.setStatus(entity.getStatus());
        dto.setCreateTime(entity.getCreateTime());
        dto.setUpdateTime(entity.getUpdateTime());
        return dto;
    }
}
