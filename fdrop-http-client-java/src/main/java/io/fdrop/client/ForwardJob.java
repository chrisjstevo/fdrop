package io.fdrop.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One file being sent on from a forward folder to one destination; a file sent to several destinations has one job
 * for each, sharing {@code uploadRecordId}. {@code state} is QUEUED, SENDING, RETRY_WAIT, SENT, FAILED, PAUSED or
 * CANCELLED (the owner stopped sending it to this destination, giving {@code cancelReason}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ForwardJob(String id, String fileName, long size, String state, int attempts, String lastError,
                         String createdAt, String sentAt, String nextAttemptAt, String uploadRecordId,
                         String destinationId, String destinationName, String cancelReason) {
}
