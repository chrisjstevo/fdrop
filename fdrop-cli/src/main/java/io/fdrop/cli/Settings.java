package io.fdrop.cli;

import picocli.CommandLine;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where option values come from when not given on the command line: an environment variable for the connection
 * options, then the properties file ({@code --config}, {@code $FDROP_CONFIG_FILE}, or
 * {@code ~/.config/fdrop/cli.properties}). Keys in the file are the long option names without dashes. The file may
 * hold the key password, so it must not be readable by anyone but its owner.
 */
final class Settings implements CommandLine.IDefaultValueProvider {

    static final Map<String, String> ENV = Map.of(
            "url", "FDROP_URL",
            "cert", "FDROP_CERT",
            "key", "FDROP_KEY",
            "key-password-file", "FDROP_KEY_PASSWORD_FILE",
            "keystore", "FDROP_KEYSTORE",
            "keystore-password-file", "FDROP_KEYSTORE_PASSWORD_FILE",
            "ca", "FDROP_CA");

    private static final Pattern DURATION = Pattern.compile("^\\s*(\\d+)\\s*(ms|s|m|h|d)\\s*$");

    private final Map<String, String> env;
    private final Properties file;

    private Settings(Map<String, String> env, Properties file) {
        this.env = env;
        this.file = file;
    }

    /** Finds and reads the config file: {@code --config} in the arguments wins over the environment. */
    static Settings load(String[] args, Map<String, String> env) {
        Path explicit = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--config") && i + 1 < args.length) {
                explicit = Path.of(args[i + 1]);
            } else if (args[i].startsWith("--config=")) {
                explicit = Path.of(args[i].substring("--config=".length()));
            }
        }
        if (explicit == null && env.get("FDROP_CONFIG_FILE") != null && !env.get("FDROP_CONFIG_FILE").isBlank()) {
            explicit = Path.of(env.get("FDROP_CONFIG_FILE"));
        }
        Path path = explicit != null ? explicit
                : Path.of(System.getProperty("user.home"), ".config", "fdrop", "cli.properties");
        Properties p = new Properties();
        if (explicit == null && !Files.exists(path)) {
            return new Settings(env, p);
        }
        if (!Files.isRegularFile(path)) {
            throw new CliException(CliException.USAGE, "config file " + path + " not found");
        }
        checkPrivate(path);
        try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            throw new CliException(CliException.USAGE, "cannot read config file " + path + ": " + e.getMessage());
        }
        return new Settings(env, p);
    }

    private static void checkPrivate(Path path) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(path);
            if (perms.contains(PosixFilePermission.GROUP_READ) || perms.contains(PosixFilePermission.OTHERS_READ)) {
                throw new CliException(CliException.USAGE, "config file " + path + " is readable by others; make it"
                        + " private with: chmod 600 " + path);
            }
        } catch (UnsupportedOperationException e) {
            // not a POSIX file system: nothing to check
        } catch (IOException e) {
            throw new CliException(CliException.USAGE, "cannot read config file " + path + ": " + e.getMessage());
        }
    }

    /** {@code watch}'s two folders also come from these environment variables. */
    static final Map<String, String> WATCH_ENV = Map.of(
            "local-dir", "FDROP_WATCH_LOCAL_DIR",
            "remote-dir", "FDROP_WATCH_REMOTE_DIR");

    @Override
    public String defaultValue(ArgSpec arg) {
        if (!(arg instanceof OptionSpec opt)) {
            return null;
        }
        String name = opt.longestName().replaceFirst("^--", "");
        boolean watch = opt.command() != null && "watch".equals(opt.command().name());
        String var = watch && WATCH_ENV.containsKey(name) ? WATCH_ENV.get(name) : ENV.get(name);
        if (var != null && env.get(var) != null && !env.get(var).isBlank()) {
            return env.get(var);
        }
        // watch settings are watch.<camelCase> in the file (watch.localDir, watch.afterUpload), then the plain name
        if (watch && file.getProperty("watch." + camel(name)) != null) {
            return file.getProperty("watch." + camel(name));
        }
        return file.getProperty(name);
    }

    /** {@code after-upload} → {@code afterUpload}. */
    static String camel(String name) {
        StringBuilder b = new StringBuilder();
        boolean up = false;
        for (char c : name.toCharArray()) {
            if (c == '-') {
                up = true;
            } else {
                b.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return b.toString();
    }

    /** {@code 30s}, {@code 15m}, {@code 1h}, {@code 2d}. */
    static Duration duration(String text) {
        Matcher m = DURATION.matcher(text);
        if (!m.matches()) {
            throw new CliException(CliException.USAGE, "not a duration: " + text + " (use e.g. 30m, 1h or 2d)");
        }
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "ms" -> Duration.ofMillis(n);
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            case "h" -> Duration.ofHours(n);
            default -> Duration.ofDays(n);
        };
    }

    /** A password from a file, without its trailing newline. */
    static char[] password(Path file) {
        if (file == null) {
            return null;
        }
        try {
            String s = Files.readString(file, StandardCharsets.UTF_8);
            return s.replaceAll("[\\r\\n]+$", "").toCharArray();
        } catch (IOException e) {
            throw new CliException(CliException.USAGE, "cannot read password file " + file);
        }
    }
}
