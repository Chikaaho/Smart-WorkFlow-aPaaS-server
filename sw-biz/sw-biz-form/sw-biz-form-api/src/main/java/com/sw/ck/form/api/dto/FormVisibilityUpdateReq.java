package com.sw.ck.form.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/** 表单业务发起可见范围更新请求。空 userIds 表示当前租户内全部用户。 */
@Data
public class FormVisibilityUpdateReq implements Serializable {

    /** 允许发起该表单的用户 ID；仅支持显式用户范围。 */
    private List<Long> userIds;
}
