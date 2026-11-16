package com.sw.ck.agent.controller;

import org.springframework.beans.factory.annotation.Autowired;

import com.sw.ck.agent.dto.AgentGraphBreakpointsReq;
import com.sw.ck.agent.dto.AgentGraphDebugCreateReq;
import com.sw.ck.agent.dto.AgentGraphDebugNodeDTO;
import com.sw.ck.agent.dto.AgentGraphDebugSessionDTO;
import com.sw.ck.agent.service.AgentGraphDebugService;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.response.R;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 图单步调试会话 Controller（M07-F02-04 单步调试）。
 * <p>
 * 会话绑定发布版本快照，不随图后续编辑/发布漂移；状态机 PAUSED ↔ 终态
 * (COMPLETED/FAILED/STOPPED/EXPIRED)；重复/并发 step 通过 version 乐观锁 409。
 * </p>
 */
@RestController
@RequestMapping("/agent/graph-debug-sessions")
public class AgentGraphDebugController {

    @Autowired
    private AgentGraphDebugService debugService;

    /** 创建调试会话（沿用 graph-defs 执行权限，manage） */
    @PostMapping
    @PreAuthorize("@ss.hasPermi('agent:model:manage')")
    public R<AgentGraphDebugSessionDTO> create(@RequestBody AgentGraphDebugCreateReq req) {
        return R.ok(debugService.createSession(req.getGraphDefId(), req.getInput()));
    }

    /** 会话列表（view） */
    @GetMapping
    @PreAuthorize("@ss.hasPermi('agent:model:view')")
    public R<PageResult<AgentGraphDebugSessionDTO>> page(PageParam pageParam,
                                                         @RequestParam(required = false) Long graphDefId) {
        return R.ok(debugService.pageSessions(pageParam, graphDefId));
    }

    /** 会话详情（含变量快照、下一位置、版本快照） */
    @GetMapping("/{sessionId}")
    @PreAuthorize("@ss.hasPermi('agent:model:view')")
    public R<AgentGraphDebugSessionDTO> detail(@PathVariable Long sessionId) {
        return R.ok(debugService.getSession(sessionId));
    }

    /** 节点轨迹（按 nodeSeq 升序） */
    @GetMapping("/{sessionId}/nodes")
    @PreAuthorize("@ss.hasPermi('agent:model:view')")
    public R<List<AgentGraphDebugNodeDTO>> nodes(@PathVariable Long sessionId) {
        return R.ok(debugService.listNodes(sessionId));
    }

    /** 单步执行（PAUSED → PAUSED/COMPLETED/FAILED；终态拒绝；重复/并发 409） */
    @PostMapping("/{sessionId}/step")
    @PreAuthorize("@ss.hasPermi('agent:model:manage')")
    public R<AgentGraphDebugSessionDTO> step(@PathVariable Long sessionId,
                                             @RequestParam(required = false) Long expectedVersion) {
        return R.ok(debugService.step(sessionId, expectedVersion));
    }

    /** 继续到断点（PAUSED → PAUSED/COMPLETED/FAILED；断点前暂停） */
    @PostMapping("/{sessionId}/continue")
    @PreAuthorize("@ss.hasPermi('agent:model:manage')")
    public R<AgentGraphDebugSessionDTO> continueToBreakpoint(@PathVariable Long sessionId) {
        return R.ok(debugService.continueUntilBreakpoint(sessionId));
    }

    /** 停止会话（仅 PAUSED → STOPPED） */
    @PostMapping("/{sessionId}/stop")
    @PreAuthorize("@ss.hasPermi('agent:model:manage')")
    public R<AgentGraphDebugSessionDTO> stop(@PathVariable Long sessionId) {
        return R.ok(debugService.stop(sessionId));
    }

    /** 更新断点集合（仅 PAUSED；校验 nodeId 存在性） */
    @PutMapping("/{sessionId}/breakpoints")
    @PreAuthorize("@ss.hasPermi('agent:model:manage')")
    public R<AgentGraphDebugSessionDTO> updateBreakpoints(@PathVariable Long sessionId,
                                                          @RequestBody AgentGraphBreakpointsReq req) {
        return R.ok(debugService.updateBreakpoints(sessionId, req.getBreakpoints()));
    }
}
