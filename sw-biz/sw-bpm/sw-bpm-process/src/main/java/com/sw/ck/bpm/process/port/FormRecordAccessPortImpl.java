package com.sw.ck.bpm.process.port;

import org.springframework.beans.factory.annotation.Autowired;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import com.sw.ck.bpm.api.dto.BpmTaskDTO;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.ParticipantSnapshot;
import com.sw.ck.bpm.process.mapper.BpmInstanceMapper;
import com.sw.ck.bpm.process.mapper.ParticipantSnapshotMapper;
import com.sw.ck.form.api.port.FormRecordAccessPort;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

import lombok.extern.log4j.Log4j2;
/**
 * FormRecordAccessPort 实现：按流程参与关系判定表单记录可读性。
 * <p>
 * 命中条件（任一）：用户为实例发起人；用户在实例内持有运行期任务或被指派过历史任务；
 * 用户为节点参与人快照成员（候选/会签参与人）。仅对该记录引用的实例生效，
 * 不改变表单数据范围语义。
 * </p>
 */
@Service
@Log4j2
public class FormRecordAccessPortImpl implements FormRecordAccessPort {

    @Autowired
    private BpmInstanceMapper instanceMapper;
    @Autowired
    private ParticipantSnapshotMapper snapshotMapper;
    @Autowired
    private ObjectProvider<BpmTaskFacade> taskFacadeProvider;

    @Override
    public Optional<Boolean> isProcessParticipant(Long tenantId, Long userId,
                                                            String formKey, String recordId) {
        if (tenantId == null || userId == null || recordId == null || recordId.isBlank()) {
            // 缺少查询上下文：判定未执行（调用方 fail-closed）
            return Optional.empty();
        }
        List<BpmInstance> instances;
        try {
            instances = instanceMapper.selectList(Wrappers.<BpmInstance>lambdaQuery()
                    .eq(BpmInstance::getTenantId, tenantId)
                    .eq(BpmInstance::getBusinessKey, recordId)
                    .eq(formKey != null && !formKey.isBlank(), BpmInstance::getFormKey, formKey));
        } catch (RuntimeException e) {
            // 读取通道异常按不可访问处理（fail-closed），不把查询故障放大为记录可读
            log.warn("流程参与核验查询失败，按不可访问处理: recordId={}, error={}", recordId, e.getMessage());
            return Optional.of(false);
        }
        BpmTaskFacade taskFacade = taskFacadeProvider.getIfAvailable();
        for (BpmInstance instance : instances) {
            if (userId.equals(instance.getInitiatorId())) {
                return Optional.of(true);
            }
            if (taskFacade != null) {
                String instanceId = instance.getProcessInstanceId();
                if (holdsTask(taskFacade.queryByProcessInstance(instanceId).orElse(List.of()), userId)
                        || holdsTask(taskFacade.queryHistoryByProcessInstance(instanceId).orElse(List.of()),
                                userId)) {
                    return Optional.of(true);
                }
            }
            long snapshots = snapshotMapper.selectCount(Wrappers.<ParticipantSnapshot>lambdaQuery()
                    .eq(ParticipantSnapshot::getTenantId, tenantId)
                    .eq(ParticipantSnapshot::getProcessInstanceId, instance.getProcessInstanceId())
                    .eq(ParticipantSnapshot::getParticipantId, String.valueOf(userId)));
            if (snapshots > 0) {
                return Optional.of(true);
            }
        }
        return Optional.of(false);
    }

    private boolean holdsTask(List<BpmTaskDTO> tasks, Long userId) {
        String uid = String.valueOf(userId);
        for (BpmTaskDTO task : tasks) {
            if (uid.equals(task.getAssignee())) {
                return true;
            }
            if (task.getCandidateUserIds() != null && task.getCandidateUserIds().contains(uid)) {
                return true;
            }
        }
        return false;
    }
}
