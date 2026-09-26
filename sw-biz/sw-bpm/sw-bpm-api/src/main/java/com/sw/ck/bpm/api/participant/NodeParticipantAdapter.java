package com.sw.ck.bpm.api.participant;

import java.util.List;
import java.util.Optional;

/** 后端受控参与人适配器 SPI；id 是稳定业务标识，不是 Bean 名或类名。 */
public interface NodeParticipantAdapter {

    /**
     * 适配器稳定业务标识。
     *
     * @return present = 标识；当前契约恒 present（注册期契约，不得缺省）
     */
    Optional<String> id();

    /**
     * 解析参与人。
     *
     * @return present = 解析结果（解析成功但无参与人时为空列表，交由注册失败策略处置）；
     *         当前契约恒 present，解析失败抛明确异常
     */
    Optional<List<String>> resolve(NodeParticipantContext context);
}
