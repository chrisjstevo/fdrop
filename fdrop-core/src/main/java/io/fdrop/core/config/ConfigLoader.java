package io.fdrop.core.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads {@code fdrop.yaml}, substitutes {@code ${VAR}} and {@code ${VAR:-default}} references from the environment,
 * then applies {@code FDROP_*} environment variables as overrides. An override's name is the key path in upper case
 * joined by underscores, matched case-insensitively against the YAML keys: {@code FDROP_STORAGE_S3_BUCKET} sets
 * {@code storage.s3.bucket}, {@code FDROP_AUTH_OIDC_WEBCLIENTSECRET} sets {@code auth.oidc.webClientSecret}.
 * List values are given comma-separated.
 */
public final class ConfigLoader {

    private static final Pattern VAR = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?}");
    private static final String PREFIX = "FDROP_";

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    private final Map<String, String> env;

    public ConfigLoader(Map<String, String> env) {
        this.env = env;
    }

    public static FdropConfig loadDefault() {
        String file = System.getenv().getOrDefault("FDROP_CONFIG", "fdrop.yaml");
        return new ConfigLoader(System.getenv()).load(Path.of(file));
    }

    public FdropConfig load(Path file) {
        try {
            ObjectNode tree = yaml.valueToTree(new FdropConfig());
            if (Files.exists(file)) {
                JsonNode fromFile = yaml.readTree(substitute(Files.readString(file)));
                if (fromFile instanceof ObjectNode obj) {
                    merge(tree, obj);
                }
            }
            applyEnvironment(tree);
            return yaml.treeToValue(tree, FdropConfig.class);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read config " + file, e);
        }
    }

    public FdropConfig loadFromString(String text) {
        try {
            ObjectNode tree = yaml.valueToTree(new FdropConfig());
            JsonNode fromText = yaml.readTree(substitute(text));
            if (fromText instanceof ObjectNode obj) {
                merge(tree, obj);
            }
            applyEnvironment(tree);
            return yaml.treeToValue(tree, FdropConfig.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String substitute(String text) {
        Matcher m = VAR.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = env.get(m.group(1));
            if (value == null || value.isEmpty()) {
                value = m.group(2) != null ? m.group(2) : "";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static void merge(ObjectNode target, ObjectNode source) {
        for (Map.Entry<String, JsonNode> e : source.properties()) {
            JsonNode existing = target.get(e.getKey());
            if (existing instanceof ObjectNode existingObj && e.getValue() instanceof ObjectNode incoming) {
                merge(existingObj, incoming);
            } else {
                target.set(e.getKey(), e.getValue());
            }
        }
    }

    private void applyEnvironment(ObjectNode tree) {
        for (Map.Entry<String, String> e : env.entrySet()) {
            String name = e.getKey();
            if (!name.startsWith(PREFIX) || name.equals("FDROP_CONFIG")) {
                continue;
            }
            String[] parts = name.substring(PREFIX.length()).toLowerCase(Locale.ROOT).split("_");
            set(tree, parts, 0, e.getValue());
        }
    }

    private static void set(ObjectNode node, String[] parts, int i, String value) {
        // Keys are matched case-insensitively and may themselves contain no underscores, so walk greedily.
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String field = names.next();
            String lower = field.toLowerCase(Locale.ROOT);
            if (!lower.equals(parts[i])) {
                continue;
            }
            JsonNode child = node.get(field);
            if (i == parts.length - 1) {
                if (child instanceof ArrayNode) {
                    ArrayNode arr = node.arrayNode();
                    for (String item : value.split(",")) {
                        if (!item.isBlank()) {
                            arr.add(item.trim());
                        }
                    }
                    node.set(field, arr);
                } else if (!(child instanceof ObjectNode)) {
                    node.set(field, TextNode.valueOf(value));
                }
                return;
            }
            if (child instanceof ObjectNode obj) {
                set(obj, parts, i + 1, value);
            }
            return;
        }
    }
}
