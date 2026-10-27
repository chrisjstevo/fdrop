package io.fdrop.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** A page of upload history; pass {@code nextCursor} back for the next page (null on the last). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UploadHistory(long totalCount, long totalBytes, String nextCursor, List<UploadRecord> rows) {
}
