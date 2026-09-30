package com.apryse.demo;

import java.util.*;

/**
 * Mutable canonical document model, per CONTRACT.md schema.
 * Built incrementally by the Normalizer, then consumed by the LetterGenerator.
 */
final class CanonicalDoc {

    // ── Nested records ──────────────────────────────────────────────────────

    record Customer(
            String nameThai,
            String nameEnglish,
            String title,          // MR | MRS | MISS | null
            String customerId,
            String referenceNo,
            String branchEn,
            String branchTh,
            String submissionDate  // ISO 8601, CE: yyyy-MM-dd
    ) {}

    record ContactInfo(
            String mobile,
            String email,
            String homePhone,
            String officePhone,
            String fax
    ) {}

    record MailingAddress(
            String no,
            String moo,
            String building,
            String soi,
            String street,
            String subDistrict,
            String district,
            String province,
            String postalCode,
            String country
    ) {
        /** Single-line English summary for the amendment table. */
        String formatted() {
            StringBuilder sb = new StringBuilder();
            append(sb, no);
            append(sb, building);
            if (soi != null && !soi.isBlank()) sb.append(" Soi ").append(soi);
            append(sb, street);
            append(sb, subDistrict);
            append(sb, district);
            append(sb, province);
            append(sb, postalCode);
            if (country != null && !country.isBlank()) sb.append(", ").append(country);
            return sb.toString().strip();
        }

        private static void append(StringBuilder sb, String v) {
            if (v != null && !v.isBlank()) {
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(v.strip());
            }
        }
    }

    record Amendment(
            String type,
            Map<String, Object> extra,
            int page
    ) {
        Amendment(String type, int page) { this(type, new LinkedHashMap<>(), page); }

        Amendment with(String key, Object val) {
            LinkedHashMap<String, Object> m = new LinkedHashMap<>(extra);
            m.put(key, val);
            return new Amendment(type, m, page);
        }

        String extra(String key) {
            Object v = extra.get(key);
            return v == null ? "" : v.toString();
        }
    }

    record Provenance(int page, double[] bbox) {}

    // ── Mutable top-level fields ────────────────────────────────────────────

    String document;
    int    pages;
    Customer       customer;
    ContactInfo    contact;
    MailingAddress newMailingAddress;
    String         effectiveDate;

    final List<Amendment>          requestedAmendments = new ArrayList<>();
    final List<String>             fieldObservations   = new ArrayList<>();
    final Map<String, Provenance>  provenance          = new LinkedHashMap<>();

    // ── Serialisation → extracted.json ─────────────────────────────────────

    String toJson() {
        StringBuilder j = new StringBuilder();
        j.append("{\n");
        if (document != null)
            j.append("  \"document\": ").append(MinimalJson.quoted(document)).append(",\n");
        j.append("  \"pages\": ").append(pages).append(",\n");
        j.append("  \"customer\": ").append(customerJson()).append(",\n");
        if (contact != null)
            j.append("  \"contact\": ").append(contactJson()).append(",\n");
        if (newMailingAddress != null)
            j.append("  \"newMailingAddress\": ").append(addressJson()).append(",\n");
        if (effectiveDate != null)
            j.append("  \"effectiveDate\": ").append(MinimalJson.quoted(effectiveDate)).append(",\n");
        j.append("  \"requestedAmendments\": ").append(amendmentsJson()).append(",\n");
        j.append("  \"_fieldObservations\": ").append(observationsJson()).append(",\n");
        j.append("  \"provenance\": ").append(provenanceJson()).append("\n");
        j.append("}");
        return j.toString();
    }

    private String customerJson() {
        if (customer == null) return "{}";
        StringBuilder j = new StringBuilder("{\n");
        field(j, "nameThai",      customer.nameThai());
        field(j, "nameEnglish",   customer.nameEnglish());
        field(j, "title",         customer.title());
        field(j, "customerId",    customer.customerId());
        field(j, "referenceNo",   customer.referenceNo());
        j.append("    \"branch\": {");
        j.append("\"en\": ").append(MinimalJson.quoted(customer.branchEn()));
        j.append(", \"th\": ").append(MinimalJson.quoted(customer.branchTh()));
        j.append("},\n");
        field(j, "submissionDate", customer.submissionDate());
        if (j.charAt(j.length() - 2) == ',') j.deleteCharAt(j.length() - 2);
        j.append("  }");
        return j.toString();
    }

    private String contactJson() {
        if (contact == null) return "{}";
        StringBuilder j = new StringBuilder("{\n");
        field(j, "mobile",       contact.mobile());
        field(j, "email",        contact.email());
        field(j, "homePhone",    contact.homePhone());
        field(j, "officePhone",  contact.officePhone());
        field(j, "fax",          contact.fax());
        if (j.charAt(j.length() - 2) == ',') j.deleteCharAt(j.length() - 2);
        j.append("  }");
        return j.toString();
    }

    private String addressJson() {
        if (newMailingAddress == null) return "{}";
        MailingAddress a = newMailingAddress;
        StringBuilder j = new StringBuilder("{\n");
        field(j, "no",         a.no());
        field(j, "moo",        a.moo());
        field(j, "building",   a.building());
        field(j, "soi",        a.soi());
        field(j, "street",     a.street());
        field(j, "subDistrict",a.subDistrict());
        field(j, "district",   a.district());
        field(j, "province",   a.province());
        field(j, "postalCode", a.postalCode());
        field(j, "country",    a.country());
        if (j.charAt(j.length() - 2) == ',') j.deleteCharAt(j.length() - 2);
        j.append("  }");
        return j.toString();
    }

    private String amendmentsJson() {
        StringBuilder j = new StringBuilder("[\n");
        for (int i = 0; i < requestedAmendments.size(); i++) {
            Amendment a = requestedAmendments.get(i);
            j.append("    {\"type\": \"").append(a.type()).append("\"");
            j.append(", \"page\": ").append(a.page());
            for (var e : a.extra().entrySet()) {
                j.append(", \"").append(MinimalJson.esc(e.getKey())).append("\": ");
                Object v = e.getValue();
                if (v instanceof String s) j.append(MinimalJson.quoted(s));
                else j.append(v);
            }
            j.append("}");
            if (i < requestedAmendments.size() - 1) j.append(",");
            j.append("\n");
        }
        j.append("  ]");
        return j.toString();
    }

    private String observationsJson() {
        StringBuilder j = new StringBuilder("[\n");
        for (int i = 0; i < fieldObservations.size(); i++) {
            j.append("    ").append(MinimalJson.quoted(fieldObservations.get(i)));
            if (i < fieldObservations.size() - 1) j.append(",");
            j.append("\n");
        }
        j.append("  ]");
        return j.toString();
    }

    private String provenanceJson() {
        StringBuilder j = new StringBuilder("{\n");
        boolean first = true;
        for (var e : provenance.entrySet()) {
            if (!first) j.append(",\n");
            first = false;
            j.append("    ").append(MinimalJson.quoted(e.getKey())).append(": {");
            j.append("\"page\": ").append(e.getValue().page());
            double[] b = e.getValue().bbox();
            j.append(", \"bbox\": [").append(b[0]).append(",").append(b[1])
             .append(",").append(b[2]).append(",").append(b[3]).append("]}");
        }
        j.append("\n  }");
        return j.toString();
    }

    private static void field(StringBuilder j, String key, String val) {
        if (val != null && !val.isBlank())
            j.append("    \"").append(key).append("\": ").append(MinimalJson.quoted(val)).append(",\n");
    }
}
