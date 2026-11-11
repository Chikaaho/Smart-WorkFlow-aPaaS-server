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

    /**
     * LIKE 模式（R5 修复）：在应用侧生成 {@code %keyword%}，避免 SQL 里
     * {@code CONCAT('%', ?, '%')} 让 PostgreSQL 无法推断参数类型
     * （could not determine data type of parameter），同时保持各数据库可移植。
     */
    public String getKeywordLike() {
        if (keyword == null) {
            return null;
        }
        String trimmed = keyword.trim();
        return trimmed.isEmpty() ? null : "%" + trimmed + "%";
    }
}
