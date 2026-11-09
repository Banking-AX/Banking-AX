-- Dedicated banking_ax_bench fixture only. Creates no data outside its schema.
DELETE FROM event_log;
DELETE FROM notification;
DELETE FROM visit_reservation;
DELETE FROM case_revision;
DELETE FROM service_case;
DELETE FROM work_item;
DELETE FROM workflow;
DELETE FROM `user`;
DELETE FROM branch;

INSERT INTO branch(id, branch_code, name, address, is_active) VALUES (1, 'BENCH-01', 'Benchmark Branch', 'isolated', true);
INSERT INTO `user`(id, login_id, password_hash, `role`, name, branch_id) VALUES
  (101, 'bench-staff-1', 'x', 'STAFF', 'Bench Staff 1', 1),
  (102, 'bench-staff-2', 'x', 'STAFF', 'Bench Staff 2', 1);

DELIMITER //
CREATE PROCEDURE seed_reservation_benchmark()
BEGIN
  DECLARE n INT DEFAULT 1;
  WHILE n <= 20 DO
    INSERT INTO `user`(id, login_id, password_hash, `role`, name) VALUES (1000+n, CONCAT('bench-customer-',n), 'x', 'CUSTOMER', CONCAT('Bench Customer ',n));
    INSERT INTO workflow(id, customer_id, status) VALUES (2000+n, 1000+n, 'RUNNING');
    INSERT INTO work_item(id, workflow_id, task_code, sequence_no, status, request_id) VALUES (3000+n, 2000+n, 'INHERITANCE_DEPOSIT', 1, 'HANDED_OFF', CONCAT('bench-work-',n));
    INSERT INTO service_case(id, work_item_id, customer_id, case_type, case_status, revision, visit_required, application, result_notified_at)
      VALUES (7000+n, 3000+n, 1000+n, 'INHERITANCE_DEPOSIT', 'REVIEW_PASSED', 1, true, JSON_OBJECT(), UTC_TIMESTAMP(6));
    SET n = n + 1;
  END WHILE;
END //
DELIMITER ;
CALL seed_reservation_benchmark();
DROP PROCEDURE seed_reservation_benchmark;
