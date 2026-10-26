package io.fdrop.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The CLI's copy of the expiry parser passes the same cases as the server's. */
class CliExpiryTest {

    @TestFactory
    List<DynamicTest> sharedCases() throws IOException {
        JsonNode all = new ObjectMapper().readTree(Path.of("../fdrop-core/src/test/resources/expiry-syntax-cases.json")
                .toFile());
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : all.get("valid")) {
            String input = c.get("input").asText();
            tests.add(DynamicTest.dynamicTest("valid: '" + input + "'", () ->
                    assertThat(ExpirySyntax.canonical(input)).isEqualTo(c.get("canonical").asText())));
        }
        for (JsonNode c : all.get("invalid")) {
            String input = c.get("input").asText();
            tests.add(DynamicTest.dynamicTest("invalid: '" + input + "'", () ->
                    assertThatThrownBy(() -> ExpirySyntax.canonical(input))
                            .isInstanceOfSatisfying(CliException.class, e -> {
                                assertThat(e.code()).isEqualTo(CliException.USAGE);
                                assertThat(e.getMessage()).isEqualTo(c.get("message").asText());
                            })));
        }
        return tests;
    }
}
