package com.sw.ck.bpm.process.service.impl;

import com.sw.ck.bpm.api.dto.BpmTaskDTO;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.process.dto.CopyItemDTO;
import com.sw.ck.bpm.process.entity.ApprovalActionRecord;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.CopyRecord;
import com.sw.ck.bpm.process.mapper.CopyRecordMapper;
import com.sw.ck.bpm.process.service.ApprovalActionService;
import com.sw.ck.bpm.process.service.BpmCopyQueryService;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 抄送我的查询服务实现。 */
@Service
public class BpmCopyQueryServiceImpl implements BpmCopyQueryService {

    private static final Logger log = LoggerFactory.getLogger(BpmCopyQueryServiceImpl.class);

    private final CopyRecordMapper copyRecordMapper;
    private final BpmInstanceService bpmInstanceService;
    private final BpmTaskFacade bpmTaskFacade;
    private final ApprovalActionService approvalActionService;

    public BpmCopyQueryServiceImpl(CopyRecordMapper copyRecordMapper,
                                   BpmInstanceService bpmInstanceService,
                                   BpmTaskFacade bpmTaskFacade,
                                   ApprovalActionService approvalActionService) {
        this.copyRecordMapper = copyRecordMapper;
        this.bpmInstanceService = bpmInstanceService;
        this.bpmTaskFacade = bpmTaskFacade;
        this.approvalActionService = approvalActionService;
    }

    @Override
    public PageResult<CopyItemDTO> myCopies(String keyword, LocalDateTime timeFrom, LocalDateTime timeTo,
                                            PageParam pageParam) {
        LoginUser loginUser = LoginUserHolder.get();
        String recipientId = String.valueOf(loginUser.getUserId());
        long total = copyRecordMapper.countMyCopies(loginUser.getTenantId(), recipientId,
                blankToNull(keyword), timeFrom, timeTo);
        int offset = (int) ((pageParam.getPageNum() - 1) * pageParam.getPageSize());
        List<CopyItemDTO> records = total == 0 ? List.of()
                : copyRecordMapper.selectMyCopies(loginUser.getTenantId(), recipientId,
                        blankToNull(keyword), timeFrom, timeTo, (int) pageParam.getPageSize(), offset);
        PageResult<CopyItemDTO> page = new PageResult<>();
        page.setRecords(records);
        page.setTotal(total);
        page.setPageNum(pageParam.getPageNum());
        page.setPageSize(pageParam.getPageSize());
        log.debug("抄送我的查询: userId={}, total={}", loginUser.getUserId(), total);
        return page;
    }

    @Override
    public Map<String, Object> myCopyDetail(Long copyId) {
        LoginUser loginUser = LoginUserHolder.get();
        CopyRecord copy = copyRecordMapper.selectById(copyId);
        if (copy == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "抄送记录不存在");
        }
        if (!String.valueOf(loginUser.getUserId()).equals(copy.getRecipientId())) {
            log.warn("抄送详情越权拒绝: copyId={}, recipient={}, currentUser={}",
                    copyId, copy.getRecipientId(), loginUser.getUserId());
            throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(), "仅接收人可查看该抄送");
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("copy", copy);

        BpmInstance instance = bpmInstanceService.lambdaQuery()
                .eq(BpmInstance::getProcessInstanceId, copy.getProcessInstanceId())
                .last("LIMIT 1")
                .one();
        detail.put("instance", instance);

        if (instance != null) {
            // 只读表单快照（历史变量中的 formData，与审批意见初始化同源；实例已结束仍可读）
            Map<String, Object> variables = bpmTaskFacade.getHistoricVariables(instance.getProcessInstanceId());
            Object formData = variables == null ? null : variables.get("formData");
            detail.put("formData", formData);

            // 审批进度（活动任务）与流转记录（含审批意见），只读
            List<BpmTaskDTO> activeTasks =
                    bpmTaskFacade.queryByProcessInstance(instance.getProcessInstanceId());
            detail.put("progress", activeTasks);
            detail.put("history",
                    approvalActionService.findByProcessInstanceId(instance.getProcessInstanceId()));
        }
        return detail;
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
