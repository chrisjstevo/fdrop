# SFTP server

[fdrop specification](../SPEC.md)

SFTP runs on Apache MINA SSHD 2.x (`sshd-core` + `sshd-sftp`) on `sftp.port` (default 2222), exposing each user's namespace through a custom NIO `FileSystem` backed by the same `StorageBackend`. Only the SFTP subsystem is enabled: no shell, exec, port forwarding or SCP.

## Server setup

- `SshServer.setUpDefaultServer()` with the NIO2 I/O factory (MINA's default; Netty I/O factory is an option to share the event loop group).
- Host key from `sftp.hostKeyPath` (Ed25519 generated on first start if missing, then persisted so clients don't see key changes).
- `PasswordAuthenticator` and `PublickeyAuthenticator` delegate to the shared `AuthService`; keyboard-interactive disabled.
- Modern algorithms only: `curve25519-sha256`, `chacha20-poly1305`, `aes256-gcm`, `ssh-ed25519`, `rsa-sha2-512`.
- `SftpSubsystemFactory` with an `SftpEventListener` for audit logging, metrics, queueing a forward job when a file written into a forward folder is closed, and applying the folder's default or a pending expiry when any file is closed (see File expiry).
- `FileSystemFactory` returns a per-session `FdropFileSystem` presenting the user's virtual tree (`/home`, `/shared`, `/forward`; see Folders), so nothing outside it is reachable by construction. Membership is re-checked on each operation, so a removed member loses access mid-session.
- Limits: `MAX_CONCURRENT_SESSIONS` per user (default 10), idle timeout 10 min, auth timeout 60 s, max 3 auth attempts.

## FdropFileSystem

A `java.nio.file.spi.FileSystemProvider` + `FileSystem` + `Path` implementation translating NIO calls to the Storage SPI:

| NIO call | Storage SPI |
| --- | --- |
| `readAttributes` | `stat` (synthesised POSIX perms: dirs 0755, files 0644, owner = user) |
| `newDirectoryStream` | `list(dir, false)` |
| `newByteChannel(READ)` | `openRead(path, offset, …)`; seekable via re-open at new offset on S3 |
| `newByteChannel(WRITE, CREATE, TRUNCATE_EXISTING)` | `openWrite`; commit on close |
| `createDirectory` | `mkdirs` |
| `delete` | `delete` |
| `move` | `move` |
| `setAttribute` (mtime, perms) | Accepted and ignored (or stored as metadata for mtime) |
| Any call on `<name>.fdrop-expires` | No storage call: reads, sets or clears `<name>`'s expiry (see File expiry); never listed |

## Writes and resume over SFTP

- Sequential writes stream directly into `openWrite`; S3 uses an internal multipart upload with 8 MiB buffered parts, so memory per open file is bounded at roughly one part.
- Random-offset writes (rare; some clients use them for parallel transfer) fall back to a local spool file under `sftp.spoolDir`, encrypted with a per-process key held only in memory, and are uploaded on close.
- If the session drops before close, the partial object is discarded (never visible). SFTP resume (`reput` in OpenSSH, resume in WinSCP) opens with `APPEND`: without encryption at rest the local backend appends in place and the S3 backend implements append as a server-side `UploadPartCopy` of the existing object followed by new parts; with it, the existing file is decrypted into a new upload, the new bytes follow, and the result replaces the old object atomically.
- Downloads support arbitrary offsets via ranged reads, so client-side resume of downloads works on both backends.
- A file past its expiry is not found, for new opens and for the next read of an open handle; see File expiry.
