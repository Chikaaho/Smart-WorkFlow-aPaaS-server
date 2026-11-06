package com.sw.ck.agent.orchestration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.agent.entity.tool.AgentToolExternalConfig;
import com.sw.ck.agent.entity.tool.AgentToolInternalConfig;
import com.sw.ck.agent.mapper.tool.AgentToolExternalConfigMapper;
import com.sw.ck.agent.mapper.tool.AgentToolInternalConfigMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 工具工厂（M07 Step3 → F1 阶段 1 LangChain4j 化）：从 DB 白名单表加载启用工具，
 * 构造 {@link ToolHandle}（{@link ToolSpecification} + {@link ToolExecutor}）列表。
 * <p>
 * <b>参数约定</b>：工具入参统一为单个字符串（沿用 M07 现场实测约定，内部工具签名
 * {@code String execute(String)}），LLM 侧 schema 固定为单字符串属性 {@code input}
 * （等价于旧 Spring AI inputType(String) 生成的 {"type":"string"}）；DB 的
 * input_schema 仅作为描述性配置保留，不再拼进请求 schema。
 * 工具执行循环（tool_calls → 执行 → 回喂）由调用方（AgentGraphFactory / 图 LLM 节点）
 * 基于低层 ChatModel 手工完成。
 * </p>
 * <p>
 * <b>安全边界不变</b>：beanName/methodName（内部）与 url/httpMethod（外部）均来自 DB
 * 白名单表，LLM 只能携带工具名与参数；名称 → 目标的映射在本类内部完成。
 * fail-fast：bean/方法不存在在构造阶段即抛；外部工具仅 GET/POST/PUT。
 * </p>
 */
@Component
@ConditionalOnProperty(prefix = "sw.agent", name = "enabled", havingValue = "true")
public class AgentToolCallbackFactory {

    /** timeoutSeconds 未配置时的默认值（秒），与 V20 表结构 DEFAULT 30 一致 */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /** 外部工具支持的 HTTP 方法白名单 */
    private static final Set<String> SUPPORTED_HTTP_METHODS = Set.of("GET", "POST", "PUT");

    /** arguments JSON 解析（字符串字面量反序列化）；静态无状态可复用 */
    private static final ObjectMapper ARGUMENTS_JSON = new ObjectMapper();

    /** LangChain4j 工具句柄：规格（发给 LLM）+ 执行器（本地执行 tool_calls） */
    public record ToolHandle(ToolSpecification specification, ToolExecutor executor) {
    }

    private final AgentToolInternalConfigMapper internalMapper;
    private final AgentToolExternalConfigMapper externalMapper;
    private final ApplicationContext applicationContext;

    public AgentToolCallbackFactory(AgentToolInternalConfigMapper internalMapper,
                                    AgentToolExternalConfigMapper externalMapper,
                                    ApplicationContext applicationContext) {
        this.internalMapper = internalMapper;
        this.externalMapper = externalMapper;
        this.applicationContext = applicationContext;
    }

    /**
     * 加载启用工具并构造句柄列表（内部 + 外部）。
     *
     * @param tenantId 租户过滤条件；null 时不显式过滤（生产环境由 MyBatis-Plus
     *                 租户拦截器按当前登录租户自动隔离）
     * @return 工具句柄列表（可能为空）
     */
    public List<ToolHandle> buildToolCallbacks(Long tenantId) {
        // enabled 条件用数字字面量 1（非 Boolean 参数）：H2/PG 下 Boolean 参数与
        // SMALLINT 列比较不稳定（M07 Step4 现场实证，见回执 §7）
        List<AgentToolInternalConfig> internals = internalMapper.selectList(
                Wrappers.<AgentToolInternalConfig>lambdaQuery()
                        .eq(AgentToolInternalConfig::getEnabled, true)
                        .eq(tenantId != null, AgentToolInternalConfig::getTenantId, tenantId)
                        .orderByDesc(AgentToolInternalConfig::getId));
        List<AgentToolExternalConfig> externals = externalMapper.selectList(
                Wrappers.<AgentToolExternalConfig>lambdaQuery()
                        .eq(AgentToolExternalConfig::getEnabled, true)
                        .eq(tenantId != null, AgentToolExternalConfig::getTenantId, tenantId)
                        .orderByDesc(AgentToolExternalConfig::getId));
        List<ToolHandle> handles = new ArrayList<>(internals.size() + externals.size());
        for (AgentToolInternalConfig config : internals) {
            handles.add(buildInternalTool(config));
        }
        for (AgentToolExternalConfig config : externals) {
            handles.add(buildExternalTool(config));
        }
        return handles;
    }

    /** 按工具名解析执行器（工具循环回喂时使用；未知名返回 null，由调用方判空） */
    public static ToolExecutor executorOf(List<ToolHandle> handles, String toolName) {
        for (ToolHandle handle : handles) {
            if (handle.specification().name().equals(toolName)) {
                return handle.executor();
            }
        }
        return null;
    }

    /** 构造工具规格（单字符串入参 schema） */
    private ToolSpecification specOf(String name, String description) {
        return ToolSpecification.builder()
                .name(name)
                .description(description)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("input", "工具入参（JSON 字符串）")
                        .required("input")
                        .build())
                .build();
    }

    /**
     * 构造内部工具：运行时反射调用白名单 bean 的约定方法
     * {@code String execute(String params)}（构造阶段完成 bean 解析与签名校验，fail-fast）。
     */
    private ToolHandle buildInternalTool(AgentToolInternalConfig config) {
        // beanName 来自 DB 白名单，不存在时 getBean 抛 NoSuchBeanDefinitionException（fail-fast）
        Object bean = applicationContext.getBean(config.getBeanName());
        Method method;
        try {
            method = bean.getClass().getMethod(config.getMethodName(), String.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "内部工具方法不存在（白名单配置错误，须 String 入参签名）: "
                            + config.getBeanName() + "." + config.getMethodName() + "(String)",
                    e);
        }
        if (!String.class.equals(method.getReturnType())) {
            // 约定：白名单方法签名 = String execute(String params)，不符即拒绝（禁止静默失败）
            throw new IllegalStateException(
                    "内部工具方法返回类型与约定不符（须返回 String）: "
                            + config.getBeanName() + "." + config.getMethodName()
                            + " 实际返回 " + method.getReturnType().getSimpleName());
        }
        ToolExecutor executor = (request, memoryId) -> {
            // F04：lambda 包装计时 + 调用摘要写入 ThreadLocal 载体（未绑定记录列表时跳过）
            long start = System.currentTimeMillis();
            String args = unwrapArguments(request);
            String result = invokeInternal(bean, method, config.getName(), args);
            recordToolCall(config.getName(), args, result, System.currentTimeMillis() - start);
            return result;
        };
        return new ToolHandle(specOf(config.getName(), config.getDescription()), executor);
    }

    /**
     * 构造外部工具：按白名单 URL + HTTP 方法发起 RestClient 请求，超时从
     * {@code timeoutSeconds} 字段读。
     */
    private ToolHandle buildExternalTool(AgentToolExternalConfig config) {
        String httpMethodName = config.getHttpMethod() == null
                ? "POST"
                : config.getHttpMethod().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_HTTP_METHODS.contains(httpMethodName)) {
            throw new IllegalArgumentException(
                    "外部工具 HTTP 方法不支持（仅 GET/POST/PUT）: " + config.getHttpMethod());
        }
        int timeoutSeconds = config.getTimeoutSeconds() == null
                ? DEFAULT_TIMEOUT_SECONDS
                : Math.max(1, config.getTimeoutSeconds());
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(timeoutSeconds));
        requestFactory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        RestClient restClient = RestClient.builder().requestFactory(requestFactory).build();
        HttpMethod httpMethod = HttpMethod.valueOf(httpMethodName);
        String url = config.getUrl();
        ToolExecutor executor = (request, memoryId) -> {
            // GET 工具必须把入参放到查询串：JDK SimpleClientHttpRequestFactory 会忽略
            // GET body，历史上导致搜索类工具收到空查询（REAL-01 实测）。
            long start = System.currentTimeMillis();
            String args = unwrapArguments(request);
            String targetUrl = httpMethod == HttpMethod.GET ? getUrlWithArgs(url, args) : url;
            RestClient.RequestBodySpec spec = restClient.method(httpMethod)
                    .uri(java.net.URI.create(targetUrl))
                    .contentType(MediaType.APPLICATION_JSON);
            String result = (httpMethod == HttpMethod.GET ? spec : spec.body(args))
                    .retrieve()
                    .body(String.class);
            recordToolCall(config.getName(), args, result, System.currentTimeMillis() - start);
            return result;
        };
        return new ToolHandle(specOf(config.getName(), config.getDescription()), executor);
    }

    /** GET 入参编码：URL 已以 ? 或 = 结尾时直接追加，否则用 input 参数追加。 */
    static String getUrlWithArgs(String url, String args) {
        String encoded = java.net.URLEncoder.encode(args == null ? "" : args,
                java.nio.charset.StandardCharsets.UTF_8);
        if (url.endsWith("?") || url.endsWith("=")) {
            return url + encoded;
        }
        return url + (url.contains("?") ? "&input=" : "?input=") + encoded;
    }

    /**
     * 反射调用内部工具方法（异常语义与 Step3 一致：目标方法异常经原因链展开为
     * IllegalStateException，由工具循环转为错误消息回喂 LLM）。
     */
    private String invokeInternal(Object bean, Method method, String toolName, String jsonArgs) {
        try {
            return (String) method.invoke(bean, jsonArgs);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("内部工具调用失败: " + toolName, e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("内部工具执行失败: " + toolName
                    + " - " + cause.getMessage(), cause);
        }
    }

    /**
     * 反序列化工具入参：LLM 产出的 arguments 是 schema {@code input} 属性的 JSON 值；
     * 单字符串入参约定下为 JSON 字符串字面量（如 {@code "\"你好\""}）——解出原始字符串
     * 后再传给白名单方法 / 外部工具。非字面量（对象、非法 JSON）原样透传。
     */
    private static String unwrapArguments(ToolExecutionRequest request) {
        String raw = request.arguments();
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        try {
            JsonNode node = ARGUMENTS_JSON.readTree(raw);
            if (node.isTextual()) {
                return node.asText();
            }
        } catch (Exception ignored) {
            // 非法 JSON：原样透传（由白名单方法/外部服务自行处理）
        }
        return raw;
    }

    /**
     * 将一次成功的工具调用摘要写入 {@link AgentGraphFactory#TOOL_CALL_RECORDS_BINDING}。
     * 未绑定记录列表时跳过——不抛异常、不记录。
     */
    private void recordToolCall(String toolName, String args, String result, long latencyMs) {
        List<ToolCallRecord> records = AgentGraphFactory.TOOL_CALL_RECORDS_BINDING.get();
        if (records != null) {
            records.add(new ToolCallRecord(toolName, args, result, latencyMs));
        }
    }
}
