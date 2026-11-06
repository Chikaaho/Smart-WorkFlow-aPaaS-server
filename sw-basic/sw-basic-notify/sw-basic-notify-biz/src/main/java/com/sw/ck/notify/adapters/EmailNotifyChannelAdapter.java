package com.sw.ck.notify.adapters;

import com.sw.ck.notify.api.NotifyChannel;
import com.sw.ck.notify.api.NotifyChannelAdapter;
import com.sw.ck.notify.api.NotifySendRequest;
import com.sw.ck.notify.api.NotifySendResult;
import com.sw.ck.notify.api.NotifyTargetResolver;
import com.sw.ck.notify.render.NotifyHtmlSanitizer;
import com.sw.ck.notify.service.NotifyChannelConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * 邮件生产渠道适配器（I6）。
 * <p>系统级装配：{@code sw.notify.channels.EMAIL.enabled=true} 即注册；
 * 发信配置解析顺序（0.2.4）：①租户级渠道凭据密文（{@code sw_notify_channel_config.config_cipher}，
 * 与模型 Key 同构的 AES-256-GCM，接口写入、明文仅内存）→ ②yml/env 的 SMTP 配置。
 * 收件地址由服务端从同租户有效用户解析（{@link NotifyTargetResolver}），不接受客户端原始地址旁路。
 * HTML 正文按目标格式转义渲染（Render 侧保证）。</p>
 */
@Component
@ConditionalOnProperty(prefix = "sw.notify.channels.EMAIL", name = "enabled", havingValue = "true")
public class EmailNotifyChannelAdapter implements NotifyChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(EmailNotifyChannelAdapter.class);

    private static final NotifyChannel CHANNEL = NotifyChannel.EMAIL;

    /** yml/env 装配的发信器（未配置 spring.mail 时可为空）。 */
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final NotifyTargetResolver targetResolver;
    private final NotifyChannelConfigService channelConfigService;
    private final String from;
    private final Integer port;

    @Autowired
    public EmailNotifyChannelAdapter(ObjectProvider<JavaMailSender> mailSenderProvider,
                                     NotifyTargetResolver targetResolver,
                                     com.sw.ck.notify.config.NotifyChannelProperties props,
                                     ObjectProvider<NotifyChannelConfigService> channelConfigServiceProvider) {
        this.mailSenderProvider = mailSenderProvider;
        this.targetResolver = targetResolver;
        this.channelConfigService = channelConfigServiceProvider.getIfAvailable();
        com.sw.ck.notify.config.NotifyChannelProperties.ChannelProps emailProps = props.getChannels() == null ? null : props.getChannels().get("EMAIL");
        this.from = emailProps == null ? null : emailProps.getFrom();
        this.port = emailProps == null ? null : emailProps.getPort();
    }

    @Override
    public Optional<NotifyChannel> channel() {
        return Optional.of(CHANNEL);
    }

    @Override
    public Optional<NotifySendResult> send(NotifySendRequest request) {
        Optional<String> to = targetResolver == null
                ? Optional.empty()
                : targetResolver.resolveEmail(request.getRecipientId()).filter(value -> !value.isBlank());
        if (to.isEmpty()) {
            return Optional.of(NotifySendResult.builder().channel(CHANNEL).status("FAILED")
                    .failureReason("无法解析收件邮箱（用户无效或未登记邮箱），拒绝发送").build());
        }
        Map<String, String> dbConfig = channelConfigService == null
                ? null
                : channelConfigService.resolveChannelConfig(CHANNEL.name()).orElse(null);
        try {
            JavaMailSender sender = dbConfig == null ? mailSenderProvider.getIfAvailable() : buildSender(dbConfig);
            String effectiveFrom = dbConfig == null ? from
                    : firstNonBlank(dbConfig.get("from"), dbConfig.get("username"), from);
            if (sender == null) {
                return Optional.of(NotifySendResult.builder().channel(CHANNEL).status("FAILED")
                        .failureReason("SMTP 未配置（渠道凭据与 spring.mail 均不可用）").build());
            }
            if (effectiveFrom == null || effectiveFrom.isBlank()) {
                return Optional.of(NotifySendResult.builder().channel(CHANNEL).status("FAILED")
                        .failureReason("发件人未配置（from/username）").build());
            }
            jakarta.mail.internet.MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(effectiveFrom);
            helper.setTo(to.orElseThrow());
            helper.setSubject(org.springframework.web.util.HtmlUtils.htmlEscape(request.getTitle() == null ? "" : request.getTitle()));
            helper.setText("<html><body>" + NotifyHtmlSanitizer.clean(request.getContent()) + "</body></html>", true);
            sender.send(message);
            log.info("邮件投递成功: recipient={}, source={}, external=smtp-{}", request.getRecipientId(),
                    dbConfig == null ? "yml" : "db", System.nanoTime());
            return Optional.of(NotifySendResult.builder().channel(CHANNEL).status("SUCCESS")
                    .externalMessageId("smtp-" + Math.abs(System.nanoTime()))
                    .build());
        } catch (Exception e) {
            log.warn("邮件投递失败: recipient={}, exceptionClass={}, message={}", request.getRecipientId(),
                    e.getClass().getSimpleName(), e.getMessage());
            return Optional.of(NotifySendResult.builder().channel(CHANNEL).status("FAILED")
                    .failureReason("邮件投递失败: " + e.getClass().getSimpleName()).build());
        }
    }

    /** 按渠道凭据构建一次性发信器（host/port/username/password/starttls/ssl；配置即改即生效）。 */
    private JavaMailSender buildSender(Map<String, String> config) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        String host = config.get("host");
        if (host == null || host.isBlank()) {
            return null;
        }
        sender.setHost(host);
        String portText = config.get("port");
        int resolvedPort = portText == null || portText.isBlank()
                ? (port == null ? 25 : port)
                : Integer.parseInt(portText.trim());
        sender.setPort(resolvedPort);
        if (config.get("username") != null) {
            sender.setUsername(config.get("username"));
        }
        if (config.get("password") != null) {
            sender.setPassword(config.get("password"));
        }
        sender.setProtocol(config.getOrDefault("protocol", "smtp"));
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        Properties props = new Properties();
        props.put("mail.transport.protocol", sender.getProtocol());
        props.put("mail.smtp.auth",
                String.valueOf(config.get("password") != null && !config.get("password").isBlank()));
        boolean ssl = "true".equalsIgnoreCase(config.getOrDefault("ssl", "false"));
        boolean starttls = "true".equalsIgnoreCase(config.getOrDefault("starttls",
                ssl ? "false" : "true"));
        props.put("mail.smtp.ssl.enable", String.valueOf(ssl));
        props.put("mail.smtp.starttls.enable", String.valueOf(starttls));
        props.put("mail.smtp.ssl.trust", host);
        sender.setJavaMailProperties(props);
        return sender;
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }
}
