package com.sw.ck.system.dto;

import lombok.Data;

import java.util.List;

/**
 * 当前用户 SSO 绑定列表。
 */
@Data
public class SsoBindingListDTO {

    private List<SsoBindingItemDTO> bindings;
}
