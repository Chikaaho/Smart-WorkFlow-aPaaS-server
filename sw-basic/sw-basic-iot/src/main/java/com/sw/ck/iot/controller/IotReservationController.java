package com.sw.ck.iot.controller;

import com.sw.ck.common.response.R;
import com.sw.ck.iot.api.IotCommandReservationFacade;
import com.sw.ck.iot.api.IotCommandReservationFacade.IotReservationView;
import com.sw.ck.security.holder.LoginUserHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * IoT 预约查询与取消入口（P63）。
 * <p>
 * 查询沿 {@code iot:view}；取消为独立最小权限 {@code iot:reservation:cancel}。
 * 已进入下发阶段（DISPATCHING/DISPATCHED）或已取消/过期的预约返回明确不可取消结果，
 * 不假称撤销了设备动作；设备侧结果经 commandId 沿既有命令/回执链回查。
 * </p>
 */
@RestController
@RequestMapping("/iot/reservations")
public class IotReservationController {

    private final IotCommandReservationFacade reservationFacade;

    public IotReservationController(IotCommandReservationFacade reservationFacade) {
        this.reservationFacade = reservationFacade;
    }

    /** 按流程实例回查预约（关联链：流程 → 预约 → 命令 → 回执）。 */
    @GetMapping
    @PreAuthorize("@ss.hasPermi('iot:view')")
    public R<List<IotReservationView>> byInstance(@RequestParam("processInstanceId") String processInstanceId) {
        Long tenantId = LoginUserHolder.get() == null ? null : LoginUserHolder.get().getTenantId();
        return R.ok(reservationFacade.findByProcessInstance(tenantId, processInstanceId).orElse(List.of()));
    }

    /** 单条预约详情（含状态/取消信息/commandId）。 */
    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('iot:view')")
    public R<IotReservationView> detail(@PathVariable Long id) {
        Long tenantId = LoginUserHolder.get() == null ? null : LoginUserHolder.get().getTenantId();
        return reservationFacade.find(tenantId, id)
                .map(R::ok)
                .orElseGet(() -> R.fail(404, "预约不存在"));
    }

    /** 取消待触发预约（独立最小权限；竞争结果确定并留审计）。 */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("@ss.hasPermi('iot:reservation:cancel')")
    public R<Map<String, Object>> cancel(@PathVariable Long id,
                                         @RequestBody CancelRequest request) {
        Long tenantId = LoginUserHolder.get() == null ? null : LoginUserHolder.get().getTenantId();
        Long actorId = LoginUserHolder.get() == null ? null : LoginUserHolder.get().getUserId();
        String outcome = reservationFacade
                .cancel(tenantId, id, actorId, request == null ? null : request.reason())
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "预约不存在"));
        return R.ok(Map.of("outcome", outcome));
    }

    public record CancelRequest(String reason) {
    }
}
