package com.apryse.demo;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Normalizer} against the committed Apryse keyValExtract fixture.
 *
 * <p>The fixture at project root contains three known scrambles:
 * <ul>
 *   <li>CUSTOMER_ID field → value is {@code 05/08/2026} (a date, not an ID)</li>
 *   <li>SUBMISSION_DATE field → value is {@code REF-2026-081451} (a reference number)</li>
 *   <li>REFERENCE_NO field → value is {@code SOMCHAI WATTANA} (a person name)</li>
 * </ul>
 */
class NormalizerTest {

    private static final String FIXTURE =
            "73cb029f-1bca-4ebd-a235-6261ea2e5ccc-extraction.json";

    private static CanonicalDoc doc;

    @BeforeAll
    static void loadFixture() throws Exception {
        Path fixtureFile = Paths.get(FIXTURE);
        // Fall back to the project root when tests run from a different cwd
        if (!fixtureFile.toFile().exists()) {
            fixtureFile = Paths.get("").toAbsolutePath().resolve(FIXTURE);
        }
        assertTrue(fixtureFile.toFile().exists(),
                "Fixture not found: " + fixtureFile.toAbsolutePath());
        doc = new Normalizer().normalize(fixtureFile);
        assertNotNull(doc, "normalize() must not return null");
    }

    // ── Scramble corrections ──────────────────────────────────────────────────

    @Test
    void scramble_customerIdContainsDate_isMovedToSubmissionDate() {
        // The CUSTOMER_ID field contains a date "05/08/2026" — normalizer should
        // detect it and route it to submissionDate.
        assertNotNull(doc.customer, "customer record must be populated");
        String sd = doc.customer.submissionDate();
        assertNotNull(sd, "submissionDate must be populated from the scrambled CUSTOMER_ID field");
        // Normalizer converts DD/MM/YYYY to ISO YYYY-MM-DD
        assertEquals("2026-08-05", sd,
                "submissionDate should be ISO-formatted date extracted from the scrambled CUSTOMER_ID field");
    }

    @Test
    void scramble_submissionDateContainsRefNo_isMovedToReferenceNo() {
        // The SUBMISSION_DATE field contains "REF-2026-081451" — normalizer should
        // route it to referenceNo.
        String ref = doc.customer.referenceNo();
        assertNotNull(ref, "referenceNo must be populated");
        assertEquals("REF-2026-081451", ref,
                "referenceNo should be extracted from the scrambled SUBMISSION_DATE field");
    }

    @Test
    void scramble_referenceNoContainsName_isMovedToNameEnglish() {
        // The REFERENCE_NO field contains "SOMCHAI WATTANA" — normalizer should
        // route it to nameEnglish.
        String name = doc.customer.nameEnglish();
        assertNotNull(name, "nameEnglish must be populated");
        assertTrue(name.contains("SOMCHAI") && name.contains("WATTANA"),
                "nameEnglish should contain 'SOMCHAI WATTANA', got: " + name);
    }

    // ── Field observations (scramble flags) ───────────────────────────────────

    @Test
    void fieldObservations_containScrambleEntries() {
        assertNotNull(doc.fieldObservations, "fieldObservations must not be null");
        assertFalse(doc.fieldObservations.isEmpty(),
                "fieldObservations must contain at least one entry for the known scrambles");

        boolean hasScramble = doc.fieldObservations.stream()
                .anyMatch(o -> o.contains("SCRAMBLE") || o.contains("scramble")
                        || o.contains("mismatch") || o.contains("MISMATCH"));
        assertTrue(hasScramble,
                "At least one field observation should describe a scramble/mismatch. " +
                "Got: " + doc.fieldObservations);
    }

    // ── Amendments ───────────────────────────────────────────────────────────

    @Test
    void amendments_atLeastOnePresent() {
        assertNotNull(doc.requestedAmendments, "requestedAmendments must not be null");
        assertFalse(doc.requestedAmendments.isEmpty(),
                "At least one amendment must be present in the fixture");
    }

    @Test
    void amendments_containRequestNewPin() {
        boolean found = doc.requestedAmendments.stream()
                .anyMatch(a -> "REQUEST_NEW_PIN".equals(a.type()));
        assertTrue(found, "REQUEST_NEW_PIN amendment expected in fixture");
    }

    @Test
    void amendments_containChangeMailingAddress() {
        boolean found = doc.requestedAmendments.stream()
                .anyMatch(a -> "CHANGE_MAILING_ADDRESS".equals(a.type()));
        assertTrue(found, "CHANGE_MAILING_ADDRESS amendment expected in fixture");
    }

    // ── Validation ───────────────────────────────────────────────────────────

    @Test
    void validation_passesWith_requiredFields() {
        // Validation should pass (return null) because the normalizer has populated
        // the required fields from the scramble corrections above.
        String err = new Normalizer().validate(doc);
        assertNull(err, "validate() should return null for a valid canonical doc, got: " + err);
    }

    // ── Provenance ───────────────────────────────────────────────────────────

    @Test
    void provenance_isPopulated() {
        assertNotNull(doc.provenance, "provenance map must not be null");
        assertFalse(doc.provenance.isEmpty(), "provenance map must not be empty");
    }

    // ── Notable pairs ────────────────────────────────────────────────────────

    @Test
    void notablePairs_returnNonEmpty() throws Exception {
        Path fixtureFile = Paths.get(FIXTURE);
        if (!fixtureFile.toFile().exists())
            fixtureFile = Paths.get("").toAbsolutePath().resolve(FIXTURE);

        List<String[]> pairs = new Normalizer().getNotablePairs(fixtureFile);
        assertNotNull(pairs);
        assertFalse(pairs.isEmpty(), "getNotablePairs() should return at least one entry");
        for (String[] pair : pairs) {
            assertEquals(2, pair.length, "each pair must have exactly 2 elements");
            assertNotNull(pair[0], "raw label must not be null");
            assertNotNull(pair[1], "canonical value must not be null");
        }
    }

    // ── toJson round-trip ─────────────────────────────────────────────────────

    @Test
    void toJson_producesValidJson() {
        String json = doc.toJson();
        assertNotNull(json);
        assertTrue(json.startsWith("{"), "JSON must start with {");
        assertTrue(json.endsWith("}"), "JSON must end with }");
        assertTrue(json.contains("\"customer\""), "JSON must contain customer section");
        assertTrue(json.contains("\"requestedAmendments\""), "JSON must contain amendments");
    }
}
