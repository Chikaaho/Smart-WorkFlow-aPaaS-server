package com.sw.ck.notify.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * 批量发送站内通知请求。
 * <p>
 * 直接内容（title+content）与模板（templateCode+variables）互斥；
 * 接收对象三选一或多选组合，服务端去重后投递。
 * </p>
 */
@Data
public class NotifyBatchSendReq {

    /** 直接指定的用户ID列表 */
    private List<Long> recipientUserIds;

    /** 部门ID列表（解析为该部门下的有效用户） */
    private List<Long> recipientDeptIds;

    /** 角色code列表（解析为拥有该角色的有效用户） */
    private List<String> recipientRoleCodes;

    /** 投递渠道（可选）：缺省 IN_APP 走既有批量入口；指定其他渠道时逐接收人投递并保留失败子记录。 */
    private String channel;

    /** 直接内容标题（与templateCode互斥） */
    private String title;

    /** 直接内容正文（与templateCode互斥） */
    private String content;

    /** 模板code（与title/content互斥） */
    private String templateCode;

    /** 模板变量（模板模式时必填） */
    private Map<String, String> variables;
}
