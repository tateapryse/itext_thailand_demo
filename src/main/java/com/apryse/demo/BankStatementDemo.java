package com.apryse.demo;

import com.itextpdf.forms.PdfAcroForm;
import com.itextpdf.forms.fields.PdfFormField;
import com.itextpdf.forms.fields.PdfTextFormField;
import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.font.PdfFontFactory.EmbeddingStrategy;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.EncryptionConstants;
import com.itextpdf.kernel.pdf.PdfAConformance;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfName;
import com.itextpdf.kernel.pdf.PdfOutputIntent;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.ReaderProperties;
import com.itextpdf.kernel.pdf.StampingProperties;
import com.itextpdf.kernel.pdf.WriterProperties;
import com.itextpdf.kernel.pdf.filespec.PdfFileSpec;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.VerticalAlignment;
import com.itextpdf.pdfa.PdfADocument;
import com.itextpdf.forms.form.element.SignatureFieldAppearance;
import com.itextpdf.signatures.BouncyCastleDigest;
import com.itextpdf.signatures.IExternalDigest;
import com.itextpdf.signatures.IExternalSignature;
import com.itextpdf.signatures.PdfPKCS7;
import com.itextpdf.signatures.PdfSigner;
import com.itextpdf.signatures.PrivateKeySignature;
import com.itextpdf.signatures.SignerProperties;
import com.itextpdf.signatures.SignatureUtil;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.Certificate;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Bangkok Bank / Apryse iText (Java) demo.
 *
 * <p>Usage:
 * <ul>
 *   <li>Default (amendment scenario): {@code java -jar itext-shenzhen-demo.jar}</li>
 *   <li>Custom input: {@code java -jar itext-shenzhen-demo.jar --input path/to/extraction.json}</li>
 *   <li>Tamper test:  {@code java -jar itext-shenzhen-demo.jar --tamper}</li>
 *   <li>Loan scenario: {@code java -jar itext-shenzhen-demo.jar --scenario loan}</li>
 * </ul>
 */
public class BankStatementDemo {

    // ── Shared config ─────────────────────────────────────────────────────────
    private static final Path   PROJECT_DIR      = Paths.get("").toAbsolutePath();
    private static final Path   OUTPUT_DIR       = PROJECT_DIR.resolve("demo_output");
    private static final Path   FONT_DIR         = PROJECT_DIR.resolve("fonts");
    private static final String CHINESE_FONT     = FONT_DIR.resolve("wqy-microhei.ttc").toString() + ",0";
    private static final String USER_PASSWORD    = "user1234";
    private static final String OWNER_PASSWORD   = "owner1234";
    private static final String KEYSTORE_PASSWORD = "keystore1234";
    private static final String KEY_ALIAS         = "apryse-demo";
    private static final String WATERMARK_TEXT    = "ARCHIVE COPY";
    private static final int    STATUS_PORT       = 8080;

    private static final Scanner         SCANNER   = new Scanner(System.in);
    private static final StatusDashboard DASHBOARD = new StatusDashboard();

    // ── Loan scenario paths ───────────────────────────────────────────────────
    private static final Path THAI_DIR             = PROJECT_DIR.resolve("thai");
    private static final Path THAI_WORKBOOK        = THAI_DIR.resolve("Banking forms.xlsx");
    private static final Path THAI_TEMPLATE_SOURCE = THAI_DIR.resolve("Bank-A_PersonalLoanform_interactive (6).pdf");
    private static final Path TXT_FILE             = PROJECT_DIR.resolve("bank_statement_data.txt");
    private static final Path TEMPLATE_PDF         = OUTPUT_DIR.resolve("statement_template.pdf");
    private static final Path FILLED_PDF           = OUTPUT_DIR.resolve("statement_filled.pdf");
    private static final Path PDFA_FILE            = OUTPUT_DIR.resolve("statement_pdfa.pdf");
    private static final Path WATERMARKED_PDF      = OUTPUT_DIR.resolve("statement_watermarked.pdf");
    private static final Path PROTECTED_PDF        = OUTPUT_DIR.resolve("statement_protected.pdf");
    private static final Path SIGNED_PDF           = OUTPUT_DIR.resolve("statement_signed.pdf");
    private static final Path KEYSTORE             = OUTPUT_DIR.resolve("apryse_demo_keystore.p12");

    // mutable active paths (locked-file fallback)
    private static Path activeTemplate    = TEMPLATE_PDF;
    private static Path activeFilledPdf   = FILLED_PDF;
    private static Path activeWatermarked = WATERMARKED_PDF;
    private static Path activeProtected   = PROTECTED_PDF;
    private static Path activeSigned      = SIGNED_PDF;
    private static Path activePdfa        = PDFA_FILE;
    private static Path activeKeystore    = KEYSTORE;

    // ── Amendment scenario paths ──────────────────────────────────────────────
    private static final String DEFAULT_FIXTURE   = "apryse_idp.json";
    private static final Path EXTRACTED_JSON      = OUTPUT_DIR.resolve("extracted.json");
    private static final Path LETTER_PDF          = OUTPUT_DIR.resolve("confirmation_letter.pdf");
    private static final Path LETTER_WATERMARKED  = OUTPUT_DIR.resolve("confirmation_letter_watermarked.pdf");
    private static final Path LETTER_PROTECTED    = OUTPUT_DIR.resolve("confirmation_letter_protected.pdf");
    private static final Path LETTER_SIGNED       = OUTPUT_DIR.resolve("confirmation_letter_signed.pdf");
    private static final Path LETTER_ARCHIVE      = OUTPUT_DIR.resolve("confirmation_letter_archive.pdf");
    private static final Path AMENDMENT_KEYSTORE  = OUTPUT_DIR.resolve("apryse_demo_keystore.p12");
    private static final String AMENDMENT_DNAME   =
            "CN=Bualuang Document Services, OU=Digital Banking, O=Bangkok Bank, L=Bangkok, C=TH";

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    // =========================================================================
    //  ENTRY POINT
    // =========================================================================

    public static void main(String[] args) {
        try {
            doMain(args);
        } catch (Throwable t) {
            try (PrintWriter pw = new PrintWriter(PROJECT_DIR.resolve("startup-error.log").toFile())) {
                pw.println("Demo failed: " + t);
                t.printStackTrace(pw);
            } catch (Exception ignore) {}
            System.err.println("[FATAL] " + t.getMessage());
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static void doMain(String[] args) throws Exception {
        String licenseEnv = System.getenv("ITEXT_LICENSE_FILE");
        Path licensePath = (licenseEnv == null || licenseEnv.isBlank())
                ? null : PROJECT_DIR.resolve(licenseEnv);
        if (licensePath == null) {
            System.out.println("  [i] ITEXT_LICENSE_FILE not set \u2014 running AGPL mode");
        } else if (!Files.exists(licensePath)) {
            System.out.println("  [!] ITEXT_LICENSE_FILE not found: " + licensePath);
        } else {
            try {
                Class.forName("com.itextpdf.licensing.base.LicenseKey")
                    .getMethod("loadLicenseFile", java.io.File.class)
                    .invoke(null, licensePath.toFile());
                System.out.println("  [\u2713] iText license loaded");
            } catch (ClassNotFoundException e) {
                System.out.println("  [i] LicenseKey not in classpath \u2014 running AGPL mode");
            } catch (Exception e) {
                System.out.println("  [!] License load failed: " + e.getMessage());
            }
        }

        String scenario = "amendment";
        boolean tamper  = false;
        Path inputPath  = PROJECT_DIR.resolve(DEFAULT_FIXTURE);

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--scenario" -> { if (i + 1 < args.length) scenario = args[++i]; }
                case "--input"    -> { if (i + 1 < args.length) inputPath = Paths.get(args[++i]); }
                case "--tamper"   -> tamper = true;
            }
        }

        banner(scenario);
        startDashboard(scenario);

        try {
            Files.createDirectories(OUTPUT_DIR);
            if ("loan".equalsIgnoreCase(scenario)) {
                runLoanScenario();
            } else {
                runAmendmentScenario(inputPath, tamper);
            }
        } finally {
            DASHBOARD.stop();
        }
    }

    // =========================================================================
    //  ACT 2 — AMENDMENT SCENARIO
    // =========================================================================

    private static void runAmendmentScenario(Path inputPath, boolean tamper) throws Exception {

        // ── INGEST ────────────────────────────────────────────────────────────
        DASHBOARD.update("INGEST", "Running",
                "Normalising Apryse extraction JSON / กำลังแปลงไฟล์ JSON จาก Apryse");
        title("INGEST", "Normalise raw Apryse JSON → canonical extracted.json");

        if (!Files.exists(inputPath))
            throw new IllegalArgumentException("Input file not found: " + inputPath);
        System.out.println("  Input: " + inputPath);

        Normalizer normalizer = new Normalizer();
        CanonicalDoc doc = normalizer.normalize(inputPath);

        List<String[]> pairs = normalizer.getNotablePairs(inputPath);
        if (!pairs.isEmpty()) {
            System.out.println();
            System.out.printf("  %-34s  %-34s%n", "RAW LABEL / ป้ายดิบ", "CANONICAL VALUE / ค่าที่แปลงแล้ว");
            System.out.println("  " + "-".repeat(70));
            for (String[] pair : pairs)
                System.out.printf("  %-34s  %-34s%n", truncate(pair[0], 34), truncate(pair[1], 34));
            System.out.println();
        }

        String validationError = normalizer.validate(doc);
        if (validationError != null) System.out.println("  [WARN] Validation: " + validationError);

        Path extractedJson = prepareOutputPath(EXTRACTED_JSON);
        Files.writeString(extractedJson, doc.toJson(), StandardCharsets.UTF_8);
        ok("Wrote extracted.json: " + rel(extractedJson));
        DASHBOARD.output(extractedJson);
        DASHBOARD.setPresenterCue("INGEST", extractedJson.getFileName().toString(),
                new String[]{"Open extracted.json", "Verify customer + amendment fields"},
                "INGEST complete — press Enter to generate letter / กด Enter เพื่อสร้างจดหมาย");
        pause("INGEST", "Extraction complete — review extracted.json");

        // ── GENERATE ─────────────────────────────────────────────────────────
        DASHBOARD.update("GENERATE", "Running",
                "Generating Amendment Confirmation Letter via pdfHTML / กำลังสร้างจดหมายยืนยัน");
        title("GENERATE", "Amendment Confirmation Letter via pdfHTML");

        Calendar sigTime = Calendar.getInstance();
        Path letterPdf = prepareOutputPath(LETTER_PDF);
        new LetterGenerator(FONT_DIR).generate(doc, sigTime, letterPdf);
        ok("Generated confirmation letter: " + rel(letterPdf));
        DASHBOARD.output(letterPdf);
        DASHBOARD.setPresenterCue("GENERATE", letterPdf.getFileName().toString(),
                new String[]{"Open confirmation_letter.pdf", "Check bilingual content", "Check Thai typography"},
                "GENERATE complete — press Enter to apply watermark / กด Enter เพื่อเพิ่มลายน้ำ");
        pause("GENERATE", "Letter PDF ready — open it in a viewer");

        // ── MARK ─────────────────────────────────────────────────────────────
        DASHBOARD.update("MARK", "Running", "Applying SPECIMEN watermark / กำลังเพิ่มลายน้ำ SPECIMEN");
        title("MARK", "Stamp SPECIMEN watermark on every page");

        Path watermarked = prepareOutputPath(LETTER_WATERMARKED);
        addWatermarkTo(letterPdf, watermarked, "SPECIMEN");
        ok("Watermarked: " + rel(watermarked));
        DASHBOARD.output(watermarked);
        DASHBOARD.setPresenterCue("MARK", watermarked.getFileName().toString(),
                new String[]{"Open confirmation_letter_watermarked.pdf"},
                "MARK complete — press Enter to encrypt / กด Enter เพื่อเข้ารหัส");
        pause("MARK", "Watermark applied");

        // ── LOCK ─────────────────────────────────────────────────────────────
        DASHBOARD.update("LOCK", "Running", "Encrypting with AES-256 / กำลังเข้ารหัสด้วย AES-256");
        title("LOCK", "AES-256 password encryption");

        Path protectedPdf = prepareOutputPath(LETTER_PROTECTED);
        encryptPdf(watermarked, protectedPdf);
        ok("Encrypted: " + rel(protectedPdf) + "  (open pwd: " + USER_PASSWORD + ")");
        DASHBOARD.output(protectedPdf);
        DASHBOARD.setPresenterCue("LOCK", protectedPdf.getFileName().toString(),
                new String[]{"Try opening the PDF — it should ask for a password", "Password: " + USER_PASSWORD},
                "LOCK complete — press Enter to digitally sign / กด Enter เพื่อลงลายมือชื่อ");
        pause("LOCK", "Document encrypted");

        // ── SEAL ─────────────────────────────────────────────────────────────
        DASHBOARD.update("SEAL", "Running",
                "Applying CMS digital signature / กำลังลงลายมือชื่อดิจิทัล CMS");
        title("SEAL", "CMS detached digital signature + signing time");

        Path ks = prepareOutputPath(AMENDMENT_KEYSTORE);
        prepareKeystoreWith(AMENDMENT_DNAME, ks);
        DASHBOARD.output(ks);

        Path signedPdf = prepareOutputPath(LETTER_SIGNED);
        Calendar signDate = Calendar.getInstance();
        signPdf(protectedPdf, signedPdf, ks, signDate,
                "Bangkok Bank amendment authorisation", "Bangkok, Thailand");
        ok("Signed: " + rel(signedPdf) + "  signing time: " + fmtCalendar(signDate));
        DASHBOARD.output(signedPdf);
        DASHBOARD.setPresenterCue("SEAL", signedPdf.getFileName().toString(),
                new String[]{"Open the signed PDF", "Check Signatures panel — should show valid"},
                "SEAL complete — press Enter to archive / กด Enter เพื่อจัดเก็บ");
        pause("SEAL", "Digital signature applied");

        // ── ARCHIVE ───────────────────────────────────────────────────────────
        DASHBOARD.update("ARCHIVE", "Running",
                "Creating PDF/A-3B archive with embedded JSON / กำลังสร้าง PDF/A-3B พร้อมฝัง JSON");
        title("ARCHIVE", "PDF/A-3B archive with embedded JSON files");

        Path archivePdf = prepareOutputPath(LETTER_ARCHIVE);
        createPdfA3Archive(watermarked, archivePdf, extractedJson, inputPath);
        ok("PDF/A-3B archive: " + rel(archivePdf));
        DASHBOARD.output(archivePdf);
        DASHBOARD.setPresenterCue("ARCHIVE", archivePdf.getFileName().toString(),
                new String[]{"Open the archive PDF", "Check embedded attachments panel"},
                "ARCHIVE complete — press Enter to verify / กด Enter เพื่อตรวจสอบ");
        pause("ARCHIVE", "Archive created");

        // ── VERIFY ────────────────────────────────────────────────────────────
        DASHBOARD.update("VERIFY", "Running",
                "Verifying digital signature / กำลังตรวจสอบลายมือชื่อดิจิทัล");
        title("VERIFY", "Signature verification");

        boolean valid = verifySignature(signedPdf);
        ok("Signature: " + (valid ? "VALID ✓" : "INVALID ✗"));

        if (tamper) {
            System.out.println("\n  [i] --tamper: creating tampered copy...");
            Path tampered = OUTPUT_DIR.resolve("confirmation_letter_tampered.pdf");
            createTamperedCopy(signedPdf, tampered);
            boolean stillValid = verifySignature(tampered);
            ok("Tampered copy: " + (stillValid ? "VALID (unexpected)" : "INVALID ✓ tamper detected"));
            DASHBOARD.output(tampered);
        }

        DASHBOARD.update("COMPLETE", "Done",
                "All demo outputs generated / สร้างไฟล์ผลลัพธ์ครบทุกไฟล์แล้ว");
        amendmentSummary(extractedJson, letterPdf, watermarked, protectedPdf, ks, signedPdf, archivePdf, tamper);
    }

    // ── Amendment pipeline helpers ────────────────────────────────────────────

    private static void addWatermarkTo(Path src, Path dest, String text) throws Exception {
        try (PdfReader reader = new PdfReader(src.toString());
             PdfWriter writer = new PdfWriter(dest.toString());
             PdfDocument pdf  = new PdfDocument(reader, writer);
             Document doc     = new Document(pdf)) {

            PdfFont font = createFont();
            Paragraph wm = new Paragraph(text)
                    .setFont(font).setFontSize(72)
                    .setFontColor(ColorConstants.LIGHT_GRAY).setOpacity(0.35f);
            int pages = pdf.getNumberOfPages();
            for (int i = 1; i <= pages; i++) {
                Rectangle box = pdf.getPage(i).getPageSizeWithRotation();
                doc.showTextAligned(wm, box.getWidth()/2, box.getHeight()/2, i,
                        TextAlignment.CENTER, VerticalAlignment.MIDDLE, (float)Math.toRadians(45));
            }
        }
    }

    private static void encryptPdf(Path src, Path dest) throws Exception {
        WriterProperties props = new WriterProperties().setStandardEncryption(
                USER_PASSWORD.getBytes(StandardCharsets.UTF_8),
                OWNER_PASSWORD.getBytes(StandardCharsets.UTF_8),
                EncryptionConstants.ALLOW_PRINTING,
                EncryptionConstants.ENCRYPTION_AES_256);
        try (PdfReader reader = new PdfReader(src.toString());
             PdfWriter writer = new PdfWriter(dest.toString(), props);
             PdfDocument pdf  = new PdfDocument(reader, writer)) { /* close writes */ }
    }

    private static void signPdf(Path src, Path dest, Path ks,
            Calendar signDate, String reason, String location) throws Exception {
        char[] pwd = KEYSTORE_PASSWORD.toCharArray();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(ks.toFile())) { keyStore.load(in, pwd); }
        String alias = keyStore.aliases().nextElement();
        PrivateKey privateKey = (PrivateKey) keyStore.getKey(alias, pwd);
        Certificate[] chain   = keyStore.getCertificateChain(alias);

        ReaderProperties rp = new ReaderProperties().setPassword(OWNER_PASSWORD.getBytes(StandardCharsets.UTF_8));
        try (PdfReader reader = new PdfReader(src.toString(), rp);
             OutputStream out  = new FileOutputStream(dest.toFile())) {

            PdfSigner signer = new PdfSigner(reader, out, new StampingProperties().useAppendMode());
            SignerProperties props = new SignerProperties()
                    .setClaimedSignDate(signDate)
                    .setReason(reason)
                    .setLocation(location)
                    .setFieldName("BangkokBankAmendmentSignature")
                    .setPageNumber(1)
                    .setPageRect(new Rectangle(36, 36, 230, 70));
            signer.setSignerProperties(props);
            IExternalSignature sig = new PrivateKeySignature(privateKey, "SHA-256",
                    BouncyCastleProvider.PROVIDER_NAME);
            signer.signDetached(new BouncyCastleDigest(), sig, chain,
                    null, null, null, 0, PdfSigner.CryptoStandard.CMS);
        }
    }

    private static void prepareKeystoreWith(String dname, Path dest) throws Exception {
        List<String> cmd = List.of("keytool","-genkeypair",
                "-alias", KEY_ALIAS, "-keyalg","RSA","-keysize","2048","-sigalg","SHA256withRSA",
                "-dname", dname, "-validity","730","-storetype","PKCS12",
                "-keystore", dest.toString(), "-storepass", KEYSTORE_PASSWORD, "-keypass", KEYSTORE_PASSWORD);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        if (p.waitFor() != 0 || !Files.exists(dest))
            throw new IllegalStateException("keytool failed");
        System.out.println("    Keystore: " + rel(dest) + " (alias=" + KEY_ALIAS + ")");
    }

    private static void createPdfA3Archive(Path src, Path dest, Path... attachments) throws Exception {
        byte[] iccBytes = ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();
        try (PdfDocument srcDoc = new PdfDocument(new PdfReader(src.toString()));
             PdfADocument pdfa  = new PdfADocument(new PdfWriter(dest.toString()),
                     PdfAConformance.PDF_A_3B,
                     new PdfOutputIntent("Custom","","https://www.color.org","sRGB IEC61966-2.1",
                             new ByteArrayInputStream(iccBytes)))) {

            pdfa.getDocumentInfo().setTitle("Bangkok Bank Amendment Confirmation Letter");
            pdfa.getDocumentInfo().setAuthor("Bualuang Document Services");
            srcDoc.copyPagesTo(1, srcDoc.getNumberOfPages(), pdfa);

            for (Path att : attachments) {
                if (!Files.exists(att)) continue;
                PdfFileSpec spec = PdfFileSpec.createEmbeddedFileSpec(pdfa,
                        Files.readAllBytes(att), "Extracted data: " + att.getFileName(),
                        att.getFileName().toString(), new PdfName("application/json"), null, null);
                pdfa.addAssociatedFile(att.getFileName().toString(), spec);
                System.out.println("    Embedded: " + att.getFileName());
            }
        }
    }

    private static boolean verifySignature(Path signedPdf) {
        try (PdfReader reader = new PdfReader(signedPdf.toString(),
                new ReaderProperties().setPassword(OWNER_PASSWORD.getBytes(StandardCharsets.UTF_8)));
             PdfDocument pdf = new PdfDocument(reader)) {

            SignatureUtil su = new SignatureUtil(pdf);
            List<String> names = su.getSignatureNames();
            if (names.isEmpty()) { System.out.println("  [!] No signatures found"); return false; }
            boolean all = true;
            for (String name : names) {
                boolean ok = su.readSignatureData(name).verifySignatureIntegrityAndAuthenticity();
                System.out.println("  Signature '" + name + "': " + (ok ? "VALID ✓" : "INVALID ✗"));
                if (!ok) all = false;
            }
            return all;
        } catch (Exception e) { System.out.println("  [WARN] Verify: " + e.getMessage()); return false; }
    }

    private static void createTamperedCopy(Path src, Path dest) throws Exception {
        byte[] bytes = Files.readAllBytes(src);
        bytes[bytes.length / 2] ^= 0x01;
        Files.write(dest, bytes);
    }

    // =========================================================================
    //  ACT 1 — LOAN SCENARIO
    // =========================================================================

    private static void runLoanScenario() throws Exception {
        DASHBOARD.update("INGEST","Running","Reading Thai workbook data / กำลังอ่านข้อมูลจากไฟล์ Excel");
        LoanApplication application = setupMockData();
        DASHBOARD.output(TXT_FILE);

        DASHBOARD.update("GENERATE","Running","Preparing fillable template / กำลังเตรียมแบบฟอร์มที่กรอกได้");
        step1CreateTemplate();
        DASHBOARD.output(activeTemplate);
        pause("GENERATE-prep","Template ready / แบบฟอร์มพร้อมแล้ว");

        DASHBOARD.update("GENERATE","Running","Filling form / กำลังกรอกข้อมูลผู้ขอสินเชื่อ");
        step2FillTemplate(application);
        DASHBOARD.output(activeFilledPdf);
        pause("GENERATE","Filled application ready / ใบสมัครที่กรอกแล้วพร้อม");

        DASHBOARD.update("MARK","Running","Applying watermark / กำลังเพิ่มลายน้ำสำหรับจัดเก็บ");
        step3AddWatermark();
        DASHBOARD.output(activeWatermarked);
        pause("MARK","Watermarked document ready / เอกสารที่มีลายน้ำพร้อม");

        DASHBOARD.update("LOCK","Running","Encrypting with AES-256 / กำลังเข้ารหัสด้วย AES-256");
        step4AddPassword();
        DASHBOARD.output(activeProtected);
        pause("LOCK","Encrypted document ready / เอกสารที่เข้ารหัสพร้อม");

        DASHBOARD.update("SEAL","Running","Applying digital signature / กำลังลงลายมือชื่อดิจิทัล");
        step5Sign();
        DASHBOARD.output(activeKeystore);
        DASHBOARD.output(activeSigned);
        pause("SEAL","Signed document ready / เอกสารที่ลงลายมือชื่อพร้อม");

        DASHBOARD.update("ARCHIVE","Running","Converting to PDF/A-3B / กำลังแปลงเป็น PDF/A-3B");
        step6ConvertToPdfA();
        DASHBOARD.output(activePdfa);

        DASHBOARD.update("COMPLETE","Done","All demo outputs generated / สร้างไฟล์ผลลัพธ์ครบทุกไฟล์แล้ว");
        loanSummary();
    }

    private static LoanApplication setupMockData() throws Exception {
        title("INGEST / นำเข้าข้อมูล","Extract Thai workbook data / ดึงข้อมูลจากไฟล์ Excel ภาษาไทย");
        LoanApplication app = parseThaiWorkbook(THAI_WORKBOOK);
        String content = String.join("\n",
            "Product=" + app.product(),
            "Application Date=" + app.applicationDate(),
            "Applicant First Name=" + app.applicantFirstName(),
            "Applicant Last Name=" + app.applicantLastName(),
            "Relationship=" + app.relationshipToMainBorrower(),
            "Loan Amount=" + app.loanAmount(),
            "Loan Term Years=" + app.loanTermYears(),
            "Repayment Mode=" + app.repaymentMode(),
            "Purpose=" + app.loanPurpose(),
            "Spouse First Name=" + app.spouseFirstName(),
            "Spouse Last Name=" + app.spouseLastName(),
            "Spouse National ID=" + app.spouseNationalId(),
            "Employer=" + app.employer(),
            "Monthly Income=" + app.monthlyIncome(),
            "Source Workbook=" + rel(THAI_WORKBOOK),
            "Source Template=" + rel(THAI_TEMPLATE_SOURCE),"");
        Files.writeString(TXT_FILE, content, StandardCharsets.UTF_8);
        ok("Wrote data summary: " + rel(TXT_FILE));
        return app;
    }

    private static void step1CreateTemplate() throws Exception {
        title("GENERATE / สร้างเอกสาร","Prepare fillable template / เตรียมแบบฟอร์มที่กรอกได้");
        activeTemplate = TEMPLATE_PDF;
        try {
            Files.copy(THAI_TEMPLATE_SOURCE, TEMPLATE_PDF, StandardCopyOption.REPLACE_EXISTING);
            ok("Prepared template: " + rel(TEMPLATE_PDF));
        } catch (FileSystemException ex) {
            activeTemplate = THAI_TEMPLATE_SOURCE;
            ok("Using source template directly (working copy locked).");
        }
    }

    private static void step2FillTemplate(LoanApplication app) throws Exception {
        title("GENERATE / สร้างเอกสาร","Fill form with applicant data / กรอกข้อมูลผู้ขอสินเชื่อ");
        activeFilledPdf = prepareOutputPath(FILLED_PDF);
        try (PdfReader reader = new PdfReader(activeTemplate.toString());
             PdfWriter writer = new PdfWriter(activeFilledPdf.toString());
             PdfDocument pdf  = new PdfDocument(reader, writer)) {

            PdfFont font = createFont();
            PdfAcroForm form = PdfAcroForm.getAcroForm(pdf, true);
            for (PdfFormField field : form.getAllFormFields().values()) {
                if (!PdfName.Btn.equals(field.getFormType())) {
                    field.setFont(font);
                    if (field instanceof PdfTextFormField tf) tf.setFontSizeAutoScale();
                }
            }
            setDateFields(form,"applicationDateDay","applicationDateMonth","applicationDateYear",app.applicationDate());
            setCheckbox(form,"isMainBorrower",app.isMainBorrower());
            setCheckbox(form,"isJointBorrower",!app.isMainBorrower());
            setField(form,"applicantFirstName",app.applicantFirstName());
            setField(form,"applicantLastName",app.applicantLastName());
            setField(form,"relationshipToMainBorrower",app.relationshipToMainBorrower());
            setField(form,"jointBorrowerNumber",app.jointBorrowerNumber());
            setCheckbox(form,"isLoanType1Selected",true);
            setField(form,"loanType1Amount",app.loanAmount());
            setField(form,"loanType1TermYears",app.loanTermYears());
            setCheckbox(form,"loanRepaymentBySelf","Pay1".equals(app.repaymentMode()));
            setCheckbox(form,"loanRepaymentByPayrollDeduction","Pay2".equals(app.repaymentMode()));
            setCheckbox(form,"loanRepaymentByAutoDebit","Pay3".equals(app.repaymentMode()));
            setCheckbox(form,"loanRepaymentByGroup","Pay4".equals(app.repaymentMode()));
            setCheckbox(form,"loanPurposeType1Selected","type1".equals(app.loanPurpose()));
            setCheckbox(form,"loanPurposeType2Selected","type2".equals(app.loanPurpose()));
            setCheckbox(form,"loanPurposeType3Selected","type3".equals(app.loanPurpose()));
            boolean otherPurpose = !("type1".equals(app.loanPurpose())||"type2".equals(app.loanPurpose())||"type3".equals(app.loanPurpose()));
            setCheckbox(form,"loanPurposeOther",otherPurpose);
            setField(form,"loanPurposeOtherDetails",app.loanPurpose());
            setField(form,"applicantSpouseFirstName",app.spouseFirstName());
            setField(form,"applicantSpouseLastName",app.spouseLastName());
            setField(form,"applicantSpouseMaidenName",app.spouseMaidenName());
            setField(form,"applicantSpouseNationalId",app.spouseNationalId());
            setField(form,"applicantSpouseAge",app.spouseAge());
            setField(form,"applicantSpouseEmployer",app.employer());
            setField(form,"applicantSpouseMonthlyIncome",app.monthlyIncome());
            setField(form,"applicantIncomeCompanyName",app.employer());
            setField(form,"applicantIncomeMonthlySalary",app.monthlyIncome());
            setField(form,"borrowerFullName",app.applicantFirstName()+" "+app.applicantLastName());
            setDateFields(form,"borrowerSignatureDateDay","borrowerSignatureDateMonth","borrowerSignatureDateYear",app.applicationDate());
            form.flattenFields();
        }
        ok("Filled application: " + rel(activeFilledPdf));
    }

    private static void setField(PdfAcroForm form, String name, String value) {
        PdfFormField f = form.getField(name);
        if (f != null) {
            if (f instanceof PdfTextFormField tf) tf.setFontSizeAutoScale();
            f.setValue(value == null ? "" : value);
        }
    }

    private static void setCheckbox(PdfAcroForm form, String name, boolean selected) {
        PdfFormField f = form.getField(name);
        if (f != null) f.setValue(selected ? "Yes" : "Off");
    }

    private static void setDateFields(PdfAcroForm form,
            String dayF, String monthF, String yearF, LocalDate date) {
        if (date == null) return;
        setField(form, dayF,   Integer.toString(date.getDayOfMonth()));
        setField(form, monthF, Integer.toString(date.getMonthValue()));
        setField(form, yearF,  Integer.toString(date.getYear()));
    }

    private static void step3AddWatermark() throws Exception {
        title("MARK / เพิ่มลายน้ำ","Add watermark \"" + WATERMARK_TEXT + "\"");
        activeWatermarked = prepareOutputPath(WATERMARKED_PDF);
        addWatermarkTo(activeFilledPdf, activeWatermarked, WATERMARK_TEXT);
        ok("Added watermark: " + rel(activeWatermarked));
    }

    private static void step4AddPassword() throws Exception {
        title("LOCK / เข้ารหัส","Encrypt with AES-256 / เข้ารหัสด้วยรหัสผ่าน AES-256");
        activeProtected = prepareOutputPath(PROTECTED_PDF);
        encryptPdf(activeWatermarked, activeProtected);
        ok("Encrypted: " + rel(activeProtected));
        System.out.println("    Open password: " + USER_PASSWORD);
    }

    private static void step5Sign() throws Exception {
        title("SEAL / ลงลายมือชื่อ","Apply digital signature / ลงลายมือชื่อดิจิทัล");
        activeSigned   = prepareOutputPath(SIGNED_PDF);
        activeKeystore = prepareOutputPath(KEYSTORE);
        prepareKeystoreWith(
                "CN=Apryse SE Demo, OU=Solutions Engineering, O=Apryse, L=Shenzhen, C=CN",
                activeKeystore);
        Calendar signDate = Calendar.getInstance();
        signPdf(activeProtected, activeSigned, activeKeystore, signDate,
                "Thai personal loan archival signing","Bangkok, Thailand");
        ok("Signed: " + rel(activeSigned));
    }

    private static void step6ConvertToPdfA() throws Exception {
        title("ARCHIVE / จัดเก็บ","Convert to PDF/A-3B / แปลงเป็นไฟล์จัดเก็บ PDF/A-3B");
        activePdfa = prepareOutputPath(PDFA_FILE);
        byte[] iccBytes = ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();
        try (PdfDocument src   = new PdfDocument(new PdfReader(activeWatermarked.toString()));
             PdfADocument pdfa = new PdfADocument(new PdfWriter(activePdfa.toString()),
                     PdfAConformance.PDF_A_3B,
                     new PdfOutputIntent("Custom","","https://www.color.org","sRGB IEC61966-2.1",
                             new ByteArrayInputStream(iccBytes)))) {
            pdfa.getDocumentInfo().setTitle("Thai Personal Loan Application Demo");
            pdfa.getDocumentInfo().setAuthor("Apryse iText Demo");
            src.copyPagesTo(1, src.getNumberOfPages(), pdfa);
        }
        ok("PDF/A archive: " + rel(activePdfa));
    }

    // =========================================================================
    //  SHARED HELPERS
    // =========================================================================

    private static Path prepareOutputPath(Path preferred) throws Exception {
        try { Files.deleteIfExists(preferred); return preferred; }
        catch (FileSystemException ex) { return versionedOutputPath(preferred); }
    }

    private static Path versionedOutputPath(Path preferred) {
        String fn  = preferred.getFileName().toString();
        int dot    = fn.lastIndexOf('.');
        String base = dot >= 0 ? fn.substring(0, dot) : fn;
        String ext  = dot >= 0 ? fn.substring(dot) : "";
        return preferred.getParent().resolve(base + "_" + System.currentTimeMillis() + ext);
    }

    private static PdfFont createFont() throws Exception {
        return PdfFontFactory.createFont(CHINESE_FONT, PdfEncodings.IDENTITY_H,
                EmbeddingStrategy.FORCE_EMBEDDED);
    }

    private static String rel(Path path) {
        try { return PROJECT_DIR.relativize(path).toString(); }
        catch (Exception e) { return path.toString(); }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String fmtCalendar(Calendar c) {
        if (c == null) return "";
        return String.format("%04d-%02d-%02d %02d:%02d:%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH)+1, c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND));
    }

    private static void banner(String scenario) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        if ("loan".equalsIgnoreCase(scenario)) {
            System.out.println("║  Apryse · iText (Java) Thai Personal Loan Demo               ║");
            System.out.println("║  เดโมสินเชื่อส่วนบุคคล (ภาษาไทย)                              ║");
        } else {
            System.out.println("║  Bangkok Bank · Amendment Confirmation Letter Demo            ║");
            System.out.println("║  เดโมจดหมายยืนยันการแก้ไขบัญชี · ธนาคารกรุงเทพ               ║");
        }
        System.out.println("║  iText pdfHTML · PDF/A-3B · AES-256 · CMS Signature          ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println("  Working dir: " + PROJECT_DIR);
    }

    private static void title(String step, String desc) {
        System.out.println("\n────────────────────────────────────────────────────────────────");
        System.out.println("  " + step + "  —  " + desc);
        System.out.println("────────────────────────────────────────────────────────────────");
    }

    private static void ok(String msg) { System.out.println("  [✓] " + msg); }

    private static void pause(String step, String message) {
        DASHBOARD.update(step, "Waiting", message);
        System.out.print("\n>>> Press ENTER to continue / กด ENTER เพื่อไปต่อ ... ");
        SCANNER.nextLine();
    }

    private static void startDashboard(String scenario) {
        List<Path> tracked = "loan".equalsIgnoreCase(scenario)
                ? Arrays.asList(TXT_FILE, TEMPLATE_PDF, FILLED_PDF,
                        WATERMARKED_PDF, PROTECTED_PDF, KEYSTORE, SIGNED_PDF, PDFA_FILE)
                : Arrays.asList(EXTRACTED_JSON, LETTER_PDF, LETTER_WATERMARKED,
                        LETTER_PROTECTED, AMENDMENT_KEYSTORE, LETTER_SIGNED, LETTER_ARCHIVE);
        DASHBOARD.start(STATUS_PORT, tracked, scenario);
        String url = "http://localhost:" + STATUS_PORT + "/";
        System.out.println("  Dashboard: " + url);
        if (Desktop.isDesktopSupported()) {
            try { Desktop.getDesktop().browse(URI.create(url)); }
            catch (Exception e) { System.out.println("  (Cannot open browser: " + e.getMessage() + ")"); }
        }
    }

    private static void amendmentSummary(Path extracted, Path letter, Path watermarked,
            Path protectedPdf, Path ks, Path signed, Path archive, boolean tamper) {
        System.out.println("\n╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║  DEMO COMPLETE / เดโมเสร็จสมบูรณ์                            ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println("  INGEST  → " + rel(extracted));
        System.out.println("  GENERATE→ " + rel(letter));
        System.out.println("  MARK    → " + rel(watermarked));
        System.out.println("  LOCK    → " + rel(protectedPdf) + "  (pwd=" + USER_PASSWORD + ")");
        System.out.println("  SEAL    → " + rel(ks) + " / " + rel(signed));
        System.out.println("  ARCHIVE → " + rel(archive));
        if (tamper) System.out.println("  VERIFY  → confirmation_letter_tampered.pdf");
    }

    private static void loanSummary() {
        System.out.println("\n╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║  LOAN DEMO COMPLETE / เดโมสินเชื่อเสร็จสมบูรณ์               ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println("  1 " + rel(activeSigned) + "  (protected + signed)");
        System.out.println("  2 " + rel(activePdfa)   + "  (PDF/A-3B archive)");
        System.out.println("  Open password: " + USER_PASSWORD);
    }

    // =========================================================================
    //  LOAN WORKBOOK PARSING
    // =========================================================================

    private static LoanApplication parseThaiWorkbook(Path workbook) throws Exception {
        try (ZipFile zip = new ZipFile(workbook.toFile())) {
            List<String> ss   = parseSharedStrings(zip, "xl/sharedStrings.xml");
            List<Map<String,String>> rows = parseWorksheetRows(zip, "xl/worksheets/sheet1.xml", ss);
            Map<String,String> bestRow = rows.stream()
                    .filter(r -> !cell(r,"E").isBlank() && !cell(r,"F").isBlank())
                    .max((a,b) -> Integer.compare(nonBlankCount(a), nonBlankCount(b)))
                    .orElseThrow(() -> new IllegalStateException("No applicant rows found"));
            return new LoanApplication(
                    cell(rows.get(0),"B"), excelSerialToDate(cell(bestRow,"B")),
                    "Y".equalsIgnoreCase(cell(bestRow,"C")),
                    cell(bestRow,"E"), cell(bestRow,"F"),
                    normalizeBlank(cell(bestRow,"G")), normalizeBlank(cell(bestRow,"D")),
                    cell(bestRow,"P"), cell(bestRow,"Q"),
                    repaymentMode(bestRow), loanPurpose(bestRow),
                    normalizeBlank(cell(bestRow,"AF")), normalizeBlank(cell(bestRow,"AG")),
                    normalizeBlank(cell(bestRow,"AH")), normalizeBlank(cell(bestRow,"AI")),
                    normalizeBlank(cell(bestRow,"AK")), normalizeBlank(cell(bestRow,"AL")),
                    normalizeBlank(cell(bestRow,"AM")));
        }
    }

    private static List<String> parseSharedStrings(ZipFile zip, String name) throws Exception {
        ZipEntry e = zip.getEntry(name);
        if (e == null) return List.of();
        try (InputStream in = zip.getInputStream(e)) {
            org.w3c.dom.Document doc = xmlDoc(in);
            NodeList items = doc.getElementsByTagNameNS("*","si");
            List<String> ss = new ArrayList<>(items.getLength());
            for (int i = 0; i < items.getLength(); i++)
                ss.add(joinText((Element)items.item(i),"t"));
            return ss;
        }
    }

    private static List<Map<String,String>> parseWorksheetRows(ZipFile zip, String name,
            List<String> ss) throws Exception {
        ZipEntry e = zip.getEntry(name);
        if (e == null) throw new IllegalStateException("Worksheet not found: " + name);
        try (InputStream in = zip.getInputStream(e)) {
            org.w3c.dom.Document doc = xmlDoc(in);
            NodeList rowNodes = doc.getElementsByTagNameNS("*","row");
            List<Map<String,String>> rows = new ArrayList<>(rowNodes.getLength());
            for (int i = 0; i < rowNodes.getLength(); i++) {
                Element rowEl = (Element)rowNodes.item(i);
                NodeList cellNodes = rowEl.getElementsByTagNameNS("*","c");
                Map<String,String> row = new LinkedHashMap<>();
                row.put("_row", rowEl.getAttribute("r"));
                for (int j = 0; j < cellNodes.getLength(); j++) {
                    Element c = (Element)cellNodes.item(j);
                    String ref = c.getAttribute("r");
                    String col = ref.replaceAll("\\d","");
                    String raw = firstChild(c,"v");
                    String val = "s".equals(c.getAttribute("t")) ? ss.get(Integer.parseInt(raw)) : raw;
                    row.put(col, val == null ? "" : val.trim());
                }
                rows.add(row);
            }
            return rows;
        }
    }

    private static org.w3c.dom.Document xmlDoc(InputStream in) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        return f.newDocumentBuilder().parse(in);
    }

    private static String joinText(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagNameNS("*",tag);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nl.getLength(); i++) sb.append(nl.item(i).getTextContent());
        return sb.toString();
    }

    private static String firstChild(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagNameNS("*",tag);
        if (nl.getLength() == 0) return "";
        Node n = nl.item(0);
        return n == null ? "" : n.getTextContent();
    }

    private static String cell(Map<String,String> row, String col) { return row.getOrDefault(col,""); }

    private static int nonBlankCount(Map<String,String> row) {
        int c = 0; for (String v : row.values()) if (!v.isBlank()) c++; return c;
    }

    private static String repaymentMode(Map<String,String> r) {
        if ("Y".equalsIgnoreCase(cell(r,"R"))) return "Pay1";
        if ("Y".equalsIgnoreCase(cell(r,"S"))) return "Pay2";
        if ("Y".equalsIgnoreCase(cell(r,"T"))) return "Pay3";
        if ("Y".equalsIgnoreCase(cell(r,"U"))) return "Pay4";
        return "";
    }

    private static String loanPurpose(Map<String,String> r) {
        if ("Y".equalsIgnoreCase(cell(r,"V"))) return "type1";
        if ("Y".equalsIgnoreCase(cell(r,"W"))) return "type2";
        if ("Y".equalsIgnoreCase(cell(r,"X"))) return "type3";
        return "other";
    }

    private static LocalDate excelSerialToDate(String serial) {
        if (serial == null || serial.isBlank()) return null;
        return LocalDate.of(1899,12,30).plusDays(Long.parseLong(serial));
    }

    private static String normalizeBlank(String v) { return "-".equals(v) ? "" : v; }

    // =========================================================================
    //  STATUS DASHBOARD
    // =========================================================================

    private static final class StatusDashboard {
        private static final String[][] AMEND_STAGES = {
            {"INGEST",   "Ingest",    "นำเข้าข้อมูล",  "Normalise raw Apryse JSON into canonical extracted.json",        "แปลง JSON ดิบของ Apryse ให้เป็น extracted.json ที่เป็นมาตรฐาน"},
            {"GENERATE", "Generate", "สร้างเอกสาร",    "Produce bilingual Amendment Confirmation Letter via pdfHTML",    "สร้างจดหมายยืนยันการแก้ไขบัญชีสองภาษาด้วย pdfHTML"},
            {"MARK",     "Watermark","เพิ่มลายน้ำ",    "Stamp SPECIMEN watermark on every page",                        "ประทับลายน้ำ SPECIMEN บนทุกหน้า"},
            {"LOCK",     "Encrypt",  "เข้ารหัส",       "Protect with AES-256 password encryption",                     "ป้องกันด้วยการเข้ารหัส AES-256"},
            {"SEAL",     "Sign",     "ลงลายมือชื่อ",    "Apply CMS digital signature + signing time",                   "ลงลายมือชื่อดิจิทัล CMS พร้อมเวลาลงลายมือชื่อ"},
            {"ARCHIVE",  "Archive",  "จัดเก็บ",         "Create PDF/A-3B with embedded extracted.json + raw JSON",      "สร้าง PDF/A-3B พร้อมฝัง JSON ทั้งสองไฟล์"},
            {"VERIFY",   "Verify",   "ตรวจสอบ",         "Verify signature integrity and authenticity",                  "ตรวจสอบความถูกต้องของลายมือชื่อดิจิทัล"},
            {"COMPLETE", "Complete", "เสร็จสมบูรณ์",    "All demo outputs generated",                                   "สร้างไฟล์ผลลัพธ์ครบทุกไฟล์แล้ว"},
        };
        private static final String[][] LOAN_STAGES = {
            {"INGEST",   "Ingest",    "นำเข้าข้อมูล",  "Extract applicant data from the Thai Excel workbook",           "ดึงข้อมูลผู้ขอสินเชื่อจากไฟล์ Excel ภาษาไทย"},
            {"GENERATE", "Generate", "สร้างเอกสาร",    "Load and fill the interactive loan application form",           "โหลดและกรอกแบบฟอร์มสินเชื่อที่กรอกได้"},
            {"MARK",     "Watermark","เพิ่มลายน้ำ",    "Stamp the archival watermark on every page",                   "ประทับลายน้ำสำหรับจัดเก็บบนทุกหน้า"},
            {"LOCK",     "Encrypt",  "เข้ารหัส",       "Protect the file with an AES-256 password",                    "ป้องกันไฟล์ด้วยรหัสผ่าน AES-256"},
            {"SEAL",     "Sign",     "ลงลายมือชื่อ",    "Apply a CMS digital signature",                                "ลงลายมือชื่อดิจิทัลแบบ CMS"},
            {"ARCHIVE",  "Archive",  "จัดเก็บ",         "Convert to a PDF/A-3B archival copy",                         "แปลงเป็นสำเนาจัดเก็บ PDF/A-3B"},
            {"COMPLETE", "Complete", "เสร็จสมบูรณ์",    "All demo outputs generated",                                   "สร้างไฟล์ผลลัพธ์ครบทุกไฟล์แล้ว"},
        };

        private HttpServer server;
        private String step    = "Not started";
        private String state   = "Idle";
        private String message = "Waiting to start demo. / รอเริ่มเดโม";
        private String   presenterArtifact = "";
        private String[] presenterShowNow  = new String[0];
        private String   presenterSayNow   = "";
        private List<Path> trackedOutputs  = List.of();
        private String[][] stages          = AMEND_STAGES;

        synchronized void start(int port, List<Path> outputs, String scenario) {
            this.trackedOutputs = new ArrayList<>(outputs);
            this.stages = "loan".equalsIgnoreCase(scenario) ? LOAN_STAGES : AMEND_STAGES;
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
                server.createContext("/", this::handleHome);
                server.createContext("/status", this::handleStatus);
                server.setExecutor(null);
                server.start();
                update("Starting","Running","Launching demo / กำลังเริ่มเดโม");
            } catch (Exception ex) {
                System.out.println("  [i] Dashboard unavailable (port " + port + " in use): " + ex.getMessage());
            }
        }

        synchronized void update(String s, String st, String m) { step=s; state=st; message=m; }

        synchronized void output(Path p) { if (!trackedOutputs.contains(p)) trackedOutputs.add(p); }

        synchronized void setPresenterCue(String stage, String artifact, String[] showNow, String sayNow) {
            this.presenterArtifact = artifact;
            this.presenterShowNow  = showNow;
            this.presenterSayNow   = sayNow;
        }

        synchronized void stop() { if (server != null) { server.stop(0); server = null; } }

        private void handleHome(HttpExchange ex) throws java.io.IOException {
            byte[] body = htmlPage().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type","text/html; charset=UTF-8");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) { out.write(body); }
        }

        private void handleStatus(HttpExchange ex) throws java.io.IOException {
            byte[] body = jsonStatus().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type","application/json; charset=UTF-8");
            ex.getResponseHeaders().set("Cache-Control","no-store");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) { out.write(body); }
        }

        private synchronized String jsonStatus() {
            int current = currentIdx();
            boolean complete = "COMPLETE".equals(step);
            String stageEn = complete ? "Complete" : (current >= 0 ? stages[current][1] : "Starting");
            String stageTh = complete ? "เสร็จสมบูรณ์" : (current >= 0 ? stages[current][2] : "กำลังเริ่ม");
            boolean waiting = "Waiting".equals(state);

            StringBuilder j = new StringBuilder("{");
            j.append("\"step\":\"").append(esc(step)).append("\",");
            j.append("\"state\":\"").append(esc(state)).append("\",");
            j.append("\"message\":\"").append(esc(message)).append("\",");
            j.append("\"stageEn\":\"").append(esc(stageEn)).append("\",");
            j.append("\"stageTh\":\"").append(esc(stageTh)).append("\",");
            j.append("\"waiting\":").append(waiting).append(",");
            j.append("\"presenterCue\":{\"artifact\":\"").append(esc(presenterArtifact)).append("\",");
            j.append("\"showNow\":[");
            for (int i = 0; i < presenterShowNow.length; i++) {
                if (i > 0) j.append(",");
                j.append("\"").append(esc(presenterShowNow[i])).append("\"");
            }
            j.append("],\"sayNow\":\"").append(esc(presenterSayNow)).append("\"},");
            j.append("\"steps\":[");
            for (int i = 0; i < stages.length; i++) {
                if (i > 0) j.append(",");
                String st = (complete || i < current) ? "done" : (i == current ? "current" : "pending");
                j.append("{\"index\":").append(i+1)
                 .append(",\"titleEn\":\"").append(esc(stages[i][1])).append("\"")
                 .append(",\"titleTh\":\"").append(esc(stages[i][2])).append("\"")
                 .append(",\"detailEn\":\"").append(esc(stages[i][3])).append("\"")
                 .append(",\"detailTh\":\"").append(esc(stages[i][4])).append("\"")
                 .append(",\"status\":\"").append(st).append("\"}");
            }
            j.append("],\"outputs\":[");
            for (int i = 0; i < trackedOutputs.size(); i++) {
                Path p = trackedOutputs.get(i);
                if (i > 0) j.append(",");
                j.append("{\"name\":\"").append(esc(rel(p)))
                 .append("\",\"exists\":").append(Files.exists(p)).append("}");
            }
            j.append("]}");
            return j.toString();
        }

        private int currentIdx() {
            for (int i = 0; i < stages.length; i++) if (stages[i][0].equals(step)) return i;
            return -1;
        }

        private String htmlPage() {
            // language=html
            return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="UTF-8"/>
                <meta name="viewport" content="width=device-width,initial-scale=1.0"/>
                <title>Bangkok Bank · iText Demo</title>
                <style>
                :root{--bg:#f4efe6;--panel:rgba(255,255,255,0.88);--ink:#1d2b38;--muted:#607284;
                --accent:#0b4f8a;--accent-soft:#dce9f7;--line:rgba(29,43,56,0.12);
                --shadow:0 24px 60px rgba(21,35,47,0.12);--wait:#a5691a;--wait-bg:#fdf3e0;}
                *{box-sizing:border-box;}
                body{margin:0;min-height:100vh;font-family:Georgia,"Times New Roman",serif;color:var(--ink);
                background:radial-gradient(circle at top left,rgba(11,79,138,0.14),transparent 34%),
                radial-gradient(circle at bottom right,rgba(180,130,40,0.13),transparent 30%),
                linear-gradient(135deg,#f8f4ed,#ece4d6 58%,#f4efe6);padding:28px;}
                .shell{max-width:1020px;margin:0 auto;background:var(--panel);
                border:1px solid rgba(255,255,255,0.65);border-radius:24px;box-shadow:var(--shadow);
                backdrop-filter:blur(18px);overflow:hidden;}
                .hero{padding:32px 36px 22px;border-bottom:1px solid var(--line);}
                .eyebrow{font-size:11px;letter-spacing:.22em;text-transform:uppercase;color:var(--muted);margin-bottom:10px;}
                h1{margin:0;font-size:clamp(24px,4vw,42px);line-height:1;font-weight:700;}
                .sub{margin-top:10px;max-width:700px;color:var(--muted);font-size:15px;line-height:1.5;}
                .grid{display:grid;grid-template-columns:1.15fr 0.85fr;}
                .panel{padding:26px 30px 30px;}
                .panel+.panel{border-left:1px solid var(--line);}
                .lbl{color:var(--muted);font-size:11px;letter-spacing:.14em;text-transform:uppercase;}
                .stage{margin-top:8px;font-size:28px;font-weight:700;}
                .pill{display:inline-flex;margin-top:12px;padding:6px 13px;border-radius:999px;
                background:var(--accent-soft);color:var(--accent);font-size:12px;font-weight:700;
                letter-spacing:.06em;text-transform:uppercase;}
                .pill.wait{background:var(--wait-bg);color:var(--wait);}
                .msg{margin-top:13px;font-size:15px;line-height:1.5;}
                #nowPanel{border-top:1px solid var(--line);padding:22px 30px;display:none;}
                #nowPanel.on{display:block;background:#fffbf2;}
                .now-lbl{font-size:12px;letter-spacing:.16em;text-transform:uppercase;color:var(--wait);font-weight:700;margin-bottom:8px;}
                .now-art{font-size:20px;font-weight:700;margin-bottom:12px;font-family:monospace;}
                .now-list{list-style:none;padding:0;margin:0 0 12px;}
                .now-list li{padding:4px 0;font-size:14px;}
                .now-list li::before{content:"▶  ";color:var(--wait);}
                .now-say{font-size:16px;font-style:italic;padding:11px 15px;
                background:rgba(165,105,26,0.08);border-radius:8px;border-left:3px solid var(--wait);}
                ul{list-style:none;padding:0;margin:14px 0 0;}
                li{display:flex;justify-content:space-between;gap:10px;align-items:center;
                padding:11px 0;border-bottom:1px solid var(--line);font-size:13px;}
                li:last-child{border-bottom:0;}
                .dot{width:9px;height:9px;border-radius:50%;background:#c7ced4;flex:0 0 auto;}
                .done .dot{background:var(--accent);}
                .fr{display:flex;align-items:center;gap:10px;min-width:0;}
                .nm{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-family:monospace;font-size:12px;}
                .fs{color:var(--muted);font-size:11px;text-transform:uppercase;letter-spacing:.07em;}
                .steps{list-style:none;padding:0;margin:14px 0 0;}
                .steps li{display:grid;grid-template-columns:30px 1fr auto;align-items:start;
                gap:10px;padding:13px 0;border-bottom:1px solid var(--line);}
                .steps li:last-child{border-bottom:0;}
                .num{width:30px;height:30px;border-radius:50%;display:flex;align-items:center;
                justify-content:center;font-weight:700;font-size:13px;background:#e7e2d7;
                color:var(--muted);border:1px solid var(--line);flex:0 0 auto;}
                .steps li.done .num{background:var(--accent);color:#fff;border-color:var(--accent);}
                .steps li.current .num{background:#fff;color:var(--accent);border:2px solid var(--accent);
                box-shadow:0 0 0 4px rgba(11,79,138,0.1);}
                .en{font-size:15px;font-weight:700;line-height:1.25;}
                .en-th{font-size:13px;color:var(--ink);margin-top:2px;
                font-family:"Leelawadee UI","Tahoma","Noto Sans Thai",sans-serif;}
                .det{color:var(--muted);font-size:12px;line-height:1.4;margin-top:4px;}
                .steps li.pending .en,.steps li.pending .en-th{color:var(--muted);}
                .sp{align-self:center;padding:4px 10px;border-radius:999px;font-size:11px;
                font-weight:700;letter-spacing:.06em;text-transform:uppercase;white-space:nowrap;}
                .sp.done{background:var(--accent-soft);color:var(--accent);}
                .sp.current{background:#fbe9c9;color:#a5691a;}
                .sp.pending{background:#ececec;color:#8a8a8a;}
                .th{font-family:"Leelawadee UI","Tahoma","Noto Sans Thai",sans-serif;}
                @media(max-width:820px){body{padding:12px;}.grid{grid-template-columns:1fr;}
                .panel+.panel{border-left:0;border-top:1px solid var(--line);}
                .hero,.panel{padding:18px;}}
                </style>
                </head>
                <body>
                <div class="shell">
                <section class="hero">
                <div class="eyebrow">Live Demo Monitor / จอแสดงสถานะเดโม</div>
                <h1>Bangkok Bank · iText Document Pipeline</h1>
                <div class="sub">Watch each pipeline stage as the terminal demo runs.<br/>
                <span class="th">ติดตามแต่ละขั้นตอนของท่อประมวลผลเอกสาร iText ขณะเดโมทำงาน</span></div>
                </section>
                <section class="grid">
                <div class="panel">
                <div class="lbl">Current stage / ขั้นตอนปัจจุบัน</div>
                <div class="stage" id="stageEn">Starting</div>
                <div class="stage th" id="stageTh" style="font-size:18px;color:var(--muted);margin-top:4px;">กำลังเริ่ม</div>
                <div class="pill" id="statePill">Running</div>
                <div class="msg" id="message">Launching demo…</div>
                </div>
                <div class="panel">
                <div class="lbl">Generated outputs / ไฟล์ที่สร้าง</div>
                <ul id="outputs"></ul>
                </div>
                </section>
                <section id="nowPanel">
                <div class="now-lbl">⏸ NOW — PRESENTER CUE / สัญญาณผู้นำเสนอ</div>
                <div class="now-art" id="nowArt"></div>
                <ul class="now-list" id="nowList"></ul>
                <div class="now-say" id="nowSay"></div>
                </section>
                <section class="panel" style="border-top:1px solid var(--line);">
                <div class="lbl">Workflow steps / ขั้นตอนการทำงาน</div>
                <ul class="steps" id="steps"></ul>
                </section>
                </div>
                <script>
                function sp(s){
                if(s==='done') return '<span class="sp done">Done / เสร็จ</span>';
                if(s==='current') return '<span class="sp current">In progress / กำลังทำ</span>';
                return '<span class="sp pending">Pending / รอ</span>';}
                async function refresh(){
                const d=await(await fetch('/status',{cache:'no-store'})).json();
                document.getElementById('stageEn').textContent=d.stageEn;
                document.getElementById('stageTh').textContent=d.stageTh;
                const p=document.getElementById('statePill');
                p.textContent=d.state;p.className='pill'+(d.waiting?' wait':'');
                document.getElementById('message').textContent=d.message;
                const np=document.getElementById('nowPanel');
                const c=d.presenterCue;
                if(d.waiting&&c&&c.artifact){np.classList.add('on');
                document.getElementById('nowArt').textContent=c.artifact;
                const nl=document.getElementById('nowList');nl.innerHTML='';
                (c.showNow||[]).forEach(s=>{const li=document.createElement('li');li.textContent=s;nl.appendChild(li);});
                document.getElementById('nowSay').textContent=c.sayNow||'';}
                else{np.classList.remove('on');}
                const sl=document.getElementById('steps');sl.innerHTML='';
                for(const s of d.steps){const li=document.createElement('li');li.className=s.status;
                li.innerHTML=`<span class="num">${s.index}</span><div><div class="en">${s.titleEn}</div>
                <div class="en-th">${s.titleTh}</div><div class="det">${s.detailEn}<br/><span class="th">${s.detailTh}</span></div></div>${sp(s.status)}`;
                sl.appendChild(li);}
                const ol=document.getElementById('outputs');ol.innerHTML='';
                for(const item of d.outputs){const li=document.createElement('li');
                if(item.exists)li.className='done';
                li.innerHTML=`<div class="fr"><span class="dot"></span><span class="nm">${item.name}</span></div><span class="fs">${item.exists?'Ready / พร้อม':'Pending / รอ'}</span>`;
                ol.appendChild(li);}
                }
                refresh();setInterval(refresh,1000);
                </script>
                </body>
                </html>
                """;
        }

        private static String esc(String v) {
            return v.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");
        }
    }

    // =========================================================================
    //  DATA RECORD
    // =========================================================================

    private record LoanApplication(
            String product, LocalDate applicationDate, boolean isMainBorrower,
            String applicantFirstName, String applicantLastName,
            String relationshipToMainBorrower, String jointBorrowerNumber,
            String loanAmount, String loanTermYears, String repaymentMode, String loanPurpose,
            String spouseFirstName, String spouseLastName, String spouseMaidenName,
            String spouseNationalId, String spouseAge, String employer, String monthlyIncome
    ) {}
}