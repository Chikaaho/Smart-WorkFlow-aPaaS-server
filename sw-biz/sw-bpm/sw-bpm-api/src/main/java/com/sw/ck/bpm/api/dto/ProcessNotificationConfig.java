package com.sw.ck.bpm.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 流程结束通知配置（50:88，随 ProcessGraph 进入 graph_json 与发布快照）。
 *
 * <p>仅作用于合法 APPROVED 终态的结束通知；任务通知、审批责任和拒绝/撤回/废弃事件不受影响。
 * 旧定义缺省（null）保持原“通知发起人 + 路由渠道”行为。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProcessNotificationConfig implements Serializable {

    /** 通知流程发起人。 */
    private Boolean initiator;

    /** 通知本实例参与审批人（参与人快照中的去重用户）。 */
    private Boolean approvers;

    /** 显式选择的其他接收人（必须为本租户启用用户，服务端保存/发布时复核）。 */
    private List<Long> otherRecipientIds;

    /** 渠道令牌：IN_APP / EMAIL；实际投递仍由通知域租户启停与路由裁决。 */
    private List<String> channels;
}
