package com.sw.ck.system.api.dict;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 字典数据项 DTO。
 * <p>
 * 对应 {@code sys_dict_data} 表中的一条字典条目，
 * 用于跨模块传递字典数据（即字典的"键值对"）。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DictItemDTO implements Serializable {

    /** 字典类型编码（对应 sys_dict_type.code） */
    private String dictType;

    /** 字典值（对应 sys_dict_data.dict_value） */
    private String code;

    /** 字典标签（对应 sys_dict_data.label） */
    private String label;

    /** 排序号 */
    private Integer sort;

    /** 状态：0=正常 1=停用（对应 sys_dict_data.status） */
    private Integer status;

    /** 是否默认：0=否 1=是 */
    private Integer isDefault;

    /** CSS 类名 */
    private String cssClass;

    /** 列表类名 */
    private String listClass;

    /** 备注（对应 sys_dict_data.description） */
    private String description;
}
