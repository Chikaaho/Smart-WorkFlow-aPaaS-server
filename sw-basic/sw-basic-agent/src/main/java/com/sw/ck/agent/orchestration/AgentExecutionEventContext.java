package com.sw.ck.agent.orchestration;

import java.util.function.BiConsumer;

/** 将生命周期执行事件透传到解释器工作线程，不改变既有同步 API。 */
public final class AgentExecutionEventContext {
    private static final InheritableThreadLocal<BiConsumer<String, String>> CURRENT =
            new InheritableThreadLocal<>();

    private AgentExecutionEventContext() {
    }

    public static void set(BiConsumer<String, String> listener) {
        CURRENT.set(listener);
    }

    public static BiConsumer<String, String> get() {
        return CURRENT.get();
    }

    public static void emit(String type, String payload) {
        BiConsumer<String, String> listener = CURRENT.get();
        if (listener != null) listener.accept(type, payload);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
