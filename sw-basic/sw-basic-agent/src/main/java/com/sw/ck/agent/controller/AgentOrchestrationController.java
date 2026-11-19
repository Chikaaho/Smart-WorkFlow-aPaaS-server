package com.sw.ck.agent.controller;

import org.springframework.beans.factory.annotation.Autowired;

import com.sw.ck.agent.dto.AgentOrchestrationRunReqDTO;
import com.sw.ck.agent.vo.AgentOrchestrationRunRespVO;
import com.sw.ck.agent.service.AgentOrchestrationService;
import com.sw.ck.common.response.R;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 编排执行引擎 Controller（M07 Step2）。
 * <p>
 * 权限码与 Step1 的 model 三权限码（view/manage/test）互不重叠，写法对齐仓库
 * {@code @ss.hasPermi(...)} 惯例（ExternalDatasourceController / AgentModelController 同款）。
 * 响应统一 {@code R<T>} 包装。
 * </p>
 */
@RestController
@RequestMapping("/agent/orchestration")
public class AgentOrchestrationController {

    @Autowired
    private AgentOrchestrationService agentOrchestrationService;

    /** 触发一次编排执行（单轮同步调用，模型服务不可达返回 success=false 而非 500） */
    @PostMapping("/run")
    @PreAuthorize("@ss.hasPermi('agent:orchestration:run')")
    public R<AgentOrchestrationRunRespVO> run(@RequestBody @Validated AgentOrchestrationRunReqDTO req) {
        return R.ok(agentOrchestrationService.run(req));
    }
}
