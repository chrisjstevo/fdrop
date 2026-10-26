package io.fdrop.core.expiry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fdrop.core.error.FdropException;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExpiryTest {

    private static JsonNode cases() throws IOException {
        try (InputStream in = ExpiryTest.class.getResourceAsStream("/expiry-syntax-cases.json")) {
            return new ObjectMapper().readTree(in);
        }
    }

    @TestFactory
    List<DynamicTest> sharedCases() throws IOException {
        JsonNode all = cases();
        Instant defaultFrom = Instant.parse(all.get("defaultFrom").asText());
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : all.get("valid")) {
            String input = c.get("input").asText();
            tests.add(DynamicTest.dynamicTest("valid: '" + input + "'", () -> {
                Expiry e = Expiry.parse(input);
                assertThat(e.canonical()).isEqualTo(c.get("canonical").asText());
                Instant from = c.hasNonNull("from") ? Instant.parse(c.get("from").asText()) : defaultFrom;
                Instant to = c.get("to").isNull() ? null : Instant.parse(c.get("to").asText());
                assertThat(e.from(from)).isEqualTo(to);
                assertThat(Expiry.parse(e.canonical())).isEqualTo(e);
            }));
        }
        for (JsonNode c : all.get("invalid")) {
            String input = c.get("input").asText();
            tests.add(DynamicTest.dynamicTest("invalid: '" + input + "'", () ->
                    assertThatThrownBy(() -> Expiry.parse(input))
                            .isInstanceOfSatisfying(FdropException.class, ex -> {
                                assertThat(ex.status()).isEqualTo(400);
                                assertThat(ex.type()).isEqualTo(Expiry.PROBLEM_INVALID);
                                assertThat(ex.getMessage()).isEqualTo(c.get("message").asText());
                            })));
        }
        return tests;
    }

    @Test
    void comparesLengthsAtAStartInstant() {
        Instant feb = Instant.parse("2026-02-01T00:00:00Z");
        assertThat(Expiry.parse("1 month").longerThan(Expiry.parse("28 days"), feb)).isFalse();
        assertThat(Expiry.parse("1 month").longerThan(Expiry.parse("27 days"), feb)).isTrue();
        assertThat(Expiry.NEVER.longerThan(Expiry.parse("100y"), feb)).isTrue();
        assertThat(Expiry.parse("100y").longerThan(Expiry.NEVER, feb)).isFalse();
    }
}
