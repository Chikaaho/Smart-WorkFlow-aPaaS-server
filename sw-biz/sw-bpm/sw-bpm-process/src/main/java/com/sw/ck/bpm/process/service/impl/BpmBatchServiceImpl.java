package com.sw.ck.bpm.process.service.impl;

import com.sw.ck.bpm.process.dto.ApprovalActionRequest;
import com.sw.ck.bpm.process.service.TaskActionService;
import com.sw.ck.bpm.process.service.BpmBatchService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.response.R;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批量审批实现（I4 §3.5）。
 * <p>
 * 逐项复用单任务动作链（executeTaskAction）：归属/状态/版本/意见要求/业务权限
 * 全部走同一校验，不得绕过强制意见表单或"不允许批处理"节点。
 * 单项失败捕获错误码后继续其他项；整批响应不掩盖部分执行结果。
 * </p>
 */
@Service
public class BpmBatchServiceImpl implements BpmBatchService {

    private static final Logger log = LoggerFactory.getLogger(BpmBatchServiceImpl.class);

    private final TaskActionService taskActionService;

    public BpmBatchServiceImpl(TaskActionService taskActionService) {
        this.taskActionService = taskActionService;
    }

    @Override
    public List<Map<String, Object>> batchAction(List<BatchItem> items) {
        if (items == null || items.isEmpty()) {
            throw new BaseException(com.sw.ck.common.exception.CommonErrorCode.PARAM_ERROR.getCode(),
                    "批量审批至少包含一个任务项");
        }
        List<Map<String, Object>> results = new ArrayList<>();
        int success = 0;
        int failed = 0;
        for (BatchItem item : items) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("taskId", item.taskId());
            row.put("action", item.action() == null ? null : item.action().name());
            try {
                if (item.taskId() == null || item.taskId().isBlank()) {
                    throw new BaseException(com.sw.ck.common.exception.CommonErrorCode.PARAM_ERROR.getCode(),
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
                row.put("success", response != null && response.getCode() == 0);
                if (response != null && response.getCode() != 0) {
                    row.put("errorCode", response.getCode());
                    row.put("message", response.getMsg());
                    failed++;
                } else {
                    success++;
                }
            } catch (BaseException e) {
                row.put("success", false);
                row.put("errorCode", e.getCode());
                row.put("message", e.getMessage());
                failed++;
            } catch (RuntimeException e) {
                row.put("success", false);
                row.put("errorCode", -1);
                row.put("message", e.getMessage());
                failed++;
            }
            results.add(row);
        }
        log.info("批量审批完成: total={}, success={}, failed={}", items.size(), success, failed);
        return results;
    }
}
