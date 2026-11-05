package com.bankingax.task.c02;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Spec 11-3 notification codes for the C-02 staff outcome and their read-back mapping. */
class C02NotificationTypeTest {
    @Test void staff_outcomes_use_spec_notification_codes_and_korean_titles() {
        assertEquals("CASE_APPROVED", C02StaffDecisionPersistence.notificationType("PROCEED"));
        assertEquals("CASE_REJECTED_REQUIREMENT", C02StaffDecisionPersistence.notificationType("REQUIREMENT_FAILED"));
        assertEquals("CASE_REJECTED_DOCUMENT", C02StaffDecisionPersistence.notificationType("DOCUMENT_INSUFFICIENT"));
        assertEquals("상속예금 사전검토 결과: 진행 가능", C02StaffDecisionPersistence.notificationTitle("PROCEED"));
        assertThrows(IllegalArgumentException.class, () -> C02StaffDecisionPersistence.notificationType("APPROVED"));
    }

    @Test void read_back_maps_new_and_legacy_types_to_the_staff_outcome() {
        for (String outcome : new String[] {"PROCEED", "REQUIREMENT_FAILED", "DOCUMENT_INSUFFICIENT"}) {
            assertEquals(outcome, C02StaffDecisionPersistence.outcomeOf(C02StaffDecisionPersistence.notificationType(outcome)));
            assertEquals(outcome, C02StaffDecisionPersistence.outcomeOf("C02_STAFF_REVIEW_" + outcome));
        }
    }
}
