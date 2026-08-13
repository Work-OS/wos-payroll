package com.gpr.payroll.repository;

import com.gpr.common.entity.Payslip;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PayslipRepository extends JpaRepository<Payslip, Long> {
    /**
     * Optional filters, so a null parameter means "don't filter on this".
     *
     * <p>The CASTs are load-bearing, not decoration. Postgres cannot infer the type of a bare null
     * bind, so it defaults to {@code bytea} and {@code LOWER(CONCAT(…))} fails with
     * "function lower(bytea) does not exist" — a 500 on the unfiltered payslip list, which is the
     * default view. Same trap that hit the request repositories; see the CAST there too.
     */
    @Query("SELECT p FROM Payslip p WHERE " +
           "(CAST(:runId AS long) IS NULL OR p.payrollRun.id = :runId) " +
           "AND (CAST(:status AS string) IS NULL OR p.status = :status) " +
           "AND (CAST(:search AS string) IS NULL " +
           "     OR LOWER(p.employeeName) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) " +
           "     OR LOWER(p.employeeId)   LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))")
    Page<Payslip> search(@Param("runId") Long runId,
                          @Param("status") String status,
                          @Param("search") String search,
                          Pageable pageable);

    List<Payslip> findByPayrollRunId(Long runId);

    /**
     * One employee's own payslips, newest first. Only released runs — a payslip from a draft or
     * merely generated run is still being worked on and must not be visible to the employee.
     */
    @Query("""
        SELECT p FROM Payslip p
        WHERE p.employeeId = :employeeId
          AND p.payrollRun.status = 'released'
        ORDER BY p.periodEnd DESC
        """)
    Page<Payslip> findMine(@Param("employeeId") String employeeId, Pageable pageable);

    @Query("SELECT COALESCE(SUM(p.incentives),0) FROM Payslip p WHERE p.payrollRun.status = 'released'")
    BigDecimal sumReleasedIncentives();

    @Query("SELECT COALESCE(SUM(p.basicSalary),0) FROM Payslip p WHERE p.payrollRun.status = 'released'")
    BigDecimal sumReleasedBasicSalary();

    @Query("SELECT COALESCE(SUM(p.absences),0) FROM Payslip p WHERE p.payrollRun.status = 'released'")
    BigDecimal sumReleasedAbsences();

    @Query("SELECT COALESCE(SUM(p.latePenalties),0) FROM Payslip p WHERE p.payrollRun.status = 'released'")
    BigDecimal sumReleasedLatePenalties();

    @Query("SELECT COALESCE(SUM(p.cashAdvances),0) FROM Payslip p WHERE p.payrollRun.status = 'released'")
    BigDecimal sumReleasedCashAdvances();

    @Query("SELECT COALESCE(SUM(p.sss + p.philhealth + p.pagibig + p.tax),0) FROM Payslip p WHERE p.payrollRun.status = 'released'")
    BigDecimal sumReleasedStatutoryDeductions();
}
