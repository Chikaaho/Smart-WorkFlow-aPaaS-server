package com.sw.ck.bpm.process.service.impl;

import org.springframework.beans.factory.annotation.Autowired;

import com.sw.ck.bpm.api.vo.BpmTaskVO;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.process.vo.CopyItemVO;
import com.sw.ck.bpm.process.vo.MyCopyDetailVO;
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
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import lombok.extern.log4j.Log4j2;
import com.sw.ck.common.value.DynamicData;
/** 抄送我的查询服务实现。 */
@Service
@Log4j2
public class BpmCopyQueryServiceImpl implements BpmCopyQueryService {

    @Autowired
    private CopyRecordMapper copyRecordMapper;
    @Autowired
    private BpmInstanceService bpmInstanceService;
    @Autowired
    private BpmTaskFacade bpmTaskFacade;
    @Autowired
    private ApprovalActionService approvalActionService;

    @Override
    public PageResult<CopyItemVO> myCopies(String processInstanceId, String keyword,
                                            LocalDateTime timeFrom, LocalDateTime timeTo,
                                            PageParam pageParam) {
        LoginUser loginUser = LoginUserHolder.get();
        String recipientId = String.valueOf(loginUser.getUserId());
        String normalizedProcessInstanceId = blankToNull(processInstanceId);
        String normalizedKeyword = blankToNull(keyword);
        long total = copyRecordMapper.countMyCopies(loginUser.getTenantId(), recipientId,
                normalizedProcessInstanceId,
                normalizedKeyword, timeFrom, timeTo);
        int offset = (int) ((pageParam.getPageNum() - 1) * pageParam.getPageSize());
        List<CopyItemVO> records = total == 0 ? List.of()
                : copyRecordMapper.selectMyCopies(loginUser.getTenantId(), recipientId,
                        normalizedProcessInstanceId, normalizedKeyword, timeFrom, timeTo,
                        (int) pageParam.getPageSize(), offset);
        PageResult<CopyItemVO> page = new PageResult<>();
        page.setRecords(records);
        page.setTotal(total);
        page.setPageNum(pageParam.getPageNum());
        page.setPageSize(pageParam.getPageSize());
        log.debug("抄送我的查询: userId={}, total={}", loginUser.getUserId(), total);
        return page;
    }

    @Override
    public MyCopyDetailVO myCopyDetail(Long copyId) {
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

        MyCopyDetailVO detail = new MyCopyDetailVO();
        detail.setCopy(copy);

        BpmInstance instance = bpmInstanceService.lambdaQuery()
                .eq(BpmInstance::getProcessInstanceId, copy.getProcessInstanceId())
                .last("LIMIT 1")
                .one();
        detail.setInstance(instance);

        if (instance != null) {
            // 只读表单快照（历史变量中的 formData，与审批意见初始化同源；实例已结束仍可读）
            // getHistoricVariables 当前契约恒 present（无历史为空 Map）
            Map<String, Object> variables = bpmTaskFacade
                    .getHistoricVariables(instance.getProcessInstanceId())
                    .orElseThrow(() -> new IllegalStateException(
                            "历史变量查询未返回结果: processInstanceId=" + instance.getProcessInstanceId()));
            detail.setFormData(DynamicData.of(variables.get("formData")));

            // 审批进度（活动任务）与流转记录（含审批意见），只读
            // empty = 实例标识缺失：无活动任务（对外响应字段保持原列表形状，不漂移为 Optional）
            List<BpmTaskVO> activeTasks = bpmTaskFacade
                    .queryByProcessInstance(instance.getProcessInstanceId())
                    .orElse(List.of());
            detail.setProgress(activeTasks);
            detail.setHistory(approvalActionService.findByProcessInstanceId(instance.getProcessInstanceId()));
        }
        return detail;
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
