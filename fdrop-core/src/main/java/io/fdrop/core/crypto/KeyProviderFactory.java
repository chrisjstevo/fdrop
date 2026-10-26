package io.fdrop.core.crypto;

import io.fdrop.core.config.FdropConfig;

import java.io.IOException;

/**
 * Makes a {@link KeyProvider} named by {@code storage.encryption.keyProvider}. Found with
 * {@link java.util.ServiceLoader}, so a provider with its own dependencies (AWS KMS, in {@code fdrop-storage-s3})
 * lives in the module that has them.
 */
public interface KeyProviderFactory {

    String name();

    KeyProvider create(FdropConfig.Encryption cfg) throws IOException;
}
