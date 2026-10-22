package io.fdrop.core.storage;

/** One stored part of a multipart upload. */
public record PartInfo(int partNumber, long size, String etag, String sha256Hex) {
}
