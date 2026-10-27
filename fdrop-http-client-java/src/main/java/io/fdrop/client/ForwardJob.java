package io.fdrop.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** One file being sent on from a forward folder; {@code state} is QUEUED, SENDING, RETRY_WAIT, SENT, FAILED or PAUSED. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ForwardJob(String id, String fileName, long size, String state, int attempts, String lastError,
                         String createdAt, String sentAt, String nextAttemptAt) {
}
