package com.gpr.payroll.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Deducts approved unpaid leave from a payslip.
 *
 * <p>Which leave types are unpaid is a company decision, held on {@code leave_policies.paid} and
 * edited in Config → Leave. LWOP seeds unpaid; everything else seeds paid. Reading the flag rather
 * than hard-coding the type means a company that treats, say, emergency leave as unpaid gets the
 * deduction without a code change.
 *
 * <p>Queried directly against the shared {@code workos} database — wos-hr owns these tables, but
 * both services share the schema, and the alternative (an HTTP call per employee per run) would be
 * far heavier for a batch job.
 *
 * <p>Half-days are honoured: {@code leave_requests.days} is already fractional, so a HALF_AM day
 * deducts 0.5.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnpaidLeaveCalculator {

    /**
     * Same basis {@link OvertimePayCalculator} uses to derive an hourly rate — 22 working days a
     * month. Deriving both from one constant keeps a day's pay and eight hours' pay identical.
     */
    private static final BigDecimal DAYS_PER_MONTH = new BigDecimal("22");

    /**
     * Approved leave overlapping the period whose type the company marks unpaid.
     *
     * <p>A leave row has no company column, so the policy join is what scopes this to the right
     * tenant: {@code leave_policies} is per company, and only that company's rows can match.
     */
    private static final String UNPAID_DAYS_SQL =
            """
            SELECT COALESCE(SUM(lr.days), 0)
            FROM leave_requests lr
            JOIN leave_policies lp
              ON lp.leave_type = lr.leave_type
             AND lp.company_id = ?
            WHERE lr.user_id = ?
              AND lr.status = 'APPROVED'
              AND lr.deleted_at IS NULL
              AND lp.paid = false
              AND lp.is_enabled = true
              AND lr.start_date <= ?
              AND lr.end_date   >= ?
            """;

    private final JdbcTemplate jdbc;

    /** Deduction amount and the day count behind it. */
    public record Result(BigDecimal amount, double days) {
        public static Result none() {
            return new Result(BigDecimal.ZERO, 0);
        }
    }

    public Result compute(
            Long userId,
            Long companyId,
            BigDecimal monthlySalary,
            LocalDate periodStart,
            LocalDate periodEnd) {
        if (userId == null || companyId == null || monthlySalary == null) return Result.none();
        if (monthlySalary.compareTo(BigDecimal.ZERO) <= 0) return Result.none();

        double days;
        try {
            Double sum = jdbc.queryForObject(
                    UNPAID_DAYS_SQL, Double.class, companyId, userId, periodEnd, periodStart);
            days = sum == null ? 0 : sum;
        } catch (Exception e) {
            // Never fail a payroll run over this: a missing deduction is visible and correctable,
            // a crashed run blocks everyone's pay.
            log.warn("Unpaid-leave lookup failed for user {} in company {}: {}", userId, companyId, e.toString());
            return Result.none();
        }
        if (days <= 0) return Result.none();

        BigDecimal daily = monthlySalary.divide(DAYS_PER_MONTH, 10, RoundingMode.HALF_UP);
        BigDecimal amount =
                daily.multiply(BigDecimal.valueOf(days)).setScale(2, RoundingMode.HALF_UP);

        log.info(
                "Unpaid leave for user {}: {} day(s) → {} deducted ({}–{})",
                userId, days, amount, periodStart, periodEnd);
        return new Result(amount, days);
    }
}
