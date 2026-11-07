package com.bankingax.task.c02;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** Staff Agent review opinion. The answer is reference text: it never changes a Case or reaches a customer by itself. */
public interface StaffBriefPort {
    JsonNode write(Map<String, Object> facts);
}
