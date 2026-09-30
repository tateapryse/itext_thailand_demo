package com.apryse.demo;

import com.itextpdf.html2pdf.ConverterProperties;
import com.itextpdf.html2pdf.HtmlConverter;
import com.itextpdf.html2pdf.resolver.font.DefaultFontProvider;
import com.itextpdf.io.font.FontProgramFactory;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.layout.font.FontProvider;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Calendar;
import java.util.Locale;
import java.util.Map;

/**
 * Generates the Amendment Confirmation Letter PDF from a {@link CanonicalDoc}
 * using iText pdfHTML (html2pdf).
 *
 * Thai typography: if a Sarabun font is present at {@code fonts/Sarabun-Regular.ttf}
 * and a pdfCalligraph license is loaded, Thai will be properly shaped.
 * Falls back to the system/default font stack otherwise — demo continues.
 */
final class LetterGenerator {

    private static final String[] THAI_MONTHS = {
        "", "มกราคม", "กุมภาพันธ์", "มีนาคม", "เมษายน",
        "พฤษภาคม", "มิถุนายน", "กรกฎาคม", "สิงหาคม",
        "กันยายน", "ตุลาคม", "พฤศจิกายน", "ธันวาคม"
    };

    private final Path fontDir;
    private final boolean typographyAvailable;

    LetterGenerator(Path fontDir) {
        this.fontDir = fontDir;
        this.typographyAvailable = probeTypography();
    }

    /**
     * Reads the letter HTML template from classpath, populates all placeholders
     * from the canonical doc, then converts to PDF and writes to {@code dest}.
     */
    void generate(CanonicalDoc doc, Calendar signTimestamp, Path dest) throws Exception {
        String html = buildHtml(doc, signTimestamp);
        try (OutputStream out = Files.newOutputStream(dest)) {
            ConverterProperties props = buildConverterProperties();
            HtmlConverter.convertToPdf(html, out, props);
        }
    }

    // ── HTML population ──────────────────────────────────────────────────────

    private String buildHtml(CanonicalDoc doc, Calendar signTimestamp) throws Exception {
        try (InputStream tmpl = getClass().getClassLoader()
                .getResourceAsStream("confirmationletter-template.html")) {
            if (tmpl == null) throw new IllegalStateException("confirmationletter-template.html not found on classpath");
            String html = new String(tmpl.readAllBytes(), StandardCharsets.UTF_8);

            CanonicalDoc.Customer c = doc.customer;
            LocalDate date = parseIso(c.submissionDate());

            html = html.replace("{{referenceNo}}",  nvl(c.referenceNo()));
            html = html.replace("{{customerId}}",   nvl(c.customerId()));
            html = html.replace("{{dateThai}}",     dateThai(date));
            html = html.replace("{{dateEnglish}}",  dateEn(date));
            html = html.replace("{{branchTh}}",     nvl(c.branchTh()));
            html = html.replace("{{branchEn}}",     nvl(c.branchEn()));
            html = html.replace("{{nameThai}}",     nvl(c.nameThai(), c.nameEnglish()));
            html = html.replace("{{titleEn}}",      titleEn(c.title()));
            html = html.replace("{{nameEnglish}}",  nvl(c.nameEnglish(), c.nameThai()));
            html = html.replace("{{sigTimestamp}}", fmtCalendar(signTimestamp));
            html = html.replace("{{AMENDMENT_ROWS}}", buildAmendmentRows(doc, date));
            return html;
        }
    }

    private String buildAmendmentRows(CanonicalDoc doc, LocalDate date) {
        StringBuilder sb = new StringBuilder();
        int row = 1;
        for (CanonicalDoc.Amendment a : doc.requestedAmendments) {
            sb.append("<tr>\n  <td>").append(row++).append("</td>\n");
            sb.append("  <td>").append(amendmentTitle(a.type())).append("</td>\n");
            sb.append("  <td>").append(amendmentDetail(a, doc, date)).append("</td>\n");
            sb.append("  <td class=\"status\">ดำเนินการแล้ว<br><span class=\"en\">Completed</span></td>\n</tr>\n");
        }
        return sb.toString();
    }

    private String amendmentTitle(String type) {
        return switch (type) {
            case "REQUEST_NEW_PIN"          -> "ออกรหัสลับแรกเข้าใหม่<br><span class=\"en\">New PIN issued</span>";
            case "CHANGE_MAILING_ADDRESS"   -> "เปลี่ยนที่อยู่ทางไปรษณีย์<br><span class=\"en\">Mailing address updated</span>";
            case "CHANGE_CONTACT_DETAILS"   -> "เปลี่ยนข้อมูลการติดต่อ<br><span class=\"en\">Contact details updated</span>";
            case "CHANGE_NAME"              -> "เปลี่ยนชื่อ/นามสกุล<br><span class=\"en\">Name changed</span>";
            case "CHANGE_PRIMARY_ACCOUNT"   -> "เปลี่ยนบัญชีเงินฝากหลัก<br><span class=\"en\">Primary account changed</span>";
            case "ADD_DEPOSIT_ACCOUNT"      -> "เพิ่มบัญชีเงินฝาก<br><span class=\"en\">Deposit account added</span>";
            case "ADD_THIRD_PARTY_ACCOUNT"  -> "เพิ่มบัญชีบุคคลที่สาม<br><span class=\"en\">Third-party account added</span>";
            case "ADD_CREDIT_CARD"          -> "เพิ่มบัตรเครดิต<br><span class=\"en\">Credit card added</span>";
            case "ADD_LOAN_ACCOUNT"         -> "เพิ่มบัญชีสินเชื่อ<br><span class=\"en\">Loan account added</span>";
            case "CANCEL_DEPOSIT_ACCOUNT"   -> "ยกเลิกบัญชีเงินฝาก<br><span class=\"en\">Deposit account cancelled</span>";
            case "CANCEL_THIRD_PARTY_ACCOUNT"-> "ยกเลิกบัญชีบุคคลที่สาม<br><span class=\"en\">Third-party account cancelled</span>";
            case "CANCEL_CREDIT_CARD"       -> "ยกเลิกบัตรเครดิต<br><span class=\"en\">Credit card cancelled</span>";
            case "CANCEL_LOAN_ACCOUNT"      -> "ยกเลิกบัญชีสินเชื่อ<br><span class=\"en\">Loan account cancelled</span>";
            case "CANCEL_IBANKING_SERVICE"  -> "ยกเลิกบริการบัวหลวง ไอแบงก์กิ้ง<br><span class=\"en\">Bualuang iBanking service cancelled</span>";
            default -> type;
        };
    }

    private String amendmentDetail(CanonicalDoc.Amendment a, CanonicalDoc doc, LocalDate date) {
        return switch (a.type()) {
            case "REQUEST_NEW_PIN" -> {
                String thReason = switch (a.extra("reason")) {
                    case "PIN_DAMAGED"       -> "ซองรหัสลับชำรุด";
                    case "PIN_NOT_RECEIVED"  -> "ไม่ได้รับรหัสลับ";
                    default                  -> "ลืมรหัสลับเดิม";
                };
                String enReason = switch (a.extra("reason")) {
                    case "PIN_DAMAGED"       -> "previous PIN envelope damaged";
                    case "PIN_NOT_RECEIVED"  -> "PIN not received";
                    default                  -> "previous PIN forgotten";
                };
                yield "เหตุผล: " + thReason + " — รหัสใหม่จะจัดส่งตามช่องทางที่ลงทะเบียนไว้<br>"
                    + "<span class=\"en\">Reason: " + enReason + " — the new PIN will be delivered via your registered channel</span>";
            }
            case "CHANGE_MAILING_ADDRESS" -> {
                String formatted = doc.newMailingAddress != null ? doc.newMailingAddress.formatted() : "(see form)";
                yield formatted + "<br><span class=\"en\">" + formatted + "</span>";
            }
            case "CHANGE_CONTACT_DETAILS" -> {
                StringBuilder sb = new StringBuilder();
                if (doc.contact != null) {
                    if (doc.contact.mobile()      != null && !doc.contact.mobile().isBlank())
                        sb.append("มือถือ / Mobile: ").append(doc.contact.mobile()).append("<br>");
                    if (doc.contact.email()       != null && !doc.contact.email().isBlank())
                        sb.append("อีเมล / E-mail: ").append(doc.contact.email()).append("<br>");
                    if (doc.contact.officePhone() != null && !doc.contact.officePhone().isBlank())
                        sb.append("ที่ทำงาน / Office: ").append(doc.contact.officePhone()).append("<br>");
                    if (doc.contact.fax()         != null && !doc.contact.fax().isBlank())
                        sb.append("โทรสาร / Fax: ").append(doc.contact.fax()).append("<br>");
                }
                yield sb.isEmpty() ? "(see form)" : sb.toString();
            }
            case "CHANGE_PRIMARY_ACCOUNT" -> {
                String from = a.extra("from"); String to = a.extra("to");
                String th = (from.isBlank() ? "" : "จากบัญชีเลขที่ " + from) + (to.isBlank() ? "" : " เป็นบัญชีเลขที่ " + to);
                String en = (from.isBlank() ? "" : "From account no. " + from) + (to.isBlank() ? "" : " to account no. " + to);
                yield (th.isBlank() ? "(see form)" : th) + "<br><span class=\"en\">" + (en.isBlank() ? "(see form)" : en) + "</span>";
            }
            case "CANCEL_LOAN_ACCOUNT" -> {
                String ln = a.extra("loanAccountNo");
                yield "บัญชีสินเชื่อเลขที่ / Loan account no. " + (ln.isBlank() ? "(see form)" : ln);
            }
            case "CANCEL_IBANKING_SERVICE" -> {
                String effTh = dateThai(date); String effEn = dateEn(date);
                yield "มีผลตั้งแต่วันที่ " + effTh + "<br><span class=\"en\">Effective from " + effEn + "</span>";
            }
            default -> "(see form / ดูในแบบฟอร์ม)";
        };
    }

    // ── Converter properties + font setup ────────────────────────────────────

    private ConverterProperties buildConverterProperties() {
        ConverterProperties props = new ConverterProperties();
        FontProvider fp = new DefaultFontProvider(true, true, false);

        // Register Sarabun Thai font if present (graceful degradation if absent)
        Path sarabun = fontDir.resolve("Sarabun-Regular.ttf");
        if (Files.exists(sarabun)) {
            try {
                fp.addFont(sarabun.toString());
                if (typographyAvailable)
                    System.out.println("  [✓] Sarabun font + pdfCalligraph: Thai shaping active");
                else
                    System.out.println("  [i] Sarabun font loaded; pdfCalligraph license not active — basic embedding");
            } catch (Exception e) {
                System.out.println("  [WARN] Could not load Sarabun font: " + e.getMessage());
            }
        } else {
            System.out.println("  [i] Sarabun-Regular.ttf not found in " + fontDir
                    + " — using system Thai font fallback (see RUNBOOK.md for download instructions)");
        }

        props.setFontProvider(fp);
        return props;
    }

    private boolean probeTypography() {
        try {
            Class.forName("com.itextpdf.typography.shaping.Shaper");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    // ── Date formatting ───────────────────────────────────────────────────────

    static String dateThai(LocalDate d) {
        if (d == null) return "(วันที่ไม่ระบุ)";
        return d.getDayOfMonth() + " " + THAI_MONTHS[d.getMonthValue()] + " " + (d.getYear() + 543);
    }

    static String dateEn(LocalDate d) {
        if (d == null) return "(date unknown)";
        return d.getDayOfMonth() + " " + d.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + d.getYear();
    }

    private static LocalDate parseIso(String iso) {
        if (iso == null || iso.isBlank()) return LocalDate.now();
        try { return LocalDate.parse(iso); } catch (Exception e) { return LocalDate.now(); }
    }

    private static String fmtCalendar(Calendar c) {
        if (c == null) return "";
        return String.format("%04d-%02d-%02d %02d:%02d:%02d (+07:00)",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND));
    }

    private static String nvl(String primary, String fallback) {
        return (primary != null && !primary.isBlank()) ? primary : nvl(fallback);
    }

    private static String nvl(String s) { return s == null ? "" : s; }

    private static String titleEn(String t) {
        if (t == null) return "";
        return switch (t) {
            case "MR"   -> "Mr. ";
            case "MRS"  -> "Mrs. ";
            case "MISS" -> "Miss ";
            default     -> "";
        };
    }
}
