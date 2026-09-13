package com.sw.ck.openapi.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sw.ck.openapi.biz.entity.OpenApiIdempotency;
import org.apache.ibatis.annotations.Mapper;

/** OpenApiIdempotency Mapper（I4 开放接口）。 */
@Mapper
public interface OpenApiIdempotencyMapper extends BaseMapper<OpenApiIdempotency> {
}
