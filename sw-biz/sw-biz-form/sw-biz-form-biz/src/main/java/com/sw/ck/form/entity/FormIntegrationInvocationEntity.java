package com.sw.ck.form.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 表单受控集成写副作用调用账本。
 * <p>
 * 仅 {@code WRITE} 能力落行：以（租户、表单、版本、能力、阶段、提交标识、主体）为稳定身份，
 * 成功后重放返回原结果，不重复外部副作用；失败保留错误并允许同一标识重试。
 * {@code QUERY} 调用不落该表，只记录服务端调用日志。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_form_integration_invocation")
public class FormIntegrationInvocationEntity extends FormBaseEntity {

    private String formKey;

    private Integer formVersion;

    private String capabilityId;

    /** BEFORE_SUBMIT / AFTER_SUBMIT */
    private String phase;

    /** 稳定提交/动作标识（afterSubmit 使用记录 ID 或草稿提交记录 ID）。 */
    private String requestKey;

    /** 请求输入 SHA-256；同标识不同载荷必须拒绝，不能返回旧结果。 */
    private String requestHash;

    private Long subjectId;

    /** PROCESSING / SUCCEEDED / FAILED */
    private String status;

    private String resultJson;

    private String errorMessage;

    private Long latencyMs;
}
