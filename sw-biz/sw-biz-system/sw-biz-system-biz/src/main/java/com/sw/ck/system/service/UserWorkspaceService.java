package com.sw.ck.system.service;

import java.util.Map;

/** 用户工作台布局服务（v0.0.2 OA，P54）。 */
public interface UserWorkspaceService {

    /**
     * 当前用户布局；无配置或配置不可解析时返回默认布局（可用首页兜底）。
     *
     * @return {custom: boolean, layout: Map}
     */
    Map<String, Object> getLayout();

    /** 保存当前用户布局（组件键白名单 + 体积上限校验），两用户配置相互独立。 */
    void saveLayout(Map<String, Object> layout);

    /** 恢复默认：清空个性化配置，后续读取回落默认布局。 */
    void resetLayout();
}
