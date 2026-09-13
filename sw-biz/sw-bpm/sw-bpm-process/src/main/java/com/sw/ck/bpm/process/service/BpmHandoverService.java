package com.sw.ck.bpm.process.service;

import com.sw.ck.bpm.process.entity.BpmHandover;
import com.sw.ck.bpm.process.entity.BpmHandoverItem;

import java.util.List;

/**
 * 流程交接（I4 §3.6）：离岗/调岗时迁移选定范围内尚未完成的可办理任务；
 * 代理规则仅显式勾选且通过校验时随迁；抄送/已办/历史意见/已过期规则不迁移。
 */
public interface BpmHandoverService {

    BpmHandover handover(Long fromUserId, Long toUserId, List<String> scopeDefKeys,
                         boolean includeProxyRules);

    List<BpmHandoverItem> items(Long handoverId);
}
