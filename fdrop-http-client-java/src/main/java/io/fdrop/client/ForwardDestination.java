package io.fdrop.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A destination the caller may choose for a forward folder. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ForwardDestination(String id, String name, String description) {
}
