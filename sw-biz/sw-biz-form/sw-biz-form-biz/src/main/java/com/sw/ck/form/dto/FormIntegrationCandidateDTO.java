package com.sw.ck.form.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 表单设计器可选择的受控集成能力（不暴露 URL 与 HTTP 方法）。
 */
@Data
@AllArgsConstructor
public class FormIntegrationCandidateDTO {

    private String name;

    private String description;

    private String effectScope;

    private int timeoutSeconds;
}
