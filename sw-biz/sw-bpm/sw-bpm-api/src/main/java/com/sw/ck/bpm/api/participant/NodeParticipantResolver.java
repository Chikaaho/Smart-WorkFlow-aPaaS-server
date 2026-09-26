package com.sw.ck.bpm.api.participant;

import java.util.List;
import java.util.Optional;

/** 人员型节点统一解析 SPI；实现通过稳定 strategy 注册，不接受任意类名或脚本。 */
public interface NodeParticipantResolver {

    /**
     * 解析策略稳定标识。
     *
     * @return present = 策略标识；当前契约恒 present（注册期契约，不得缺省）
     */
    Optional<String> strategy();

    /**
     * 解析参与人。
     *
     * @return present = 解析结果（解析成功但无参与人时为空列表，交由注册失败策略处置）；
     *         当前契约恒 present，解析失败抛明确异常
     */
    Optional<List<String>> resolve(NodeParticipantContext context);
}
