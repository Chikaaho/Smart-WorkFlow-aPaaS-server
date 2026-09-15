package com.sw.ck.notify.api;

import lombok.Value;

/**
 * 统一通知链选择的已发布模板快照。
 * <p>调用方只拿到发送所需的模板元数据，不依赖 notify-biz 实体。</p>
 */
@Value
public class NotifyTemplateSelection {
    Long templateId;
    Integer templateVersion;
    String title;
    String content;
}
