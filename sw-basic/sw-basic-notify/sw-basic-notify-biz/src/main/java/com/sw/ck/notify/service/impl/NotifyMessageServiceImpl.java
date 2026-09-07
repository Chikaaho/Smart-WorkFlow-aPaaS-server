package com.sw.ck.notify.service.impl;

import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.common.service.BaseServiceImpl;
import com.sw.ck.notify.dto.NotifyBatchSendReq;
import com.sw.ck.notify.entity.NotifyMessage;
import com.sw.ck.notify.entity.NotifyTemplate;
import com.sw.ck.notify.mapper.NotifyMessageMapper;
import com.sw.ck.notify.render.TemplateRenderService;
import com.sw.ck.notify.service.NotifyMessageService;
import com.sw.ck.notify.service.NotifyTemplateService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 站内信通知 Service 实现。
 */
@Service
public class NotifyMessageServiceImpl
        extends BaseServiceImpl<NotifyMessageMapper, NotifyMessage>
        implements NotifyMessageService {

    private static final int MAX_BATCH_RECIPIENTS = 500;

    private final NotifyTemplateService templateService;
    private final TemplateRenderService templateRenderService;
    private final LoginContextProvider loginContextProvider;

    public NotifyMessageServiceImpl(NotifyTemplateService templateService,
                                    TemplateRenderService templateRenderService,
                                    LoginContextProvider loginContextProvider) {
        this.templateService = templateService;
        this.templateRenderService = templateRenderService;
        this.loginContextProvider = loginContextProvider;
    }

    @Override
    public List<NotifyMessage> findByRecipient(Long recipientId) {
        return lambdaQuery()
                .eq(NotifyMessage::getRecipientId, recipientId)
                .orderByDesc(NotifyMessage::getCreateTime)
                .list();
    }

    @Override
    public NotifyMessage findByIdempotencyKey(String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) return null;
        return getBaseMapper().selectByIdempotencyKey(idempotencyKey);
    }

    @Override
    public List<NotifyMessage> findByRecipientWithFilter(Long recipientId, Boolean read, String keyword) {
        var wrapper = lambdaQuery()
                .eq(NotifyMessage::getRecipientId, recipientId);
        if (read != null) {
            wrapper.eq(NotifyMessage::getRead, read);
        }
        if (StringUtils.hasText(keyword)) {
            String pattern = "%" + keyword + "%";
            wrapper.and(w -> w
                    .like(NotifyMessage::getTitle, pattern)
                    .or()
                    .like(NotifyMessage::getContent, pattern));
        }
        return wrapper.orderByDesc(NotifyMessage::getCreateTime).list();
    }

    @Override
    public void deleteMessage(Long id) {
        removeById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchSend(NotifyBatchSendReq req) {
        // 1. 内容模式互斥校验
        boolean hasDirectContent = StringUtils.hasText(req.getTitle()) && StringUtils.hasText(req.getContent());
        boolean hasTemplate = StringUtils.hasText(req.getTemplateCode());
        if (hasDirectContent == hasTemplate) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR,
                    "必须选择直接内容模式（标题+正文）或模板模式（模板code），且只能选一种");
        }

        // 2. 先验证对象本身，再解析并去重接收人，避免无效对象被有效对象掩盖
        validateRecipientObjects(req);
        Set<Long> recipientIds = resolveRecipientIds(req);

        // 3. 零接收人拒绝
        if (recipientIds.isEmpty()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "无有效接收人");
        }

        // 4. 超限拒绝
        if (recipientIds.size() > MAX_BATCH_RECIPIENTS) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR,
                    "接收人数超过上限（最大" + MAX_BATCH_RECIPIENTS + "人），实际" + recipientIds.size() + "人");
        }

        // 5. 渲染内容
        String title;
        String content;
        if (hasTemplate) {
            NotifyTemplate template = templateService.getEnabledByCode(req.getTemplateCode());
            if (template == null) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR, "模板不存在或已停用：" + req.getTemplateCode());
            }
            Map<String, String> variables = req.getVariables() != null ? req.getVariables() : Map.of();
            title = templateRenderService.render(template.getTitleTemplate(), variables);
            content = templateRenderService.render(template.getContentTemplate(), variables);
        } else {
            title = req.getTitle();
            content = req.getContent();
        }

        // 6. 构建通知消息列表
        List<NotifyMessage> messages = new ArrayList<>(recipientIds.size());
        for (Long recipientId : recipientIds) {
            NotifyMessage msg = new NotifyMessage();
            msg.setRecipientId(recipientId);
            msg.setTitle(title);
            msg.setContent(content);
            msg.setBizType("SYSTEM");
            msg.setRead(false);
            // 旧批量入口依赖真实表默认值；新渠道入口由 NotifyFacade 显式写入渠道和结果。
            messages.add(msg);
        }

        // 7. 事务原子落库
        persistBatchMessages(messages);
        return messages.size();
    }

    @Override
    public java.util.List<Long> resolveRecipientUserIds(NotifyBatchSendReq req) {
        validateRecipientObjects(req);
        return new java.util.ArrayList<>(resolveRecipientIds(req));
    }

    @Override
    public int resolveCount(NotifyBatchSendReq req) {
        // 仅解析接收对象并去重，不校验内容、不发送
        validateRecipientObjects(req);
        return resolveRecipientIds(req).size();
    }

    /**
     * 真实事务内的批量持久化接缝；测试可在首批 SQL 完成后注入失败，验证事务回滚。
     */
    protected void persistBatchMessages(List<NotifyMessage> messages) {
        super.saveBatch(messages, 500);
    }

    private Set<Long> resolveRecipientIds(NotifyBatchSendReq req) {
        Set<Long> recipientIds = new LinkedHashSet<>();
        if (req.getRecipientUserIds() != null && !req.getRecipientUserIds().isEmpty()) {
            recipientIds.addAll(findValidUserIds(req.getRecipientUserIds()));
        }
        if (req.getRecipientDeptIds() != null && !req.getRecipientDeptIds().isEmpty()) {
            List<Long> deptUserIds = findActiveUserIdsByDeptIds(req.getRecipientDeptIds());
            recipientIds.addAll(deptUserIds);
        }
        if (req.getRecipientRoleCodes() != null && !req.getRecipientRoleCodes().isEmpty()) {
            List<Long> roleUserIds = findActiveUserIdsByRoleCodes(req.getRecipientRoleCodes());
            recipientIds.addAll(roleUserIds);
        }
        return recipientIds;
    }

    private void validateRecipientObjects(NotifyBatchSendReq req) {
        if (req == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "请求不能为空");
        }
        Long tenantId = loginContextProvider.getTenantId();
        if (tenantId == null) {
            throw new BaseException(CommonErrorCode.UNAUTHORIZED, "租户上下文不存在");
        }
        validateUserInput(req.getRecipientUserIds());
        validateDeptInput(req.getRecipientDeptIds(), tenantId);
        validateRoleInput(req.getRecipientRoleCodes(), tenantId);
    }

    private void validateUserInput(List<Long> userIds) {
        if (userIds != null && userIds.stream().anyMatch(id -> id == null)) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "用户ID不能为空");
        }
    }

    private void validateDeptInput(List<Long> deptIds, Long tenantId) {
        if (deptIds == null || deptIds.isEmpty()) {
            return;
        }
        if (deptIds.stream().anyMatch(id -> id == null)) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "部门ID不能为空");
        }
        Set<Long> requested = new HashSet<>(deptIds);
        Set<Long> valid = new HashSet<>(getBaseMapper().selectValidDeptIds(List.copyOf(requested), tenantId));
        if (!valid.containsAll(requested)) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR,
                    "部门不存在、跨租户、已停用或已删除");
        }
    }

    private void validateRoleInput(List<String> roleCodes, Long tenantId) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return;
        }
        if (roleCodes.stream().anyMatch(code -> !StringUtils.hasText(code))) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "角色编码不能为空");
        }
        Set<String> requested = new HashSet<>(roleCodes);
        Set<String> valid = new HashSet<>(getBaseMapper().selectValidRoleCodes(List.copyOf(requested), tenantId));
        if (!valid.containsAll(requested)) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR,
                    "角色不存在、跨租户、已停用或已删除");
        }
    }

    @Override
    public void saveBatchMessages(List<NotifyMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        persistBatchMessages(messages);
    }

    @Override
    public List<Long> findActiveUserIdsByDeptIds(List<Long> deptIds) {
        if (deptIds == null || deptIds.isEmpty()) {
            return List.of();
        }
        Long tenantId = loginContextProvider.getTenantId();
        return getBaseMapper().selectActiveUserIdsByDeptIds(deptIds, tenantId);
    }

    @Override
    public List<Long> findActiveUserIdsByRoleCodes(List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return List.of();
        }
        Long tenantId = loginContextProvider.getTenantId();
        return getBaseMapper().selectActiveUserIdsByRoleCodes(roleCodes, tenantId);
    }

    @Override
    public List<Long> findValidUserIds(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        Long tenantId = loginContextProvider.getTenantId();
        return getBaseMapper().selectValidUserIds(userIds, tenantId);
    }
}
