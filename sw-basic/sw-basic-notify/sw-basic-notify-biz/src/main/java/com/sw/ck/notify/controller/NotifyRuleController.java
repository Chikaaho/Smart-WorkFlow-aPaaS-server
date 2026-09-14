package com.sw.ck.notify.controller;

import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.response.R;
import com.sw.ck.notify.dto.NotifyRuleDTO;
import com.sw.ck.notify.dto.NotifyRuleQuery;
import com.sw.ck.notify.service.NotifyRuleService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 通知规则管理控制器（I6）。 */
@RestController
@RequestMapping("/notify/rules")
public class NotifyRuleController {

    private final NotifyRuleService notifyRuleService;

    public NotifyRuleController(NotifyRuleService notifyRuleService) {
        this.notifyRuleService = notifyRuleService;
    }

    @GetMapping
    @PreAuthorize("@ss.hasPermi('notify:rule:view')")
    public R<PageResult<com.sw.ck.notify.dto.NotifyRuleDTO>> list(NotifyRuleQuery query) {
        return R.ok(notifyRuleService.pageRules(query));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('notify:rule:view')")
    public R<com.sw.ck.notify.dto.NotifyRuleDTO> get(@PathVariable Long id) {
        return R.ok(notifyRuleService.getRule(id));
    }

    @PostMapping
    @PreAuthorize("@ss.hasPermi('notify:rule:manage')")
    public R<Long> create(@RequestBody com.sw.ck.notify.dto.NotifyRuleDTO dto) {
        return R.ok(notifyRuleService.createRule(dto));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('notify:rule:manage')")
    public R<Void> update(@PathVariable Long id, @RequestBody com.sw.ck.notify.dto.NotifyRuleDTO dto) {
        notifyRuleService.updateRule(id, dto);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('notify:rule:manage')")
    public R<Void> delete(@PathVariable Long id) {
        notifyRuleService.deleteRule(id);
        return R.ok();
    }

    /** 启停：按方向 §3.2 发布校验取代运行期接管，构造请求同样受权限拒绝。 */
    @PostMapping("/{id}/enabled/{enabled}")
    @PreAuthorize("@ss.hasPermi('notify:rule:manage')")
    public R<Void> toggle(@PathVariable Long id, @PathVariable boolean enabled) {
        notifyRuleService.toggleRule(id, enabled);
        return R.ok();
    }
}
