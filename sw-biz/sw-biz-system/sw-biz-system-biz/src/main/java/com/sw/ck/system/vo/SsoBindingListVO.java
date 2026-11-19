package com.sw.ck.system.vo;

import lombok.Data;


import java.util.List;

/**
 * 当前用户 SSO 绑定列表。
 */
@Data
public class SsoBindingListVO {

    private List<SsoBindingItemVO> bindings;
}
