package com.sw.ck.bpm.engine.resolver;

import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.spi.assignee.NodeApproverContext;
import com.sw.ck.bpm.api.spi.assignee.NodeApproverResolver;
import com.sw.ck.bpm.api.spi.assignee.NodeApproverType;
import com.sw.ck.common.exception.BaseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 未支持的审批人类型桩 —— SCRIPT 档位占位者。
 * <p>
 * 证明 Map 分发插槽支持第二档，resolve 直接抛 2201 拒绝执行。
 * 本刀（cut B）不实现任何脚本/表达式求值。
 * </p>
 *
 * <h3>分发 Key</h3>
 * 注册为 {@link NodeApproverType#SCRIPT}。
 */
@Component("unsupportedApproverResolver")
public class UnsupportedApproverResolver implements NodeApproverResolver {

    private static final Logger log = LoggerFactory.getLogger(UnsupportedApproverResolver.class);

    @Override
    public Optional<List<String>> resolve(NodeApproverContext context) {
        log.warn("SCRIPT approver type is not yet implemented: nodeKey={}",
                context.getNodeKey());
        throw new BaseException(BpmErrorCode.APPROVER_TYPE_NOT_IMPLEMENTED);
    }
}
