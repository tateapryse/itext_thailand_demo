# Act 1 → Act 2 Data Contract
### `extracted.json` — the structured output Apryse SDK must produce for the Amendment Confirmation Letter

Version 1.0 · The letter template (`confirmation-letter.html`) consumes exactly the variables below.
Anything marked **derived** is computed by Act 2 (iText side) and must NOT be supplied by Act 1.

---

## 1. Template variables — where each value lands in the letter

| # | Letter location | Template variable | JSON path | Required |
|---|---|---|---|---|
| 1 | Header · Ref. No. | `{{referenceNo}}` | `customer.referenceNo` | ✔ (or customerId) |
| 2 | Header · Customer ID | `{{customerId}}` | `customer.customerId` | ✔ (or referenceNo) |
| 3 | Header · Date (Thai, BE) | `{{dateThai}}` | **derived** from `customer.submissionDate` | — |
| 4 | Header · Date (English) | `{{dateEnglish}}` | **derived** from `customer.submissionDate` | — |
| 5 | Header · Branch | `{{branchEn}}` / `{{branchTh}}` | `customer.branch.en` / `customer.branch.th` | ✔ en; th optional |
| 6 | Salutation (Thai) | `{{nameThai}}` | `customer.nameThai` | ✔ at least one name |
| 7 | Salutation (English) | `{{titleEn}} {{nameEnglish}}` | `customer.title` + `customer.nameEnglish` | ✔ at least one name |
| 8 | Body ¶ · branch + date | reuses 3–5 | — | — |
| 9 | Amendment table | one row per entry | `requestedAmendments[]` | ✔ min 1 entry |
| 10 | Effective date (row 7 / footer) | `{{effectiveDateThai/English}}` | `effectiveDate` else **derived** = submissionDate | optional |
| 11 | Signature block · signer, timestamp | — | **runtime** (Act 2 signing step) | — |
| 12 | Console display at INGEST (not in letter) | — | `_fieldObservations[]` | optional |

**At-least-one rules:** (`customer.referenceNo` OR `customer.customerId`) · (`customer.nameThai` OR `customer.nameEnglish`).
If only one name script is present, the letter renders that script in both salutation lines.

---

## 2. JSON Schema (draft-07)

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "Act1ExtractionOutput",
  "type": "object",
  "required": ["customer", "requestedAmendments"],
  "properties": {
    "document":  { "type": "string", "description": "Source form identifier, e.g. 'SFKD 060-6'" },
    "pages":     { "type": "integer" },
    "customer": {
      "type": "object",
      "required": ["branch", "submissionDate"],
      "properties": {
        "nameThai":       { "type": "string", "description": "e.g. สมชาย วัฒนา (no title prefix)" },
        "nameEnglish":    { "type": "string", "description": "e.g. SOMCHAI WATTANA (no title prefix)" },
        "title":          { "enum": ["MR", "MRS", "MISS", null], "description": "From นาย/นาง/นางสาว on the form" },
        "customerId":     { "type": "string" },
        "referenceNo":    { "type": "string" },
        "branch": {
          "type": "object",
          "required": ["en"],
          "properties": { "en": { "type": "string" }, "th": { "type": "string" } }
        },
        "submissionDate": { "type": "string", "format": "date", "description": "ISO 8601, CE calendar: 2026-08-05" }
      }
    },
    "contact": {
      "type": "object",
      "description": "Only for CHANGE_CONTACT_DETAILS; include only fields actually changed",
      "properties": {
        "mobile":      { "type": "string" },
        "email":       { "type": "string" },
        "homePhone":   { "type": "string" },
        "officePhone": { "type": "string" },
        "fax":         { "type": "string" }
      }
    },
    "newMailingAddress": {
      "type": "object",
      "description": "Only for CHANGE_MAILING_ADDRESS; strings verbatim as written on the form",
      "required": ["no", "district", "province", "postalCode"],
      "properties": {
        "no":          { "type": "string" },
        "moo":         { "type": "string" },
        "building":    { "type": "string" },
        "soi":         { "type": "string" },
        "street":      { "type": "string" },
        "subDistrict": { "type": "string" },
        "district":    { "type": "string" },
        "province":    { "type": "string" },
        "postalCode":  { "type": "string" },
        "country":     { "type": "string" }
      }
    },
    "effectiveDate": { "type": "string", "format": "date", "description": "Defaults to submissionDate if absent" },
    "requestedAmendments": {
      "type": "array",
      "minItems": 1,
      "items": {
        "type": "object",
        "required": ["type"],
        "properties": {
          "type": { "enum": [
            "REQUEST_NEW_PIN",
            "CHANGE_MAILING_ADDRESS",
            "CHANGE_CONTACT_DETAILS",
            "CHANGE_NAME",
            "CHANGE_PRIMARY_ACCOUNT",
            "ADD_DEPOSIT_ACCOUNT",
            "ADD_THIRD_PARTY_ACCOUNT",
            "ADD_CREDIT_CARD",
            "ADD_LOAN_ACCOUNT",
            "CANCEL_DEPOSIT_ACCOUNT",
            "CANCEL_THIRD_PARTY_ACCOUNT",
            "CANCEL_CREDIT_CARD",
            "CANCEL_LOAN_ACCOUNT",
            "CANCEL_IBANKING_SERVICE"
          ]},
          "page": { "type": "integer", "description": "Source page on the form" }
        }
      }
    },
    "_fieldObservations": {
      "type": "array",
      "items": { "type": "string" },
      "description": "Extraction anomalies (values in wrong boxes etc.) — displayed at INGEST, embedded in archive, never printed in the letter"
    },
    "provenance": {
      "type": "object",
      "description": "OPTIONAL map of JSON-pointer → {page, bbox:[x0,y0,x1,y1]} for coordinate traceability",
      "additionalProperties": {
        "type": "object",
        "properties": { "page": { "type": "integer" }, "bbox": { "type": "array", "items": { "type": "number" } } }
      }
    }
  }
}
```

---

## 3. Per-amendment-type payloads → letter table rows

Each entry in `requestedAmendments[]` carries type-specific fields. Act 2 owns ALL wording
(Thai and English row text); Act 1 supplies only enums and values.

| `type` | Extra required fields | Letter row it produces (EN summary) |
|---|---|---|
| `REQUEST_NEW_PIN` | `reason`: `PIN_DAMAGED` \| `PIN_NOT_RECEIVED` \| `PIN_FORGOTTEN` | "New PIN issued — reason: …" |
| `CHANGE_MAILING_ADDRESS` | (uses top-level `newMailingAddress`) | "Mailing address updated → {formatted address}" |
| `CHANGE_CONTACT_DETAILS` | (uses top-level `contact`; ≥1 field) | "Contact details updated: {only fields present}" |
| `CHANGE_NAME` | `newNameThai` and/or `newNameEnglish` | "Name changed to …" |
| `CHANGE_PRIMARY_ACCOUNT` | `from`, `to`; optional `accountName` | "Primary account changed from {from} to {to}" |
| `ADD_DEPOSIT_ACCOUNT` | `accounts[]`: `{accountNo, accountName?}` (1–2) | "Deposit account(s) added: …" |
| `ADD_THIRD_PARTY_ACCOUNT` | `accounts[]`: `{accountNo, accountName?}` (1–2) | "Third-party account(s) added: …" |
| `ADD_CREDIT_CARD` | `cardNos[]` (1–2) | "Credit card(s) added: …" |
| `ADD_LOAN_ACCOUNT` | `loanAccountNo` | "Loan account added: …" |
| `CANCEL_DEPOSIT_ACCOUNT` | `accountNo` (may be empty if blank on form) | "Deposit account cancelled: …" |
| `CANCEL_THIRD_PARTY_ACCOUNT` | `accountNo` | "Third-party account cancelled: …" |
| `CANCEL_CREDIT_CARD` | `cardNo` | "Credit card cancelled: …" |
| `CANCEL_LOAN_ACCOUNT` | `loanAccountNo` | "Loan account cancelled: …" |
| `CANCEL_IBANKING_SERVICE` | optional `effectiveDate` | "Bualuang iBanking service cancelled, effective …" |

**Ordering:** rows render in `requestedAmendments[]` array order.
**Exclusivity:** a type absent from the array MUST NOT appear in the letter — no default rows.

---

## 4. Values Act 2 derives (Act 1 must not send these)

| Derived value | Rule |
|---|---|
| `dateThai` | `submissionDate` → `d <Thai month name> <year + 543>` → e.g. `5 สิงหาคม 2569` |
| `dateEnglish` | `submissionDate` → `d MMMM yyyy` → `5 August 2026` |
| `effectiveDateThai/English` | same rules applied to `effectiveDate` (default = `submissionDate`) |
| `titleEn` rendering | `MR`→`Mr.` · `MRS`→`Mrs.` · `MISS`→`Miss` · null→omit |
| Thai salutation prefix | always `คุณ` regardless of `title` |
| PIN reason wording (TH+EN) | mapped from the `reason` enum inside Act 2 |
| Status column | always `ดำเนินการแล้ว / Completed` (this letter confirms processed requests) |
| Letter ref, signer identity, signing timestamp | Act 2 runtime |

---

## 5. Minimal valid example

```json
{
  "customer": {
    "nameThai": "สมชาย วัฒนา",
    "nameEnglish": "SOMCHAI WATTANA",
    "title": "MR",
    "customerId": "BBL-0098765432",
    "referenceNo": "REF-2026-081451",
    "branch": { "en": "Silom Branch", "th": "สาขาสีลม" },
    "submissionDate": "2026-08-05"
  },
  "requestedAmendments": [
    { "type": "REQUEST_NEW_PIN", "reason": "PIN_FORGOTTEN", "page": 1 },
    { "type": "CHANGE_PRIMARY_ACCOUNT", "from": "123-4-56789-0", "to": "456-7-89012-3", "page": 1 },
    { "type": "CANCEL_CREDIT_CARD", "cardNo": "4000-0012-3456-7890", "page": 3 }
  ]
}
```

## 6. INGEST validation rules (Act 2 rejects with bilingual error if violated)

1. `customer.branch.en` and `customer.submissionDate` present; date parses as ISO 8601.
2. At least one of `customer.nameThai` / `customer.nameEnglish`.
3. At least one of `customer.customerId` / `customer.referenceNo`.
4. `requestedAmendments` non-empty; every `type` in the enum; type-specific required fields present
   (e.g. `CHANGE_PRIMARY_ACCOUNT` without `from`+`to` is a hard error).
5. `CHANGE_MAILING_ADDRESS` present ⇒ top-level `newMailingAddress` present (and vice-versa warning).
6. `CHANGE_CONTACT_DETAILS` present ⇒ top-level `contact` has ≥ 1 field.
7. Unknown extra fields are ignored (forward compatibility), but logged.
