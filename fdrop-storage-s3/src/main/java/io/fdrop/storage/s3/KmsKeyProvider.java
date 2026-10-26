package io.fdrop.storage.s3;

import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.crypto.EncryptionException;
import io.fdrop.core.crypto.KeyProvider;
import io.fdrop.core.crypto.KeyProviderFactory;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.http.crt.AwsCrtHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.KmsClientBuilder;
import software.amazon.awssdk.services.kms.model.DecryptResponse;
import software.amazon.awssdk.services.kms.model.EncryptResponse;
import software.amazon.awssdk.services.kms.model.IncorrectKeyException;
import software.amazon.awssdk.services.kms.model.InvalidCiphertextException;
import software.amazon.awssdk.services.kms.model.KmsException;
import software.amazon.awssdk.services.kms.model.NotFoundException;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

/**
 * KEKs in AWS KMS. Data keys are wrapped with {@code Encrypt} and unwrapped with {@code Decrypt}, bound to an
 * encryption context so a wrapped key from another system cannot be passed off as an fdrop one. The stored key id
 * is the key ARN KMS reports; KMS keeps rotated key material, so its automatic rotation needs nothing from fdrop.
 */
public final class KmsKeyProvider implements KeyProvider {

    public static final int CODE = 2;
    private static final Map<String, String> CONTEXT = Map.of("purpose", "fdrop-data-key");

    private final KmsClient kms;
    private final String keyId;

    public KmsKeyProvider(KmsClient kms, String keyId) {
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalStateException("storage.encryption.awsKms.keyId is not set");
        }
        this.kms = kms;
        this.keyId = keyId;
    }

    static KmsClient client(FdropConfig.AwsKms c) {
        KmsClientBuilder b = KmsClient.builder().httpClient(AwsCrtHttpClient.builder().build())
                .credentialsProvider(DefaultCredentialsProvider.builder().build());
        if (c.region != null && !c.region.isBlank()) {
            b.region(Region.of(c.region));
        }
        if (c.endpointOverride != null && !c.endpointOverride.isBlank()) {
            b.endpointOverride(URI.create(c.endpointOverride));
        }
        return b.build();
    }

    @Override
    public String name() {
        return "aws-kms";
    }

    @Override
    public int code() {
        return CODE;
    }

    @Override
    public String primaryKeyId() {
        return keyId;
    }

    @Override
    public Wrapped wrap(byte[] dataKey) throws IOException {
        try {
            EncryptResponse r = kms.encrypt(b -> b.keyId(keyId).plaintext(SdkBytes.fromByteArray(dataKey))
                    .encryptionContext(CONTEXT));
            return new Wrapped(r.keyId(), r.ciphertextBlob().asByteArray());
        } catch (KmsException e) {
            throw new IOException("KMS Encrypt failed: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] unwrap(String id, byte[] wrapped) throws IOException {
        try {
            DecryptResponse r = kms.decrypt(b -> b.keyId(id).ciphertextBlob(SdkBytes.fromByteArray(wrapped))
                    .encryptionContext(CONTEXT));
            return r.plaintext().asByteArray();
        } catch (NotFoundException | IncorrectKeyException e) {
            throw new EncryptionException(EncryptionException.Reason.UNKNOWN_KEY, "KMS key " + id
                    + " is not available");
        } catch (InvalidCiphertextException e) {
            throw new EncryptionException(EncryptionException.Reason.HEADER, "KMS refused the wrapped key");
        } catch (KmsException e) {
            throw new IOException("KMS Decrypt failed: " + e.getMessage(), e);
        }
    }

    /** Registered for {@code keyProvider: aws-kms}. */
    public static final class Factory implements KeyProviderFactory {
        @Override
        public String name() {
            return "aws-kms";
        }

        @Override
        public KeyProvider create(FdropConfig.Encryption cfg) {
            return new KmsKeyProvider(client(cfg.awsKms), cfg.awsKms.keyId);
        }
    }
}
