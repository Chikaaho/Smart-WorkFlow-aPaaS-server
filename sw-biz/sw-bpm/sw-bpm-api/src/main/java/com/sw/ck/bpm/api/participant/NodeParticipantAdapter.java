package com.sw.ck.bpm.api.participant;

import java.util.List;

/** 后端受控参与人适配器 SPI；id 是稳定业务标识，不是 Bean 名或类名。 */
public interface NodeParticipantAdapter {

    String id();

    List<String> resolve(NodeParticipantContext context);
}
