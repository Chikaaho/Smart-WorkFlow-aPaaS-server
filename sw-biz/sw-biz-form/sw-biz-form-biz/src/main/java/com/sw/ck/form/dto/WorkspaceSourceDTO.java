package com.sw.ck.form.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 工作台数据组件可关联的已发布表单。
 * <p>
 * appId/appName 仅当前账号可见应用时回显；recordCount 为同一可见口径的记录数。
 * </p>
 */
@Data
public class WorkspaceSourceDTO {

    private String formKey;

    private String name;

    private Integer formVersion;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String appId;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String appName;

    private long recordCount;
}
