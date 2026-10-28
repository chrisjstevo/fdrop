package io.fdrop.core.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fdrop.core.error.FdropException;
import io.fdrop.core.metrics.FdropMetrics;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Keycloak Admin REST API, called as the {@code fdrop-sftp} service account. Used to create and delete
 * users, assign roles, log users out, and check whether a key-authenticated SFTP user is still enabled.
 */
public class KeycloakAdmin {

    /** A Keycloak user as the admin screens show it. */
    public record KcUser(String id, String username, String email, String firstName, String lastName,
                         boolean enabled, long createdTimestamp) {
    }

    /** One of a user's Keycloak credentials (password, otp, webauthn, recovery-authn-codes, ...). */
    public record KcCredential(String id, String type, String userLabel, long createdDate, String credentialData) {
    }

    private final OidcClient oidc;
    private final FdropMetrics metrics;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final URI base;
    private volatile OidcClient.Tokens token;

    public KeycloakAdmin(OidcClient oidc, FdropMetrics metrics) {
        this.oidc = oidc;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).sslContext(oidc.tls()).build();
        this.metrics = metrics;
        String issuer = oidc.config().issuer;
        int i = issuer.indexOf("/realms/");
        if (i < 0) {
            throw new IllegalArgumentException("issuer is not a Keycloak realm URL: " + issuer);
        }
        this.base = URI.create(issuer.substring(0, i) + "/admin" + issuer.substring(i));
    }

    public List<KcUser> listUsers(String search, int first, int max) {
        String q = "?first=" + first + "&max=" + max + "&briefRepresentation=false"
                + (search == null || search.isBlank() ? "" : "&search=" + enc(search));
        JsonNode arr = call("GET", "/users" + q, null);
        List<KcUser> out = new ArrayList<>();
        for (JsonNode n : arr) {
            out.add(user(n));
        }
        return out;
    }

    public Optional<KcUser> getUser(String id) {
        try {
            return Optional.of(user(call("GET", "/users/" + enc(id), null)));
        } catch (FdropException e) {
            if (e.status() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    public Optional<KcUser> findByUsername(String username) {
        JsonNode arr = call("GET", "/users?exact=true&username=" + enc(username), null);
        return arr.isEmpty() ? Optional.empty() : Optional.of(user(arr.get(0)));
    }

    /** Creates a user and returns its id. A duplicate username gives 409. */
    public String createUser(String username, String email, String firstName, String lastName) {
        return createUser(username, email, false, firstName, lastName);
    }

    /** As above; {@code emailVerified} for addresses fdrop made up itself, which nobody can verify. */
    public String createUser(String username, String email, boolean emailVerified, String firstName,
                             String lastName) {
        ObjectNode rep = json.createObjectNode();
        rep.put("username", username);
        rep.put("enabled", true);
        if (email != null && !email.isBlank()) {
            rep.put("email", email);
            rep.put("emailVerified", emailVerified);
        }
        if (firstName != null) {
            rep.put("firstName", firstName);
        }
        if (lastName != null) {
            rep.put("lastName", lastName);
        }
        call("POST", "/users", rep);
        return findByUsername(username).map(KcUser::id)
                .orElseThrow(() -> FdropException.unavailable("created user not found"));
    }

    public void setTemporaryPassword(String id, String password) {
        ObjectNode cred = json.createObjectNode();
        cred.put("type", "password");
        cred.put("value", password);
        cred.put("temporary", true);
        call("PUT", "/users/" + enc(id) + "/reset-password", cred);
    }

    /** A permanent password, for application accounts, which never sign in interactively to change one. */
    public void setPassword(String id, String password) {
        ObjectNode cred = json.createObjectNode();
        cred.put("type", "password");
        cred.put("value", password);
        cred.put("temporary", false);
        call("PUT", "/users/" + enc(id) + "/reset-password", cred);
    }

    public void setEnabled(String id, boolean enabled) {
        ObjectNode rep = json.createObjectNode();
        rep.put("enabled", enabled);
        call("PUT", "/users/" + enc(id), rep);
    }

    /** The id of the top-level group with this exact name. Needs query-groups. */
    public Optional<String> groupId(String name) {
        JsonNode arr = call("GET", "/groups?exact=true&briefRepresentation=true&search=" + enc(name), null);
        for (JsonNode g : arr) {
            if (name.equals(g.path("name").asText())) {
                return Optional.of(g.path("id").asText());
            }
        }
        return Optional.empty();
    }

    public void addToGroup(String id, String groupId) {
        call("PUT", "/users/" + enc(id) + "/groups/" + enc(groupId), null);
    }

    public void sendUpdatePasswordEmail(String id) {
        ArrayNode actions = json.createArrayNode().add("UPDATE_PASSWORD");
        call("PUT", "/users/" + enc(id) + "/execute-actions-email", actions);
    }

    /** True when the realm has an SMTP server configured. Needs view-realm; false when not permitted. */
    public boolean realmHasSmtp() {
        try {
            JsonNode realm = call("GET", "", null);
            JsonNode smtp = realm.path("smtpServer");
            return smtp.isObject() && smtp.has("host") && !smtp.path("host").asText().isBlank();
        } catch (FdropException e) {
            return false;
        }
    }

    public void assignRealmRoles(String id, List<String> roleNames) {
        ArrayNode reps = json.createArrayNode();
        for (String name : roleNames) {
            reps.add(call("GET", "/roles/" + enc(name), null));
        }
        call("POST", "/users/" + enc(id) + "/role-mappings/realm", reps);
    }

    public List<String> realmRoles(String id) {
        JsonNode arr = call("GET", "/users/" + enc(id) + "/role-mappings/realm/composite", null);
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) {
            out.add(n.path("name").asText());
        }
        return out;
    }

    public List<KcUser> usersWithRole(String role) {
        JsonNode arr = call("GET", "/roles/" + enc(role) + "/users?first=0&max=1000&briefRepresentation=false", null);
        List<KcUser> out = new ArrayList<>();
        for (JsonNode n : arr) {
            out.add(user(n));
        }
        return out;
    }

    public List<KcCredential> credentials(String id) {
        JsonNode arr = call("GET", "/users/" + enc(id) + "/credentials", null);
        List<KcCredential> out = new ArrayList<>();
        for (JsonNode n : arr) {
            out.add(new KcCredential(n.path("id").asText(), n.path("type").asText(), textOrNull(n, "userLabel"),
                    n.path("createdDate").asLong(0), textOrNull(n, "credentialData")));
        }
        return out;
    }

    public void deleteCredential(String id, String credentialId) {
        call("DELETE", "/users/" + enc(id) + "/credentials/" + enc(credentialId), null);
    }

    public void logoutUser(String id) {
        call("POST", "/users/" + enc(id) + "/logout", null);
    }

    public void deleteUser(String id) {
        call("DELETE", "/users/" + enc(id), null);
    }

    private static KcUser user(JsonNode n) {
        return new KcUser(n.path("id").asText(), n.path("username").asText(), textOrNull(n, "email"),
                textOrNull(n, "firstName"), textOrNull(n, "lastName"), n.path("enabled").asBoolean(true),
                n.path("createdTimestamp").asLong(0));
    }

    private static String textOrNull(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private String bearer() {
        OidcClient.Tokens t = token;
        if (t == null || t.accessExpiresAt() < System.currentTimeMillis() + 30_000) {
            t = oidc.serviceAccountToken();
            token = t;
        }
        return t.accessToken();
    }

    private JsonNode call(String method, String path, JsonNode body) {
        long start = System.nanoTime();
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + bearer())
                    .header("Accept", "application/json");
            if (body != null) {
                b.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            } else {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            metrics.keycloakCall("admin_api", resp.statusCode() < 500, System.nanoTime() - start);
            int s = resp.statusCode();
            if (s == 404) {
                throw FdropException.notFound("not found in Keycloak");
            }
            if (s == 409) {
                throw FdropException.conflict("a user with that username or email already exists");
            }
            if (s == 401 || s == 403) {
                throw new FdropException(502, "Bad gateway", "fdrop is not permitted to manage users in Keycloak"
                        + " (the fdrop-sftp service account needs manage-users)");
            }
            if (s >= 400) {
                throw new FdropException(502, "Bad gateway", "Keycloak returned " + s + ": " + resp.body());
            }
            String text = resp.body();
            return text == null || text.isBlank() ? json.nullNode() : json.readTree(text);
        } catch (FdropException e) {
            throw e;
        } catch (IOException e) {
            metrics.keycloakCall("admin_api", false, System.nanoTime() - start);
            throw FdropException.unavailable("Keycloak admin API unreachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw FdropException.unavailable("interrupted");
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
