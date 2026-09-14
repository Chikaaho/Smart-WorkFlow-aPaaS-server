package com.sw.ck.notify.adapters;

import com.sw.ck.notify.api.NotifyChannel;
import com.sw.ck.notify.api.NotifyChannelAdapter;
import com.sw.ck.notify.api.NotifySendRequest;
import com.sw.ck.notify.api.NotifySendResult;
import com.sw.ck.notify.api.NotifyTargetResolver;
import com.sw.ck.notify.render.NotifyHtmlSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;


import java.nio.charset.StandardCharsets;

/**
 * 邮件生产渠道适配器（I6）。
 * <p>仅在 {@code sw.notify.channels.EMAIL.enabled=true} 且 SMTP 装配完整时注册；
 * 收件地址由服务端从同租户有效用户解析（{@link NotifyTargetResolver}），
 * 不接受客户端原始地址旁路。HTML 正文按目标格式转义渲染（Render 侧保证）。</p>
 */
@Component
@ConditionalOnProperty(prefix = "sw.notify.channels.EMAIL", name = "enabled", havingValue = "true")
public class EmailNotifyChannelAdapter implements NotifyChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(EmailNotifyChannelAdapter.class);

    private final JavaMailSender mailSender;
    private final NotifyTargetResolver targetResolver;
    private final String from;
    private final Integer port;

    @Autowired
    public EmailNotifyChannelAdapter(JavaMailSender mailSender,
                                     NotifyTargetResolver targetResolver,
                                     com.sw.ck.notify.config.NotifyChannelProperties props) {
        this.mailSender = mailSender;
        this.targetResolver = targetResolver;
        this.from = props.getChannels().get("EMAIL").getFrom();
        this.port = props.getChannels().get("EMAIL").getPort();
    }

    @Override
    public NotifyChannel channel() {
        return NotifyChannel.EMAIL;
    }

    @Override
    public NotifySendResult send(NotifySendRequest request) {
        String to = targetResolver == null ? null : targetResolver.resolveEmail(request.getRecipientId());
        if (to == null || to.isBlank()) {
            return NotifySendResult.builder().channel(channel()).status("FAILED")
                    .failureReason("无法解析收件邮箱（用户无效或未登记邮箱），拒绝发送").build();
        }
        try {
            jakarta.mail.internet.MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(org.springframework.web.util.HtmlUtils.htmlEscape(request.getTitle() == null ? "" : request.getTitle()));
            helper.setText("<html><body>" + NotifyHtmlSanitizer.clean(request.getContent()) + "</body></html>", true);
            mailSender.send(message);
            log.info("邮件投递成功: recipient={}, external=smtp-{}", request.getRecipientId(), System.nanoTime());
            return NotifySendResult.builder().channel(channel()).status("SUCCESS")
                    .externalMessageId("smtp-" + Math.abs(System.nanoTime()))
                    .build();
        } catch (Exception e) {
            log.warn("邮件投递失败: recipient={}, exceptionClass={}", request.getRecipientId(),
                    e.getClass().getSimpleName());
            return NotifySendResult.builder().channel(channel()).status("FAILED")
                    .failureReason("邮件投递失败: " + e.getClass().getSimpleName()).build();
        }
    }
}
