package com.sw.ck.agent.orchestration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.sw.ck.agent.entity.AgentModel;
import com.sw.ck.agent.entity.AgentProvider;
import com.sw.ck.agent.mapper.AgentModelMapper;
import com.sw.ck.agent.mapper.AgentProviderMapper;
import com.sw.ck.common.crypto.AesGcmCipher;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentModelRuntimeStreamingRotationTest {

    @Test
    void rotatesExactlyOnceOnPreDelta429AndLocksFirstKey() {
        initTableInfo();
        AgentModelMapper models = mock(AgentModelMapper.class);
        AgentModelRuntimeService runtime = spy(new AgentModelRuntimeService(
                models, mock(AgentProviderMapper.class), new AesGcmCipher("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")));
        AgentModel first = model(1L, "fixed-two-keys", 17);
        AgentModel second = model(2L, "fixed-two-keys", 17);
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        doReturn(new AgentModelRuntimeService.StreamingModelInstance(first, provider(),
                failingBeforeDelta(firstCalls, new HttpException(429, "quota"))))
                .when(runtime).acquireStreaming(1L);
        doReturn(new AgentModelRuntimeService.StreamingModelInstance(second, provider(),
                successful(secondCalls, "rotated-ok")))
                .when(runtime).acquireStreaming(2L);
        doReturn(second).when(runtime).nextCandidate(eq("fixed-two-keys"), any(), any(LocalDateTime.class));

        StringBuilder deltas = new StringBuilder();
        runtime.chatStreamingWithRotation(1L, List.of(UserMessage.from("hello")), null, deltas::append);

        assertThat(firstCalls).hasValue(1);
        assertThat(secondCalls).hasValue(1);
        assertThat(deltas).hasToString("rotated-ok");
        verify(models, times(1)).update(isNull(), any());
        verify(runtime, times(1)).nextCandidate(eq("fixed-two-keys"), any(), any(LocalDateTime.class));
    }

    @Test
    void neverRotatesOrReplaysAfterFirstVisibleDelta() {
        AgentModelMapper models = mock(AgentModelMapper.class);
        AgentModelRuntimeService runtime = spy(new AgentModelRuntimeService(
                models, mock(AgentProviderMapper.class), new AesGcmCipher("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")));
        AgentModel first = model(1L, "fixed-two-keys", 17);
        AtomicInteger calls = new AtomicInteger();
        doReturn(new AgentModelRuntimeService.StreamingModelInstance(first, provider(),
                failingAfterDelta(calls, "visible", new HttpException(429, "quota"))))
                .when(runtime).acquireStreaming(1L);
        StringBuilder deltas = new StringBuilder();

        assertThatThrownBy(() -> runtime.chatStreamingWithRotation(
                1L, List.of(UserMessage.from("hello")), null, deltas::append))
                .isInstanceOf(HttpException.class);

        assertThat(calls).hasValue(1);
        assertThat(deltas).hasToString("visible");
        verify(models, never()).update(any(), any());
        verify(runtime, never()).nextCandidate(any(), any(), any());
    }

    private StreamingChatModel failingBeforeDelta(AtomicInteger calls, RuntimeException error) {
        StreamingChatModel model = mock(StreamingChatModel.class);
        doAnswer(invocation -> {
            calls.incrementAndGet();
            invocation.<StreamingChatResponseHandler>getArgument(1).onError(error);
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        return model;
    }

    private StreamingChatModel failingAfterDelta(AtomicInteger calls, String delta, RuntimeException error) {
        StreamingChatModel model = mock(StreamingChatModel.class);
        doAnswer(invocation -> {
            calls.incrementAndGet();
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onPartialResponse(delta);
            handler.onError(error);
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        return model;
    }

    private StreamingChatModel successful(AtomicInteger calls, String delta) {
        StreamingChatModel model = mock(StreamingChatModel.class);
        doAnswer(invocation -> {
            calls.incrementAndGet();
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onPartialResponse(delta);
            handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(delta)).build());
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        return model;
    }

    private void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "streaming-rotation-test"),
                AgentModel.class);
    }

    private AgentModel model(Long id, String groupKey, int cooldown) {
        AgentModel model = new AgentModel();
        model.setId(id);
        model.setGroupKey(groupKey);
        model.setQuotaCooldownSeconds(cooldown);
        return model;
    }

    private AgentProvider provider() {
        return new AgentProvider();
    }
}
