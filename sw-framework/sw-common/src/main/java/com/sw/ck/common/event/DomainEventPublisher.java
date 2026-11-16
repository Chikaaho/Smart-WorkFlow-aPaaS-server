package com.sw.ck.common.event;

import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 领域事件发布器。
 * <p>
 * 对 {@link ApplicationEventPublisher} 的薄封装，保持事件发布机制可替换。
 * 所有跨模块事件需经此发布器，不得直接调用 {@code applicationEventPublisher.publishEvent}。
 * </p>
 *
 * <p>约定：</p>
 * <ul>
 *   <li>发布事件时，业务代码已在 {@code @Transactional} 事务边界内</li>
 *   <li>事件监听使用 {@code @TransactionalEventListener(phase = AFTER_COMMIT)} +
 *       {@code @Async}，确保事务提交后才消费</li>
 * </ul>
 */
@Component
public class DomainEventPublisher {

    @Autowired
    private ApplicationEventPublisher delegate;

    /**
     * 发布领域事件。
     *
     * @param event 事件对象（通常为可序列化的 POJO）
     */
    public void publish(Object event) {
        delegate.publishEvent(event);
    }
}
