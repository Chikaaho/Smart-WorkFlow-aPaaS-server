package com.sw.ck.notify.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.notify.entity.NotifyTemplate;
import com.sw.ck.notify.entity.NotifyTemplateVersion;
import com.sw.ck.notify.mapper.NotifyTemplateVersionMapper;
import com.sw.ck.notify.service.NotifyTemplateVersionService;
import org.springframework.stereotype.Service;

import java.util.List;

/** 模板版本服务实现（I6）。 */
@Service
public class NotifyTemplateVersionServiceImpl implements NotifyTemplateVersionService {

    private final NotifyTemplateVersionMapper versionMapper;

    public NotifyTemplateVersionServiceImpl(NotifyTemplateVersionMapper versionMapper) {
        this.versionMapper = versionMapper;
    }

    @Override
    public NotifyTemplateVersion release(NotifyTemplate template) {
        if (template == null || template.getId() == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "模板不存在");
        }
        NotifyTemplateVersion latest = versionMapper.selectOne(
                Wrappers.<NotifyTemplateVersion>lambdaQuery()
                        .eq(NotifyTemplateVersion::getTemplateId, template.getId())
                        .orderByDesc(NotifyTemplateVersion::getTemplateVersion)
                        .last("LIMIT 1"));
        int nextVersion = latest == null || latest.getTemplateVersion() == null
                ? 1 : latest.getTemplateVersion() + 1;
        NotifyTemplateVersion version = new NotifyTemplateVersion();
        version.setTemplateId(template.getId());
        version.setTemplateVersion(nextVersion);
        version.setEventType(orDefault(template.getEventType(), "SYSTEM"));
        version.setChannel(orDefault(template.getChannel(), "IN_APP"));
        version.setTitleTemplate(template.getTitleTemplate());
        version.setContentTemplate(template.getContentTemplate());
        version.setVariablesAllowed(template.getVariablesAllowed());
        version.setJumpRef(template.getJumpRef());
        version.setStatus("RELEASED");
        versionMapper.insert(version);
        return version;
    }

    @Override
    public List<NotifyTemplateVersion> listVersions(Long templateId) {
        return versionMapper.selectList(Wrappers.<NotifyTemplateVersion>lambdaQuery()
                .eq(NotifyTemplateVersion::getTemplateId, templateId)
                .orderByDesc(NotifyTemplateVersion::getTemplateVersion));
    }

    @Override
    public NotifyTemplateVersion getSnapshot(Long templateId, Integer templateVersion) {
        if (templateId == null || templateVersion == null) {
            return null;
        }
        return versionMapper.selectOne(Wrappers.<NotifyTemplateVersion>lambdaQuery()
                .eq(NotifyTemplateVersion::getTemplateId, templateId)
                .eq(NotifyTemplateVersion::getTemplateVersion, templateVersion));
    }

    @Override
    public NotifyTemplateVersion latestSnapshot(Long templateId) {
        return versionMapper.selectOne(Wrappers.<NotifyTemplateVersion>lambdaQuery()
                .eq(NotifyTemplateVersion::getTemplateId, templateId)
                .orderByDesc(NotifyTemplateVersion::getTemplateVersion)
                .last("LIMIT 1"));
    }

    private String orDefault(String v, String d) {
        return v == null || v.isBlank() ? d : v;
    }
}
