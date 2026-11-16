package com.sw.ck.system.controller;

import org.springframework.beans.factory.annotation.Autowired;

import com.sw.ck.common.response.R;
import com.sw.ck.system.vo.NotifySubjectBindingVO;
import com.sw.ck.system.entity.NotifySubjectBinding;
import com.sw.ck.system.service.NotifySubjectBindingService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import lombok.extern.log4j.Log4j2;
/**
 * 通知 Provider 主体映射控制器（I6 G5a-I）。
 * <p>绑定/解绑是渠道身份管理动作（notify:channel:manage）。响应只回
 * SHA-256 摘要，不回 subject_cipher 与明文主体（方向 §3.6 最小暴露）；
 * 发送侧不接受客户端原始主体旁路（§D-14）。</p>
 */
@RestController
@RequestMapping("/notify/subject-bindings")
@Log4j2
public class NotifySubjectBindingController {

    @Autowired
    private NotifySubjectBindingService service;

    /** 绑定请求体（subject 为一次性录入的明文主体，仅在服务端加密后落库）。 */
    public static class BindRequest {
        private Long userId;
        private String provider;
        private String subject;

        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getSubject() { return subject; }
        public void setSubject(String subject) { this.subject = subject; }
    }

    @PostMapping
    @PreAuthorize("@ss.hasPermi('notify:channel:manage')")
    public R<NotifySubjectBindingVO> bind(@RequestBody BindRequest request) {
        NotifySubjectBinding binding = service.bind(request.getUserId(), request.getProvider(), request.getSubject());
        return R.ok(toDTO(binding));
    }

    @GetMapping
    @PreAuthorize("@ss.hasPermi('notify:channel:manage')")
    public R<List<NotifySubjectBindingVO>> list(@RequestParam(required = false) String provider) {
        return R.ok(service.list(provider).stream().map(this::toDTO).toList());
    }

    private NotifySubjectBindingVO toDTO(NotifySubjectBinding binding) {
        NotifySubjectBindingVO dto = new NotifySubjectBindingVO();
        dto.setId(binding.getId());
        dto.setUserId(binding.getUserId());
        dto.setProvider(binding.getProvider());
        dto.setSubjectDigest(binding.getSubjectDigest());
        dto.setBindStatus(binding.getBindStatus());
        return dto;
    }

    @PostMapping("/{id}/toggle/{enabled}")
    @PreAuthorize("@ss.hasPermi('notify:channel:manage')")
    public R<Void> toggle(@PathVariable Long id, @PathVariable boolean enabled) {
        service.toggle(id, enabled);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('notify:channel:manage')")
    public R<Void> unbind(@PathVariable Long id) {
        service.unbind(id);
        log.info("通知主体已解绑: bindingId={}", id);
        return R.ok();
    }
}
