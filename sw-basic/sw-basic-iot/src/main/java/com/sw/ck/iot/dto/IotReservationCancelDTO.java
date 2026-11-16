package com.sw.ck.iot.dto;

import lombok.Data;

/**
 * 预约取消结果：CANCELED 或 NOT_CANCELLABLE。
 */
@Data
public class IotReservationCancelDTO {

    private String outcome;
}
