# RUNBOOK — Bangkok Bank Amendment Demo

## Overview

This is Act 2 of a two-act live demo. Act 2 takes a real Apryse `keyValExtract` JSON output, normalises it into a canonical document model, then drives the full iText document lifecycle:

```
INGEST → GENERATE → MARK → LOCK → SEAL → ARCHIVE → VERIFY
```

---

## Pre-Demo Checklist

Run this **before** the presentation.

| # | Item | Check |
|---|------|-------|
| 1 | Java 17+ on PATH: `java -version` | ☐ |
| 2 | `keytool` on PATH (comes with JDK): `keytool -help` | ☐ |
| 3 | Fat-jar present: `target/itext-shenzhen-demo.jar` | ☐ |
| 4 | Fixture present: `73cb029f-1bca-4ebd-a235-6261ea2e5ccc-extraction.json` | ☐ |
| 5 | Port 8080 free: `netstat -ano | findstr 8080` (Windows) | ☐ |
| 6 | Previous outputs deleted (or they'll be versioned): `*.pdf`, `*.json`, `*.p12` | ☐ |
| 7 | Browser open at `http://localhost:8080/` (auto-opens on start) | ☐ |
| 8 | (Optional) Sarabun font for Thai shaping: `fonts/Sarabun-Regular.ttf` | ☐ |
| 9 | (Optional) iText license key set for pdfCalligraph | ☐ |

### Download Sarabun Font (optional — Thai typography)

```powershell
Invoke-WebRequest `
  -Uri "https://fonts.gstatic.com/s/sarabun/v14/DtVmJx26TKEr37c9YK5sulop.ttf" `
  -OutFile fonts/Sarabun-Regular.ttf
```

---

## Launch Commands

### Windows (PowerShell) — Happy Path (amendment scenario)

```powershell
chcp 65001
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new()
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
java "-Dfile.encoding=UTF-8" -jar target/itext-shenzhen-demo.jar
```

### Windows — with custom input file

```powershell
java "-Dfile.encoding=UTF-8" -jar target/itext-shenzhen-demo.jar `
  --input 73cb029f-1bca-4ebd-a235-6261ea2e5ccc-extraction.json
```

### Windows — Tamper test (shows INVALID signature detection)

```powershell
java "-Dfile.encoding=UTF-8" -jar target/itext-shenzhen-demo.jar --tamper
```

### Windows — Loan scenario (Act 1 preserved)

```powershell
java "-Dfile.encoding=UTF-8" -jar target/itext-shenzhen-demo.jar --scenario loan
```

### macOS / Linux

```bash
java -Dfile.encoding=UTF-8 -jar target/itext-shenzhen-demo.jar
```

---

## One-Command Smoke Test (before presentation)

```powershell
java "-Dfile.encoding=UTF-8" -jar target/itext-shenzhen-demo.jar < /dev/null
```

*(Pipes empty stdin so all `Press ENTER` prompts auto-advance — non-interactive dry-run.)*

On Windows:

```powershell
java "-Dfile.encoding=UTF-8" -jar target/itext-shenzhen-demo.jar | Out-Null
```

---

## Step-by-Step Presenter Guide

Open the browser at `http://localhost:8080/` alongside the terminal.

### INGEST — Normalise raw Apryse JSON

**What appears in terminal:**
```
────────────────────────────────────────────────────────────────
  INGEST  —  Normalise raw Apryse JSON → canonical extracted.json
────────────────────────────────────────────────────────────────
  RAW LABEL / ป้ายดิบ               CANONICAL VALUE / ค่าที่แปลงแล้ว
  -----------------------------------------------------------------------
  เลขที่บัตรประชาชน/Customer ID     05/08/2026 → submissionDate (scramble)
  เลขที่อ้างอิง/Reference No        SOMCHAI WATTANA → nameEnglish (scramble)
  ...
  [✓] Wrote extracted.json: extracted.json
>>> Press ENTER to continue ...
```

**Dashboard shows:** NOW panel with `extracted.json`; checklist items glow green.

**File to open in explorer:** `extracted.json` — show the structured JSON with `customer`, `requestedAmendments`, `fieldObservations` (scramble corrections logged here).

**Say:**
> "Apryse's AI extraction engine reads the scanned amendment form. We can see three known field scrambles — the reference number ended up in the customer-ID slot, and the submission date in the reference slot. Our normalizer detects these by value-pattern analysis and corrects them automatically before any downstream processing."

Press **Enter** to proceed.

---

### GENERATE — Amendment Confirmation Letter

**What appears:** iText pdfHTML converts the bilingual HTML template into a PDF letter.

**File to open:** `confirmation_letter.pdf`

**Say:**
> "iText pdfHTML takes the canonical data and merges it into our bilingual HTML template. If a Sarabun font and a pdfCalligraph license are present, Thai glyphs are fully shaped. Without it, we degrade gracefully — the letter still renders."

---

### MARK — SPECIMEN watermark

**File to open:** `confirmation_letter_watermarked.pdf`

**Say:**
> "A 45° diagonal watermark is stamped on every page using iText's layout engine — a single call per page regardless of page count."

---

### LOCK — AES-256 encryption

**File to open:** `confirmation_letter_protected.pdf`

**Demonstrate:** Try to open the PDF — Adobe Reader should show a password prompt.  
**Password:** `user1234`

**Say:**
> "AES-256 encryption with separate user and owner passwords. The owner password allows us to open the document programmatically for signing without distributing that credential."

---

### SEAL — CMS Digital Signature

**File to open:** `confirmation_letter_signed.pdf`

**Demonstrate:** Open in Adobe Reader → hamburger menu → Document Properties → Signatures tab → should show "Signature is VALID".

**Say:**
> "A CMS detached SHA256withRSA signature is embedded in the PDF. The signing time is set at `signer.setSignDate(Calendar.getInstance())` — that timestamp travels inside the signature structure and is visible in any PDF viewer."

---

### ARCHIVE — PDF/A-3B with embedded JSON

**File to open:** `confirmation_letter_archive.pdf`

**Demonstrate:** In Adobe Reader → View → Show/Hide → Navigation Panes → Attachments — you should see `extracted.json` and the raw Apryse JSON listed as embedded files.

**Say:**
> "PDF/A-3B is Bangkok Bank's required long-term archival format. Unlike PDF/A-1, it allows embedded files — so we attach both JSONs *inside* the signed document before we close it. The signature then covers everything: the letter, the raw extraction, and the canonical record. One file, one signature, one audit trail. An auditor in ten years opens this and the entire decision chain is right there — without touching a database."

**Why embed the JSONs — if asked:**

1. **They travel together forever.** Whoever receives or archives the PDF gets the raw extraction data and the canonical data automatically — no separate file management, no broken links 20 years later.
2. **The signature covers them.** The CMS signature was applied to the PDF that already has the attachments baked in. The attachment content is part of the signed byte range — you cannot swap out the JSON without breaking the signature.
3. **It's the original evidence chain.** The raw Apryse JSON is what the AI *read* off the scanned form. The canonical JSON is what the pipeline *decided* it meant. Both are preserved alongside the human-readable outcome. An auditor in 2036 can open one file and see: here's the original scan data, here's how it was interpreted, here's the letter that was sent — and a valid signature proves none of it was touched.

---

### VERIFY — Signature check

**What appears:**
```
  Signature 'BangkokBankAmendmentSignature': VALID ✓
```

With `--tamper`:
```
  Tampered copy signature: INVALID ✓ tamper detected
```

---

## Tamper-Test Path

```powershell
java "-Dfile.encoding=UTF-8" -jar target/itext-shenzhen-demo.jar --tamper
```

After the SEAL step the demo creates `confirmation_letter_tampered.pdf` by flipping one byte in the middle of the signed file. The VERIFY step then re-opens both files and shows:

- `confirmation_letter_signed.pdf` → **VALID ✓**
- `confirmation_letter_tampered.pdf` → **INVALID ✓ tamper detected**

**Say:**
> "To prove the signature is real, we flip a single byte in the signed PDF. iText's `SignatureUtil` immediately reports the signature as invalid — even a one-bit change is detected."

---

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `startup-error.log` written, demo exits | Unhandled startup exception | Read `startup-error.log` for stack trace |
| Dashboard not opening | Port 8080 in use | Kill the other process or change `STATUS_PORT` |
| Thai text shows boxes | Sarabun font missing or no pdfCalligraph license | Download Sarabun TTF (see above); Latin fallback still works |
| `keytool failed` | keytool not on PATH | Add JDK `bin/` to PATH |
| `Input file not found` | Fixture JSON missing | Ensure `73cb029f-...json` is in the project root |
| Locked output file (versioned name) | Previous run left file open in a PDF viewer | Close the viewer; the demo falls back to a timestamped filename |

---

## Build from Source

```powershell
mvn -q -DskipTests package
# → target/itext-shenzhen-demo.jar
```

Run tests (requires fixture in project root):

```powershell
mvn test
```

---

## Output File Reference

| Step | File | Description |
|------|------|-------------|
| INGEST | `extracted.json` | Canonical normalised document (CONTRACT.md schema) |
| GENERATE | `confirmation_letter.pdf` | Bilingual pdfHTML letter |
| MARK | `confirmation_letter_watermarked.pdf` | + SPECIMEN watermark |
| LOCK | `confirmation_letter_protected.pdf` | + AES-256 (open pwd: `user1234`) |
| SEAL | `apryse_demo_keystore.p12` | Self-signed PKCS#12 keystore |
| SEAL | `confirmation_letter_signed.pdf` | + CMS signature with signing time |
| ARCHIVE | `confirmation_letter_archive.pdf` | PDF/A-3B + embedded JSONs |
| VERIFY (tamper) | `confirmation_letter_tampered.pdf` | Byte-flipped copy (tamper test only) |
