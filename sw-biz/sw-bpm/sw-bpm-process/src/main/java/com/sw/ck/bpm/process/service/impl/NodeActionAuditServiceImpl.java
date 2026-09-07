package com.sw.ck.bpm.process.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.participant.NodeActionAuditPort;
import com.sw.ck.bpm.process.entity.BranchTrace;
import com.sw.ck.bpm.process.entity.CopyRecord;
import com.sw.ck.bpm.process.mapper.BranchTraceMapper;
import com.sw.ck.bpm.process.mapper.CopyRecordMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class NodeActionAuditServiceImpl implements NodeActionAuditPort {
    private final CopyRecordMapper copyMapper;
    private final BranchTraceMapper branchMapper;
    private final ObjectMapper objectMapper;

    public NodeActionAuditServiceImpl(CopyRecordMapper copyMapper, BranchTraceMapper branchMapper,
                                      ObjectMapper objectMapper) {
        this.copyMapper = copyMapper;
        this.branchMapper = branchMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void recordCopy(String processInstanceId, String nodeKey, String taskId,
                           String recipientId, String status, String reason, Long tenantId) {
        CopyRecord row = new CopyRecord();
        row.setProcessInstanceId(processInstanceId);
        row.setNodeKey(nodeKey);
        row.setTaskId(taskId);
        row.setRecipientId(recipientId);
        row.setDeliveryStatus(status);
        row.setFailureReason(reason);
        row.setTenantId(tenantId);
        copyMapper.insert(row);
    }

    @Override
    @Transactional
    public void recordBranch(String processInstanceId, String nodeKey, String branchId,
                             String conditionVersion, Map<String, Object> inputSummary, Long tenantId) {
        BranchTrace row = new BranchTrace();
        row.setProcessInstanceId(processInstanceId);
        row.setNodeKey(nodeKey);
        row.setBranchId(branchId);
        row.setConditionVersion(conditionVersion);
        try {
            row.setInputSummary(objectMapper.writeValueAsString(inputSummary));
        } catch (JsonProcessingException e) {
            row.setInputSummary("{}");
        }
        row.setTenantId(tenantId);
        branchMapper.insert(row);
    }
}
