package com.sw.ck.form.api.form;

import com.sw.ck.form.api.dto.FormDefDTO;

/**
 * 表单定义查询服务 SPI。
 * <p>
 * 工作流模块通过此接口获取表单定义，不直接依赖 form-biz。
 * 实现由 sw-biz-form-biz 提供。
 * </p>
 */
public interface FormDefinitionService {

    /**
     * 根据 formKey 获取表单定义 JSON。
     *
     * @param formKey 表单业务标识
     * @return 表单定义的 JSON 字符串，不存在时返回 null
     */
    String getFormDefinition(String formKey);

    /**
     * 根据 ID 获取表单定义 JSON。
     *
     * @param formId 表单 ID
     * @return 表单定义的 JSON 字符串，不存在时返回 null
     */
    String getFormDefinitionById(String formId);

    /**
     * 判断表单定义是否存在。
     *
     * @param formKey 表单业务标识
     * @return true 如果存在
     */
    boolean formExists(String formKey);

    /**
     * 根据 formKey 获取表单定义 DTO。
     *
     * @param formKey 表单业务标识
     * @return 表单定义 DTO，不存在时返回 null
     */
    FormDefDTO getFormDef(String formKey);

    /**
     * 根据 ID 获取表单定义 DTO。
     *
     * @param formId 表单 ID
     * @return 表单定义 DTO，不存在时返回 null
     */
    FormDefDTO getFormDefById(String formId);

    /** 判断当前登录用户是否可从业务入口发起该已发布表单。 */
    boolean canCurrentUserInitiate(String formKey);
}
