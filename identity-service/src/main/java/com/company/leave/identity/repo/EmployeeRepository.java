package com.company.leave.identity.repo;

import com.company.leave.identity.domain.Employee;
import com.company.leave.identity.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EmployeeRepository extends JpaRepository<Employee, Long>, JpaSpecificationExecutor<Employee> {

    Optional<Employee> findByEmployeeCodeIgnoreCase(String employeeCode);

    Optional<Employee> findByEmailIgnoreCase(String email);

    /** Matches employee code or email; the argument must already be lower-cased. */
    @Query("select e from Employee e where lower(e.employeeCode) = :value or lower(e.email) = :value order by e.id")
    List<Employee> findByIdentifier(@Param("value") String value);

    boolean existsByManagerIdAndActiveTrue(Long managerId);

    List<Employee> findByRoleAndActiveTrueOrderByIdAsc(Role role);

    List<Employee> findByManagerIdAndActiveTrueOrderByIdAsc(Long managerId);

    List<Employee> findByDepartmentIgnoreCaseAndActiveTrueOrderByIdAsc(String department);
}
