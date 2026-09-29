package com.sw.ck.system.sso;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G3b/G4a R2：钉钉企业模式 corpid scope 受影响验证（审查04——scope 修正的
 * 受影响验证）。企业模式（extra.enterpriseId 配置）授权必须带 openid+corpid
 * （否则换票响应无 corpId、归属不可校验——在线实测）；个人模式保持 openid。
 */
@DisplayName("钉钉授权 scope：企业模式 openid+corpid；个人模式 openid")
class DingtalkSsoProviderClientScopeTest {

    private final DingtalkSsoProviderClient client = new DingtalkSsoProviderClient();

    @Test
    @DisplayName("企业模式：extra.enterpriseId 配置 → scope=openid+corpid")
    void buildAuthorizeUrl_enterpriseMode_shouldRequestCorpidScope() {
        SsoProviderClient.SsoProviderConfigView config = new SsoProviderClient.SsoProviderConfigView(
                "dingzoptrn9m3m33rwe1", "secret", Map.of("enterpriseId", "dingd6efc2501230ee694210d8170298fd9d"));
        String url = client.buildAuthorizeUrl(config, "http://localhost:8081/sw-server/api/auth/sso/dingtalk/callback", "st");
        assertThat(url).contains("scope=openid%2Bcorpid");
        assertThat(url).contains("clientId=dingzoptrn9m3m33rwe1");
        assertThat(url).contains("prompt=consent");
    }

    @Test
    @DisplayName("个人模式：extra 无 enterpriseId → scope=openid（无组织选择）")
    void buildAuthorizeUrl_personalMode_shouldUseOpenidOnly() {
        SsoProviderClient.SsoProviderConfigView config = new SsoProviderClient.SsoProviderConfigView(
                "dingzoptrn9m3m33rwe1", "secret", Map.of());
        String url = client.buildAuthorizeUrl(config, "http://localhost:8081/sw-server/api/auth/sso/dingtalk/callback", "st");
        assertThat(url).contains("scope=openid");
        assertThat(url).doesNotContain("corpid");
    }
}
