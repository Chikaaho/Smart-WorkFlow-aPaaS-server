package com.sw.ck.agent.integration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.agent.entity.tool.AgentToolExternalConfig;
import com.sw.ck.agent.mapper.tool.AgentToolExternalConfigMapper;
import com.sw.ck.common.integration.ExternalCapability;
import com.sw.ck.common.integration.ExternalCapabilityFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 外部 HTTP 工具作为表单受控能力的实现。
 * <p>
 * 复用既有 {@code sw_agent_tool_external} 白名单：表单只传稳定名称，URL/方法仍由本类
 * 从数据库解析，不接受运行时任意 URL。只有 {@code effect_scope} 显式登记为
 * {@code QUERY}/{@code WRITE} 且启用的同租户能力才对表单可见。
 * </p>
 */
@Component
public class AgentExternalCapabilityFacade implements ExternalCapabilityFacade {

    private static final Logger log = LoggerFactory.getLogger(AgentExternalCapabilityFacade.class);

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_RESPONSE_CHARS = 256 * 1024;
    private static final Set<String> SUPPORTED_HTTP_METHODS = Set.of("GET", "POST", "PUT");
    private static final Set<String> SUPPORTED_EFFECT_SCOPES = Set.of("QUERY", "WRITE");

    private final AgentToolExternalConfigMapper externalMapper;

    public AgentExternalCapabilityFacade(AgentToolExternalConfigMapper externalMapper) {
        this.externalMapper = externalMapper;
    }

    @Override
    public List<ExternalCapability> listEnabled(Long tenantId) {
        if (tenantId == null) return List.of();
        List<AgentToolExternalConfig> rows = externalMapper.selectList(
                Wrappers.<AgentToolExternalConfig>lambdaQuery()
                        .eq(AgentToolExternalConfig::getTenantId, tenantId)
                        .eq(AgentToolExternalConfig::getEnabled, 1)
                        .orderByDesc(AgentToolExternalConfig::getId));
        List<ExternalCapability> result = new ArrayList<>(rows.size());
        for (AgentToolExternalConfig row : rows) {
            ExternalCapability capability = toCapability(row);
            if (capability != null) result.add(capability);
        }
        return result;
    }

    @Override
    public ExternalCapability findEnabled(Long tenantId, String capabilityId) {
        AgentToolExternalConfig row = requireConfig(tenantId, capabilityId, false);
        return row == null ? null : toCapability(row);
    }

    @Override
    public String invoke(ExternalCapability capability, String inputJson) {
        if (capability == null) {
            throw new IllegalArgumentException("外部能力不能为空");
        }
        AgentToolExternalConfig config = requireConfig(capability.tenantId(), capability.name(), true);
        if (config == null) {
            throw new IllegalStateException("外部能力未登记、跨租户或已停用: " + capability.name());
        }
        String methodName = config.getHttpMethod() == null
                ? "POST"
                : config.getHttpMethod().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_HTTP_METHODS.contains(methodName)) {
            throw new IllegalStateException("外部能力 HTTP 方法不受支持: " + methodName);
        }
        int timeoutSeconds = config.getTimeoutSeconds() == null
                ? DEFAULT_TIMEOUT_SECONDS
                : Math.max(1, config.getTimeoutSeconds());
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(timeoutSeconds));
        requestFactory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        RestClient restClient = RestClient.builder().requestFactory(requestFactory).build();

        long started = System.currentTimeMillis();
        try {
            String body = restClient.method(HttpMethod.valueOf(methodName))
                    .uri(config.getUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(inputJson == null ? "{}" : inputJson)
                    .retrieve()
                    .body(String.class);
            String normalized = body == null ? "" : body;
            if (normalized.length() > MAX_RESPONSE_CHARS) {
                throw new IllegalStateException("外部能力响应超过 256KB 上限");
            }
            log.info("External capability invoked: name={}, tenantId={}, status=OK, latencyMs={}",
                    capability.name(), capability.tenantId(), System.currentTimeMillis() - started);
            return normalized;
        } catch (RestClientResponseException e) {
            log.warn("External capability HTTP failure: name={}, tenantId={}, status={}, latencyMs={}",
                    capability.name(), capability.tenantId(), e.getStatusCode().value(),
                    System.currentTimeMillis() - started);
            throw new IllegalStateException("外部能力调用失败: HTTP " + e.getStatusCode().value(), e);
        } catch (RuntimeException e) {
            log.warn("External capability invocation failure: name={}, tenantId={}, latencyMs={}, error={}",
                    capability.name(), capability.tenantId(), System.currentTimeMillis() - started,
                    e.getClass().getSimpleName());
            throw e;
        }
    }

    private ExternalCapability toCapability(AgentToolExternalConfig row) {
        String scope = normalizeScope(row.getEffectScope());
        if (scope == null) return null;
        int timeoutSeconds = row.getTimeoutSeconds() == null
                ? DEFAULT_TIMEOUT_SECONDS
                : Math.max(1, row.getTimeoutSeconds());
        return new ExternalCapability(row.getName(), row.getDescription(), scope,
                timeoutSeconds, row.getTenantId());
    }

    private AgentToolExternalConfig requireConfig(Long tenantId, String name, boolean requireClassified) {
        if (tenantId == null || name == null || name.isBlank()) return null;
        List<AgentToolExternalConfig> rows = externalMapper.selectList(
                Wrappers.<AgentToolExternalConfig>lambdaQuery()
                        .eq(AgentToolExternalConfig::getTenantId, tenantId)
                        .eq(AgentToolExternalConfig::getName, name)
                        .eq(AgentToolExternalConfig::getEnabled, 1)
                        .orderByDesc(AgentToolExternalConfig::getId));
        if (rows.isEmpty()) return null;
        AgentToolExternalConfig row = rows.get(0);
        if (requireClassified && normalizeScope(row.getEffectScope()) == null) {
            return null;
        }
        return row;
    }

    private String normalizeScope(String value) {
        if (value == null || value.isBlank()) return null;
        String upper = value.trim().toUpperCase(Locale.ROOT);
        return SUPPORTED_EFFECT_SCOPES.contains(upper) ? upper : null;
    }
}
