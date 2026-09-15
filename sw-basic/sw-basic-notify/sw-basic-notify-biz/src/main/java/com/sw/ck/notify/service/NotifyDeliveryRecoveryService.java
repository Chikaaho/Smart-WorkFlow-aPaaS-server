package com.sw.ck.notify.service;

/** 投递恢复与重试服务（I6）：服务重启或瞬时故障后从持久状态恢复未完成投递。 */
public interface NotifyDeliveryRecoveryService {

    /** 扫描到期未完成投递并按退避重试；返回本轮恢复条数。 */
    int recoverDue();
}
