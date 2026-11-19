package com.sw.ck.notify.vo;

import lombok.Data;

/**
 * 站内信受保护深链目标：业务对象类型与稳定 ID。
 */
@Data
public class NotifyLinkVO {

    private String linkType;

    private String linkId;
}
