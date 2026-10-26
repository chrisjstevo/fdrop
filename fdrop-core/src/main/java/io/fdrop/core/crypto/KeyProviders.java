package io.fdrop.core.crypto;

import io.fdrop.core.config.FdropConfig;

import java.io.IOException;
import java.util.ServiceLoader;

/** Finds the key provider named by {@code storage.encryption.keyProvider}. */
public final class KeyProviders {

    private KeyProviders() {
    }

    /**
     * The configured provider. With encryption off and no keys configured there is none (null): new files are
     * stored as they are, and encrypted files cannot be read until keys are configured again.
     */
    public static KeyProvider load(FdropConfig.Encryption cfg) throws IOException {
        if (!cfg.enabled && !configured(cfg)) {
            return null;
        }
        for (KeyProviderFactory f : ServiceLoader.load(KeyProviderFactory.class)) {
            if (f.name().equals(cfg.keyProvider)) {
                return f.create(cfg);
            }
        }
        throw new IllegalStateException("no key provider '" + cfg.keyProvider + "' on the classpath (local, vault;"
                + " aws-kms comes with fdrop-storage-s3)");
    }

    private static boolean configured(FdropConfig.Encryption cfg) {
        return switch (cfg.keyProvider) {
            case "local" -> cfg.local.keys != null && !cfg.local.keys.isBlank();
            case "aws-kms" -> cfg.awsKms.keyId != null && !cfg.awsKms.keyId.isBlank();
            case "vault" -> cfg.vault.address != null && !cfg.vault.address.isBlank();
            default -> false;
        };
    }
}
