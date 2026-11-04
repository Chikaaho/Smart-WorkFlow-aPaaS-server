package com.sw.ck.system.api.dict;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 字典类型详情 DTO（含类型元数据与数据项）。
 * <p>
 * 供应用整包导出（0.2.2 F2）等需要完整字典定义的消费方使用；
 * 仅经由 {@link DictFacade} 提供查询，消费方禁止直查 sys_dict_type / sys_dict_data。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DictTypeDetailDTO implements Serializable {

    /** 字典类型编码（对应 sys_dict_type.code） */
    private String code;

    /** 字典类型名称 */
    private String name;

    /** 状态：0=正常 1=停用 */
    private Integer status;

    /** 备注 */
    private String description;

    /** 数据项（按 sort 升序） */
    private List<DictItemDTO> items;
}
