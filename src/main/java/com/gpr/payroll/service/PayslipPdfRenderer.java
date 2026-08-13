package com.gpr.payroll.service;

import com.gpr.common.entity.Payslip;
import com.gpr.common.entity.PayslipLine;
import com.gpr.common.entity.PayslipOvertimeLine;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Renders a payslip PDF by walking the company's configured template.
 *
 * <p>This is the Java counterpart of wos-ui's {@code payslip-figures.ts} + {@code BlockView}: the
 * same block types, the same section projection, the same "omit an empty section" rule — so what an
 * admin arranges in Configure → Communications → Payslip format is what the employee downloads.
 * Keeping the two in step is a real maintenance cost, but the alternative is a preview that lies.
 *
 * <p>When no layout is available (template service down, or an unrecognised layout) the caller
 * falls back to {@link #renderFallback}, which prints the same figures in a plain arrangement. A
 * payslip that cannot be downloaded is worse than one that is not branded.
 */
@Slf4j
@Component
public class PayslipPdfRenderer {

    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*(\\w+)\\s*}}");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMMM d, yyyy");
    private static final Color MUTED = new Color(100, 116, 139);
    private static final Color RULE = new Color(226, 232, 240);
    private static final Color INK = new Color(15, 23, 42);

    /** One label/amount row. */
    private record Row(String label, String amount) {}

    /** A section of the payslip: rows (or sub-grouped rows) and an optional total. */
    private record Section(List<Row> rows, Map<String, List<Row>> groups, String total, boolean empty) {}

    public byte[] render(Payslip p, Map<String, Object> layout, Map<String, String> company) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            Document doc = new Document(PageSize.A4, 48, 48, 48, 48);
            PdfWriter.getInstance(doc, out);
            doc.open();
            if (layout == null || blocksOf(layout).isEmpty()) {
                renderFallback(doc, p, company);
            } else {
                Map<String, String> values = variableValues(p, company);
                for (Map<String, Object> block : blocksOf(layout)) {
                    renderBlock(doc, block, p, values);
                }
            }
            doc.close();
        } catch (Exception e) {
            log.error("Payslip PDF generation failed for payslip {}", p.getId(), e);
            throw new IllegalStateException("Could not generate payslip PDF", e);
        }
        return out.toByteArray();
    }

    // ── Block walk ───────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> blocksOf(Map<String, Object> layout) {
        Object blocks = layout == null ? null : layout.get("blocks");
        return blocks instanceof List ? (List<Map<String, Object>>) blocks : List.of();
    }

    @SuppressWarnings("unchecked")
    private void renderBlock(Document doc, Map<String, Object> block, Payslip p,
                             Map<String, String> values) throws Exception {
        String type = String.valueOf(block.get("type"));
        Map<String, Object> content = (Map<String, Object>) block.getOrDefault("content", Map.of());
        Map<String, Object> style = (Map<String, Object>) block.getOrDefault("style", Map.of());

        switch (type) {
            case "heading", "text", "footer" -> {
                String text = fill(str(content.get("text")), values);
                // A line that resolves to nothing — or to only the separators meant to join values —
                // is skipped, matching the on-screen renderer.
                if (text.isBlank() || text.matches("^[\\s·•|,;:/–—-]*$")) return;
                doc.add(paragraph(text, style, type.equals("heading") ? 16 : 10));
            }
            case "divider" -> doc.add(rule());
            case "spacer" -> doc.add(spacer(intOf(style.get("height"), 12)));
            case "image" -> {
                String src = fill(str(content.get("src")), values);
                Image img = decodeImage(src);
                if (img == null) return; // No logo uploaded: print nothing, not an empty frame.
                float pct = intOf(style.get("width"), 25);
                img.scaleToFit(PageSize.A4.getWidth() * (pct / 100f), 90f);
                img.setAlignment(alignOf(style));
                doc.add(img);
            }
            case "payslipHeader" -> doc.add(headerTable(p));
            case "compensation" -> addSection(doc, "Compensation", compensation(p));
            case "overtimeTable" -> addSection(doc, "Overtime", overtime(p));
            case "allowancesTable" -> addSection(doc, "Allowances", allowances(p));
            case "grossPay" -> addSection(doc, "Gross pay",
                    new Section(List.of(), Map.of(), peso(p.getGrossPay()), false));
            case "deductionsTable" -> addSection(doc, "Deductions", deductions(p));
            case "netPay" -> doc.add(netPayBox(p));
            default -> { /* button/columns/social have no meaning on a payslip */ }
        }
    }

    // ── Section projection (mirrors payslip-figures.ts) ──────────────────────

    private static Section compensation(Payslip p) {
        return new Section(List.of(new Row("Basic pay", peso(p.getBasicSalary()))),
                Map.of(), null, false);
    }

    private static Section overtime(Payslip p) {
        List<Row> rows = new ArrayList<>();
        for (PayslipOvertimeLine l : nn(p.getOvertimeBreakdown())) {
            String label = l.getOvertimeType() == null ? "Overtime" : humanise(l.getOvertimeType());
            rows.add(new Row(l.getHours() > 0 ? label + " · " + trim(l.getHours()) + " hrs" : label,
                    peso(l.getAmount())));
        }
        if (rows.isEmpty()) rows.add(new Row("Overtime & premium pay", peso(p.getOvertimePay())));
        return new Section(rows, Map.of(), peso(p.getOvertimePay()), isZero(p.getOvertimePay()));
    }

    private static Section allowances(Payslip p) {
        List<Row> rows = toRows(p.getAllowanceLines());
        if (rows.isEmpty()) rows.add(new Row("Allowances", peso(p.getIncentives())));
        return new Section(rows, Map.of(), peso(p.getIncentives()), isZero(p.getIncentives()));
    }

    private static Section deductions(Payslip p) {
        Map<String, List<Row>> groups = new LinkedHashMap<>();

        List<Row> statutory = new ArrayList<>();
        addIfAny(statutory, "SSS", p.getSss());
        addIfAny(statutory, "PhilHealth", p.getPhilhealth());
        addIfAny(statutory, "Pag-IBIG / HDMF", p.getPagibig());
        addIfAny(statutory, "Withholding tax (BIR)", p.getTax());
        if (!statutory.isEmpty()) groups.put("Government", statutory);

        List<Row> named = toRows(p.getDeductionLines());
        if (!named.isEmpty()) groups.put("Loans & other", named);

        if (!isZero(p.getAbsences())) {
            groups.put("Leave", List.of(new Row("Unpaid leave", peso(p.getAbsences()))));
        }
        return new Section(List.of(), groups, peso(p.getTotalDeductions()),
                isZero(p.getTotalDeductions()));
    }

    // ── Layout primitives ────────────────────────────────────────────────────

    private void addSection(Document doc, String heading, Section s) throws Exception {
        if (s.empty()) return; // Rows of zeros are noise; the on-screen renderer omits them too.

        Paragraph h = new Paragraph(heading.toUpperCase(),
                FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, MUTED));
        h.setSpacingBefore(12f);
        h.setSpacingAfter(4f);
        doc.add(h);

        PdfPTable t = new PdfPTable(new float[] {70, 30});
        t.setWidthPercentage(100);
        for (Row r : s.rows()) addRow(t, r, false);
        for (Map.Entry<String, List<Row>> g : s.groups().entrySet()) {
            PdfPCell label = new PdfPCell(new Phrase(g.getKey(),
                    FontFactory.getFont(FontFactory.HELVETICA, 8, MUTED)));
            label.setColspan(2);
            label.setBorder(Rectangle.NO_BORDER);
            label.setPaddingTop(6f);
            t.addCell(label);
            for (Row r : g.getValue()) addRow(t, r, false);
        }
        if (s.total() != null) addRow(t, new Row(heading, s.total()), true);
        doc.add(t);
    }

    private static void addRow(PdfPTable t, Row r, boolean strong) {
        Font f = FontFactory.getFont(strong ? FontFactory.HELVETICA_BOLD : FontFactory.HELVETICA, 10, INK);
        PdfPCell label = new PdfPCell(new Phrase(r.label(), f));
        PdfPCell amount = new PdfPCell(new Phrase(r.amount(), f));
        amount.setHorizontalAlignment(Element.ALIGN_RIGHT);
        for (PdfPCell c : List.of(label, amount)) {
            c.setBorder(strong ? Rectangle.TOP : Rectangle.NO_BORDER);
            c.setBorderColor(RULE);
            c.setPaddingTop(strong ? 5f : 3f);
            c.setPaddingBottom(3f);
        }
        t.addCell(label);
        t.addCell(amount);
    }

    private static PdfPTable headerTable(Payslip p) {
        PdfPTable t = new PdfPTable(new float[] {28, 72});
        t.setWidthPercentage(100);
        t.setSpacingBefore(6f);
        addHeaderRow(t, "Employee", p.getEmployeeName());
        addHeaderRow(t, "Employee ID", p.getEmployeeId());
        addHeaderRow(t, "Position", p.getPosition());
        addHeaderRow(t, "Pay period",
                fmtDate(p.getPeriodStart()) + " – " + fmtDate(p.getPeriodEnd()));
        return t;
    }

    private static void addHeaderRow(PdfPTable t, String label, String value) {
        PdfPCell l = new PdfPCell(new Phrase(label,
                FontFactory.getFont(FontFactory.HELVETICA, 9, MUTED)));
        PdfPCell v = new PdfPCell(new Phrase(value == null || value.isBlank() ? "—" : value,
                FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, INK)));
        for (PdfPCell c : List.of(l, v)) {
            c.setBorder(Rectangle.NO_BORDER);
            c.setPaddingBottom(2f);
        }
        t.addCell(l);
        t.addCell(v);
    }

    private static PdfPTable netPayBox(Payslip p) {
        PdfPTable t = new PdfPTable(new float[] {60, 40});
        t.setWidthPercentage(100);
        t.setSpacingBefore(14f);
        PdfPCell label = new PdfPCell(new Phrase("NET PAY",
                FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, MUTED)));
        PdfPCell amount = new PdfPCell(new Phrase(peso(p.getNetPay()),
                FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15, INK)));
        amount.setHorizontalAlignment(Element.ALIGN_RIGHT);
        for (PdfPCell c : List.of(label, amount)) {
            c.setBorder(Rectangle.NO_BORDER);
            c.setBackgroundColor(new Color(241, 245, 249));
            c.setPadding(9f);
        }
        t.addCell(label);
        t.addCell(amount);
        return t;
    }

    private static Paragraph paragraph(String text, Map<String, Object> style, int defaultSize) {
        int size = intOf(style.get("fontSize"), defaultSize);
        boolean bold = Boolean.TRUE.equals(style.get("bold"));
        Paragraph para = new Paragraph(text, FontFactory.getFont(
                bold ? FontFactory.HELVETICA_BOLD : FontFactory.HELVETICA,
                // Screen px run larger than PDF points; scaling keeps the proportions the admin
                // arranged without the document coming out oversized.
                size * 0.75f,
                colorOf(style.get("color"), INK)));
        para.setAlignment(alignOf(style));
        para.setSpacingBefore(intOf(style.get("paddingY"), 2) * 0.5f);
        return para;
    }

    private static Paragraph rule() {
        Paragraph p = new Paragraph(new com.lowagie.text.Chunk(
                new com.lowagie.text.pdf.draw.LineSeparator(0.6f, 100, RULE, Element.ALIGN_CENTER, -2)));
        p.setSpacingBefore(8f);
        p.setSpacingAfter(4f);
        return p;
    }

    private static Paragraph spacer(int height) {
        Paragraph p = new Paragraph(" ");
        p.setSpacingAfter(height * 0.5f);
        return p;
    }

    /** Plain layout used when no template could be resolved. */
    private void renderFallback(Document doc, Payslip p, Map<String, String> company)
            throws Exception {
        String name = company == null ? null : company.get("companyName");
        if (name != null && !name.isBlank()) {
            doc.add(new Paragraph(name, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15, INK)));
        }
        doc.add(new Paragraph("Payslip · " + fmtDate(p.getPeriodStart()) + " to "
                + fmtDate(p.getPeriodEnd()),
                FontFactory.getFont(FontFactory.HELVETICA, 10, MUTED)));
        doc.add(rule());
        doc.add(headerTable(p));
        addSection(doc, "Compensation", compensation(p));
        addSection(doc, "Overtime", overtime(p));
        addSection(doc, "Allowances", allowances(p));
        addSection(doc, "Gross pay", new Section(List.of(), Map.of(), peso(p.getGrossPay()), false));
        addSection(doc, "Deductions", deductions(p));
        doc.add(netPayBox(p));
    }

    // ── Values / helpers ─────────────────────────────────────────────────────

    private static Map<String, String> variableValues(Payslip p, Map<String, String> company) {
        Map<String, String> v = new LinkedHashMap<>();
        if (company != null) v.putAll(company);
        v.put("employeeName", nvl(p.getEmployeeName()));
        v.put("employeeId", nvl(p.getEmployeeId()));
        v.put("position", nvl(p.getPosition()));
        v.put("periodStart", fmtDate(p.getPeriodStart()));
        v.put("periodEnd", fmtDate(p.getPeriodEnd()));
        v.put("netPay", peso(p.getNetPay()));
        v.put("payDate", p.getReleasedAt() == null ? "" : p.getReleasedAt().toLocalDate().format(DATE));
        return v;
    }

    private static String fill(String text, Map<String, String> values) {
        if (text == null || text.isEmpty()) return "";
        Matcher m = VAR.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String replacement = values.getOrDefault(m.group(1), "");
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString().trim();
    }

    /** Decodes a base64 data-URL logo. Remote URLs are not fetched — a PDF build must not do I/O. */
    private static Image decodeImage(String src) {
        if (src == null || !src.startsWith("data:")) return null;
        int comma = src.indexOf(',');
        if (comma < 0) return null;
        try {
            return Image.getInstance(Base64.getDecoder().decode(src.substring(comma + 1)));
        } catch (Exception e) {
            log.warn("Could not decode payslip logo — skipping it: {}", e.getMessage());
            return null;
        }
    }

    private static List<Row> toRows(List<PayslipLine> lines) {
        List<Row> rows = new ArrayList<>();
        for (PayslipLine l : nn(lines)) {
            rows.add(new Row(nvl(l.getLabel()), peso(l.getAmount())));
        }
        return rows;
    }

    private static void addIfAny(List<Row> rows, String label, BigDecimal amount) {
        if (!isZero(amount)) rows.add(new Row(label, peso(amount)));
    }

    /** "REGULAR_HOLIDAY_REST_DAY_OT" → "Regular holiday rest day OT". */
    private static String humanise(String enumName) {
        String s = enumName.toLowerCase().replace('_', ' ').trim();
        if (s.isEmpty()) return enumName;
        s = Character.toUpperCase(s.charAt(0)) + s.substring(1);
        return s.replaceAll("\\bot\\b", "OT");
    }

    private static int alignOf(Map<String, Object> style) {
        return switch (String.valueOf(style.get("align"))) {
            case "center" -> Element.ALIGN_CENTER;
            case "right" -> Element.ALIGN_RIGHT;
            default -> Element.ALIGN_LEFT;
        };
    }

    private static Color colorOf(Object hex, Color fallback) {
        if (!(hex instanceof String s) || !s.startsWith("#") || s.length() != 7) return fallback;
        try {
            return new Color(Integer.parseInt(s.substring(1), 16));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int intOf(Object v, int fallback) {
        return v instanceof Number n ? n.intValue() : fallback;
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static <T> List<T> nn(List<T> l) {
        return l == null ? List.of() : l;
    }

    private static boolean isZero(BigDecimal v) {
        return v == null || v.signum() == 0;
    }

    private static String peso(BigDecimal v) {
        if (v == null) return "—";
        return "PHP " + v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String fmtDate(java.time.LocalDate d) {
        return d == null ? "—" : d.format(DATE);
    }

    private static String trim(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}
