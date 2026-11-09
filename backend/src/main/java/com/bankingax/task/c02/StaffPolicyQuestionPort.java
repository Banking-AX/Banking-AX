package com.bankingax.task.c02;

import com.fasterxml.jackson.databind.JsonNode;

/** STAFF-19: the staff Agent's search-backed answer. It returns reference text only and writes nothing. */
public interface StaffPolicyQuestionPort {
    JsonNode ask(String question, String taskCode);
}
