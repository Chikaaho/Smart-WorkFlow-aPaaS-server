package com.sw.ck.agent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.agent.dto.AgentGraphSnapshotDTO;
import com.sw.ck.agent.dto.graph.ProcessGraph;
import com.sw.ck.agent.entity.AgentGraphDef;
import com.sw.ck.agent.entity.AgentGraphSnapshot;
import com.sw.ck.agent.mapper.AgentGraphSnapshotMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 图发布快照服务（D022-06）：发布写快照、执行/调试按版本读取、历史版本列表。
 * <p>
 * 兼容存量已发布图：登记版本但尚无快照时（升级回填未覆盖的边界），按当前
 * graph_json 补写一条同版本快照后再返回，保证执行不中断。
 * </p>
 */
@Service
public class AgentGraphSnapshotServiceImpl {

    private final AgentGraphSnapshotMapper snapshotMapper;
    private final ObjectMapper objectMapper;

    public AgentGraphSnapshotServiceImpl(AgentGraphSnapshotMapper snapshotMapper,
                                         ObjectMapper objectMapper) {
        this.snapshotMapper = snapshotMapper;
        this.objectMapper = objectMapper;
    }

    /** 发布时固化当前 graph_json（调用方已完成 defVersion 递增与状态回写）。 */
    public AgentGraphSnapshot createSnapshot(AgentGraphDef def) {
        AgentGraphSnapshot snapshot = new AgentGraphSnapshot();
        snapshot.setGraphDefId(def.getId());
        snapshot.setDefVersion(def.getDefVersion());
        snapshot.setGraphKey(def.getGraphKey());
        snapshot.setName(def.getName());
        snapshot.setGraphJson(def.getGraphJson());
        snapshotMapper.insert(snapshot);
        return snapshot;
    }

    /**
     * 读取指定版本的快照；缺失时按当前定义补写（存量兼容）。
     */
    public AgentGraphSnapshot requireSnapshot(AgentGraphDef def, Integer defVersion) {
        AgentGraphSnapshot snapshot = snapshotMapper.selectOne(
                Wrappers.<AgentGraphSnapshot>lambdaQuery()
                        .eq(AgentGraphSnapshot::getGraphDefId, def.getId())
                        .eq(AgentGraphSnapshot::getDefVersion, defVersion)
                        .orderByDesc(AgentGraphSnapshot::getId)
                        .last("LIMIT 1"));
        if (snapshot != null) {
            return snapshot;
        }
        AgentGraphSnapshot repaired = new AgentGraphSnapshot();
        repaired.setGraphDefId(def.getId());
        repaired.setDefVersion(defVersion);
        repaired.setGraphKey(def.getGraphKey());
        repaired.setName(def.getName());
        repaired.setGraphJson(def.getGraphJson());
        snapshotMapper.insert(repaired);
        return repaired;
    }

    /** 解析指定版本的快照为图对象（G3：恢复执行/子图按固定 defVersion 绑定）。 */
    public ProcessGraph loadGraphByVersion(AgentGraphDef def, Integer defVersion) {
        AgentGraphSnapshot snapshot = requireSnapshot(def, defVersion == null ? def.getDefVersion() : defVersion);
        if (snapshot.getGraphJson() == null || snapshot.getGraphJson().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(snapshot.getGraphJson(), ProcessGraph.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** 解析指定版本的快照为图对象。 */
    public ProcessGraph loadPublishedGraph(AgentGraphDef def) {
        AgentGraphSnapshot snapshot = requireSnapshot(def, def.getDefVersion());
        if (snapshot.getGraphJson() == null || snapshot.getGraphJson().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(snapshot.getGraphJson(), ProcessGraph.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** 版本列表（新版本在前，不含 graph_json）。 */
    public List<AgentGraphSnapshotDTO> listVersions(Long graphDefId) {
        return snapshotMapper.selectList(
                        Wrappers.<AgentGraphSnapshot>lambdaQuery()
                                .eq(AgentGraphSnapshot::getGraphDefId, graphDefId)
                                .orderByDesc(AgentGraphSnapshot::getDefVersion))
                .stream().map(this::toDTO).toList();
    }

    private AgentGraphSnapshotDTO toDTO(AgentGraphSnapshot snapshot) {
        AgentGraphSnapshotDTO dto = new AgentGraphSnapshotDTO();
        dto.setDefVersion(snapshot.getDefVersion());
        dto.setGraphKey(snapshot.getGraphKey());
        dto.setName(snapshot.getName());
        dto.setCreateTime(snapshot.getCreateTime());
        return dto;
    }
}
