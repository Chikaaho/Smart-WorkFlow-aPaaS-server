package com.sw.ck.bpm.process.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 催办响应。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UrgeRespVO {

    /** 结果：ACCEPTED / COOLDOWN / REJECTED */
    private String result;

    /** 说明（冷却剩余秒数、拒绝原因、通知目标等） */
    private String detail;

    /** 催办记录 ID */
    private Long recordId;
}
