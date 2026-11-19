package com.sw.ck.notify.vo;

import com.sw.ck.notify.entity.NotifyMessage;
import com.sw.ck.notify.entity.NotifySendAttempt;
import lombok.Data;

import java.util.List;

/**
 * 通知发送记录详情：消息主体（按权限裁剪）与尝试流水。
 */
@Data
public class NotifyRecordDetailVO {

    private NotifyMessage message;

    private List<NotifySendAttempt> attempts;
}
