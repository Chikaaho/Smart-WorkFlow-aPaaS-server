package com.sw.ck.bpm.process.service;

import com.sw.ck.bpm.process.entity.ApprovalActionRecord;
import com.sw.ck.common.service.BaseService;

import java.util.List;

public interface ApprovalActionService extends BaseService<ApprovalActionRecord> {
    List<ApprovalActionRecord> findByProcessInstanceId(String processInstanceId);
    /** 查询任务已落库的动作，用于重复提交返回确定性业务错误。 */
    ApprovalActionRecord findByTaskId(String taskId);
    boolean existsForTask(String taskId);
    /** 按办理人分页查询本人动作记录（创建时间倒序），用于"我的已办"权威锚点。 */
    java.util.List<ApprovalActionRecord> pageByActor(long actorId, int offset, int limit);
    long countByActor(long actorId);
}
