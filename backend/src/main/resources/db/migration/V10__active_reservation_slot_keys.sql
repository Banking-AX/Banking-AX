-- Release slot uniqueness on cancellation while retaining the reservation audit row.
ALTER TABLE visit_reservation
  ADD KEY idx_reservation_case_fk(case_id),
  ADD KEY idx_reservation_staff_fk(assigned_staff_id),
  DROP INDEX uk_reservation_staff_time,
  DROP INDEX uk_reservation_case,
  ADD COLUMN active_case_id BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN status <> 'CANCELED' THEN case_id ELSE NULL END) STORED,
  ADD COLUMN active_staff_slot VARCHAR(96) GENERATED ALWAYS AS (CASE WHEN status <> 'CANCELED' AND assigned_staff_id IS NOT NULL THEN CONCAT(assigned_staff_id,'@',DATE_FORMAT(starts_at,'%Y-%m-%d %H:%i:%s.%f')) ELSE NULL END) STORED,
  ADD UNIQUE KEY uk_reservation_active_case(active_case_id),
  ADD UNIQUE KEY uk_reservation_active_staff_slot(active_staff_slot);
