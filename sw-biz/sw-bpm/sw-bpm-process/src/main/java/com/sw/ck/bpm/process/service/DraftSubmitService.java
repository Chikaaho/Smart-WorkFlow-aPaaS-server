package com.sw.ck.bpm.process.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.process.dto.CommandAcceptRespDTO;
import com.sw.ck.bpm.process.entity.BpmDraft;
import com.sw.ck.bpm.process.entity.CommandChannelEnum;
import com.sw.ck.bpm.process.entity.CommandTypeEnum;
import com.sw.ck.bpm.process.entity.DraftStatusEnum;
import com.sw.ck.bpm.process.queue.BpmCommandQueue;
import com.sw.ck.bpm.process.queue.CommandEnvelope;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.api.facade.FormDataSubmitFacade;
import com.sw.ck.form.api.form.FormDefinitionService;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 草稿正式提交服务（D3 校验 + 冻结 + 统一命令受理）。
 * <p>
 * 事务边界（G5）：本方法整体为一个事务，受理行随本事务提交；
 * P0 有界等待发生在事务之外（控制器层），等待期间受理对消费者可见。
 * </p>
 */
@Service
public class DraftSubmitService {

    private static final Logger log = LoggerFactory.getLogger(DraftSubmitService.class);

    private final BpmDraftService draftService;
    private final BpmFormBindingService bindingService;
    private final FormDefinitionService formDefinitionService;
    private final BpmCommandQueue commandQueue;
    private final ObjectMapper objectMapper;
    private final FormDataSubmitFacade formDataSubmitFacade;

    public DraftSubmitService(BpmDraftService draftService,
                              BpmFormBindingService bindingService,
                              FormDefinitionService formDefinitionService,
                              BpmCommandQueue commandQueue,
                              ObjectMapper objectMapper,
                              FormDataSubmitFacade formDataSubmitFacade) {
        this.draftService = draftService;
        this.bindingService = bindingService;
        this.formDefinitionService = formDefinitionService;
        this.commandQueue = commandQueue;
        this.objectMapper = objectMapper;
        this.formDataSubmitFacade = formDataSubmitFacade;
    }

    /**
     * 正式提交草稿（D3 校验 + 冻结快照 + 受理，同事务提交）。
     * 受理中/已提交重复调用返回既有受理（duplicated=true）。
     */
    @Transactional
    public CommandAcceptRespDTO submit(Long id) {
        return submit(id, CommandChannelEnum.NORMAL);
    }

    /**
     * 正式提交草稿并指定受理通道。通道仅由受信任的控制器传入，业务校验和幂等规则不变。
     */
    @Transactional
    public CommandAcceptRespDTO submit(Long id, CommandChannelEnum channel) {
        LoginUser loginUser = LoginUserHolder.get();
        requireConsumableTenant(loginUser);
        BpmDraft draft = loadOwned(id);
        if (DraftStatusEnum.SUBMITTING.getCode().equals(draft.getStatus())
                || DraftStatusEnum.SUBMITTED.getCode().equals(draft.getStatus())) {
            // 幂等：受理中/已提交不重复受理
            CommandEnvelope existing = draft.getCommandId() == null ? null
                    : commandQueue.findById(draft.getCommandId()).orElse(null);
            if (existing != null) {
                CommandAcceptRespDTO resp = new CommandAcceptRespDTO();
                resp.setCommandId(existing.getCommandId());
                resp.setCommandKey(existing.getCommandKey());
                resp.setCommandType(existing.getCommandType().getCode());
                resp.setChannel(existing.getChannel().getCode());
                resp.setDuplicated(true);
                return resp;
            }
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "草稿正在提交中");
        }
        requireEditable(draft);

        // D3 提交校验：表单仍发布 + 版本仍可用
        FormDefDTO formDef = requirePublishedForm(draft.getFormKey());
        if (draft.getFormVersion() != null && formDef.getFormVersion() != null
                && !draft.getFormVersion().equals(Long.valueOf(formDef.getFormVersion()))) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(),
                    "表单已发布新版本 v" + formDef.getFormVersion()
                            + "，草稿绑定 v" + draft.getFormVersion()
                            + "；请核对内容后刷新版本再提交");
        }
        // D3：流程不由填报人选择。提交时按当前租户内的唯一有效绑定解析，
        // 并把解析结果写入草稿/命令快照，消费时再校验该绑定仍有效。
        String processDefKey = resolveUniqueActiveProcessDefKey(draft.getFormKey());
        // 受理前已知失效拒绝（审查04）：草稿快照绑定与当前唯一有效绑定不一致时，
        // 属于管理员已更新绑定且用户尚未确认更新——不静默改绑、不受理已知无效命令；
        // 用户在填报页勾选"重绑最新版本"保存后（refreshFormVersion）方可提交。
        if (draft.getProcessDefKey() != null && !draft.getProcessDefKey().isBlank()
                && !draft.getProcessDefKey().equals(processDefKey)) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(),
                    BpmErrorCode.DRAFT_BINDING_CHANGED,
                    "表单关联的审批流程已由管理员更新，请在填报页确认更新后提交");
        }
        draft.setProcessDefKey(processDefKey);

        // D3：受理前执行字段级校验（与消费落库同一实现）。失败定位到字段、
        // 保留草稿内容，不受理审批命令、不启动流程。
        Map<String, Object> draftData = fromJson(draft.getPayload());
        // validateSubmission 当前契约恒 present（VALID）或抛业务异常：显式断言 present，契约违背不得静默通过
        formDataSubmitFacade.validateSubmission(draft.getFormKey(), draftData)
        .orElseThrow(() -> new IllegalStateException(
                "FormDataSubmitFacade#validateSubmission 契约恒 present，empty 属契约违约"));

        // 冻结快照 + 受理（同事务；commandKey 带提交序号防重复实例）
        int submitSeq = (draft.getSubmitSeq() == null ? 0 : draft.getSubmitSeq()) + 1;
        draft.setStatus(DraftStatusEnum.SUBMITTING.getCode());
        draft.setSubmitSeq(submitSeq);
        draftService.updateById(draft);

        CommandEnvelope envelope = new CommandEnvelope();
        envelope.setCommandType(CommandTypeEnum.DRAFT_SUBMIT);
        envelope.setChannel(channel == null ? CommandChannelEnum.NORMAL : channel);
        envelope.setCommandKey("DRAFT_SUBMIT:" + draft.getId() + ":" + submitSeq);
        envelope.setTenantId(loginUser.getTenantId());
        envelope.setInitiatorId(loginUser.getUserId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("draftId", String.valueOf(draft.getId()));
        payload.put("formKey", draft.getFormKey());
        payload.put("processDefKey", processDefKey);
        payload.put("submitSeq", submitSeq);
        payload.put("submittedData", fromJson(draft.getPayload()));
        envelope.setPayload(toJson(payload));
        try {
            commandQueue.enqueue(envelope);
        } catch (DuplicateKeyException e) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "该草稿已有提交在处理中");
        }
        draft.setCommandId(envelope.getCommandId());
        draftService.updateById(draft);

        CommandAcceptRespDTO resp = new CommandAcceptRespDTO();
        resp.setCommandId(envelope.getCommandId());
        resp.setCommandKey(envelope.getCommandKey());
        resp.setCommandType(envelope.getCommandType().getCode());
        resp.setChannel(envelope.getChannel().getCode());
        log.info("草稿提交已受理: draftId={}, commandId={}", draft.getId(), envelope.getCommandId());
        return resp;
    }

    /**
     * 受理前租户边界（I5 收口）：命令信封承载租户语义，消费侧按信封租户还原身份并
     * 一致性校验；任何有效租户的命令均可受理，不再以「仅超租户可消费」为由拒绝。
     * 缺失租户上下文仍 fail closed。
     */
    private void requireConsumableTenant(LoginUser loginUser) {
        if (loginUser == null || loginUser.getTenantId() == null) {
            throw new BaseException(CommonErrorCode.UNAUTHORIZED, "未登录");
        }
    }

    /** 草稿归属校验（详情/更新/删除/提交共用）。 */
    public BpmDraft loadOwned(Long id) {
        BpmDraft draft = draftService.getById(id);
        if (draft == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "草稿不存在");
        }
        LoginUser loginUser = LoginUserHolder.get();
        if (loginUser == null) {
            throw new BaseException(CommonErrorCode.UNAUTHORIZED, "未登录");
        }
        if (!isSameTenant(loginUser, draft) || !loginUser.getUserId().equals(draft.getCreateBy())) {
            log.warn("草稿越权拒绝: draftId={}, owner={}, currentUser={}",
                    id, draft.getCreateBy(), loginUser.getUserId());
            throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(), "无权访问该草稿");
        }
        // empty = formKey 空白缺少判定目标：与 present-false 同判拒绝（fail closed）
        if (!isAllowed(formDefinitionService.canCurrentUserPerformAction(draft.getFormKey(), "view"))) {
            log.warn("草稿视图权限拒绝: draftId={}, formKey={}, currentUser={}",
                    id, draft.getFormKey(), loginUser.getUserId());
            throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(), "无权访问该草稿");
        }
        return draft;
    }

    /** 草稿列表过滤使用：不抛出业务异常，不向撤权主体泄露草稿对象。 */
    public boolean canCurrentUserView(BpmDraft draft) {
        LoginUser loginUser = LoginUserHolder.get();
        return loginUser != null
                && draft != null
                && loginUser.getUserId() != null
                && loginUser.getUserId().equals(draft.getCreateBy())
                && isSameTenant(loginUser, draft)
                && isAllowed(formDefinitionService.canCurrentUserPerformAction(draft.getFormKey(), "view"));
    }

    /**
     * 判定类契约统一 fail closed：empty（缺少判定目标/上下文）与 present-false 同判拒绝。
     */
    private static boolean isAllowed(java.util.Optional<Boolean> decision) {
        return decision.isPresent() && decision.get();
    }

    private boolean isSameTenant(LoginUser loginUser, BpmDraft draft) {
        return loginUser.getTenantId() != null
                && draft.getTenantId() != null
                && loginUser.getTenantId().equals(draft.getTenantId());
    }

    /** 已发布表单校验（保存/提交共用）。 */
    public FormDefDTO requirePublishedForm(String formKey) {
        if (formKey == null || formKey.isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "formKey 不能为空");
        }
        // empty = 该 formKey 无表单定义（原 null 返回路径）：与“表单不存在”同判
        java.util.Optional<FormDefDTO> formDefLookup = formDefinitionService.getFormDef(formKey);
        if (formDefLookup.isEmpty()) {
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "表单 '" + formKey + "' 不存在");
        }
        FormDefDTO formDef = formDefLookup.get();
        if (!"PUBLISHED".equals(formDef.getStatus())) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(),
                    "表单 '" + formKey + "' 未发布，不能保存/提交草稿");
        }
        // empty = formKey 空白（此处已判非空，契约不产生）：与 present-false 同判拒绝
        if (!isAllowed(formDefinitionService.canCurrentUserInitiate(formKey))) {
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "表单不存在");
        }
        return formDef;
    }

    /**
     * 解析表单当前租户内的唯一有效流程绑定。
     * <p>多条有效绑定属于管理配置错误，必须显式失败，不能由列表顺序静默选一条。</p>
     */
    public String resolveUniqueActiveProcessDefKey(String formKey) {
        var bindings = bindingService.findActiveByFormKey(formKey);
        if (bindings.isEmpty()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(),
                    BpmErrorCode.DRAFT_NO_ACTIVE_BINDING,
                    "表单尚未关联唯一已发布流程，暂不能发起审批");
        }
        if (bindings.size() != 1 || bindings.get(0).getProcessDefKey() == null
                || bindings.get(0).getProcessDefKey().isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(),
                    BpmErrorCode.DRAFT_BINDING_AMBIGUOUS,
                    "表单关联流程无效或存在多个有效绑定，请联系管理员修复");
        }
        return bindings.get(0).getProcessDefKey();
    }

    private void requireEditable(BpmDraft draft) {
        if (!DraftStatusEnum.EDITING.getCode().equals(draft.getStatus())
                && !DraftStatusEnum.FAILED.getCode().equals(draft.getStatus())) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(),
                    BpmErrorCode.DRAFT_NOT_EDITABLE,
                    "草稿当前状态 " + draft.getStatus() + " 不可编辑");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化草稿数据失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("解析草稿数据失败", e);
        }
    }
}
