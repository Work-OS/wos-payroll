package com.gpr.payroll.controller;

import com.gpr.common.entity.PayrollRun;
import com.gpr.common.entity.PayrollRunStep;
import com.gpr.common.entity.Payslip;
import com.gpr.payroll.service.PayrollService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

/**
 * Payroll runs and payslips.
 *
 * <p>No class-level path on purpose: the service's {@code context-path} is already
 * {@code /api/payroll}, so a {@code @RequestMapping("/payroll")} here would put every endpoint at
 * {@code /api/payroll/payroll/...} — which is what happened, and why these routes 404'd into the
 * catch-all exception handler as 500s. {@code RewardsController} maps {@code /rewards} because that
 * genuinely is a sub-resource; these are the payroll root.
 */
@RestController
@RequiredArgsConstructor
public class PayrollController {
    private final PayrollService payrollService;

    @GetMapping("/stats")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> stats() {
        return ResponseEntity.ok(payrollService.getStats());
    }

    @GetMapping("/runs")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Page<PayrollRun>> getRuns(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(payrollService.getRuns(page, size));
    }

    /**
     * Who a run would cover, what each would be paid for the period, and who'd be skipped and why —
     * all before the run exists. Amounts come from the same computation the run itself uses.
     */
    @GetMapping("/runs/preview")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('PAYROLL_MANAGEMENT:MANAGE_PAYROLL')")
    public ResponseEntity<List<PayrollService.RunCandidate>> previewRun(
            @RequestParam String periodStart, @RequestParam String periodEnd) {
        return ResponseEntity.ok(payrollService.previewCandidates(
                LocalDate.parse(periodStart), LocalDate.parse(periodEnd)));
    }

    /** {@code includedUserIds} omitted or empty covers every eligible employee. */
    @PostMapping("/runs")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('PAYROLL_MANAGEMENT:MANAGE_PAYROLL')")
    public ResponseEntity<PayrollRun> createRun(@RequestBody CreateRunRequest body) {
        return ResponseEntity.ok(payrollService.createRun(
                LocalDate.parse(body.periodStart()),
                LocalDate.parse(body.periodEnd()),
                body.includedUserIds()));
    }

    public record CreateRunRequest(String periodStart, String periodEnd, List<Long> includedUserIds) {}

    @GetMapping("/runs/{id}/steps")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<PayrollRunStep>> getSteps(@PathVariable Long id) {
        return ResponseEntity.ok(payrollService.getSteps(id));
    }

    @PostMapping("/runs/{id}/process")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PayrollRun> processRun(@PathVariable Long id) {
        return ResponseEntity.ok(payrollService.processRun(id));
    }

    @PostMapping("/runs/{id}/release")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PayrollRun> releaseRun(@PathVariable Long id) {
        return ResponseEntity.ok(payrollService.releaseRun(id));
    }

    /**
     * The signed-in employee's own payslips. Separate from the admin listing below so self-service
     * never needs an authority that would also expose everyone else's pay.
     */
    @GetMapping("/payslips/me")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('PAYROLL:VIEW_PAYSLIP')")
    public ResponseEntity<Page<Payslip>> getMyPayslips(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(payrollService.getMyPayslips(userDetails.getUsername(), page, size));
    }

    /** Every payslip in the company — admin listing, hence the management authority. */
    @GetMapping("/payslips")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('PAYROLL_MANAGEMENT:VIEW_ALL_PAYSLIPS')")
    public ResponseEntity<Page<Payslip>> getPayslips(
            @RequestParam(required = false) Long runId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(payrollService.getPayslips(runId, status, search, page, size));
    }

    @GetMapping("/payslips/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Payslip> getPayslip(@PathVariable Long id) {
        return ResponseEntity.ok(payrollService.getPayslip(id));
    }

    @GetMapping("/payslips/{id}/pdf")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> downloadPdf(@PathVariable Long id) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"payslip-" + id + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(payrollService.generatePayslipPdf(id));
    }

    @GetMapping("/payslips/{id}/excel")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> downloadExcel(@PathVariable Long id) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"payslip-" + id + ".xlsx\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(payrollService.generatePayslipExcel(id));
    }
}
