package com.gpr.payroll.service;

import com.gpr.common.entity.EmploymentContract;
import com.gpr.payroll.entity.PayrollSetup;
import com.gpr.payroll.repository.EmploymentContractRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Works out what an employee is actually paid.
 *
 * <p><b>The contract wins.</b> {@code EmploymentContract.salaryAmount} is the negotiated, per-person
 * figure agreed at hiring — the legally binding one. The job position's {@link PayrollSetup} carries
 * a <em>salary grade</em>: the standard rate for that role, useful as a default and for budgeting,
 * but not what any individual agreed to. Payroll previously paid the grade, so two people in one
 * position were paid identically no matter what they signed, and anyone whose position lacked a
 * setup couldn't be paid at all despite having a valid contract.
 *
 * <p>Everything else on the setup — allowances, named deductions, overtime rate, cutoff — is
 * genuinely role-level and stays there.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SalaryResolver {

    /** Same 22 days × 8h basis the overtime and unpaid-leave calculators use. */
    private static final BigDecimal HOURS_PER_MONTH = new BigDecimal("176");
    private static final BigDecimal WEEKS_PER_MONTH = new BigDecimal("4.333");
    private static final BigDecimal DAYS_PER_MONTH = new BigDecimal("22");
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private final EmploymentContractRepository contractRepo;

    /** Monthly pay plus where it came from — the source is surfaced in the run preview. */
    public record Resolved(BigDecimal monthly, String source, String problem) {
        public boolean payable() {
            return problem == null;
        }
    }

    /**
     * The employee's monthly pay for a period.
     *
     * @param setup may be null — a position without a payroll setup no longer blocks payment, it
     *     just means no allowances and no named deductions.
     */
    public Resolved resolve(Long userId, PayrollSetup setup, LocalDate periodEnd) {
        List<EmploymentContract> contracts = contractRepo.findInForce(userId, periodEnd);
        EmploymentContract contract = contracts.isEmpty() ? null : contracts.get(0);

        if (contract != null && contract.getSalaryAmount() != null
                && contract.getSalaryAmount().compareTo(BigDecimal.ZERO) > 0) {

            String period = contract.getSalaryPeriod() == null
                    ? "monthly"
                    : contract.getSalaryPeriod().trim().toLowerCase();

            // A project fee isn't a periodic salary: running it through SSS, PhilHealth and
            // withholding tax as if it were monthly produces meaningless figures.
            if (period.startsWith("fixed")) {
                return new Resolved(BigDecimal.ZERO, "contract",
                        "Fixed-price contract — not paid through payroll runs");
            }

            BigDecimal monthly = toMonthly(contract.getSalaryAmount(), period);
            return new Resolved(monthly, "Contract (" + period + ")", null);
        }

        BigDecimal grade = setup == null ? null : setup.getBaseSalary();
        if (grade != null && grade.compareTo(BigDecimal.ZERO) > 0) {
            return new Resolved(grade, "Position salary grade", null);
        }

        return new Resolved(BigDecimal.ZERO, null,
                "No salary on the employment contract or the position");
    }

    /** Normalise a contract's stated period to the monthly basis statutory deductions assume. */
    private static BigDecimal toMonthly(BigDecimal amount, String period) {
        BigDecimal monthly = switch (period) {
            case "hourly" -> amount.multiply(HOURS_PER_MONTH);
            case "daily" -> amount.multiply(DAYS_PER_MONTH);
            case "weekly" -> amount.multiply(WEEKS_PER_MONTH);
            case "semi-monthly", "semi_monthly", "semimonthly" -> amount.multiply(TWO);
            case "yearly", "annual" -> amount.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
            // "monthly" and anything unrecognised are taken at face value — the safest reading, and
            // the offer form's own default.
            default -> amount;
        };
        return monthly.setScale(2, RoundingMode.HALF_UP);
    }
}
