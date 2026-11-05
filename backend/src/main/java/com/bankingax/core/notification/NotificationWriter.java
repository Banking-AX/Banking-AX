package com.bankingax.core.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Stores a customer web notification (NOTI-01/02/10). dedup_key is UNIQUE, so replaying the same
 * business event keeps the first row instead of creating a duplicate. Email delivery is not implemented;
 * email_status keeps its column default.
 */
@Component
@com.bankingax.core.persistence.ConditionalOnDataSource
public class NotificationWriter {
    private final JdbcTemplate jdbc;

    public NotificationWriter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // Connector/J reports found rows by default, so the update count cannot distinguish insert from replay.
    public void store(long customerId, Long caseId, Long workItemId, String type, String title, String content, String dedupKey) {
        jdbc.update("insert into notification(customer_id,case_id,work_item_id,type,title,content,dedup_key) values(?,?,?,?,?,?,?) "
                + "on duplicate key update id=id", customerId, caseId, workItemId, type, title, content, dedupKey);
    }
}
