package com.sw.ck.bpm.process.service.impl;

import org.springframework.beans.factory.annotation.Autowired;

import com.sw.ck.bpm.process.dto.ApprovalActionRequest;
import com.sw.ck.bpm.process.vo.BatchActionResultVO;
import com.sw.ck.bpm.process.service.BpmBatchService;
import com.sw.ck.bpm.process.service.TaskActionService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.i18n.LocalizedMessages;
import com.sw.ck.common.response.R;
import com.sw.ck.common.trace.EventRef;
import com.sw.ck.common.exception.CommonErrorCode;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import lombok.extern.log4j.Log4j2;
/**
 * 批量审批实现（I4 §3.5）。
 * <p>
 * 逐项复用单任务动作链（executeTaskAction）：归属/状态/版本/意见要求/业务权限
 * 全部走同一校验，不得绕过强制意见表单或"不允许批处理"节点。
 * 单项失败捕获错误码后继续其他项；整批响应不掩盖部分执行结果。
 * </p>
 */
@Service
@Log4j2
public class BpmBatchServiceImpl implements BpmBatchService {

    @Autowired
    private TaskActionService taskActionService;

    @Override
    public List<BatchActionResultVO> batchAction(List<BatchItem> items) {
        if (items == null || items.isEmpty()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(),
                    "批量审批至少包含一个任务项");
        }
        List<BatchActionResultVO> results = new ArrayList<>();
        int success = 0;
        int failed = 0;
        for (BatchItem item : items) {
            BatchActionResultVO row = new BatchActionResultVO();
            row.setTaskId(item.taskId());
            row.setAction(item.action() == null ? null : item.action().name());
            try {
                if (item.taskId() == null || item.taskId().isBlank()) {
                    throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(),
                            "任务标识不能为空");
                }
                ApprovalActionRequest request = new ApprovalActionRequest();
                request.setTaskId(item.taskId());
                request.setAction(item.action());
                request.setComment(item.comment());
                request.setOpinionFormId(item.opinionFormId());
                request.setOpinionFormVersion(item.opinionFormVersion());
                if (item.opinionData() != null) {
                    request.setOpinionData(item.opinionData());
                }
                request.setReturnTargetNodeId(item.returnTargetNodeId());
                R<Void> response = taskActionService.execute(item.taskId(), request);
                row.setSuccess(response != null && response.getCode() == 0);
                if (response != null && response.getCode() != 0) {
                    row.setErrorCode(response.getCode());
                    row.setMessage(response.getMsg());
                    failed++;
                } else {
                    success++;
                }
            } catch (BaseException e) {
                row.setSuccess(false);
                row.setErrorCode(e.getCode());
                // 与单任务路径同一文案权威：目录+参数解析，en 请求也能得到目录文案
                row.setMessage(LocalizedMessages.textArgs(e.getErrorKey(), e.getMessage(), e.getMessageArgs()));
                failed++;
            } catch (RuntimeException e) {
                // 未预期异常只进授权诊断日志；逐项明细对用户只给安全分类文案，不透原始异常
                log.warn("批量审批单项未预期异常: taskId={} eventRef={}", item.taskId(), EventRef.current(), e);
                row.setSuccess(false);
                row.setErrorCode(-1);
                row.setMessage(LocalizedMessages.text("common.system_error", "系统繁忙，请稍后重试或联系管理员"));
                failed++;
            }
            results.add(row);
        }
        log.info("批量审批完成: total={}, success={}, failed={}", items.size(), success, failed);
        return results;
    }
}
