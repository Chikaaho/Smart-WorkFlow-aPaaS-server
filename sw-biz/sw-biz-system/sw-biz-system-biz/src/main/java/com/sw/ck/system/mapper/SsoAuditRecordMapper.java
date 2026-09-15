package com.sw.ck.system.mapper;

import com.sw.ck.common.mapper.BaseMapperX;
import com.sw.ck.system.entity.SsoAuditRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * SSO 审计 Mapper（I5）。按租户隔离。
 */
@Mapper
public interface SsoAuditRecordMapper extends BaseMapperX<SsoAuditRecord> {
}
