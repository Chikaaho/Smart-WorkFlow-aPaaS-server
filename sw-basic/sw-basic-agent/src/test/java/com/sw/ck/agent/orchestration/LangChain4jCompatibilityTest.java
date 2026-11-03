package com.sw.ck.agent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F1 阶段 1 · D022-01 依赖兼容验证（D022-01）。
 *
 * 验证点：
 * 1. LangChain4j 1.23.0 三种格式模型在 Spring Boot 3.4.4 + jackson-bom 2.22.4 下可构造、可发起请求、可解析响应
 *    （桩服务为本地 HttpServer，不产生外部费用）。
 * 2. 类路径上的 Jackson 版本与 LC4j 编译基线一致（2.22.x），排除 NoSuchMethodError 风险。
 */
class LangChain4jCompatibilityTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            String body = "{\"error\":{\"message\":\"unexpected path " + path + "\",\"type\":\"invalid_request_error\"}}";
            int status = 404;
            if (path.endsWith("/chat/completions")) {
                body = "{\"id\":\"cmpl-1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"stub\","
                        + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
                status = 200;
            } else if (path.endsWith("/responses")) {
                body = "{\"id\":\"resp-1\",\"object\":\"response\",\"created_at\":1,\"status\":\"completed\",\"model\":\"stub\","
                        + "\"output\":[{\"type\":\"message\",\"id\":\"msg-1\",\"role\":\"assistant\",\"status\":\"completed\","
                        + "\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}],"
                        + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1,\"total_tokens\":2}}";
                status = 200;
            } else if (path.endsWith("/v1/messages") || path.endsWith("/messages")) {
                body = "{\"id\":\"msg-1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"stub\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],"
                        + "\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
                status = 200;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void jacksonOnClasspathMatchesLangChain4jBaseline() {
        String version = ObjectMapper.class.getPackage().getImplementationVersion();
        // LC4j 1.23.0 编译基线 2.22.x；经 sw-dependencies 的 jackson-bom 2.22.4 提升后类路径应一致。
        assertThat(version).startsWith("2.22");
    }

    @Test
    void openAiChatCompletionsRoundTrip() {
        ChatModel model = OpenAiChatModel.builder()
                .baseUrl(baseUrl + "/v1")
                .apiKey("stub-key")
                .modelName("stub")
                .build();
        ChatResponse response = model.chat(ChatRequest.builder().messages(UserMessage.from("hi")).build());
        assertThat(response.aiMessage().text()).isEqualTo("ok");
        assertThat(hits.get()).isPositive();
    }

    @Test
    void openAiResponsesRoundTrip() {
        ChatModel model = OpenAiResponsesChatModel.builder()
                .baseUrl(baseUrl + "/v1")
                .apiKey("stub-key")
                .modelName("stub")
                .build();
        ChatResponse response = model.chat(ChatRequest.builder().messages(UserMessage.from("hi")).build());
        assertThat(response.aiMessage().text()).isEqualTo("ok");
        assertThat(hits.get()).isPositive();
    }

    @Test
    void anthropicMessagesRoundTrip() {
        ChatModel model = AnthropicChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey("stub-key")
                .modelName("stub")
                .build();
        ChatResponse response = model.chat(ChatRequest.builder().messages(UserMessage.from("hi")).build());
        assertThat(response.aiMessage().text()).isEqualTo("ok");
        assertThat(hits.get()).isPositive();
    }
}
