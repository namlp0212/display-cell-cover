package com.example.cellcover.repository;

import com.example.cellcover.entity.CatDepartment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CatDepartmentRepository extends JpaRepository<CatDepartment, Long> {

    Optional<CatDepartment> findByDeptCode(String deptCode);

    List<CatDepartment> findByParentId(Long parentId);
}
