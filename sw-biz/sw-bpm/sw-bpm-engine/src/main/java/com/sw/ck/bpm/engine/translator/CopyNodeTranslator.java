package com.sw.ck.bpm.engine.translator;

import org.springframework.stereotype.Component;

import java.util.Optional;

/** 抄送节点：统一参与人解析后经 NotifyFacade 发送站内信。 */
@Component
public class CopyNodeTranslator extends ServiceTaskNodeTranslator {
    @Override public Optional<String> type() { return Optional.of("COPY"); }
    @Override protected String delegateBean() { return "copyNodeDelegate"; }
    @Override protected String nodeName() { return "抄送"; }
}
