package com.sw.ck.form.dto;

import lombok.Data;

import java.util.Map;

/**
 * 表单运行期受控集成调用请求。
 * <p>
 * 客户端只提交稳定能力标识、绑定表单版本/记录与结构化输入；
 * 目标 URL、HTTP 方法与授权范围全部由服务端按登记数据解析。
 * </p>
 */
@Data
public class FormIntegrationInvokeReq {

    private String formKey;

    /** 绑定发布版本；为空时按当前发布版本解析。 */
    private Integer formVersion;

    /** 已存在记录 ID；提交后阶段必填。 */
    private String recordId;

    /** BEFORE_SUBMIT / AFTER_SUBMIT。 */
    private String phase;

    private String capabilityId;

    /** 写副作用幂等标识；AFTER_SUBMIT 必填，通常为提交后的记录 ID。 */
    private String requestKey;

    private Map<String, Object> input;
}
