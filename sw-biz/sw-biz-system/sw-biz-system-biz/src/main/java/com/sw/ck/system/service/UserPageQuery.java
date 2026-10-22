package com.sw.ck.system.service;
import lombok.Data;
@Data
public class UserPageQuery {
    private String keyword;
    private Integer status;
    private Long deptId;
    private Long postId;
    private Long roleId;
    private Long tenantId;
}
