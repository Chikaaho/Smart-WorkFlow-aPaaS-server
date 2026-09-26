package com.sw.ck.notify.api;

import java.util.Optional;

/**
 * 通知深链打开授权 SPI（I6）。
 * <p>
 * 深链只保存受控对象类型与稳定 ID；打开时由业务侧实现重新鉴权，
 * 判定当前用户对该业务对象是否仍有访问权。定义于 {@code -api}，
 * 由拥有业务对象权限语义的模块（如 bpm）提供实现。
 * </p>
 * <p>
 * 本契约 fail closed：empty 表示无法裁决，调用方与实现都必须按"拒绝"处理。
 * </p>
 */
public interface NotifyLinkAuthorizer {

    /**
     * 校验当前用户是否可打开指定业务对象的深链。
     *
     * @param linkType 受控对象类型（如 WF_TASK / WF_PROCESS / SYSTEM）
     * @param linkId   稳定业务对象 ID
     * @return present = 明确裁决（true 允许跳转 / false 拒绝，含对象不存在与不受支持的类型）；
     *         empty = 缺少裁决上下文（无登录用户、缺少对象类型或标识）无法裁决，
     *         调用方必须按拒绝处理
     */
    Optional<Boolean> canOpen(String linkType, String linkId);
}
