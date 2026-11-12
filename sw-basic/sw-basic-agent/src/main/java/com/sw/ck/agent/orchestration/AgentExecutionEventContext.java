package com.sw.ck.agent.orchestration;

/**
 * 将生命周期执行事件透传到解释器工作线程，不改变既有同步 API。
 * <p>
 * G1-7 起支持节点作用域事件：监听器接收 (type, nodeId, payload)，节点事件带节点 id，
 * 整图级事件（如 MODEL_DELTA）nodeId 为 null。
 * </p>
 */
public final class AgentExecutionEventContext {
    /** 事件监听器：type 事件类型、nodeId 节点 id（可空）、payload 载荷。 */
    @FunctionalInterface
    public interface Listener {
        void accept(String type, String nodeId, String payload);
    }

    private static final InheritableThreadLocal<Listener> CURRENT = new InheritableThreadLocal<>();

    private AgentExecutionEventContext() {
    }

    public static void set(Listener listener) {
        CURRENT.set(listener);
    }

    public static Listener get() {
        return CURRENT.get();
    }

    /** 整图级事件（无节点作用域）。 */
    public static void emit(String type, String payload) {
        Listener listener = CURRENT.get();
        if (listener != null) listener.accept(type, null, payload);
    }

    /** 节点作用域事件（G1-7：NODE_STARTED / NODE_FINISHED 等）。 */
    public static void emitNode(String type, String nodeId, String payload) {
        Listener listener = CURRENT.get();
        if (listener != null) listener.accept(type, nodeId, payload);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
