package com.sw.ck.iot.mapper;

import com.sw.ck.common.mapper.BaseMapperX;
import com.sw.ck.iot.entity.IotAuditRecord;
import org.apache.ibatis.annotations.Mapper;

/** IoT 行为审计记录 Mapper。 */
@Mapper
public interface IotAuditRecordMapper extends BaseMapperX<IotAuditRecord> {
}
