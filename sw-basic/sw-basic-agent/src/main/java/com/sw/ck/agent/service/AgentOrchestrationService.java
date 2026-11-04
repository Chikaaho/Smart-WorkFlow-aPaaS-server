package com.sw.ck.agent.service;

import com.sw.ck.agent.dto.AgentOrchestrationRunReqDTO;
import com.sw.ck.agent.dto.AgentOrchestrationRunRespDTO;

/**
 * 编排执行引擎 Service（M07 Step2）。
 * <p>
 * 职责链：加载配置 → 解密 API Key → {@code AgentModelRuntimeService} 解析并复用 LangChain4j 模型客户端 →
 * 绑定到最小编排图执行 → 提取输出；模型服务不可达/协议不支持等异常转为
 * {@code success=false} 响应而非抛 500。
 * </p>
 */
public interface AgentOrchestrationService {

    /**
     * 执行一次编排（单轮同步调用）。
     *
     * @param req 配置 id + 用户输入（Service 层校验非空）
     * @return success + output / errorMessage + latencyMs
     */
    AgentOrchestrationRunRespDTO run(AgentOrchestrationRunReqDTO req);
}
