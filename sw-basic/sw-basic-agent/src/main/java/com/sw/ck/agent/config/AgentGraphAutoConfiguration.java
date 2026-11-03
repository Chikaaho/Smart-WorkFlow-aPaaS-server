package com.sw.ck.agent.config;

import com.sw.ck.agent.orchestration.AgentGraphFactory;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * AI 智能助手自动配置（LangGraph4j 调度图编排，M07 Step2 落地）。
 * <p>
 * 默认关闭，通过 {@code sw.agent.enabled=true} 开启（与 {@link AgentModelAutoConfiguration}
 * 同一开关）。本类在 Step2 起不再为空壳，注册两个 Bean：
 * </p>
 * <ul>
 *   <li>{@code agentCompiledGraph}：最小单节点 {@code StateGraph<AgentState>}（
 *       START → callModel → END）编译产物，编排执行引擎的单例图</li>
 * </ul>
 * <p>
 * F1 阶段 1：模型装配改为 {@code AgentModelRuntimeService}（@Service，经组件扫描注册，
 * LangChain4j 三格式模板 + 按"模型 id + 修订号"单例复用），本类不再注册 ChatModelFactory。
 * Bean 为手动构造（非 {@code @Component} 扫描）：本文件不新增
 * {@code @ComponentScan}，Service/Controller 由 {@link AgentModelAutoConfiguration}
 * 已覆盖的 {@code com.sw.ck.agent.service} / {@code com.sw.ck.agent.controller}
 * 子包扫描。
 * </p>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "sw.agent", name = "enabled", havingValue = "true")
public class AgentGraphAutoConfiguration {

    @Bean
    public CompiledGraph<AgentState> agentCompiledGraph() throws GraphStateException {
        return new AgentGraphFactory().buildGraph();
    }
}
