package com.bankingax.core.workflow.jpa;

import com.bankingax.core.api.DomainException;
import com.bankingax.core.workflow.TaskCode;
import com.bankingax.core.workflow.WorkItemStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "app.case-port", havingValue = "jpa")
public class B01ReconnectService {
    private static final String EVENT_TYPE = "B01_RECONNECT_SELECTED";
    private static final String RECEIPT_PREFIX = "b01-reconnect:";
    private static final String OPERATION_PREFIX = "b01-reconnect-op:";

    private final WorkflowStartService starts;
    private final WorkflowRepository workflows;
    private final WorkItemRepository workItems;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public B01ReconnectService(WorkflowStartService starts, WorkflowRepository workflows,
                               WorkItemRepository workItems, JdbcTemplate jdbc, ObjectMapper json) {
        this.starts = starts;
        this.workflows = workflows;
        this.workItems = workItems;
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public ReconnectResult reconnect(long customerId, long currentChatRoomId, ReconnectCommand command) {
        validate(command);
        starts.lockCustomerAndOwnedChat(customerId, currentChatRoomId);

        Receipt receipt = receipt(command.requestId());
        if (receipt != null) {
            requireMatchingReceipt(receipt, customerId, currentChatRoomId, command);
            requireOwnedB01(customerId, receipt.result().workItemId());
            return receipt.result();
        }

        WorkflowStartService.B01PrepareMode mode = switch (command.choice()) {
            case CONTINUE_EXISTING -> WorkflowStartService.B01PrepareMode.CONTINUE_EXISTING;
            case START_NEW -> WorkflowStartService.B01PrepareMode.START_NEW;
        };
        WorkflowStartService.B01PrepareResult prepared = starts.prepareB01(
                new WorkflowStartService.B01PrepareCommand(
                        operationRequestId(command.requestId()), customerId, currentChatRoomId,
                        mode, command.workItemId()));
        ReconnectResult result = toResult(customerId, prepared);
        recordReceipt(customerId, currentChatRoomId, command, result);
        return result;
    }

    @Transactional(readOnly = true)
    public List<WorkflowStartService.B01Candidate> candidates(long customerId, long currentChatRoomId) {
        return starts.b01Candidates(customerId, currentChatRoomId);
    }

    private ReconnectResult toResult(long customerId, WorkflowStartService.B01PrepareResult prepared) {
        WorkflowStartService.StartResult item = prepared.workItem();
        WorkflowEntity workflow = workflows.findById(item.workflowId())
                .orElseThrow(B01ReconnectService::unavailableWorkItem);
        if (workflow.customerId != customerId || item.taskCode() != TaskCode.REPAYMENT_ORDER_CHANGE) {
            throw unavailableWorkItem();
        }
        Long originalChatRoomId = workflow.chatRoomId;
        boolean chatAvailable = originalChatRoomId != null
                && workflows.existsChatRoomOwnedByCustomerId(originalChatRoomId, customerId) == 1;

        return switch (prepared.outcome()) {
            case EXISTING_RECONNECTED -> new ReconnectResult(
                    ReconnectOutcome.EXISTING_SELECTED, item.workItemId(), item.status(),
                    originalChatRoomId, chatAvailable,
                    chatAvailable ? NavigationTarget.ORIGINAL_CHAT : NavigationTarget.B01_TASK);
            case CREATED -> new ReconnectResult(
                    ReconnectOutcome.NEW_CREATED, item.workItemId(), item.status(),
                    originalChatRoomId, chatAvailable, NavigationTarget.START_B01_GRAPH);
            case SAME_CHAT_REUSED -> new ReconnectResult(
                    ReconnectOutcome.SAME_CHAT_REUSED, item.workItemId(), item.status(),
                    originalChatRoomId, chatAvailable, NavigationTarget.START_B01_GRAPH);
            case ACTIVE_WORK_ITEM_CONFLICT -> new ReconnectResult(
                    ReconnectOutcome.ACTIVE_WORK_ITEM_CONFLICT, item.workItemId(), item.status(),
                    originalChatRoomId, chatAvailable,
                    chatAvailable ? NavigationTarget.ORIGINAL_CHAT : NavigationTarget.B01_TASK);
            case REQUEST_REPLAYED -> new ReconnectResult(
                    ReconnectOutcome.REQUEST_REPLAYED, item.workItemId(), item.status(),
                    originalChatRoomId, chatAvailable, NavigationTarget.START_B01_GRAPH);
            case RECONNECT_CONFIRMATION_REQUIRED -> throw new DomainException(
                    "B01_RECONNECT_SELECTION_REQUIRED", "Select an existing B-01 work item or start a new one.");
        };
    }

    private Receipt receipt(String requestId) {
        List<ReceiptRow> rows = jdbc.query("""
                select entity_type,entity_id,event_type,actor_type,actor_id,payload
                  from event_log where request_id=?
                """, (rs, rowNum) -> new ReceiptRow(
                rs.getString("entity_type"), rs.getString("entity_id"), rs.getString("event_type"),
                rs.getString("actor_type"), rs.getObject("actor_id", Long.class), rs.getString("payload")),
                receiptRequestId(requestId));
        if (rows.isEmpty()) return null;
        ReceiptRow row = rows.get(0);
        try {
            JsonNode payload = json.readTree(row.payload());
            ReconnectResult result = new ReconnectResult(
                    ReconnectOutcome.valueOf(payload.path("outcome").asText()),
                    payload.path("resultWorkItemId").asLong(),
                    WorkItemStatus.valueOf(payload.path("workItemStatus").asText()),
                    nullableLong(payload.get("originalChatRoomId")),
                    payload.path("originalChatRoomAvailable").asBoolean(),
                    NavigationTarget.valueOf(payload.path("navigationTarget").asText()));
            return new Receipt(row, payload, result);
        } catch (RuntimeException | java.io.IOException exception) {
            throw new DomainException("B01_RECONNECT_STATE_FAILED",
                    "Stored B-01 reconnect receipt could not be read.", true);
        }
    }

    private void requireMatchingReceipt(Receipt receipt, long customerId, long currentChatRoomId,
                                        ReconnectCommand command) {
        JsonNode payload = receipt.payload();
        boolean same = "WORK_ITEM".equals(receipt.row().entityType())
                && Long.toString(receipt.result().workItemId()).equals(receipt.row().entityId())
                && EVENT_TYPE.equals(receipt.row().eventType())
                && "CUSTOMER".equals(receipt.row().actorType())
                && Objects.equals(receipt.row().actorId(), customerId)
                && payload.path("customerId").asLong() == customerId
                && payload.path("currentChatRoomId").asLong() == currentChatRoomId
                && command.choice().name().equals(payload.path("choice").asText())
                && Objects.equals(command.workItemId(), nullableLong(payload.get("selectedWorkItemId")));
        if (!same) throw duplicateRequest();
    }

    private void recordReceipt(long customerId, long currentChatRoomId,
                               ReconnectCommand command, ReconnectResult result) {
        ObjectNode payload = json.createObjectNode()
                .put("customerId", customerId)
                .put("currentChatRoomId", currentChatRoomId)
                .put("choice", command.choice().name())
                .put("outcome", result.outcome().name())
                .put("resultWorkItemId", result.workItemId())
                .put("workItemStatus", result.workItemStatus().name())
                .put("originalChatRoomAvailable", result.originalChatRoomAvailable())
                .put("navigationTarget", result.navigationTarget().name());
        if (command.workItemId() == null) payload.putNull("selectedWorkItemId");
        else payload.put("selectedWorkItemId", command.workItemId());
        if (result.originalChatRoomId() == null) payload.putNull("originalChatRoomId");
        else payload.put("originalChatRoomId", result.originalChatRoomId());
        try {
            jdbc.update("""
                    insert into event_log(entity_type,entity_id,event_type,actor_type,actor_id,request_id,payload)
                    values('WORK_ITEM',?,?,'CUSTOMER',?,?,cast(? as json))
                    """, Long.toString(result.workItemId()), EVENT_TYPE, customerId,
                    receiptRequestId(command.requestId()), json.writeValueAsString(payload));
        } catch (Exception exception) {
            throw new DomainException("B01_RECONNECT_STATE_FAILED",
                    "B-01 reconnect receipt could not be stored.", true);
        }
    }

    private void requireOwnedB01(long customerId, long workItemId) {
        WorkItemEntity item = workItems.findById(workItemId).orElseThrow(B01ReconnectService::unavailableWorkItem);
        WorkflowEntity workflow = workflows.findById(item.workflowId)
                .orElseThrow(B01ReconnectService::unavailableWorkItem);
        if (workflow.customerId != customerId || item.taskCode != TaskCode.REPAYMENT_ORDER_CHANGE) {
            throw unavailableWorkItem();
        }
    }

    private static void validate(ReconnectCommand command) {
        if (command == null || command.requestId() == null || command.requestId().isBlank()
                || command.requestId().length() > 90 || command.choice() == null) {
            throw new DomainException("INVALID_REQUEST", "A valid requestId and B-01 reconnect choice are required.");
        }
        if (command.choice() == ReconnectChoice.CONTINUE_EXISTING
                && (command.workItemId() == null || command.workItemId() <= 0)) {
            throw new DomainException("INVALID_REQUEST", "workItemId is required to continue B-01.");
        }
        if (command.choice() == ReconnectChoice.START_NEW && command.workItemId() != null) {
            throw new DomainException("INVALID_REQUEST", "workItemId is not allowed when starting a new B-01.");
        }
    }

    private static Long nullableLong(JsonNode node) {
        return node == null || node.isNull() ? null : node.asLong();
    }

    private static String receiptRequestId(String requestId) {
        return RECEIPT_PREFIX + digest(requestId);
    }

    private static String operationRequestId(String requestId) {
        return OPERATION_PREFIX + digest(requestId);
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static DomainException duplicateRequest() {
        return new DomainException("DUPLICATE_REQUEST_CONFLICT",
                "The requestId was already used for another B-01 reconnect choice.");
    }

    private static DomainException unavailableWorkItem() {
        return new DomainException("WORK_ITEM_NOT_AVAILABLE", "The B-01 work item is not available.");
    }

    public enum ReconnectChoice { CONTINUE_EXISTING, START_NEW }

    public enum ReconnectOutcome {
        EXISTING_SELECTED,
        NEW_CREATED,
        SAME_CHAT_REUSED,
        ACTIVE_WORK_ITEM_CONFLICT,
        REQUEST_REPLAYED
    }

    public enum NavigationTarget { ORIGINAL_CHAT, B01_TASK, START_B01_GRAPH }

    public record ReconnectCommand(String requestId, ReconnectChoice choice, Long workItemId) { }

    public record ReconnectResult(ReconnectOutcome outcome, long workItemId, WorkItemStatus workItemStatus,
                                  Long originalChatRoomId, boolean originalChatRoomAvailable,
                                  NavigationTarget navigationTarget) { }

    private record ReceiptRow(String entityType, String entityId, String eventType,
                              String actorType, Long actorId, String payload) { }

    private record Receipt(ReceiptRow row, JsonNode payload, ReconnectResult result) { }
}
