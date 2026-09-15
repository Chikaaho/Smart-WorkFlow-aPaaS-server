package com.sw.ck.system.api.dept;

import java.io.Serializable;

/**
 * 部门选项 DTO（不含敏感字段）。
 */
public class DeptOptionDTO implements Serializable {

    private Long id;
    private String deptName;
    private Long parentId;
    private Integer status;

    public DeptOptionDTO() {
    }

    public DeptOptionDTO(Long id, String deptName, Long parentId, Integer status) {
        this.id = id;
        this.deptName = deptName;
        this.parentId = parentId;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDeptName() {
        return deptName;
    }

    public void setDeptName(String deptName) {
        this.deptName = deptName;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
