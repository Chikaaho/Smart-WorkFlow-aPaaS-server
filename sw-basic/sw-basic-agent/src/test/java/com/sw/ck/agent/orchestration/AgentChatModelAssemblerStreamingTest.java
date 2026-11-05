package com.sw.ck.agent.orchestration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sw.ck.agent.entity.AgentModel;
import com.sw.ck.agent.entity.AgentProvider;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentChatModelAssemblerStreamingTest {
    private final AgentChatModelAssembler assembler = new AgentChatModelAssembler();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void assemblesAllSupportedStreamingFormats() {
        AgentModel model = model();
        assertThat(assembler.assembleStreaming(provider("OPENAI_CHAT", "https://example.invalid"), model, "test-key"))
                .isInstanceOf(OpenAiStreamingChatModel.class);
        assertThat(assembler.assembleStreaming(provider("OPENAI_RESPONSES", "https://example.invalid"), model, "test-key"))
                .isInstanceOf(OpenAiResponsesStreamingChatModel.class);
        assertThat(assembler.assembleStreaming(provider("ANTHROPIC", "https://example.invalid"), model, "test-key"))
                .isInstanceOf(AnthropicStreamingChatModel.class);
    }

    @Test
    void streamsRealDeltasFromAllThreeHttpProtocols() throws Exception {
        AtomicInteger chatCalls = new AtomicInteger();
        AtomicInteger responsesCalls = new AtomicInteger();
        AtomicInteger anthropicCalls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            chatCalls.incrementAndGet();
            sse(exchange, "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"chat-\"}}]}\n\n"
                    + "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n");
        });
        server.createContext("/v1/responses", exchange -> {
            responsesCalls.incrementAndGet();
            sse(exchange, "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"item_id\":\"i1\",\"output_index\":0,\"content_index\":0,\"delta\":\"responses-ok\"}\n\n"
                    + "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"id\":\"r1\",\"object\":\"response\",\"created_at\":1,\"status\":\"completed\",\"model\":\"test-model\",\"output\":[]}}\n\n");
        });
        server.createContext("/v1/messages", exchange -> {
            anthropicCalls.incrementAndGet();
            sse(exchange, "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\"test-model\",\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}\n\n"
                    + "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                    + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"anthropic-ok\"}}\n\n"
                    + "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                    + "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":1}}\n\n"
                    + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n");
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";

        assertThat(stream("OPENAI_CHAT", baseUrl)).isEqualTo("chat-ok");
        assertThat(stream("OPENAI_RESPONSES", baseUrl)).isEqualTo("responses-ok");
        assertThat(stream("ANTHROPIC", baseUrl)).isEqualTo("anthropic-ok");
        assertThat(List.of(chatCalls.get(), responsesCalls.get(), anthropicCalls.get())).containsExactly(1, 1, 1);
    }

    @Test
    void reportsPartialOutputFailureWithoutReplayingRequest() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(("data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"visible\"}}]}\n\n"
                    + "data: {broken-json}\n\n").getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";

        StreamResult result = streamResult("OPENAI_CHAT", baseUrl);
        assertThat(result.deltas()).containsExactly("visible");
        assertThat(result.error()).isNotNull();
        assertThat(calls).hasValue(1);
    }

    @Test
    void doesNotRetryStreaming429() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            byte[] body = "{\"error\":{\"message\":\"quota\",\"type\":\"rate_limit_error\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";

        assertThat(streamResult("OPENAI_CHAT", baseUrl).error()).isNotNull();
        assertThat(calls).hasValue(1);
    }

    @Test
    void rejectsUnknownStreamingFormatWithoutLeakingKey() {
        assertThatThrownBy(() -> assembler.assembleStreaming(provider("OTHER", "https://example.invalid"), model(), "secret-value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("secret-value");
    }

    private String stream(String format, String baseUrl) throws Exception {
        StreamResult result = streamResult(format, baseUrl);
        assertThat(result.error()).isNull();
        return String.join("", result.deltas());
    }

    private StreamResult streamResult(String format, String baseUrl) throws Exception {
        StreamingChatModel streaming = assembler.assembleStreaming(provider(format, baseUrl), model(), "test-key");
        List<String> deltas = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        streaming.chat("hello", new StreamingChatResponseHandler() {
            @Override public void onPartialResponse(String partialResponse) { deltas.add(partialResponse); }
            @Override public void onCompleteResponse(ChatResponse completeResponse) { done.countDown(); }
            @Override public void onError(Throwable throwable) { error.set(throwable); done.countDown(); }
        });
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        return new StreamResult(deltas, error.get());
    }

    private void sse(HttpExchange exchange, String body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
        exchange.close();
    }

    private AgentProvider provider(String format, String baseUrl) {
        AgentProvider provider = new AgentProvider();
        provider.setApiFormat(format);
        provider.setBaseUrl(baseUrl);
        return provider;
    }

    private AgentModel model() {
        AgentModel model = new AgentModel();
        model.setModelName("test-model");
        model.setTimeoutSeconds(3);
        model.setRetryCount(0);
        model.setMaxTokens(64);
        return model;
    }

    private record StreamResult(List<String> deltas, Throwable error) {}
}
