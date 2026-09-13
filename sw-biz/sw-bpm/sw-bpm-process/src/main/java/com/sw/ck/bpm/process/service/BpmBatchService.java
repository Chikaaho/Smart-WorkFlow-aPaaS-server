package com.sw.ck.bpm.process.service;

import com.sw.ck.bpm.process.dto.ApprovalAction;
import com.sw.ck.common.response.R;

import java.util.List;
import java.util.Map;

/**
 * 批量审批（I4 §3.5）。
 */
public interface BpmBatchService {

    /**
     * 对当前用户有权办理的任务逐项执行同一动作。
     * <p>
     * 服务端逐项校验归属/状态/意见要求/业务权限（复用单任务动作链，含强制意见表单），
     * 返回逐项成功/失败与稳定任务标识；单项失败不影响其他项的真实结果。
     * </p>
     */
    List<Map<String, Object>> batchAction(List<BatchItem> items);

    /** 单项：任务 ID + 动作 + 意见（复用单任务请求形状）。 */
    record BatchItem(String taskId, ApprovalAction action, String comment,
                     String opinionFormId, String opinionFormVersion,
                     Map<String, Object> opinionData, String returnTargetNodeId) {
    }
}
