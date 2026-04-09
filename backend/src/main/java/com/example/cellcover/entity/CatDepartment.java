package com.example.cellcover.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "cat_department")
public class CatDepartment {

    @Id
    @Column(name = "DEPT_ID")
    private Long deptId;

    @Column(name = "DEPT_CODE", length = 50)
    private String deptCode;

    @Column(name = "DEPT_NAME", length = 255)
    private String deptName;

    @Column(name = "PARENT_ID")
    private Long parentId;

    @Column(name = "DEPARTMENT_LEVEL")
    private int departmentLevel;

    @Column(name = "ROW_STATUS")
    private int rowStatus;

    @Column(name = "UPDATE_TIME")
    private LocalDateTime updateTime;

    public Long getDeptId() { return deptId; }
    public void setDeptId(Long deptId) { this.deptId = deptId; }

    public String getDeptCode() { return deptCode; }
    public void setDeptCode(String deptCode) { this.deptCode = deptCode; }

    public String getDeptName() { return deptName; }
    public void setDeptName(String deptName) { this.deptName = deptName; }

    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }

    public int getDepartmentLevel() { return departmentLevel; }
    public void setDepartmentLevel(int departmentLevel) { this.departmentLevel = departmentLevel; }

    public int getRowStatus() { return rowStatus; }
    public void setRowStatus(int rowStatus) { this.rowStatus = rowStatus; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
