package com.sw.ck.notify.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.notify.dto.NotifyTemplateDTO;
import com.sw.ck.notify.dto.NotifyTemplateQuery;
import com.sw.ck.notify.dto.TemplatePreviewRequest;
import com.sw.ck.notify.dto.TemplatePreviewResult;
import com.sw.ck.notify.entity.NotifyTemplate;
import com.sw.ck.notify.mapper.NotifyTemplateMapper;
import com.sw.ck.notify.render.TemplateRenderException;
import com.sw.ck.notify.render.TemplateRenderService;
import com.sw.ck.notify.service.NotifyTemplateService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 消息模板管理服务实现（P36 / M05-F02-01）。
 *
 * <p>校验沿用 AgentToolConfig 惯例：Service 层手动校验（模块无 jakarta.validation）。
 * 租户唯一性由「应用层显式查重 + V38 复合唯一索引 (tenant_id, template_code, deleted)」
 * 双保险；租户条件由 TenantLineHandler 注入，应用层查重天然限定在本租户内。</p>
 */
@Service
public class NotifyTemplateServiceImpl implements NotifyTemplateService {

    private final NotifyTemplateMapper templateMapper;

    private final TemplateRenderService renderService;

    private final com.sw.ck.notify.service.NotifyTemplateVersionService versionService;

    public NotifyTemplateServiceImpl(NotifyTemplateMapper templateMapper,
                                     TemplateRenderService renderService) {
        this(templateMapper, renderService, null);
    }

@org.springframework.beans.factory.annotation.Autowired
    public NotifyTemplateServiceImpl(NotifyTemplateMapper templateMapper,
                                     TemplateRenderService renderService,
                                     com.sw.ck.notify.service.NotifyTemplateVersionService versionService) {
        this.templateMapper = templateMapper;
        this.renderService = renderService;
        this.versionService = versionService;
    }

    @Override
    public PageResult<NotifyTemplateDTO> pageTemplates(NotifyTemplateQuery query) {
        LambdaQueryWrapper<NotifyTemplate> wrapper = Wrappers.lambdaQuery();
        if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
            String kw = query.getKeyword();
            wrapper.and(w -> w.like(NotifyTemplate::getTemplateCode, kw)
                    .or().like(NotifyTemplate::getName, kw));
        }
        // enabled 用数字字面量（SMALLINT 列，Boolean 比较在 H2/PG 下不稳定的既有实证）
        wrapper.eq(query.getEnabled() != null, NotifyTemplate::getEnabled,
                Boolean.TRUE.equals(query.getEnabled()) ? 1 : 0);
        wrapper.orderByDesc(NotifyTemplate::getId);
        Page<NotifyTemplate> page = templateMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        return PageResult.of(page.convert(this::toDTO));
    }

    @Override
    public NotifyTemplateDTO getTemplate(Long id) {
        return toDTO(requireEntity(id));
    }

    @Override
    public Long createTemplate(NotifyTemplateDTO dto) {
        validate(dto);
        requireCodeAvailable(dto.getTemplateCode());
        NotifyTemplate entity = toEntity(dto);
        templateMapper.insert(entity);
        // I6：发布即追加不可改写版本快照
        if (versionService != null) {
            versionService.release(entity);
        }
        return entity.getId();
    }

    @Override
    public void updateTemplate(Long id, NotifyTemplateDTO dto) {
        NotifyTemplate existing = requireEntity(id);
        validate(dto);
        // 稳定标识不可变更（方向 §3.1）：请求中的 templateCode 必须与库中一致
        if (!existing.getTemplateCode().equals(dto.getTemplateCode())) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(),
                    "模板代码不可变更: " + existing.getTemplateCode());
        }
        NotifyTemplate entity = toEntity(dto);
        entity.setId(id);
        templateMapper.updateById(entity);
        // I6：编辑生效即追加新版本；历史投递固定引用旧版本快照
        NotifyTemplate updated = requireEntity(id);
        if (versionService != null) {
            versionService.release(updated);
        }
    }

    @Override
    public void deleteTemplate(Long id) {
        // 逻辑删除（@TableLogic），幂等；历史通知保存渲染结果，不受影响
        templateMapper.deleteById(id);
    }

    @Override
    public void toggleTemplate(Long id, boolean enabled) {
        NotifyTemplate entity = requireEntity(id);
        entity.setEnabled(enabled);
        templateMapper.updateById(entity);
    }

    @Override
    public TemplatePreviewResult renderPreview(TemplatePreviewRequest request) {
        if (request == null
                || request.getTitleTemplate() == null || request.getTitleTemplate().isBlank()
                || request.getContentTemplate() == null || request.getContentTemplate().isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "标题/正文模板不能为空");
        }
        try {
            String title = renderService.render(request.getTitleTemplate(), request.getVariables());
            String content = renderService.render(request.getContentTemplate(), request.getVariables());
            return new TemplatePreviewResult(title, content);
        } catch (TemplateRenderException e) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), e.getMessage());
        }
    }

    @Override
    public TemplatePreviewResult previewByCode(String templateCode, Map<String, String> variables) {
        // 1. 可用性检查（停用/删除/不存在 → NOT_FOUND，与发送链路同源）
        NotifyTemplate t = requireEnabledByCode(templateCode);
        // 2. 渲染（与 renderPreview 共用同一实现，杜绝双规则漂移）
        try {
            String title = renderService.renderWithContract(t.getTitleTemplate(), variables, t.getVariablesAllowed());
            String content = renderService.renderWithContract(t.getContentTemplate(), variables, t.getVariablesAllowed());
            return new TemplatePreviewResult(title, content);
        } catch (TemplateRenderException e) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), e.getMessage());
        }
    }

    @Override
    public Set<String> extractVariables(String titleTemplate, String contentTemplate) {
        Set<String> names = new LinkedHashSet<>();
        try {
            names.addAll(renderService.extractVariables(titleTemplate));
            names.addAll(renderService.extractVariables(contentTemplate));
        } catch (TemplateRenderException e) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), e.getMessage());
        }
        return names;
    }

    @Override
    public NotifyTemplate getEnabledByCode(String code) {
        return templateMapper.selectOne(Wrappers.<NotifyTemplate>lambdaQuery()
                .eq(NotifyTemplate::getTemplateCode, code)
                .eq(NotifyTemplate::getEnabled, 1));
    }

    // ==================== 内部 ====================

    /** 供发送链路复用：按代码取启用模板，停用/删除/不存在一律 NOT_FOUND 语义拒绝 */
    public NotifyTemplate requireEnabledByCode(String templateCode) {
        if (templateCode == null || templateCode.isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "模板代码不能为空");
        }
        NotifyTemplate t = templateMapper.selectOne(Wrappers.<NotifyTemplate>lambdaQuery()
                .eq(NotifyTemplate::getTemplateCode, templateCode));
        if (t == null || !Boolean.TRUE.equals(t.getEnabled())) {
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(),
                    "模板不存在或未启用: " + templateCode);
        }
        return t;
    }

    private NotifyTemplate requireEntity(Long id) {
        NotifyTemplate t = templateMapper.selectById(id);
        if (t == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "消息模板不存在");
        }
        return t;
    }

    private void requireCodeAvailable(String templateCode) {
        Long count = templateMapper.selectCount(Wrappers.<NotifyTemplate>lambdaQuery()
                .eq(NotifyTemplate::getTemplateCode, templateCode));
        if (count != null && count > 0) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(),
                    "模板代码已存在: " + templateCode);
        }
    }

    private void validate(NotifyTemplateDTO dto) {
        if (dto.getTemplateCode() == null || dto.getTemplateCode().isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "模板代码不能为空");
        }
        if (!dto.getTemplateCode().matches("[A-Za-z][A-Za-z0-9_]{1,98}")) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(),
                    "模板代码须为字母开头、仅字母/数字/下划线、长度2-99");
        }
        if (dto.getName() == null || dto.getName().isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "模板名称不能为空");
        }
        if (dto.getTitleTemplate() == null || dto.getTitleTemplate().isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "标题模板不能为空");
        }
        if (dto.getTitleTemplate().length() > 200) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "标题模板超长（>200）");
        }
        if (dto.getContentTemplate() == null || dto.getContentTemplate().isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "正文模板不能为空");
        }
        // 模板内容本身必须可渲染（占位符合法），否则入库即坏数据
        try {
            renderService.extractVariables(dto.getTitleTemplate());
            renderService.extractVariables(dto.getContentTemplate());
            // I6 §3.4：变量类型契约（name 或 name:TYPE）入库前必须可判定
            renderService.parseContract(dto.getVariablesAllowed());
        } catch (TemplateRenderException e) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), e.getMessage());
        }
    }

    private NotifyTemplateDTO toDTO(NotifyTemplate t) {
        NotifyTemplateDTO dto = new NotifyTemplateDTO();
        dto.setId(t.getId());
        dto.setTemplateCode(t.getTemplateCode());
        dto.setName(t.getName());
        dto.setTitleTemplate(t.getTitleTemplate());
        dto.setContentTemplate(t.getContentTemplate());
        dto.setEnabled(Boolean.TRUE.equals(t.getEnabled()));
        dto.setRemark(t.getRemark());
        dto.setEventType(t.getEventType());
        dto.setChannel(t.getChannel());
        dto.setVariablesAllowed(t.getVariablesAllowed());
        dto.setJumpRef(t.getJumpRef());
        return dto;
    }

    private NotifyTemplate toEntity(NotifyTemplateDTO dto) {
        NotifyTemplate t = new NotifyTemplate();
        t.setTemplateCode(dto.getTemplateCode());
        t.setName(dto.getName());
        t.setTitleTemplate(dto.getTitleTemplate());
        t.setContentTemplate(dto.getContentTemplate());
        // 新建未显式声明 enabled 视为启用（发布语义）；显式 false 才停用
        t.setEnabled(dto.getEnabled() == null || Boolean.TRUE.equals(dto.getEnabled()));
        t.setRemark(dto.getRemark());
        t.setEventType(dto.getEventType());
        t.setChannel(dto.getChannel());
        t.setVariablesAllowed(dto.getVariablesAllowed());
        t.setJumpRef(dto.getJumpRef());
        return t;
    }
}
