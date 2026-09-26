package com.sw.ck.iot.api;

import java.util.Optional;

/**
 * IoT 设备查询门面（bpm-process → iot 只读）。
 *
 * <h3>present / empty 两层语义</h3>
 * <ul>
 *   <li><b>present</b>：查询合法执行且命中设备，值为设备业务标识。</li>
 *   <li><b>empty</b>：目标设备不存在（查询目标缺失）。空字符串不构成 present。</li>
 * </ul>
 */
public interface IotDeviceQueryFacade {

    /**
     * 按主键查询设备业务标识（跨租户由调用方约束）。
     *
     * @param deviceId 设备主键
     * @return 设备业务标识；设备不存在时返回 {@code Optional.empty()}
     */
    Optional<String> getDeviceKeyById(Long deviceId);
}
