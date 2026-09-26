package com.sw.ck.form.api.form;

import com.sw.ck.form.api.dto.FormDefDTO;

import java.util.Optional;

/**
 * 表单定义查询服务 SPI。
 * <p>
 * 工作流模块通过此接口获取表单定义，不直接依赖 form-biz。
 * 实现由 sw-biz-form-biz 提供。
 * </p>
 * <p>
 * 模块内部调用边界统一返回非空 {@link Optional}：empty 表达表单定义不存在
 * （查询目标缺失），权限与状态判定必须以 present 值表达，不得混用。
 * </p>
 */
public interface FormDefinitionService {

    /**
     * 根据 formKey 获取表单定义 JSON。
     *
     * @param formKey 表单业务标识
     * @return present = 表单定义的 JSON 字符串；empty = 该 formKey 无表单定义
     */
    Optional<String> getFormDefinition(String formKey);

    /**
     * 判断表单定义是否存在。
     *
     * @param formKey 表单业务标识
     * @return present = 判定结果（true 存在 / false 不存在，含 formKey 为空的情况）；
     *         当前契约恒 present
     */
    Optional<Boolean> formExists(String formKey);

    /**
     * 根据 formKey 获取表单定义 DTO。
     *
     * @param formKey 表单业务标识
     * @return present = 表单定义 DTO；empty = 该 formKey 无表单定义
     */
    Optional<FormDefDTO> getFormDef(String formKey);

    /**
     * 判断当前登录用户是否可从业务入口发起该已发布表单。
     *
     * @return present = 判定结果（true 可发起 / false 不可发起，含表单不存在、未发布、未登录或无权）；
     *         empty = {@code formKey} 为空白，缺少判定目标
     */
    Optional<Boolean> canCurrentUserInitiate(String formKey);

    /**
     * 判断当前用户是否具备表单 definition 顶层动作权限。
     *
     * @return present = 判定结果（false 含表单不存在或未发布）；empty = {@code formKey} 为空白
     */
    default Optional<Boolean> canCurrentUserPerformAction(String formKey, String action) {
        return canCurrentUserInitiate(formKey);
    }

    /**
     * 判断当前用户是否能访问已发布表单中的指定记录。
     * <p>
     * REFERENCE 字段保存的是目标表单记录 ID；调用方不能只校验 ID 格式，
     * 必须同时满足目标表单已发布、记录存在、未删除且属于当前租户/可见范围。
     * </p>
     *
     * @param formKey  目标表单业务标识
     * @param recordId 目标表单记录 ID
     * @return present = 判定结果（true 当前用户可见且记录存在 / false 不可见或记录不存在）；
     *         empty = formKey 或 recordId 为空白，缺少判定目标
     */
    default Optional<Boolean> canCurrentUserAccessRecord(String formKey, String recordId) {
        return Optional.of(false);
    }
}
