package com.sw.ck.bpm.process.service.impl;

import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.participant.NodeActionAuditPort;
import com.sw.ck.bpm.api.result.MutationOutcome;
import com.sw.ck.bpm.process.entity.BranchTrace;
import com.sw.ck.bpm.process.entity.CopyRecord;
import com.sw.ck.bpm.process.mapper.BranchTraceMapper;
import com.sw.ck.bpm.process.mapper.CopyRecordMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

@Service
public class NodeActionAuditServiceImpl implements NodeActionAuditPort {
    @Autowired
    private CopyRecordMapper copyMapper;
    @Autowired
    private BranchTraceMapper branchMapper;
    @Autowired
    private ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Optional<MutationOutcome> recordCopy(String processInstanceId, String nodeKey, String taskId,
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
        return Optional.of(MutationOutcome.APPLIED);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Optional<MutationOutcome> recordBranch(String processInstanceId, String nodeKey, String branchId,
                                                  String conditionVersion, Map<String, Object> inputSummary,
                                                  Long tenantId) {
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
        return Optional.of(MutationOutcome.APPLIED);
    }
}
