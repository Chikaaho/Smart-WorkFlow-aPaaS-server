package com.sw.ck.system.sso;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provider 客户端注册表：按 Provider 标识定位 {@link SsoProviderClient} 实现。
 * <p>
 * 自 SsoAuthService 拆出（行为不变）：授权发起（构造授权 URL）与回调换票共用。
 * </p>
 */
@Service
class SsoProviderClients {

    /** SSO provider 实现按条件装配（0 实现=本环境未启用 SSO）；构造器注入时代空列表合法，
     * required=false + 空兜底恢复该语义（1.1.3 CI：d53c7fb6 字段注入改造后零 Bean 环境启动失败）。 */
    @Autowired(required = false)
    private List<SsoProviderClient> clientList = new ArrayList<>();
    private Map<String, SsoProviderClient> clients;

    @PostConstruct
    void initClients() {
        this.clients = new ConcurrentHashMap<>();
        for (SsoProviderClient client : clientList) {
            this.clients.put(client.provider(), client);
        }
    }

    SsoProviderClient get(String provider) {
        return clients.get(provider);
    }
}
