-- B-01 development/test fixtures for the synthetic customer user.id=1.
-- These rows assume household-loan scenarios for demonstrations only.
-- The current loan schema has no authoritative household-loan classification column.
-- This script is intentionally outside Flyway and never updates or deletes existing rows.
-- Idempotency is based on the globally unique loan.loan_no value.

START TRANSACTION;

INSERT INTO loan (
  customer_id, loan_no, loan_name, principal_amount, balance, interest_rate,
  maturity_date, overdue_days, acceleration_declared, acceleration_date,
  repayment_order, outsourced, agreement_loan, debt_adjust_terminated_at,
  prev_adjustment_note, in_litigation, other_procedure, prescription_completed,
  has_third_party_provider, version
)
SELECT
  1, 'MOCK-B01-T01', 'B01-T01 가상 가계대출', 30000000.00, 26500000.00, 6.2500,
  '2029-06-30', 62, TRUE, '2026-08-31',
  'INTEREST_FIRST', FALSE, FALSE, NULL,
  NULL, FALSE, FALSE, FALSE,
  FALSE, 0
FROM `user`
WHERE id = 1
  AND `role` = 'CUSTOMER'
  AND NOT EXISTS (SELECT 1 FROM loan WHERE loan_no = 'MOCK-B01-T01');

INSERT INTO loan (
  customer_id, loan_no, loan_name, principal_amount, balance, interest_rate,
  maturity_date, overdue_days, acceleration_declared, acceleration_date,
  repayment_order, outsourced, agreement_loan, debt_adjust_terminated_at,
  prev_adjustment_note, in_litigation, other_procedure, prescription_completed,
  has_third_party_provider, version
)
SELECT
  1, 'MOCK-B01-T02', 'B01-T02 가상 가계대출(위탁)', 22000000.00, 18700000.00, 6.8000,
  '2028-12-31', 75, TRUE, '2026-08-18',
  'INTEREST_FIRST', TRUE, FALSE, NULL,
  NULL, FALSE, FALSE, FALSE,
  FALSE, 0
FROM `user`
WHERE id = 1
  AND `role` = 'CUSTOMER'
  AND NOT EXISTS (SELECT 1 FROM loan WHERE loan_no = 'MOCK-B01-T02');

INSERT INTO loan (
  customer_id, loan_no, loan_name, principal_amount, balance, interest_rate,
  maturity_date, overdue_days, acceleration_declared, acceleration_date,
  repayment_order, outsourced, agreement_loan, debt_adjust_terminated_at,
  prev_adjustment_note, in_litigation, other_procedure, prescription_completed,
  has_third_party_provider, version
)
SELECT
  1, 'MOCK-B01-T03', 'B01-T03 가상 가계대출(협약)', 18000000.00, 15300000.00, 5.9500,
  '2029-03-31', 48, TRUE, '2026-09-12',
  'INTEREST_FIRST', FALSE, TRUE, NULL,
  NULL, FALSE, FALSE, FALSE,
  FALSE, 0
FROM `user`
WHERE id = 1
  AND `role` = 'CUSTOMER'
  AND NOT EXISTS (SELECT 1 FROM loan WHERE loan_no = 'MOCK-B01-T03');

INSERT INTO loan (
  customer_id, loan_no, loan_name, principal_amount, balance, interest_rate,
  maturity_date, overdue_days, acceleration_declared, acceleration_date,
  repayment_order, outsourced, agreement_loan, debt_adjust_terminated_at,
  prev_adjustment_note, in_litigation, other_procedure, prescription_completed,
  has_third_party_provider, version
)
SELECT
  1, 'MOCK-B01-T04', 'B01-T04 가상 가계대출(이미 변경)', 27000000.00, 21900000.00, 6.4500,
  '2029-09-30', 91, TRUE, '2026-07-29',
  'PRINCIPAL_FIRST', FALSE, FALSE, NULL,
  NULL, FALSE, FALSE, FALSE,
  FALSE, 0
FROM `user`
WHERE id = 1
  AND `role` = 'CUSTOMER'
  AND NOT EXISTS (SELECT 1 FROM loan WHERE loan_no = 'MOCK-B01-T04');

INSERT INTO loan (
  customer_id, loan_no, loan_name, principal_amount, balance, interest_rate,
  maturity_date, overdue_days, acceleration_declared, acceleration_date,
  repayment_order, outsourced, agreement_loan, debt_adjust_terminated_at,
  prev_adjustment_note, in_litigation, other_procedure, prescription_completed,
  has_third_party_provider, version
)
SELECT
  1, 'MOCK-B01-T05', 'B01-T05 가상 가계대출(제3자 제공)', 35000000.00, 30800000.00, 6.7000,
  '2030-01-31', 55, TRUE, '2026-09-05',
  'INTEREST_FIRST', FALSE, FALSE, NULL,
  NULL, FALSE, FALSE, FALSE,
  TRUE, 0
FROM `user`
WHERE id = 1
  AND `role` = 'CUSTOMER'
  AND NOT EXISTS (SELECT 1 FROM loan WHERE loan_no = 'MOCK-B01-T05');

COMMIT;
