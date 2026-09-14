package com.sw.ck.notify.service;

import com.sw.ck.common.page.PageResult;
import com.sw.ck.notify.dto.NotifyRuleDTO;
import com.sw.ck.notify.dto.NotifyRuleQuery;
import com.sw.ck.notify.entity.NotifyRule;

import java.util.List;

/** 通知规则服务（I6）。 */
public interface NotifyRuleService {

    PageResult<NotifyRuleDTO> pageRules(NotifyRuleQuery query);

    NotifyRuleDTO getRule(Long id);

    Long createRule(NotifyRuleDTO dto);

    void updateRule(Long id, NotifyRuleDTO dto);

    void deleteRule(Long id);

    void toggleRule(Long id, boolean enabled);

    /** 按事件取启用规则（租户内唯一启用规则优先，渲染渠道顺序）。 */
    List<NotifyRule> listEnabledByEvent(String eventType);
}
