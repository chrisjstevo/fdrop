# Storage and encryption at rest

[fdrop specification](../SPEC.md)

## Storage backend SPI

All file bytes go through one Java interface, `StorageBackend`, discovered via `java.util.ServiceLoader` and selected by `storage.type` in config. Paths are logical keys (`users/<sub>/docs/report.pdf`), never OS paths; backends map them to their own layout.

```java
public interface StorageBackend extends AutoCloseable {
    String type();                                   // "local", "s3"
    void init(Config cfg);

    // Objects
    Optional<ObjectInfo> stat(StoragePath path);
    Stream<ObjectInfo> list(StoragePath dir, boolean recursive);
    ReadableByteChannel openRead(StoragePath path, long offset, long length);
    WriteHandle openWrite(StoragePath path, WriteOptions opts); // streaming, atomic on commit()
    void delete(StoragePath path);
    void move(StoragePath src, StoragePath dst);           // rename within backend
    void mkdirs(StoragePath dir);                         // no-op for S3 (prefixes)

    // Multipart (used by chunked uploads)
    MultipartUpload beginMultipart(StoragePath target, WriteOptions opts);
}

public interface MultipartUpload {
    String id();
    PartInfo putPart(int partNumber, ReadableByteChannel src, long length, Checksum sha256);
    List<PartInfo> listParts();
    ObjectInfo complete(List<PartInfo> parts);            // concatenates in part order
    void abort();
}
```

`ObjectInfo` carries path, size, last-modified, ETag/content hash, content type and an `isDirectory` flag. `WriteHandle.commit()` makes the object visible atomically; `abort()` discards it. Every method is blocking and is called from a dedicated I/O executor, never from a Netty event loop or an SSHD NIO thread.

### Backend behaviour

| Concern | `local` | `s3` |
| --- | --- | --- |
| Root | `storage.local.root` directory | `storage.s3.bucket` + optional `prefix` |
| Directories | Real directories | Zero-byte `dir/` marker objects plus prefix listing |
| Atomic write | Write to `.fdrop-tmp/<uuid>`, `fsync`, `Files.move(ATOMIC_MOVE)` | Single `PutObject` (< 16 MiB) or S3 multipart upload |
| Ranged read | `FileChannel.position(offset)` | `GetObject` with `Range: bytes=a-b` |
| Multipart parts | Files under `.fdrop-parts/<uploadId>/<n>.part` | Native S3 `UploadPart`; part number = chunk index + 1 |
| Concatenate | Stream parts in order into the target temp file, then atomic move | `CompleteMultipartUpload` (server-side, no bytes through fdrop) |
| Move/rename | `Files.move` | `CopyObject` + `DeleteObject` (multipart copy above 5 GiB) |
| Path safety | Normalise, reject `..`, resolve and verify still under root, refuse symlinks | Reject `..`, control chars and keys > 1024 bytes |

S3 uses the AWS SDK for Java v2 async client with the CRT HTTP client, credentials from the default provider chain, and optional `endpointOverride` + path-style access for MinIO, Ceph and other S3-compatible stores.

S3 requires every part except the last to be at least 5 MiB, so the chunk size floor is 5 MiB for all backends. The local backend has no such limit but uses the same chunk size so the client protocol is identical.

## Encryption at rest

Every file fdrop stores is encrypted before its bytes reach the backend, and decrypted as it is read back. Encryption is a streaming layer, `EncryptingStorage`, that wraps whichever `StorageBackend` is configured, the way `InstrumentedStorage` does, so the local file system and S3 are covered alike and neither the protocol front ends nor the backends change. Nothing above the layer sees ciphertext; nothing below it sees plaintext.

- **What is covered:** stored files, resumable upload parts (local `.fdrop-parts/` files and S3 parts), the local backend's `.fdrop-tmp/` write files, SFTP spool files under `sftp.spoolDir`, the text extracted for search (`system/search-text/`) and, through `EncryptedDirectory`, each node's search index (see Search). Folder markers (`dir/` objects on S3, real directories locally) carry no content and are not encrypted.
- **What is not hidden:** storage keys are paths (`users/<sub>/docs/report.pdf`), so folder and file names, the number of files and their approximate sizes stay visible to anyone who can list the storage. Hiding names is an open question.
- **Streaming throughout:** a file is cut into fixed-size segments (64 KiB), and each is encrypted and authenticated on its own. Uploads encrypt as bytes arrive and downloads decrypt as they leave, so memory per transfer stays at about one segment, a multi-GB file is never buffered, and a ranged read decrypts only the segments it touches.
- **Default on:** `storage.encryption.enabled` is `true` for new installations; fdrop refuses to start with it on and no usable key-encryption key. Turning it off later stops encrypting new files but still decrypts existing ones.

### Threat model

| Protects against | Does not protect against |
| --- | --- |
| A stolen or discarded disk, a copied volume or snapshot, leaked backups | Anyone who controls the running fdrop process or its host's memory |
| Read access to the S3 bucket or the storage root (operators, other tenants, a misconfigured bucket policy) | Anyone who holds the key-encryption key (KEK) and a copy of the storage |
| Silent corruption and tampering with stored bytes: any change, truncation, reordering or splicing of segments fails decryption | Swapping whole objects between paths by someone with write access to the storage (each file still decrypts, at the wrong path) |
| Files left behind by an abandoned upload or a crash, which are ciphertext like everything else | Names, sizes and timestamps visible in listings (above) |

S3 server-side encryption (`storage.s3.sse`) still applies on top and can stay on; it protects against a different party (the storage provider's media), not against bucket readers.

### Keys

Envelope encryption with two levels:

- **Data encryption key (DEK):** 32 random bytes from `SecureRandom`, new for every object. Never stored in clear: the object's header holds it wrapped (encrypted) under a KEK.
- **Key-encryption key (KEK):** held outside the storage, by one of three providers (`storage.encryption.keyProvider`):

| Provider | KEK lives in | Wrap / unwrap |
| --- | --- | --- |
| `local` | A keyring of 32-byte keys from an environment variable or secret file (`storage.encryption.local.keys`), never from `fdrop.yaml` in the image | AES-256-GCM in process, a fresh 12-byte nonce per wrap |
| `aws-kms` | An AWS KMS key (`storage.encryption.awsKms.keyId`) | KMS `GenerateDataKey` for new objects, `Decrypt` to read, via the AWS SDK and the default credential chain |
| `vault` | A HashiCorp Vault transit key (`storage.encryption.vault.*`) | Transit `datakey/plaintext` and `decrypt` |

- **Key ids:** each KEK has an id (`k2026a`, or the KMS key ARN with version, or `vault:<name>:<version>`) written into the header, so several KEKs can be in use at once. The `local` keyring is `id:base64key` pairs, comma-separated; the first is the primary that wraps new DEKs, and the others only unwrap.
- **DEK cache:** unwrapped DEKs are kept in a bounded in-memory cache keyed by object and header hash (`storage.encryption.dekCache`, default 10,000 entries, 10 min), so a ranged read or an SFTP client reading in many small requests does not call KMS each time. Evicted keys are overwritten with zeros, as far as Java allows. A resumable upload's DEK stays cached while its session is open.
- **No KEK, no service:** at startup fdrop wraps and unwraps a test DEK with the primary KEK; failure stops startup. While running, a KEK provider failure fails the affected request with `503`, `/readyz` returns 503, and `FdropKmsErrors` fires.

### Object format (`fdrop-stream-v1`)

Each stored object is a header followed by the encrypted segments. All integers are big-endian.

| Field | Bytes | Value |
| --- | --- | --- |
| Magic | 8 | `FDRP` `ENC` `\x01`: `46 44 52 50 45 4E 43 01` |
| Header length | 4 | Total header bytes, magic to MAC inclusive: always 1,024 in v1 |
| Algorithm | 1 | `1` = AES-256-GCM segments, keys from HKDF-SHA-256 |
| Segment size | 4 | Plaintext bytes per segment, `S`: always 65,536 in v1, so every chunk size the server picks (whole MiB) is a multiple |
| Key provider | 1 | `1` local, `2` aws-kms, `3` vault |
| Key id | 2 + n | Length, then the KEK id in UTF-8 |
| Wrapped DEK | 2 + n | Length, then the provider's ciphertext of the DEK |
| Padding | to byte 992 | Zero bytes |
| Header MAC | 32 | HMAC-SHA-256 over every header byte before it (bytes 0–991), with the header key below |

The header length and segment size are fixed so that a stored object's plaintext size follows from its stored size alone, and listings never read headers. They are still written into every header, so a later format version can change them.

Keys and segments:

- `headerHash = SHA-256(header bytes before the MAC)`
- `segmentKey = HKDF-SHA-256(ikm = DEK, salt = empty, info = "fdrop-stream-v1 segment" ‖ headerHash, 32 bytes)`
- `headerKey = HKDF-SHA-256(ikm = DEK, salt = empty, info = "fdrop-stream-v1 header", 32 bytes)`
- Segment `i` (0-based) of plaintext `p_i` is stored as `nonce_i ‖ AES-256-GCM-Encrypt(segmentKey, nonce_i, p_i, aad_i)`, where `nonce_i` is 12 random bytes and `aad_i = i (8 bytes) ‖ final (1 byte: 1 for the last segment, else 0)`. The GCM tag is 16 bytes, so each segment adds 28 bytes.
- Every segment but the last holds exactly `S` plaintext bytes; the last holds 0 to `S`. An empty file is one empty final segment.

Why it is built this way:

- **Random access:** segments sit at fixed offsets (segment `i` starts at `H + i × (S + 28)`, `H` being the header length), so a `Range` request, an SFTP read at any offset, or a resumed download reads and decrypts only the segments it needs.
- **Integrity of the whole file:** the index in each segment's AAD stops reordering and splicing, the final flag stops truncation (a file that ends on a non-final segment, or has data after a final one, fails), and `headerHash` in the segment key ties every segment to its own header. The header MAC stops changes to the segment size or key id. Any failure is an error, never partial plaintext: the read fails with an I/O error, the HTTP download is cut off (status already sent) or refused with `500` before the first byte, and the event is audited and counted.
- **Random nonces, one key per object:** no counter has to be coordinated between the parallel chunk uploads of one file, between nodes, or across a re-sent chunk. With a fresh DEK per object and at most 2³² segments, random 96-bit nonces stay far inside GCM's safety margin.
- **Standard parts only:** AES-GCM, HKDF and HMAC from the JDK (`javax.crypto`), which use the CPU's AES instructions. No new crypto library. The design follows Tink's streaming AEAD and age's STREAM construction; it is fdrop's own only so that independent parts can be encrypted in parallel.

**Sizes.** With `n = max(1, ⌈P / S⌉)` segments and `H` = 1,024, a `P`-byte file stores `H + P + 28n` bytes. Going back from a stored size `C`: `n = ⌈(C − H) / (S + 28)⌉` and `P = C − H − 28n`; a stored size that gives a final segment shorter than 28 bytes belongs to no valid object. The overhead is 0.04% plus the 1 KiB header (the fields use 120 bytes with `local` keys, up to about 400 with KMS). `ObjectInfo.size`, listings, quotas, `Content-Length` and My uploads all report the plaintext size, computed from the stored size without reading the object; only a read fetches the header (one small ranged read, then cached per object version with its derived key).

### How each path works

| Path | With encryption |
| --- | --- |
| Simple upload, SFTP sequential write | `openWrite` generates the DEK, writes the header, then encrypts each segment as it fills and passes ciphertext to the backend's `WriteHandle`; `commit()` encrypts the final segment. Atomic visibility is unchanged |
| Resumable upload | At `beginMultipart` the DEK and header are made. The upload's multipart id, kept in `upload_session.backend_upload_id`, becomes `fdrop-enc1|<total>|<part size>|<provider>|<key id>|<wrapped DEK>|<backend id>` (base64url fields), so any node, after any restart, can rebuild the header and encrypt any chunk; the id holds only the wrapped DEK. Chunk size must be a multiple of `S` (8 MiB = 128 segments), so chunk `k` is segments `128k` to `128k + 127` and is encrypted on its own, in any order and in parallel. Part 1 is the header plus chunk 0; the last chunk ends with the final segment. `X-Chunk-SHA256` is checked against the plaintext as before. Re-sending a chunk encrypts it again with new nonces, which is safe |
| Assembly | Local: concatenating the encrypted parts gives a valid object, since the segment layout already continues across parts. S3: `CompleteMultipartUpload` still runs server-side, with no bytes through fdrop. The whole-file SHA-256 check reads back through the layer, so it hashes the plaintext |
| Download, `Range` | The layer maps the plaintext range to whole segments, reads only that ciphertext range (one ranged `GetObject` on S3), decrypts and trims. Zero-copy `DefaultFileRegion` downloads are no longer possible for encrypted files; they use `ChunkedNioStream`, like TLS already does |
| SFTP read at offsets | As a ranged download; a seek opens at the containing segment |
| SFTP resume / `APPEND` | The existing file is decrypted into a new upload and the new bytes follow (fdrop already resumes this way), then the new object replaces the old one atomically. The final segment of the old file therefore never needs rewriting in place |
| SFTP random-offset writes | The spool file is encrypted with a per-process key that exists only in memory; spool files cannot be recovered after a restart, as today |
| Move, rename | Local `Files.move`; S3 `CopyObject`. The bytes are copied unchanged: nothing in the format depends on the path |
| Forwarding | Forward workers read through the layer and send plaintext over the mutually authenticated SSH connection; the destination receives the original file |
| File expiry, delete | Unchanged. The DEK lives only in the object's header, so it goes with the object; a backup copy stays readable for as long as its KEK can still be unwrapped (see Key rotation) |

### Key rotation and re-encryption

- **Rotating the KEK:** add the new key at the front of the keyring (or point `awsKms.keyId` at the new key), restart, and new objects use it at once; objects wrapped under older keys still decrypt as long as those keys remain in the keyring or KMS. KMS-side automatic rotation needs nothing from fdrop, since KMS keeps old key material for `Decrypt`.
- **Retiring a KEK:** the **re-encryption job** rewrites every object whose header names a given key id: it reads through the layer, writes a new object with a new DEK under the primary KEK to a temporary key, and replaces the original atomically (local `ATOMIC_MOVE`; S3 `CopyObject` within the bucket, then delete). Started by an admin with `POST /api/v1/admin/storage/encryption/reencrypt` `{ "fromKeyId": "k2025b" }`; progress is in `GET /api/v1/admin/storage/encryption`, it runs at `storage.encryption.reencrypt.bytesPerSecond` (default 50 MiB/s) per node, it claims objects through the metadata DB like the expiry sweeper so several nodes can share it, and it resumes after a restart. When `fdrop_encryption_objects{key_id="k2025b"}` reaches 0, the key can be removed from the keyring.
- **A compromised KEK** is handled the same way: rotate, re-encrypt everything from the old id, then delete the old key. Backups made before that are still readable with the old key, so retire it from backups too.
- **Inventory:** a daily job reads every object's header (one small ranged read each) and counts objects per key id and objects still in plaintext, for the gauges and the admin screen; admins can also run it on demand.
- The header cannot be re-wrapped in place (it sits at the start of the object and S3 objects are immutable), which is why retiring a key rewrites the objects. Rewrapping only the DEK would be cheaper; it is not done in v1, so that the header stays the single, self-contained record needed to decrypt a file, also from a backup without the metadata DB.

### Migrating existing plaintext files

- With `storage.encryption.enabled: true` on a store that already holds plaintext files, set `storage.encryption.allowPlaintextReads: true` for the migration. Reads then check the first 8 bytes: an object without the magic is served as plaintext and counted in `fdrop_encryption_plaintext_reads_total`. Only objects that start with the magic and whose header MAC verifies are decrypted, so a plaintext file that happens to start with the magic bytes is still served correctly.
- Admins start the same re-encryption job with `{ "plaintext": true }` to encrypt every plaintext object in place. When the inventory shows no plaintext objects left, set `allowPlaintextReads: false`, and any plaintext object is then refused as tampering.

### Admin view and audit

- **Admin: Storage encryption** screen: enabled or not, provider, primary key id, objects per key id, plaintext objects left, a re-encryption job's progress with Pause and Cancel, and the last decryption failures (path and time).
- Audit entries: `storage.encryption.start` (provider and primary key id at startup), `storage.encryption.reencrypt.started|finished|cancelled` with the acting admin, scope and object counts, and `storage.decrypt.failed` with the storage key, the segment index and the reason (`header_mac`, `segment_auth`, `truncated`, `unknown_key`).

### Metadata

- `upload_session.backend_upload_id` widens to 4,000 characters for the encrypted multipart id above.
- `encryption_inventory(key_id, objects, bytes, counted_at)`: the last inventory.
- `reencrypt_job(id, scope, from_key_id, state, objects_total, objects_done, bytes_done, started_by, started_at, finished_at)` and `reencrypt_item(job_id, storage_key, claimed_by, claimed_until, state, error)`.
