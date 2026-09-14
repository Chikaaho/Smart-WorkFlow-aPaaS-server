package com.sw.ck.notify.service;

import com.sw.ck.notify.entity.NotifyTemplate;
import com.sw.ck.notify.entity.NotifyTemplateVersion;

import java.util.List;

/** 模板版本服务（I6）：发布追加不可改写版本，历史投递固定引用版本快照。 */
public interface NotifyTemplateVersionService {

    /** 发布/追加一个版本，返回带版本号的快照；同内容不同版本号（每次发布+1）。 */
    NotifyTemplateVersion release(NotifyTemplate template);

    List<NotifyTemplateVersion> listVersions(Long templateId);

    /** 取指定版本快照；不存在（跨租户/缺快照）返回 null。 */
    NotifyTemplateVersion getSnapshot(Long templateId, Integer templateVersion);

    /** 最新发布快照；模板从未发布时返回 null。 */
    NotifyTemplateVersion latestSnapshot(Long templateId);
}
