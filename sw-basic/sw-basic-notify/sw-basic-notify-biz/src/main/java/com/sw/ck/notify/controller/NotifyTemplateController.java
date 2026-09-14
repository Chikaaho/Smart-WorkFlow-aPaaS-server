package com.sw.ck.notify.controller;

import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.response.R;
import com.sw.ck.notify.api.NotifyBizType;
import com.sw.ck.notify.dto.NotifyTemplateDTO;
import com.sw.ck.notify.dto.NotifyTemplateQuery;
import com.sw.ck.notify.dto.TemplatePreviewRequest;
import com.sw.ck.notify.dto.TemplatePreviewResult;
import com.sw.ck.notify.dto.TemplateSendRequest;
import com.sw.ck.notify.entity.NotifyMessage;
import com.sw.ck.notify.entity.NotifyTemplate;
import com.sw.ck.notify.render.TemplateRenderException;
import com.sw.ck.notify.render.TemplateRenderService;
import com.sw.ck.notify.service.NotifyMessageService;
import com.sw.ck.notify.service.NotifyTemplateService;
import com.sw.ck.notify.service.impl.NotifyTemplateServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 消息模板管理控制器（P36 / M05-F02-01）。
 *
 * <h3>权限边界（方向 §3.4）</h3>
 * 管理接口全部要求 {@code notify:template:view}（读）/
 * {@code notify:template:manage}（写）；普通收件箱用户仅有
 * {@code notify:view}，不能进入本控制器——与 V38 菜单种子一一闭合。
 *
 * <h3>租户</h3>
 * 租户条件由 {@code TenantLineHandler} 在 SQL 层自动注入；
 * 模板代码唯一性限定在同租户内。
 */
@RestController
@RequestMapping("/notify/templates")
public class NotifyTemplateController {

    private static final Logger log = LoggerFactory.getLogger(NotifyTemplateController.class);

    private final NotifyTemplateService templateService;

    private final NotifyTemplateServiceImpl templateServiceImpl;

    private final TemplateRenderService renderService;

    private final NotifyMessageService messageService;

    public NotifyTemplateController(NotifyTemplateService templateService,
                                    NotifyTemplateServiceImpl templateServiceImpl,
                                    TemplateRenderService renderService,
                                    NotifyMessageService messageService) {
        this.templateService = templateService;
        this.templateServiceImpl = templateServiceImpl;
        this.renderService = renderService;
        this.messageService = messageService;
    }

    // ==================== 管理端 ====================

    /** 分页列表 */
    @GetMapping
    @PreAuthorize("@ss.hasPermi('notify:template:view')")
    public R<PageResult<NotifyTemplateDTO>> page(NotifyTemplateQuery query) {
        return R.ok(templateService.pageTemplates(query));
    }

    /** 详情 */
    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('notify:template:view')")
    public R<NotifyTemplateDTO> get(@PathVariable Long id) {
        return R.ok(templateService.getTemplate(id));
    }

    /** 新建 */
    @PostMapping
    @PreAuthorize("@ss.hasPermi('notify:template:manage')")
    public R<Long> create(@RequestBody NotifyTemplateDTO dto) {
        return R.ok(templateService.createTemplate(dto));
    }

    /** 编辑（模板代码不可变更） */
    @PutMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('notify:template:manage')")
    public R<Void> update(@PathVariable Long id, @RequestBody NotifyTemplateDTO dto) {
        templateService.updateTemplate(id, dto);
        return R.ok();
    }

    /** 删除（逻辑删除，幂等；历史通知不受影响） */
    @DeleteMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('notify:template:manage')")
    public R<Void> delete(@PathVariable Long id) {
        templateService.deleteTemplate(id);
        return R.ok();
    }

    /** 启停切换 */
    @PutMapping("/{id}/toggle")
    @PreAuthorize("@ss.hasPermi('notify:template:manage')")
    public R<Void> toggle(@PathVariable Long id, @RequestParam boolean enabled) {
        templateService.toggleTemplate(id, enabled);
        return R.ok();
    }

    /**
     * 预览：按提交的模板内容 + 变量渲染，结果与真实发送同源同语义。
     * <p>缺失变量/非法占位符 → PARAM_ERROR 并指出缺失项（落库前拒绝语义在预览侧同样成立：
     * 不返回半成品渲染结果）。</p>
     */
    @PostMapping("/preview")
    @PreAuthorize("@ss.hasPermi('notify:template:view')")
    public R<TemplatePreviewResult> preview(@RequestBody TemplatePreviewRequest request) {
        return R.ok(templateService.renderPreview(request));
    }

    /**
     * 按模板代码预览：先 requireEnabledByCode 可用性检查，再渲染（方向 §8 标准 2）。
     * <p>停用/删除/不存在的模板 → NOT_FOUND「模板不存在或未启用」，与发送链路
     * 同源同语义；缺变量/非法占位符 → PARAM_ERROR。纯内容预览场景请使用
     * {@link #preview}（编辑页草稿渲染，不做可用性检查）。</p>
     */
    @PostMapping("/{code}/preview")
    @PreAuthorize("@ss.hasPermi('notify:template:view')")
    public R<TemplatePreviewResult> previewByCode(@PathVariable String code,
                                                  @RequestBody(required = false) TemplatePreviewRequest request) {
        Map<String, String> variables =
                request == null ? null : request.getVariables();
        return R.ok(templateService.previewByCode(code, variables));
    }

    /** 变量提取：按模板内容返回引用的变量名集（前端动态生成变量输入框） */
    @PostMapping("/variables")
    @PreAuthorize("@ss.hasPermi('notify:template:view')")
    public R<java.util.Set<String>> variables(@RequestBody TemplatePreviewRequest request) {
        if (request == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "请求体不能为空");
        }
        return R.ok(templateService.extractVariables(
                request.getTitleTemplate(), request.getContentTemplate()));
    }

    // ==================== 按模板发送 ====================

    /**
     * 按模板发送站内通知。
     *
     * <p>失败原子性（方向 §3.3）：模板停用/删除/不存在、变量缺失、接收人缺失
     * 一律在落库前拒绝——先渲染成功才构造消息落库，不存在半成品通知。</p>
     *
     * @return 通知 ID
     */
    @PostMapping("/send")
    @PreAuthorize("@ss.hasPermi('notify:template:manage')")
    public R<Long> send(@RequestBody TemplateSendRequest request) {
        if (request.getRecipientId() == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "接收人不能为空");
        }
        // 1. 取启用模板（停用/删除/不存在 → NOT_FOUND）
        NotifyTemplate t = templateServiceImpl.requireEnabledByCode(request.getTemplateCode());
        // 2. 渲染（缺变量 → PARAM_ERROR），失败发生在任何落库之前
        String title;
        String content;
        try {
            title = renderService.renderWithContract(t.getTitleTemplate(), request.getVariables(), t.getVariablesAllowed());
            content = renderService.renderWithContract(t.getContentTemplate(), request.getVariables(), t.getVariablesAllowed());
        } catch (TemplateRenderException e) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), e.getMessage());
        }
        // 3. 模板直发稳定身份幂等（方向 §3.3）：同一 (租户+事件+对象+接收人+渠道)
        // 的重复直发回照既有通知，不产生第二条消息/唯一索引冲突
        NotifyMessage existing = messageService.lambdaQuery()
                .eq(NotifyMessage::getRecipientId, request.getRecipientId())
                .eq(NotifyMessage::getBizType, NotifyBizType.SYSTEM.name())
                .eq(NotifyMessage::getBizId, t.getTemplateCode())
                .eq(NotifyMessage::getDeleted, 0)
                .last("LIMIT 1")
                .one();
        NotifyMessage msg = new NotifyMessage();
        msg.setRecipientId(request.getRecipientId());
        msg.setTitle(title);
        msg.setContent(content);
        msg.setBizType(NotifyBizType.SYSTEM.name());
        msg.setBizId(t.getTemplateCode());
        msg.setRead(false);
        if (existing != null) {
            log.info("模板直发幂等回照: code={}, recipient={}, msgId={}",
                    t.getTemplateCode(), request.getRecipientId(), existing.getId());
            return R.ok(existing.getId());
        }
        messageService.save(msg);
        log.info("模板通知已发送: code={}, recipient={}, msgId={}",
                t.getTemplateCode(), request.getRecipientId(), msg.getId());
        return R.ok(msg.getId());
    }
}
