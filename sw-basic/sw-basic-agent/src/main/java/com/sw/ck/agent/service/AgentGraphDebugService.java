package com.sw.ck.agent.service;

import com.sw.ck.agent.dto.AgentGraphBreakpointsReq;
import com.sw.ck.agent.vo.AgentGraphDebugNodeVO;
import com.sw.ck.agent.vo.AgentGraphDebugSessionVO;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;

import java.util.List;
import java.util.Set;

/**
 * 图调试服务接口（M07-F02-04 单步调试）。
 */
public interface AgentGraphDebugService {

    AgentGraphDebugSessionVO createSession(Long graphDefId, String input);

    AgentGraphDebugSessionVO getSession(Long sessionId);

    AgentGraphDebugSessionVO step(Long sessionId, Long expectedVersion);

    default AgentGraphDebugSessionVO step(Long sessionId) {
        return step(sessionId, null);
    }

    AgentGraphDebugSessionVO continueUntilBreakpoint(Long sessionId);

    AgentGraphDebugSessionVO updateBreakpoints(Long sessionId, Set<String> breakpoints);

    AgentGraphDebugSessionVO stop(Long sessionId);

    List<AgentGraphDebugNodeVO> listNodes(Long sessionId);

    PageResult<AgentGraphDebugSessionVO> pageSessions(PageParam pageParam, Long graphDefId);
}
