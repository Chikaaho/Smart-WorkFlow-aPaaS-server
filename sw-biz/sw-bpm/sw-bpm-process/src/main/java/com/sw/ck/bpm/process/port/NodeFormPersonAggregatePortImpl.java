package com.sw.ck.bpm.process.port;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.variable.NodeFormPersonAggregatePort;
import com.sw.ck.bpm.process.entity.BpmTaskFormData;
import com.sw.ck.bpm.process.mapper.BpmTaskFormDataMapper;
import com.sw.ck.bpm.process.service.NodeFormDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * P64 阶段Ⅱ（A07 聚合会签）节点表单人员字段读取端口实现。
 * <p>
 * 只读指定节点指定轮次全部有效最终提交（status=SUBMITTED），把人员字段值解析为稳定
 * 用户 ID 字符串列表（单值或多值展平）；来源轮次/任务随记录保留可追溯。
 * 已撤回/取消/被新轮次取代的数据不进入集合（仅 SUBMITTED 行）。
 * </p>
 */
@Slf4j
@Component
public class NodeFormPersonAggregatePortImpl implements NodeFormPersonAggregatePort {

    private final BpmTaskFormDataMapper taskFormDataMapper;
    private final NodeFormDataService nodeFormDataService;
    private final ObjectMapper objectMapper;

    public NodeFormPersonAggregatePortImpl(BpmTaskFormDataMapper taskFormDataMapper,
                                           NodeFormDataService nodeFormDataService,
                                           ObjectMapper objectMapper) {
        this.taskFormDataMapper = taskFormDataMapper;
        this.nodeFormDataService = nodeFormDataService;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<List<String>> readPersonFieldValues(Long tenantId, String processInstanceId,
                                                        String nodeKey, String formField,
                                                        int roundOffset) {
        if (tenantId == null || processInstanceId == null || processInstanceId.isBlank()
                || nodeKey == null || nodeKey.isBlank() || formField == null || formField.isBlank()
                || roundOffset < 0) {
            return Optional.empty();
        }
        long round = nodeFormDataService.currentRound(processInstanceId) - roundOffset;
        if (round < 1) {
            // 请求轮次尚不存在（如首轮取上一轮）：合法零匹配
            return Optional.of(List.of());
        }
        List<BpmTaskFormData> rows = taskFormDataMapper.selectList(
                Wrappers.<BpmTaskFormData>lambdaQuery()
                        .eq(BpmTaskFormData::getTenantId, tenantId)
                        .eq(BpmTaskFormData::getProcessInstanceId, processInstanceId)
                        .eq(BpmTaskFormData::getNodeKey, nodeKey)
                        .eq(BpmTaskFormData::getRoundNo, round)
                        .eq(BpmTaskFormData::getStatus, NodeFormDataService.STATUS_SUBMITTED)
                        .orderByAsc(BpmTaskFormData::getId));
        List<String> values = new ArrayList<>();
        for (BpmTaskFormData row : rows) {
            Map<String, Object> data = nodeFormDataService.parseData(row.getDataText());
            Object value = data.get(formField);
            collectIds(value, values);
        }
        return Optional.of(values);
    }

    /** USER 单值 / USER_SET 多值（JSON 数组或逗号串）展平为 ID 字符串。 */
    private void collectIds(Object value, List<String> sink) {
        if (value == null) {
            return;
        }
        if (value instanceof List<?> list) {
            list.forEach(item -> {
                if (item != null && !String.valueOf(item).isBlank()) {
                    sink.add(String.valueOf(item));
                }
            });
            return;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty() || "null".equals(text)) {
            return;
        }
        if (text.startsWith("[")) {
            try {
                List<?> parsed = objectMapper.readValue(text, List.class);
                parsed.forEach(item -> {
                    if (item != null && !String.valueOf(item).isBlank()) {
                        sink.add(String.valueOf(item));
                    }
                });
                return;
            } catch (Exception e) {
                log.warn("聚合人员字段 JSON 解析失败，按原值跳过: {}", e.getMessage());
                return;
            }
        }
        sink.add(text);
    }
}
