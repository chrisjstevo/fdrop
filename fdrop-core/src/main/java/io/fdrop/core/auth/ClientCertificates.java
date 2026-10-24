package io.fdrop.core.auth;

import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.error.FdropException;
import io.fdrop.core.util.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.security.auth.x500.X500Principal;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.cert.CRL;
import java.security.cert.CertPathValidator;
import java.security.cert.CertStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Client certificates of application accounts: fingerprints and names for matching, chain validation against
 * {@code auth.applications.mtls.trustedCas} (re-read when the file changes) with optional CRLs, and parsing of the
 * certificate a TLS-terminating proxy forwards in a header.
 */
public class ClientCertificates {

    private static final Logger log = LoggerFactory.getLogger(ClientCertificates.class);
    private static final Pattern XFCC_CERT = Pattern.compile("(?:^|;)\\s*Cert=\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE);
    private static final long CRL_REFRESH_MS = 3_600_000;

    private final FdropConfig.AppMtls cfg;
    private volatile Set<TrustAnchor> anchors = Set.of();
    private volatile List<CRL> crls = List.of();
    private long casModified = -1;
    private long crlsLoadedAt;

    public ClientCertificates(FdropConfig.AppMtls cfg) {
        this.cfg = cfg;
    }

    // ---- names ----

    /** Lower-case hex SHA-256 of the DER certificate, the pinned fingerprint. */
    public static String fingerprint(X509Certificate c) {
        try {
            return Hashing.sha256Hex(c.getEncoded());
        } catch (GeneralSecurityException e) {
            throw FdropException.badRequest("unreadable certificate");
        }
    }

    /**
     * The subject DN as by-subject matching compares it: canonical (lower case, normalised spacing) with its parts
     * sorted, so it does not matter whether the admin copied it in OpenSSL's order or Java's.
     */
    public static String subject(X509Certificate c) {
        return canonical(c.getSubjectX500Principal());
    }

    /** The subject DN for display, in RFC 2253 form. */
    public static String displaySubject(X509Certificate c) {
        return c.getSubjectX500Principal().getName(X500Principal.RFC2253);
    }

    private static String canonical(X500Principal p) {
        try {
            List<Rdn> rdns = new ArrayList<>(new LdapName(p.getName(X500Principal.CANONICAL)).getRdns());
            Collections.sort(rdns);
            return rdns.stream().map(Rdn::toString).collect(Collectors.joining(","));
        } catch (InvalidNameException e) {
            return p.getName(X500Principal.CANONICAL);
        }
    }

    /** DNS, URI and email SANs, as {@code DNS:host}, {@code URI:spiffe://…} and {@code EMAIL:a@b}. */
    public static List<String> sans(X509Certificate c) {
        List<String> out = new ArrayList<>();
        try {
            Collection<List<?>> names = c.getSubjectAlternativeNames();
            if (names == null) {
                return out;
            }
            for (List<?> n : names) {
                int type = (Integer) n.get(0);
                String value = String.valueOf(n.get(1));
                switch (type) {
                    case 1 -> out.add("EMAIL:" + value);
                    case 2 -> out.add("DNS:" + value.toLowerCase(java.util.Locale.ROOT));
                    case 6 -> out.add("URI:" + value);
                    default -> {
                        // IP and other names are not used for matching
                    }
                }
            }
        } catch (CertificateException e) {
            return out;
        }
        return out;
    }

    /** Normalises a SAN an admin typed ({@code dns:Host.Example} becomes {@code DNS:host.example}). */
    public static String normaliseSan(String san) {
        int i = san.indexOf(':');
        if (i <= 0) {
            throw FdropException.badRequest("a SAN must start with DNS:, URI: or EMAIL:");
        }
        String type = san.substring(0, i).trim().toUpperCase(java.util.Locale.ROOT);
        String value = san.substring(i + 1).trim();
        if (value.isEmpty() || !Set.of("DNS", "URI", "EMAIL").contains(type)) {
            throw FdropException.badRequest("a SAN must start with DNS:, URI: or EMAIL:");
        }
        return type + ":" + (type.equals("DNS") ? value.toLowerCase(java.util.Locale.ROOT) : value);
    }

    /** Normalises a DN an admin typed, in either order, so it compares equal to {@link #subject}. */
    public static String normaliseSubject(String dn) {
        String d = dn.trim();
        // OpenSSL's one-line form: /CN=a/O=b
        if (d.startsWith("/")) {
            d = String.join(",", d.substring(1).split("/"));
        }
        try {
            return canonical(new X500Principal(d));
        } catch (IllegalArgumentException e) {
            throw FdropException.badRequest("not a valid distinguished name: " + e.getMessage());
        }
    }

    // ---- parsing ----

    /** Every certificate in a PEM text, leaf first. */
    public static List<X509Certificate> parsePem(String pem) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            List<X509Certificate> out = new ArrayList<>();
            for (var c : cf.generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)))) {
                out.add((X509Certificate) c);
            }
            if (out.isEmpty()) {
                throw FdropException.badRequest("no certificate found in the PEM text");
            }
            return out;
        } catch (CertificateException e) {
            throw FdropException.badRequest("not a PEM certificate: " + e.getMessage());
        }
    }

    /**
     * The client certificate a proxy put in a header: URL-encoded PEM (nginx {@code $ssl_client_escaped_cert}),
     * plain PEM, or Envoy's {@code X-Forwarded-Client-Cert} with a {@code Cert="…"} element. Empty when unreadable.
     */
    public static List<X509Certificate> fromHeader(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String v = value.trim();
        Matcher m = XFCC_CERT.matcher(v);
        if (m.find()) {
            v = m.group(1);
        }
        if (v.contains("%")) {
            v = URLDecoder.decode(v, StandardCharsets.UTF_8);
        }
        // some proxies fold the PEM onto one line with spaces for newlines
        v = v.replace("-----BEGIN CERTIFICATE----- ", "-----BEGIN CERTIFICATE-----\n")
                .replace(" -----END CERTIFICATE-----", "\n-----END CERTIFICATE-----");
        try {
            return parsePem(v);
        } catch (FdropException e) {
            return List.of();
        }
    }

    // ---- validation ----

    /** True when the leaf is within its validity period now. */
    public static boolean current(X509Certificate leaf) {
        try {
            leaf.checkValidity();
            return true;
        } catch (CertificateException e) {
            return false;
        }
    }

    /**
     * True when the chain leads to one of the trusted CAs, every certificate is current, and, when CRLs are
     * configured, none is revoked.
     */
    public boolean chainsToTrustedCa(List<X509Certificate> chain) {
        reload();
        if (anchors.isEmpty() || chain.isEmpty()) {
            return false;
        }
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            // drop a self-signed root the client sent along; the anchor stands for it
            List<X509Certificate> path = new ArrayList<>(chain);
            if (path.size() > 1) {
                X509Certificate last = path.get(path.size() - 1);
                if (last.getSubjectX500Principal().equals(last.getIssuerX500Principal())) {
                    path.remove(path.size() - 1);
                }
            }
            PKIXParameters params = new PKIXParameters(anchors);
            List<CRL> currentCrls = crls;
            params.setRevocationEnabled(!currentCrls.isEmpty());
            if (!currentCrls.isEmpty()) {
                params.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(currentCrls)));
            }
            CertPathValidator.getInstance("PKIX").validate(cf.generateCertPath(path), params);
            return true;
        } catch (GeneralSecurityException e) {
            log.debug("client certificate chain rejected: {}", e.toString());
            return false;
        }
    }

    private synchronized void reload() {
        if (cfg.trustedCas != null && !cfg.trustedCas.isBlank()) {
            Path file = Path.of(cfg.trustedCas);
            try {
                long modified = Files.getLastModifiedTime(file).toMillis();
                if (modified != casModified) {
                    List<X509Certificate> cas = parsePem(Files.readString(file, StandardCharsets.US_ASCII));
                    Set<TrustAnchor> a = new HashSet<>();
                    for (X509Certificate c : cas) {
                        a.add(new TrustAnchor(c, null));
                    }
                    anchors = Set.copyOf(a);
                    casModified = modified;
                    log.info("loaded {} trusted CA certificate(s) for application client certificates", cas.size());
                }
            } catch (IOException | RuntimeException e) {
                if (casModified == -1) {
                    log.warn("cannot read auth.applications.mtls.trustedCas {}: {}", file, e.toString());
                    casModified = -2;
                }
            }
        }
        if (!cfg.crls.isEmpty() && System.currentTimeMillis() - crlsLoadedAt > CRL_REFRESH_MS) {
            List<CRL> loaded = new ArrayList<>();
            for (String source : cfg.crls) {
                try (InputStream in = source.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")
                        ? URI.create(source).toURL().openStream() : Files.newInputStream(Path.of(source))) {
                    loaded.addAll(CertificateFactory.getInstance("X.509").generateCRLs(in));
                } catch (IOException | GeneralSecurityException | RuntimeException e) {
                    log.warn("cannot load CRL {}: {}", source, e.toString());
                }
            }
            if (!loaded.isEmpty() || crls.isEmpty()) {
                crls = List.copyOf(loaded);
            }
            crlsLoadedAt = System.currentTimeMillis();
        }
    }
}
