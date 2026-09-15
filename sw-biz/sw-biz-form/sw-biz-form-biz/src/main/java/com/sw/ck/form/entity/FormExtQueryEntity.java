package com.sw.ck.form.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 受控外部数据源查询契约实体 — 对应 {@code sw_form_ext_query} 表（I2，方向 §4.3）。
 * <p>
 * 版本化查询契约：表单定义只保存 {@code queryKey + queryVersion + valueField/displayField}
 * 稳定标识，SQL 文本仅存服务端注册表，绝不进入表单 definition、前端或客户端请求。
 * 执行统一经 {@code ExtDatasourceQueryPort} 委托受控引擎。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_form_ext_query")
public class FormExtQueryEntity extends FormBaseEntity {

    /** 外部数据源 ID（指向受控数据源登记表） */
    private Long datasourceId;

    /** 查询契约稳定标识（表单 dsBinding 引用） */
    private String queryKey;

    /** 契约版本号（契约变更即升版本，不原地改写） */
    private Integer queryVersion;

    /** 只读 SELECT SQL 文本（服务端专用，敏感） */
    private String sqlText;

    /** 输出 schema JSON：[{"name":"id","type":"string"},{"name":"label","type":"string"}] */
    private String outputFields;

    /** 是否启用：1=启用 0=停用 */
    private Integer enabled;
}
