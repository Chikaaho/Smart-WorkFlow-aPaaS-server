package com.sw.ck.notify.service;

import com.sw.ck.common.page.PageResult;
import com.sw.ck.notify.vo.NotifyTemplateVO;
import com.sw.ck.notify.dto.NotifyTemplateQuery;
import com.sw.ck.notify.dto.TemplatePreviewRequest;
import com.sw.ck.notify.dto.TemplatePreviewResult;
import com.sw.ck.notify.entity.NotifyTemplate;

import java.util.Map;
import java.util.Set;

/**
 * 消息模板管理服务（P36 / M05-F02-01）。
 */
public interface NotifyTemplateService {

    /** 分页查询（keyword 匹配代码/名称，enabled 过滤） */
    PageResult<NotifyTemplateVO> pageTemplates(NotifyTemplateQuery query);

    /** 详情 */
    NotifyTemplateVO getTemplate(Long id);

    /** 新建：模板代码同租户唯一；返回新 id */
    Long createTemplate(NotifyTemplateVO dto);

    /** 编辑：templateCode 不可变更；其余字段整体更新 */
    void updateTemplate(Long id, NotifyTemplateVO dto);

    /** 删除（逻辑删除，幂等）；不影响已落库历史通知 */
    void deleteTemplate(Long id);

    /** 启停切换 */
    void toggleTemplate(Long id, boolean enabled);

    /**
     * 按模板内容预渲染（预览端点与发送服务共用同一实现，杜绝双规则漂移）。
     *
     * @throws com.sw.ck.common.exception.BaseException PARAM_ERROR — 非法占位符或缺失变量
     */
    TemplatePreviewResult renderPreview(TemplatePreviewRequest request);

    /**
     * 按已启用模板代码预览：先做可用性检查再渲染（方向 §8 标准 2 ——
     * 停用或删除的模板不能继续预览或发送）。
     *
     * <p>与 {@link #renderPreview(TemplatePreviewRequest)}（纯内容预览，
     * 服务编辑场景）不同，本方法按代码解析库中启用模板，停用/删除/不存在
     * 一律 NOT_FOUND 语义拒绝，与发送链路 {@code requireEnabledByCode} 同源。</p>
     *
     * @param templateCode 模板代码（同租户内唯一稳定标识）
     * @param variables    渲染变量值表（可为 null）
     * @throws com.sw.ck.common.exception.BaseException NOT_FOUND — 停用/删除/不存在；
     *                   PARAM_ERROR — 非法占位符或缺失变量
     */
    TemplatePreviewResult previewByCode(String templateCode, Map<String, String> variables);

    /** 提取模板引用的变量名集（前端动态生成变量输入用） */
    Set<String> extractVariables(String titleTemplate, String contentTemplate);

    /**
     * 按code查询已启用的模板。未找到或已停用返回null。
     */
    NotifyTemplate getEnabledByCode(String code);
}
