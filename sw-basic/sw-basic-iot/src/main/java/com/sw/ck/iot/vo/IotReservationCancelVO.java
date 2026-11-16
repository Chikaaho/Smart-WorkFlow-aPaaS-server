package com.sw.ck.iot.vo;

import lombok.Data;

/**
 * 预约取消结果：CANCELED 或 NOT_CANCELLABLE。
 */
@Data
public class IotReservationCancelVO {

    private String outcome;
}
