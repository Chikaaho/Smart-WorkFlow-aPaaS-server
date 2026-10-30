package com.sw.ck.system.api.user;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户候选选项 DTO（脱敏）。
 * <p>
 * 仅供跨模块选择用户场景使用（如流程节点指定审批人），
 * 不含密码、手机号、邮箱等敏感字段。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserOptionDTO implements Serializable {

    /** 用户 ID */
    private Long id;

    /** 登录名 */
    private String username;

    /** 姓名（realName，可为空） */
    private String realName;

    /** 主部门 ID（仅 includeDept 查询时填充；其余场景缺省 null，不扩大默认脱敏面）。 */
    private Long deptId;

    /** 兼容旧构造：默认不携带部门。 */
    public UserOptionDTO(Long id, String username, String realName) {
        this(id, username, realName, null);
    }
}
