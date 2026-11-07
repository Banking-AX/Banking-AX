package com.bankingax.core.policychange;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * The only seam that knows how a bank delivers change events. A replacement maps its own transport and format to
 * the canonical JSON of docs/contracts/POLICY_CHANGE; nothing behind this interface changes.
 */
public interface PolicyChangeProvider {
    String sourceSystem();

    Feed fetch(String cursor);

    record Feed(List<JsonNode> events, String nextCursor) { }
}
