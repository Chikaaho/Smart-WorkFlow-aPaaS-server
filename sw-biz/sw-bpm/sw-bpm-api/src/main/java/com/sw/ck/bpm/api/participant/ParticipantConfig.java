package com.sw.ck.bpm.api.participant;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** 人员型节点共享配置。value 的具体形状由 strategy 决定，运行期仍须后端重新校验。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParticipantConfig implements Serializable {

    private String strategy;
    private Object value;
    private String adapterId;
}
