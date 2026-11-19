package com.sw.ck.system.vo;

import lombok.Data;


/**
 * SSO 配置检查结果（只读，不宣称真实登录已通过）。
 */
@Data
public class SsoConfigCheckVO {

    private boolean configExists;

    private boolean appIdValid;

    private boolean secretUsable;

    private boolean enterpriseIdFormat;

    private boolean enabled;

    private boolean overall;

    private String summary;
}
