package com.sw.ck.notify.api;

/**
 * 通知深链打开授权 SPI（I6）。
 * <p>
 * 深链只保存受控对象类型与稳定 ID；打开时由业务侧实现重新鉴权，
 * 判定当前用户对该业务对象是否仍有访问权。定义于 {@code -api}，
 * 由拥有业务对象权限语义的模块（如 bpm）提供实现。
 * </p>
 */
public interface NotifyLinkAuthorizer {

    /**
     * 校验当前用户是否可打开指定业务对象的深链。
     *
     * @param linkType 受控对象类型（如 WF_TASK / WF_PROCESS / SYSTEM）
     * @param linkId   稳定业务对象 ID
     * @return true=允许跳转；false=拒绝
     */
    boolean canOpen(String linkType, String linkId);
}
