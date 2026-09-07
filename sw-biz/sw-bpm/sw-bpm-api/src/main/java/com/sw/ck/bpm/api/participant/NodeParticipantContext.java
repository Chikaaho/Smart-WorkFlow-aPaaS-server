package com.sw.ck.bpm.api.participant;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/** 解析节点参与人的受控上下文；不暴露 Flowable 对象。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeParticipantContext implements Serializable {

    private Long tenantId;
    private String processInstanceId;
    private String taskId;
    private String nodeKey;
    private String businessKey;
    private String formKey;
    private Long initiatorUserId;
    private Map<String, Object> variables;
    private String strategy;
    private Object strategyValue;
    private String adapterId;
}
