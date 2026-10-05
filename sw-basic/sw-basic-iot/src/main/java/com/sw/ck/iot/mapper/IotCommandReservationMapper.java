package com.sw.ck.iot.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sw.ck.common.mapper.BaseMapperX;
import com.sw.ck.iot.entity.IotCommandReservation;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * IoT 命令预约 Mapper（P63）。
 */
@Mapper
public interface IotCommandReservationMapper extends BaseMapperX<IotCommandReservation> {

    default IotCommandReservation selectByInstance(Long tenantId, String processInstanceId) {
        return selectOne(new LambdaQueryWrapper<IotCommandReservation>()
                .eq(IotCommandReservation::getTenantId, tenantId)
                .eq(IotCommandReservation::getProcessInstanceId, processInstanceId)
                .eq(IotCommandReservation::getDeleted, 0)
                .last("LIMIT 1"));
    }

    default List<IotCommandReservation> selectDuePending(LocalDateTime nowExclusive, LocalDateTime windowEnd) {
        return selectList(new LambdaQueryWrapper<IotCommandReservation>()
                .eq(IotCommandReservation::getStatus, "PENDING")
                .gt(IotCommandReservation::getDueAtUtc, nowExclusive)
                .le(IotCommandReservation::getDueAtUtc, windowEnd)
                .eq(IotCommandReservation::getDeleted, 0)
                .orderByAsc(IotCommandReservation::getDueAtUtc)
                .last("LIMIT 50"));
    }

    default List<IotCommandReservation> selectExpiredPending(LocalDateTime windowEnd) {
        return selectList(new LambdaQueryWrapper<IotCommandReservation>()
                .eq(IotCommandReservation::getStatus, "PENDING")
                .le(IotCommandReservation::getDueAtUtc, windowEnd)
                .eq(IotCommandReservation::getDeleted, 0)
                .orderByAsc(IotCommandReservation::getDueAtUtc)
                .last("LIMIT 100"));
    }
}
