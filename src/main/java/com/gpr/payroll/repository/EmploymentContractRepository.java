package com.gpr.payroll.repository;

import com.gpr.common.entity.EmploymentContract;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Read-only access to contracts for pay resolution. wos-hr owns the write side; payroll only needs
 * the negotiated salary.
 */
public interface EmploymentContractRepository extends JpaRepository<EmploymentContract, Long> {

    /**
     * Contracts in force for an employee during a pay period, newest start first.
     *
     * <p>Filtered by {@code startDate <= periodEnd} rather than simply taking the latest contract:
     * a contract signed after the period ended must not retroactively reprice it when a run is
     * reprocessed.
     */
    @Query("""
        SELECT c FROM EmploymentContract c
        WHERE c.deletedAt IS NULL
          AND c.userId = :userId
          AND (c.startDate IS NULL OR c.startDate <= :periodEnd)
        ORDER BY c.startDate DESC
        """)
    List<EmploymentContract> findInForce(
            @Param("userId") Long userId, @Param("periodEnd") LocalDate periodEnd);
}
