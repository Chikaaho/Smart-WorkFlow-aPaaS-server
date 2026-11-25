package com.sw.ck.form.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.response.R;
import com.sw.ck.form.entity.FormDefEntity;
import com.sw.ck.form.mapper.FormDefMapper;
import com.sw.ck.form.service.FormGraphActionService;
import com.sw.ck.form.service.FormGraphActionService.ButtonResult;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import lombok.extern.log4j.Log4j2;

/**
 * 表单「调用 Graph」按钮动作入口（1.2.0 P67 / E1-6）。
 *
 * <p>表单运行页按钮触发：以当前表单字段值作为入参在服务端同步执行 Graph，
 * 返回出参回填字段映射（field → value）。失败以业务异常（R.fail）返回，
 * 前端只提示原因、不清空已填内容。提交前触发不经本入口——在提交链
 * （FormSubmitService.submitForm）内执行并阻止失败提交。</p>
 */
@RestController
@RequestMapping("/form/data")
@Log4j2
public class FormGraphActionController {

    @Autowired
    private FormDefMapper formDefMapper;
    @Autowired
    private FormGraphActionService formGraphActionService;

    /** 按钮 Graph 动作请求体：fieldValues=当前表单字段值；recordId=编辑已有记录时携带。 */
    public record GraphActionRequest(Long recordId, Map<String, Object> fieldValues) {
    }

    @PostMapping("/{formKey}/graph-actions/{actionId}")
    @PreAuthorize("@ss.hasPermi('form:data:submit')")
    public R<ButtonResult> runGraphAction(@PathVariable("formKey") String formKey,
                                           @PathVariable("actionId") String actionId,
                                           @RequestBody GraphActionRequest request) {
        FormDefEntity formDef = formDefMapper.selectOne(Wrappers.lambdaQuery(FormDefEntity.class)
                .eq(FormDefEntity::getFormKey, formKey).last("LIMIT 1"));
        if (formDef == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND, "表单不存在: " + formKey);
        }
        // F1-type：类型不符的输出不回填，warnings 随响应提示（不写不合法值）
        ButtonResult result = formGraphActionService.runButton(formDef, actionId,
                request == null ? null : request.recordId(),
                request == null ? Map.of() : request.fieldValues());
        log.info("Graph 按钮动作执行: formKey={}, action={}, outputs={}, warnings={}",
                formKey, actionId, result.outputs().keySet(), result.warnings());
        return R.ok(result);
    }
}
