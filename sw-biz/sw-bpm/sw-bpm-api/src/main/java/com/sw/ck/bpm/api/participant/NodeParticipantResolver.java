package com.sw.ck.bpm.api.participant;

import java.util.List;

/** 人员型节点统一解析 SPI；实现通过稳定 strategy 注册，不接受任意类名或脚本。 */
public interface NodeParticipantResolver {

    String strategy();

    List<String> resolve(NodeParticipantContext context);
}
