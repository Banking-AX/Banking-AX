-- Banking-AX team demo seed compatibility layer (V1..V13).
-- Source of business data: banking_ax_demo_seed.sql and its C-02 contract patch.
-- This file is additive/update-only; it never changes password_hash, Flyway history,
-- or the already-progressing seed-c02 Case/WorkItem.
-- Run only against the backed-up local banking_ax database.
--
-- Applied by this file: team loan fixture (three rows), B-02 closed-account
-- history, and the second decedent/delegation-receipt fixture.
-- Deliberately deferred: chat/workflow history whose IDs already have live
-- messages, C-01 document/review rows without a current document contract,
-- C-02 Case 2 (live DRAFT/r0), file metadata without physical files, OCR
-- success without a provider result, and reservation rows using old columns.

START TRANSACTION;

-- The original three team loans used DEFAULT, which is no longer valid. The
-- maintained B01 fixture maps those same team loans to INTEREST_FIRST.
-- Restrict updates to the three prior Codex-inserted rows only.
UPDATE loan
SET loan_no='MOCK-LN-001', loan_name=CONVERT(0xEAB080EC838120EC9DBCEBB098EC8BA0EC9AA9EB8C80ECB69C USING utf8mb4),
    principal_amount=12000000.00, balance=9000000.00, interest_rate=5.2000,
    maturity_date='2028-08-31', overdue_days=0, acceleration_declared=FALSE,
    acceleration_date=NULL, repayment_order='INTEREST_FIRST', outsourced=FALSE,
    agreement_loan=FALSE, debt_adjust_terminated_at=NULL, prev_adjustment_note=NULL,
    in_litigation=FALSE, other_procedure=FALSE, prescription_completed=FALSE,
    has_third_party_provider=FALSE, version=0
WHERE id=1 AND customer_id=1 AND loan_no='DEMO-LOAN-A02-001';

UPDATE loan
SET loan_no='MOCK-LN-002', loan_name=CONVERT(0xEAB080EC838120EC839DED999CEC9588ECA09520EB8C80ECB69C USING utf8mb4),
    principal_amount=24000000.00, balance=20000000.00, interest_rate=6.1000,
    maturity_date='2029-05-31', overdue_days=45, acceleration_declared=FALSE,
    acceleration_date=NULL, repayment_order='INTEREST_FIRST', outsourced=FALSE,
    agreement_loan=FALSE, debt_adjust_terminated_at=NULL,
    prev_adjustment_note=CONVERT(0xEC868CEB939DEAB090EC868C20EC82ACEC9CA0EC9D9820ECB184EBACB4ECA1B0ECA09520EC82ACECA084ECA091EC8898EC9AA9 USING utf8mb4),
    in_litigation=FALSE, other_procedure=FALSE, prescription_completed=FALSE,
    has_third_party_provider=FALSE, version=0
WHERE id=2 AND customer_id=1 AND loan_no='DEMO-LOAN-A02-002';

UPDATE loan
SET loan_no='MOCK-LN-003', loan_name=CONVERT(0xEAB080EC838120ECA09CEC99B8ECA1B0EAB1B420EAB280ECA69D20EB8C80ECB69C USING utf8mb4),
    principal_amount=5000000.00, balance=4800000.00, interest_rate=7.0000,
    maturity_date='2027-12-31', overdue_days=0, acceleration_declared=FALSE,
    acceleration_date=NULL, repayment_order='INTEREST_FIRST', outsourced=TRUE,
    agreement_loan=FALSE, debt_adjust_terminated_at=NULL,
    prev_adjustment_note=CONVERT(0xEC99B8EBB680EAB8B0EAB48020EC9C84ED838120EC8381ED839CEBA5BC20EAB080ECA095ED959C20EC8BA4ED8AB8 USING utf8mb4),
    in_litigation=FALSE, other_procedure=FALSE, prescription_completed=FALSE,
    has_third_party_provider=TRUE, version=0
WHERE id=3 AND customer_id=1 AND loan_no='DEMO-LOAN-A02-003';

-- Missing team B-02 history account. Natural key prevents a duplicate.
INSERT INTO housing_savings_account
  (customer_id, account_no, joined_at, paid_principal, status, subscription_won,
   converted, applied_rate_type, tax_mock_data, closed_at, version)
SELECT u.id, 'MOCK-HS-002', '2022-01-01', 2500000.00, 'CLOSED', FALSE,
       TRUE, 'MOCK_GENERAL', JSON_OBJECT('mock', TRUE), '2026-10-18 10:00:00', 0
FROM `user` u
WHERE u.id=1 AND u.login_id='customer_demo'
  AND NOT EXISTS (SELECT 1 FROM housing_savings_account a WHERE a.account_no='MOCK-HS-002');

-- Missing team C-02 negative delegation fixture. It has no user-generated
-- dependencies and is keyed by its stable decedent/receipt identity.
INSERT INTO decedent (id, name, birth_date, death_date, deposit_total, deposits)
SELECT 2, CONVERT(0xEAB980ECA095EC8899 USING utf8mb4), '1952-05-04', '2026-10-01', 8100000.00,
       JSON_ARRAY(JSON_OBJECT('account_no','MOCK-DEC-002','principal',8100000.00))
WHERE NOT EXISTS (SELECT 1 FROM decedent WHERE id=2)
  AND NOT EXISTS (SELECT 1 FROM decedent WHERE name=CONVERT(0xEAB980ECA095EC8899 USING utf8mb4) AND death_date='2026-10-01');

INSERT INTO delegation_receipt
  (receipt_no, decedent_id, delegator_name, agent_name, is_valid, created_at)
SELECT 'MOCK-DEL-BAD', d.id, CONVERT(0xEAB980EB9198 USING utf8mb4), CONVERT(0xEAB980EBAFBCEC8898 USING utf8mb4), FALSE, '2026-10-21 09:00:00.000000'
FROM decedent d
WHERE d.name=CONVERT(0xEAB980ECA095EC8899 USING utf8mb4) AND d.death_date='2026-10-01'
  AND NOT EXISTS (SELECT 1 FROM delegation_receipt r WHERE r.receipt_no='MOCK-DEL-BAD');

-- C-02 policy remains owned by V13. Do not update document_type or insert OCR
-- success rows: current service must create only real/mock execution outcomes.
-- Existing seed-c02 WorkItem 6 and Case 2/DRAFT/revision 0 are intentionally preserved.

COMMIT;

-- Postflight read-only verification.
SELECT id, loan_no, loan_name, principal_amount, balance, interest_rate,
       maturity_date, overdue_days, repayment_order, outsourced,
       has_third_party_provider
FROM loan WHERE id IN (1,2,3) ORDER BY id;
SELECT account_no,status,paid_principal,converted FROM housing_savings_account
WHERE account_no IN ('MOCK-HS-001','MOCK-HS-002') ORDER BY account_no;
SELECT d.id,d.name,r.receipt_no,r.is_valid FROM decedent d
LEFT JOIN delegation_receipt r ON r.decedent_id=d.id
WHERE d.id IN (1,2) ORDER BY d.id,r.receipt_no;
SELECT sc.id,wi.id,wi.request_id,wi.status,sc.case_status,sc.revision
FROM service_case sc JOIN work_item wi ON wi.id=sc.work_item_id WHERE sc.id=2;
