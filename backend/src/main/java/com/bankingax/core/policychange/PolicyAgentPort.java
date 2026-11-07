package com.bankingax.core.policychange;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** Staff Agent impact analysis. The answer is reference text, re-checked by Spring and stored apart from the facts. */
public interface PolicyAgentPort {
    JsonNode analyze(Map<String, Object> request);
}
