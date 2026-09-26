package com.sw.ck.openapi.biz.mapper;

import com.sw.ck.common.mapper.BaseMapperX;
import com.sw.ck.openapi.biz.entity.OpenApiCallbackTask;
import org.apache.ibatis.annotations.Mapper;

/** 出站回调持久任务 Mapper（Phase 4 可靠业务事件）。 */
@Mapper
public interface OpenApiCallbackTaskMapper extends BaseMapperX<OpenApiCallbackTask> {
}
