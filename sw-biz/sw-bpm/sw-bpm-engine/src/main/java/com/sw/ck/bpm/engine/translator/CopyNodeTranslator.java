package com.sw.ck.bpm.engine.translator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** 抄送节点：统一参与人解析后经 NotifyFacade 发送站内信。 */
@Component
public class CopyNodeTranslator extends ServiceTaskNodeTranslator {
    public CopyNodeTranslator(ObjectMapper objectMapper) { super(objectMapper); }
    @Override public String type() { return "COPY"; }
    @Override protected String delegateBean() { return "copyNodeDelegate"; }
    @Override protected String nodeName() { return "抄送"; }
}
