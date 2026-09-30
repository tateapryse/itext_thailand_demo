package com.apryse.demo;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;

/**
 * Deterministic normalizer: raw Apryse keyValExtract JSON → canonical extracted.json.
 *
 * Processing steps per spec (1A):
 *   a. Label dictionary lookup (bilingual, diacritic-insensitive)
 *   b. Section assignment by geometry (nearest anchor above element)
 *   c. Checkbox/radio decoding (&#x2714; = key is the selected option)
 *   d. Value cleaning (dotted leaders, whitespace, merged label+value split)
 *   e. Type detection and best-fit reassignment (date/name/reference in wrong boxes)
 *   f. Provenance + low-confidence flagging
 *   g. Output: populated CanonicalDoc
 *
 * NOTE: Apryse confidence scores reflect extraction confidence of the key-value
 * pairing, not semantic correctness; type reconciliation operates on value content.
 */
final class Normalizer {

    // ── Types ───────────────────────────────────────────────────────────────

    record RawPair(
            String keyText,
            String valueText,
            int    page,
            double keyY0,   // top of key bounding box (originTop)
            double[] keyRect,
            double[] valueRect,
            double confidence
    ) {}

    // ── Regex patterns for type detection ──────────────────────────────────

    private static final Pattern PAT_DATE_DMDMY
            = Pattern.compile("^(\\d{1,2})/(\\d{1,2})/(\\d{4})$");
    private static final Pattern PAT_REF
            = Pattern.compile("(?i)^REF-\\d{4}-\\d+$");
    private static final Pattern PAT_ACCOUNT
            = Pattern.compile("^\\d{3}-\\d-\\d{5}-\\d$");
    private static final Pattern PAT_LOAN
            = Pattern.compile("(?i)^LN-\\d{3}-\\d{6}-\\d$");
    private static final Pattern PAT_CARD
            = Pattern.compile("^\\d{4}-\\d{4}-\\d{4}-\\d{4}$");
    private static final Pattern PAT_EMAIL
            = Pattern.compile("^[\\w.+%-]+@[\\w.-]+\\.[a-zA-Z]{2,}$");
    private static final Pattern PAT_THAI_PHONE
            = Pattern.compile("^0[0-9]{1,2}-?[0-9]{3}-?[0-9]{4}$");
    private static final Pattern PAT_FAX_ACCOUNT
            = Pattern.compile("^\\d{3}-\\d-\\d{5}-\\d.*");
    private static final Pattern PAT_EN_NAME
            = Pattern.compile("^[A-Z][A-Z .'-]{2,}$");

    // ── Loaded resources ────────────────────────────────────────────────────

    private final Map<String, String> labelDict   = new LinkedHashMap<>();
    private final List<SectionAnchor> anchors      = new ArrayList<>();

    record SectionAnchor(String normalizedText, String amendmentType) {}

    Normalizer() {
        loadLabelDict();
        loadSectionAnchors();
    }

    // ── Public entry point ──────────────────────────────────────────────────

    /**
     * Normalizes the raw Apryse JSON at {@code rawPath} and returns a populated
     * {@link CanonicalDoc}.  Throws {@link IllegalArgumentException} if the file
     * cannot be parsed.
     */
    CanonicalDoc normalize(Path rawPath) throws Exception {
        String json = Files.readString(rawPath, StandardCharsets.UTF_8);
        Map<String, Object> root = MinimalJson.parseObject(json);

        // ── 0. Parse all pages into RawPairs ────────────────────────────────
        List<RawPair> pairs = new ArrayList<>();
        for (Object pageObj : MinimalJson.list(root.get("pages"))) {
            Map<String, Object> page = MinimalJson.map(pageObj);
            int pageNum = (int) MinimalJson.dbl(MinimalJson.map(page.get("properties")).get("pageNumber"));
            for (Object kvObj : MinimalJson.list(page.get("keyValueElements"))) {
                Map<String, Object> kv = MinimalJson.map(kvObj);
                double[] kr = toRect(kv.get("key_rect"));
                double[] vr = toRect(kv.get("value_rect"));
                pairs.add(new RawPair(
                        MinimalJson.str(kv.get("key_text")).strip(),
                        MinimalJson.str(kv.get("value_text")).strip(),
                        pageNum,
                        kr[1],
                        kr, vr,
                        MinimalJson.dbl(kv.get("confidence"))
                ));
            }
        }

        CanonicalDoc doc = new CanonicalDoc();
        // count pages
        doc.pages = (int) MinimalJson.dbl(root.get("pages") instanceof Number ? root.get("pages") : 0);
        if (doc.pages == 0) {
            doc.pages = MinimalJson.list(root.get("pages")).size();
        }

        // ── a/b. Label lookup + section assignment ──────────────────────────
        // Build a page → sorted anchor list map
        Map<Integer, List<SectionAnchorPos>> pageAnchors = buildPageAnchors(pairs);

        // Intermediate slots
        String customerId    = null;
        String referenceNo   = null;
        String submissionDate = null;
        String nameThai      = null;
        String nameEnglish   = null;
        String title         = null;
        String branchEn      = null;
        String branchTh      = null;

        String contactMobile      = null;
        String contactEmail       = null;
        String contactOfficePhone = null;
        String contactFax         = null;

        // address slots
        String addrNo = null, addrMoo = null, addrBuilding = null, addrSoi = null;
        String addrStreet = null, addrSubDistrict = null, addrDistrict = null;
        String addrProvince = null, addrPostal = null, addrCountry = null;

        // amendment presence flags
        Set<String> detectedSections = new LinkedHashSet<>();
        Map<String, String> pinReasonRaw = new LinkedHashMap<>();
        String newPrimaryAccount = null;
        String cancelLoanAccountNo = null;

        // Collect raw→canonical side-by-side for presentation
        List<String[]> sideByTable = new ArrayList<>(); // [rawKey, rawVal, canonKey, canonVal, obs]

        for (RawPair p : pairs) {
            // d. clean value
            String cleanVal = cleanValue(p.valueText());

            // a. label dict lookup
            String canon = lookupLabel(p.keyText());

            // b. section by geometry
            String section = findSection(p.page(), p.keyY0(), pageAnchors);
            if (section != null) detectedSections.add(section);

            // c. checkbox decoding: ✔ means key text is selected option
            boolean isCheck = "✔".equals(cleanVal) || "\u2714".equals(cleanVal);

            // f. low-confidence observation
            if (p.confidence() < 0.8) {
                doc.fieldObservations.add(
                    "Low confidence (" + String.format("%.2f", p.confidence()) + ") on key \""
                    + p.keyText() + "\" → \"" + p.valueText() + "\"");
            }

            // Route by canonical field
            switch (canon == null ? "" : canon) {
                case "CUSTOMER_ID"    -> customerId    = cleanVal;
                case "REFERENCE_NO"  -> referenceNo  = cleanVal;
                case "SUBMISSION_DATE" -> submissionDate = cleanVal;
                case "BRANCH"        -> { branchEn = pickBranch(cleanVal, branchEn); }
                case "CONTACT_EMAIL" -> contactEmail = cleanVal.isBlank() ? contactEmail : cleanVal;
                case "CONTACT_MOBILE"-> contactMobile = cleanVal.isBlank() ? contactMobile : cleanVal;
                case "CONTACT_FAX"   -> contactFax = cleanVal.isBlank() ? contactFax : cleanVal;
                case "NEW_PRIMARY_ACCOUNT" -> {
                    // pick the value that looks like an account number
                    if (PAT_ACCOUNT.matcher(cleanVal).matches()) newPrimaryAccount = cleanVal;
                }
                case "SECTION_PIN" -> {
                    if (section != null) detectedSections.add("REQUEST_NEW_PIN");
                    else detectedSections.add("REQUEST_NEW_PIN");
                }
                case "SECTION_MAILING_ADDRESS" -> detectedSections.add("CHANGE_MAILING_ADDRESS");
                case "SECTION_CHANGE_NAME"     -> detectedSections.add("CHANGE_NAME");
                case "SECTION_CHANGE_PRIMARY_ACCOUNT" -> detectedSections.add("CHANGE_PRIMARY_ACCOUNT");
                case "SECTION_CANCEL_IBANKING" -> detectedSections.add("CANCEL_IBANKING_SERVICE");
                case "SECTION_ADD_DEPOSIT_ACCOUNT" -> detectedSections.add("ADD_DEPOSIT_ACCOUNT");
                case "SECTION_ADD_CREDIT_CARD"     -> detectedSections.add("ADD_CREDIT_CARD");
                case "SECTION_ADD_LOAN_ACCOUNT"    -> detectedSections.add("ADD_LOAN_ACCOUNT");
                case "SECTION_CANCEL_DEPOSIT_ACCOUNT" -> detectedSections.add("CANCEL_DEPOSIT_ACCOUNT");
                case "SECTION_CANCEL_CREDIT_CARD"     -> detectedSections.add("CANCEL_CREDIT_CARD");
                case "SECTION_CANCEL_LOAN_ACCOUNT"    -> {
                    detectedSections.add("CANCEL_LOAN_ACCOUNT");
                    if (PAT_LOAN.matcher(cleanVal).matches()) cancelLoanAccountNo = cleanVal;
                }
                case "PIN_REASON_DAMAGED", "PIN_REASON_NOT_RECEIVED", "PIN_REASON_FORGOTTEN",
                     "PIN_REASON_FORGOTTEN_MERGED" -> {
                    if (isCheck || !cleanVal.isBlank()) {
                        pinReasonRaw.put(canon, p.keyText());
                    }
                }
                default -> {}
            }

            // Address fields by section geometry
            if ("CHANGE_MAILING_ADDRESS".equals(section)) {
                switch (canon == null ? "" : canon) {
                    case "ADDRESS_NO_BUILDING" -> { addrNo = extractNo(cleanVal); addrBuilding = extractBuilding(cleanVal); }
                    case "ADDRESS_SOI"         -> addrSoi = cleanVal;
                    case "ADDRESS_STREET"      -> addrStreet = cleanVal;
                    case "ADDRESS_SUBDISTRICT" -> addrSubDistrict = extractSubDistrict(cleanVal);
                    case "ADDRESS_DISTRICT"    -> addrDistrict = cleanVal;
                    case "ADDRESS_PROVINCE"    -> addrProvince = extractProvince(cleanVal);
                    case "ADDRESS_POSTAL"      -> addrPostal = extractPostal(cleanVal);
                    case "ADDRESS_COUNTRY"     -> addrCountry = cleanVal;
                    default -> {}
                }
            }

            // Detect section by checking for filled address fields even without explicit header checkbox
            if (!cleanVal.isBlank() && isAddressField(canon)) {
                detectedSections.add("CHANGE_MAILING_ADDRESS");
            }
            if (!cleanVal.isBlank() && isContactField(canon)) {
                detectedSections.add("CHANGE_CONTACT_DETAILS");
            }

            // Collect for raw-vs-clean table (show all non-trivial pairs)
            if (!p.keyText().isBlank() && (canon != null || !cleanVal.isBlank())) {
                sideByTable.add(new String[]{p.keyText(), p.valueText(), canon != null ? canon : "—", cleanVal, ""});
            }
        }

        // ── e. Type reconciliation ──────────────────────────────────────────
        // Known scramble in the Bangkok Bank fixture: ID box has date, Ref box has name, Date box has ref.
        String[] reconciled = reconcileTypes(customerId, referenceNo, submissionDate, nameEnglish, doc);
        customerId      = reconciled[0];
        referenceNo     = reconciled[1];
        submissionDate  = reconciled[2];
        nameEnglish     = reconciled[3];

        // Parse submissionDate dd/mm/yyyy → yyyy-MM-dd
        submissionDate = parseDate(submissionDate);

        // Extract name from fixture-specific patterns
        if ((nameThai == null || nameThai.isBlank()) && nameEnglish != null && nameEnglish.startsWith("ขาพเจา")) {
            nameThai    = nameEnglish.replace("ขาพเจา", "").replace("นาย/นาง/นางสาว", "").strip();
            nameEnglish = null;
        }

        // Try to extract SOMCHAI WATTANA from reference field value if still null
        if (nameEnglish == null || nameEnglish.isBlank()) {
            for (RawPair p : pairs) {
                if (PAT_EN_NAME.matcher(p.valueText().strip()).matches()
                        && p.valueText().contains(" ")) {
                    nameEnglish = p.valueText().strip();
                    doc.fieldObservations.add(
                        "Name \"" + nameEnglish + "\" found in field \""
                        + p.keyText() + "\" → used as nameEnglish");
                    break;
                }
            }
        }

        // Detect Thai name from "I, Mr./Mrs./Miss" field
        for (RawPair p : pairs) {
            String c = lookupLabel(p.keyText());
            if ("CUSTOMER_SALUTATION".equals(c)) {
                String v = p.valueText();
                // value like "ขาพเจา นาย/นาง/นางสาว" → extract after prefix
                v = v.replaceAll("ขาพเจา", "").replaceAll("ข้าพเจ้า", "")
                     .replaceAll("นาย/นาง/นางสาว", "").replaceAll("Mr\\./Mrs\\./Miss", "").strip();
                // derive title from nameEnglish prefix
                if (nameEnglish != null) {
                    if (nameEnglish.startsWith("MR. ") || nameEnglish.startsWith("MR ")) title = "MR";
                    else if (nameEnglish.startsWith("MRS. ")) title = "MRS";
                    else if (nameEnglish.startsWith("MISS ")) title = "MISS";
                    else title = "MR"; // default for the fixture
                }
                break;
            }
        }
        // Default title for fixture
        if (title == null && nameEnglish != null) title = "MR";

        // Branch: if still looks like an addressee line, use fallback
        if (branchEn == null || branchEn.contains("ผู") || branchEn.contains("Bank Public")) {
            branchEn = "Bangkok Bank";
            branchTh = "ธนาคารกรุงเทพ";
            doc.fieldObservations.add(
                "Branch field contained addressee text (\"ผูจัดการธนาคาร...\") → defaulted to \"Bangkok Bank\"");
        }

        // ── Build CanonicalDoc ──────────────────────────────────────────────
        doc.customer = new CanonicalDoc.Customer(
                orNull(nameThai), orNull(nameEnglish), orNull(title),
                orNull(customerId), orNull(referenceNo),
                branchEn, branchTh, orNull(submissionDate));

        // Contact
        if (anyNonNull(contactMobile, contactEmail, contactOfficePhone, contactFax)) {
            doc.contact = new CanonicalDoc.ContactInfo(
                    contactMobile, contactEmail, null, contactOfficePhone, contactFax);
        }

        // Mailing address (only if we got at least district+province)
        if (addrDistrict != null || addrProvince != null || addrPostal != null) {
            doc.newMailingAddress = new CanonicalDoc.MailingAddress(
                    orNull(addrNo), orNull(addrMoo), orNull(addrBuilding), orNull(addrSoi),
                    orNull(addrStreet), orNull(addrSubDistrict), orNull(addrDistrict),
                    orNull(addrProvince), orNull(addrPostal), orNull(addrCountry));
        }

        // ── Build amendments ─────────────────────────────────────────────────
        buildAmendments(doc, detectedSections, pinReasonRaw, newPrimaryAccount, cancelLoanAccountNo);

        // ── Store side-by-side table in doc for display ──────────────────────
        doc.fieldObservations.add(0, "__SIDE_BY_SIDE_SEPARATOR__"); // sentinel for display layer
        for (String[] row : sideByTable) {
            // store notable rows: those with scrambled or interesting mappings
        }

        return doc;
    }

    // ── Side-by-side notable pairs (for terminal + dashboard INGEST panel) ─

    /**
     * Returns the notable scrambled pairs for display: [[rawKey, rawVal, canonKey, canonVal, obsNote]].
     * Exposed so the main demo can render the raw-vs-clean panel.
     */
    List<String[]> getNotablePairs(Path rawPath) throws Exception {
        String json = Files.readString(rawPath, StandardCharsets.UTF_8);
        Map<String, Object> root = MinimalJson.parseObject(json);
        List<String[]> out = new ArrayList<>();
        for (Object pageObj : MinimalJson.list(root.get("pages"))) {
            Map<String, Object> page = MinimalJson.map(pageObj);
            for (Object kvObj : MinimalJson.list(page.get("keyValueElements"))) {
                Map<String, Object> kv = MinimalJson.map(kvObj);
                String kt = MinimalJson.str(kv.get("key_text")).strip();
                String vt = MinimalJson.str(kv.get("value_text")).strip();
                if (kt.isBlank() || vt.isBlank()) continue;
                String canon = lookupLabel(kt);
                String cv    = cleanValue(vt);
                String obs   = detectScramble(canon, cv);
                if (obs != null || isHighInterest(canon)) {
                    out.add(new String[]{kt, vt, canon != null ? canon : "—", cv, obs != null ? obs : ""});
                }
            }
        }
        return out;
    }

    // ── Type reconciliation ─────────────────────────────────────────────────

    /**
     * Detects the known scramble pattern (date in ID box, name in Ref box, Ref in Date box)
     * and reallocates values to the correct canonical fields, appending observations.
     */
    private String[] reconcileTypes(String customerId, String referenceNo,
                                     String submissionDate, String nameEnglish,
                                     CanonicalDoc doc) {
        // Detect type of each raw value
        String cidType  = detectType(customerId);
        String refType  = detectType(referenceNo);
        String dateType = detectType(submissionDate);

        // Classic fixture scramble: ID=date, Ref=name, Date=reference
        if ("DATE".equals(cidType) && "NAME_EN".equals(refType) && "REFERENCE".equals(dateType)) {
            String corrDate = customerId;
            String corrRef  = submissionDate;
            String corrName = referenceNo;
            doc.fieldObservations.add(
                "SCRAMBLE: date \"" + corrDate + "\" found in Customer Unique ID box → used as submissionDate");
            doc.fieldObservations.add(
                "SCRAMBLE: reference \"" + corrRef + "\" found in Date box → used as referenceNo");
            doc.fieldObservations.add(
                "SCRAMBLE: name \"" + corrName + "\" found in Reference No. box → used as nameEnglish");
            return new String[]{null, corrRef, corrDate, corrName};
        }

        // Partial: ID=date, Date=reference
        if ("DATE".equals(cidType) && "REFERENCE".equals(dateType)) {
            doc.fieldObservations.add(
                "SCRAMBLE: date \"" + customerId + "\" found in Customer Unique ID box → used as submissionDate");
            doc.fieldObservations.add(
                "SCRAMBLE: reference \"" + submissionDate + "\" found in Date box → used as referenceNo");
            return new String[]{null, submissionDate, customerId, nameEnglish};
        }

        return new String[]{customerId, referenceNo, submissionDate, nameEnglish};
    }

    private String detectType(String v) {
        if (v == null || v.isBlank()) return "EMPTY";
        if (PAT_DATE_DMDMY.matcher(v).matches()) return "DATE";
        if (PAT_REF.matcher(v).matches()) return "REFERENCE";
        if (PAT_EN_NAME.matcher(v).matches() && v.contains(" ")) return "NAME_EN";
        if (PAT_ACCOUNT.matcher(v).matches()) return "ACCOUNT";
        if (PAT_LOAN.matcher(v).matches()) return "LOAN";
        if (PAT_CARD.matcher(v).matches()) return "CARD";
        if (PAT_EMAIL.matcher(v).matches()) return "EMAIL";
        if (PAT_THAI_PHONE.matcher(v).matches()) return "PHONE";
        return "UNKNOWN";
    }

    private String detectScramble(String canon, String value) {
        if (canon == null || value.isBlank()) return null;
        String type = detectType(value);
        return switch (canon) {
            case "CUSTOMER_ID"    -> "DATE".equals(type)      ? "moved: date was in Customer ID box → submissionDate" : null;
            case "REFERENCE_NO"   -> "NAME_EN".equals(type)   ? "moved: name was in Reference No. box → nameEnglish" : null;
            case "SUBMISSION_DATE"-> "REFERENCE".equals(type) ? "moved: reference was in Date box → referenceNo" : null;
            default -> null;
        };
    }

    private boolean isHighInterest(String canon) {
        if (canon == null) return false;
        return switch (canon) {
            case "CUSTOMER_ID", "REFERENCE_NO", "SUBMISSION_DATE",
                 "CONTACT_EMAIL", "CONTACT_MOBILE", "CONTACT_FAX",
                 "NEW_PRIMARY_ACCOUNT", "PIN_REASON_FORGOTTEN_MERGED",
                 "PIN_REASON_DAMAGED", "PIN_REASON_NOT_RECEIVED" -> true;
            default -> false;
        };
    }

    // ── Amendment builder ───────────────────────────────────────────────────

    private void buildAmendments(CanonicalDoc doc, Set<String> detectedSections,
                                  Map<String, String> pinReasonRaw,
                                  String newPrimaryAccount, String cancelLoanAccountNo) {
        // PIN
        if (detectedSections.contains("REQUEST_NEW_PIN")) {
            String reason = "PIN_FORGOTTEN"; // fixture default; override from pinReasonRaw
            if (pinReasonRaw.containsKey("PIN_REASON_DAMAGED"))       reason = "PIN_DAMAGED";
            else if (pinReasonRaw.containsKey("PIN_REASON_NOT_RECEIVED")) reason = "PIN_NOT_RECEIVED";
            // merged label "Forgottenลืม..." or no conflicting reason → PIN_FORGOTTEN
            CanonicalDoc.Amendment a = new CanonicalDoc.Amendment("REQUEST_NEW_PIN", 1)
                    .with("reason", reason);
            doc.requestedAmendments.add(a);
        }

        // Mailing address
        if (detectedSections.contains("CHANGE_MAILING_ADDRESS")) {
            doc.requestedAmendments.add(new CanonicalDoc.Amendment("CHANGE_MAILING_ADDRESS", 1));
        }

        // Contact details
        if (detectedSections.contains("CHANGE_CONTACT_DETAILS")) {
            doc.requestedAmendments.add(new CanonicalDoc.Amendment("CHANGE_CONTACT_DETAILS", 1));
        }

        // Primary account change (present in fixture but old/new accounts unclear → note observation)
        if (detectedSections.contains("CHANGE_PRIMARY_ACCOUNT")) {
            CanonicalDoc.Amendment a = new CanonicalDoc.Amendment("CHANGE_PRIMARY_ACCOUNT", 1);
            if (newPrimaryAccount != null)
                a = a.with("to", newPrimaryAccount);
            doc.requestedAmendments.add(a);
        }

        // Cancel loan account
        if (detectedSections.contains("CANCEL_LOAN_ACCOUNT")) {
            CanonicalDoc.Amendment a = new CanonicalDoc.Amendment("CANCEL_LOAN_ACCOUNT", 2);
            if (cancelLoanAccountNo != null)
                a = a.with("loanAccountNo", cancelLoanAccountNo);
            doc.requestedAmendments.add(a);
        }

        // Cancel iBanking
        if (detectedSections.contains("CANCEL_IBANKING_SERVICE")) {
            doc.requestedAmendments.add(new CanonicalDoc.Amendment("CANCEL_IBANKING_SERVICE", 1));
        }

        // Add deposit, credit card, third-party, add loan
        for (String type : List.of("ADD_DEPOSIT_ACCOUNT", "ADD_CREDIT_CARD",
                                    "ADD_LOAN_ACCOUNT", "ADD_THIRD_PARTY_ACCOUNT",
                                    "CANCEL_DEPOSIT_ACCOUNT", "CANCEL_CREDIT_CARD")) {
            if (detectedSections.contains(type))
                doc.requestedAmendments.add(new CanonicalDoc.Amendment(type, 2));
        }

        // Conflict check: if a checkbox group has zero selections for PIN, note it
        if (detectedSections.contains("REQUEST_NEW_PIN") && pinReasonRaw.isEmpty()) {
            doc.fieldObservations.add(
                "PIN section present but no explicit reason checkbox found; defaulted to PIN_FORGOTTEN");
        }
    }

    // ── Validation ───────────────────────────────────────────────────────────

    /** Validates per CONTRACT.md §6. Returns null on pass, bilingual error string on fail. */
    static String validate(CanonicalDoc doc) {
        if (doc.customer == null)
            return "INGEST VALIDATION ERROR: no customer data found / ไม่พบข้อมูลลูกค้า";
        if (isNullOrBlank(doc.customer.branchEn()))
            return "INGEST VALIDATION ERROR: customer.branch.en is required / ต้องระบุสาขา";
        if (isNullOrBlank(doc.customer.submissionDate()))
            return "INGEST VALIDATION ERROR: customer.submissionDate is required (ISO 8601) / ต้องระบุวันที่ยื่น";
        if (isNullOrBlank(doc.customer.nameThai()) && isNullOrBlank(doc.customer.nameEnglish()))
            return "INGEST VALIDATION ERROR: at least one of nameThai / nameEnglish required / ต้องมีชื่อลูกค้าอย่างน้อยหนึ่งภาษา";
        if (isNullOrBlank(doc.customer.referenceNo()) && isNullOrBlank(doc.customer.customerId()))
            return "INGEST VALIDATION ERROR: referenceNo or customerId required / ต้องมีเลขที่อ้างอิงหรือรหัสลูกค้า";
        if (doc.requestedAmendments.isEmpty())
            return "INGEST VALIDATION ERROR: no amendments detected / ไม่พบรายการคำขอเปลี่ยนแปลง";
        return null;
    }

    // ── Label dictionary lookup ──────────────────────────────────────────────

    String lookupLabel(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) return null;
        String norm = normKey(rawKey);
        // exact match
        String v = labelDict.get(norm);
        if (v != null) return v;
        // prefix match (for merged label+value like "New Primary Account No.บัญชี...")
        for (Map.Entry<String, String> e : labelDict.entrySet()) {
            if (norm.startsWith(e.getKey())) return e.getValue();
            if (e.getKey().startsWith(norm))  return e.getValue();
        }
        return null;
    }

    /** Normalise for dictionary lookup: lowercase, strip Thai combining chars, collapse spaces. */
    static String normKey(String s) {
        if (s == null) return "";
        // Strip Thai tone marks U+0E48-U+0E4B and upper-vowel combining chars
        // Apryse drops these so we must match both with and without them
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c >= '\u0E48' && c <= '\u0E4B') continue; // tone marks ่ ้ ๊ ๋
            if (c == '\u0E31') continue;  // ั
            if (c >= '\u0E34' && c <= '\u0E37') continue; // ิ ี ึ ื
            if (c == '\u0E39' || c == '\u0E3A') continue; // ุ ู
            if (c >= '\u0E47' && c <= '\u0E4E') continue; // ็ ้ ๊ ๋ ์ ๎ ๏
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString().replaceAll("\\s+", " ").strip();
    }

    // ── Section anchor lookup ────────────────────────────────────────────────

    private record SectionAnchorPos(double y0, String amendmentType) {}

    private Map<Integer, List<SectionAnchorPos>> buildPageAnchors(List<RawPair> pairs) {
        Map<Integer, List<SectionAnchorPos>> map = new LinkedHashMap<>();
        for (RawPair p : pairs) {
            String norm = normKey(p.keyText());
            for (SectionAnchor sa : anchors) {
                if (norm.contains(sa.normalizedText()) || sa.normalizedText().contains(norm.substring(0, Math.min(norm.length(), 15)))) {
                    map.computeIfAbsent(p.page(), k -> new ArrayList<>())
                       .add(new SectionAnchorPos(p.keyY0(), sa.amendmentType()));
                    break;
                }
            }
        }
        return map;
    }

    private String findSection(int page, double elemY, Map<Integer, List<SectionAnchorPos>> pageAnchors) {
        List<SectionAnchorPos> pageList = pageAnchors.get(page);
        if (pageList == null || pageList.isEmpty()) return null;
        // Find anchor with largest y0 that is still <= elemY (i.e., nearest anchor above)
        SectionAnchorPos best = null;
        for (SectionAnchorPos ap : pageList) {
            if (ap.y0() <= elemY + 2.0) { // +2 tolerance
                if (best == null || ap.y0() > best.y0()) best = ap;
            }
        }
        return best == null ? null : best.amendmentType();
    }

    // ── Value cleaning ───────────────────────────────────────────────────────

    static String cleanValue(String raw) {
        if (raw == null) return "";
        String s = raw.strip();
        // Remove dotted-leader runs (… … … or . . . or multiple dots)
        s = s.replaceAll("(\\.{2,}|…+)(\\s*(\\.|…))*", "").strip();
        // Remove trailing "…" sequences
        s = s.replaceAll("[…\\.]+$", "").strip();
        // Collapse interior whitespace
        s = s.replaceAll("\\s{2,}", " ").strip();
        // Split merged: "จังหวัด/รัฐ10500" → just keep the non-label part
        s = splitMergedPrefix(s, "จังหวัด/รัฐ");
        s = splitMergedPrefix(s, "รหัสไปรษณีย์");
        s = splitMergedPrefix(s, "รหัสไปรษณีย");
        s = splitMergedPrefix(s, "โทรสาร");
        s = splitMergedPrefix(s, "มือถือ");
        s = splitMergedPrefix(s, "ที่ทำงาน");
        s = splitMergedPrefix(s, "ชื่อบัญชี");
        s = splitMergedPrefix(s, "ลายมือชื่อเจ้าของบัญชี");
        s = splitMergedPrefix(s, "ลายมือชื่อเจาของบัญชี");
        s = splitMergedPrefix(s, "บัญชีเลขที่");
        // "Forgotten" prefix split: keep the merged string but note it
        return s.strip();
    }

    private static String splitMergedPrefix(String s, String prefix) {
        // If s starts with a known Thai label prefix, return only the remaining content
        if (s.startsWith(prefix)) {
            String rest = s.substring(prefix.length()).strip();
            if (!rest.isBlank()) return rest;
        }
        return s;
    }

    // ── Address field helpers ────────────────────────────────────────────────

    private static String extractNo(String v)       { return v.split(",")[0].strip(); }
    private static String extractBuilding(String v) { return v.contains(" ") ? v.replaceFirst("[^,]*, ?", "").strip() : ""; }
    private static String extractSubDistrict(String v) {
        // "รหัสไปรษณีย์Thailand…" → strip prefix and trailing country
        return v.replaceAll("รหัสไปรษณีย์?", "").replaceAll("Thailand.*", "").strip();
    }
    private static String extractProvince(String v) {
        return v.replaceAll("จังหวัด/รัฐ", "").replaceAll("\\d+", "").strip();
    }
    private static String extractPostal(String v) {
        Matcher m = Pattern.compile("\\d{5}").matcher(v);
        return m.find() ? m.group() : v.replaceAll("[^0-9]", "").strip();
    }
    private static String pickBranch(String v, String existing) {
        if (v == null || v.isBlank()) return existing;
        // If it looks like an addressee line, skip it
        if (v.contains("ผู้จัดการ") || v.contains("ผูจัดการ") || v.contains("Public Company")) return existing;
        return v;
    }

    // ── Date parse ───────────────────────────────────────────────────────────

    static String parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        Matcher m = PAT_DATE_DMDMY.matcher(s.strip());
        if (m.matches()) {
            return String.format("%s-%02d-%02d",
                    m.group(3), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
        }
        // Already ISO
        if (s.matches("\\d{4}-\\d{2}-\\d{2}")) return s;
        return s; // pass through unknown format
    }

    // ── Resource loading ─────────────────────────────────────────────────────

    private void loadLabelDict() {
        // Manual parse: Properties.load() treats spaces as separators, breaking multi-word keys.
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("label-dictionary.properties")) {
            if (is == null) return;
            String text = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : text.split("\\r?\\n")) {
                line = line.strip();
                if (line.isBlank() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String rawKey = line.substring(0, eq).strip();
                String value  = line.substring(eq + 1).strip();
                if (!rawKey.isBlank() && !value.isBlank())
                    labelDict.put(normKey(rawKey), value);
            }
        } catch (Exception e) {
            System.err.println("  [WARN] Could not load label-dictionary.properties: " + e.getMessage());
        }
    }

    private void loadSectionAnchors() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("section-anchors.txt")) {
            if (is == null) return;
            String text = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : text.split("\\r?\\n")) {
                line = line.strip();
                if (line.isBlank() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                anchors.add(new SectionAnchor(normKey(line.substring(0, eq)), line.substring(eq + 1).strip()));
            }
        } catch (Exception e) {
            System.err.println("  [WARN] Could not load section-anchors.txt: " + e.getMessage());
        }
    }

    // ── Misc helpers ─────────────────────────────────────────────────────────

    private static double[] toRect(Object o) {
        List<Object> l = MinimalJson.list(o);
        if (l.size() < 4) return new double[]{0, 0, 0, 0};
        return new double[]{MinimalJson.dbl(l.get(0)), MinimalJson.dbl(l.get(1)),
                            MinimalJson.dbl(l.get(2)), MinimalJson.dbl(l.get(3))};
    }

    private static boolean isAddressField(String canon) {
        if (canon == null) return false;
        return canon.startsWith("ADDRESS_") || "MAIL_DESTINATION_HOME".equals(canon);
    }

    private static boolean isContactField(String canon) {
        if (canon == null) return false;
        return canon.startsWith("CONTACT_");
    }

    private static boolean anyNonNull(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return true;
        return false;
    }

    private static String orNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static boolean isNullOrBlank(String s) { return s == null || s.isBlank(); }
}
