package com.sw.ck.openapi.biz.dto;

import com.sw.ck.openapi.biz.entity.OpenApiCallbackLog;
import lombok.Data;

import java.util.List;

/**
 * 出站回调投递记录查询结果。
 */
@Data
public class OpenApiCallbackRecordsDTO {

    private List<OpenApiCallbackLog> records;
}
