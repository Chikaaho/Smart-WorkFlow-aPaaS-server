package com.sw.ck.form.api.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 表单归属调整请求（应用中心）。
 * <p>
 * 变更归属是显式操作：非空目标必须是当前租户内的启用应用；目录必须属于该应用。
 * 失败保持原归属不变。
 * </p>
 */
@Data
public class FormAppMembershipReq implements Serializable {

    /** 目标应用 ID（必填；null/空拒绝） */
    private String appId;

    /** 目标目录 ID（可空=直接归属应用；非空时目录须属于目标应用） */
    private String dirId;
}
