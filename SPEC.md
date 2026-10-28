# fdrop — Technical Specification

Oct 22, 2026 · Living version: [fdrop — Technical Specification](https://claude.ai/artifact/Ghh6hqGnPMZmV8RmGQX1aN)

fdrop lets people upload and download files through a browser or any SFTP client, with every file stored, encrypted, on the local file system or in S3. The specification is split by topic into the files below; each one links back here.

| Part | Covers |
| --- | --- |
| [Overview and architecture](spec/overview.md) | What fdrop is, its goals and non-goals, and how the pieces fit together: [Overview](spec/overview.md#overview), [Architecture](spec/overview.md#architecture) |
| [Storage and encryption at rest](spec/storage.md) | The storage SPI, the local and S3 backends, and per-file envelope encryption: [Storage backend SPI](spec/storage.md#storage-backend-spi), [Encryption at rest](spec/storage.md#encryption-at-rest) |
| [HTTP API and browser UI](spec/http-api-and-ui.md) | Endpoints, the React UI, drag and drop, upload progress, My uploads, responsive layout and visual design |
| [Resumable chunked uploads](spec/resumable-uploads.md) | The chunked upload protocol, its states, storage and client behaviour |
| [SFTP server](spec/sftp.md) | The MINA SSHD server and fdrop's virtual file system |
| [Folders and forwarding](spec/folders.md) | Shared folders, forward folders, destinations, mutual authentication and sending to several destinations |
| [File expiry](spec/file-expiry.md) | Expiry syntax, defaults and limits, the HTTP and SFTP interfaces and deletion |
| [Search](spec/search.md) | Full-text search over names and contents with Lucene |
| [Audit log](spec/audit-log.md) | The tamper-evident audit log and its admin page |
| [Authentication, users and application accounts](spec/auth-and-accounts.md) | Keycloak sign-in, two-factor authentication, roles, user management and application accounts: [Authentication and authorisation](spec/auth-and-accounts.md#authentication-and-authorisation), [Users and roles](spec/auth-and-accounts.md#users-and-roles), [Application accounts](spec/auth-and-accounts.md#application-accounts) |
| [Clients](spec/clients.md) | The fdrop command line client and the Java and .NET client libraries: [Command line client (fdrop-cli)](spec/clients.md#command-line-client-fdrop-cli), [Java client library (fdrop-http-client-java)](spec/clients.md#java-client-library-fdrop-http-client-java), [.NET client library (fdrop-http-client-dotnet)](spec/clients.md#net-client-library-fdrop-http-client-dotnet) |
| [Security and configuration](spec/security-and-configuration.md) | Security measures and every configuration setting: [Security](spec/security-and-configuration.md#security), [Configuration](spec/security-and-configuration.md#configuration) |
| [Tech stack, build and dev container](spec/build-and-dev-container.md) | Libraries, the Maven build, packaging and the Podman dev container |
| [Kubernetes deployment](spec/kubernetes.md) | The reference OpenTofu deployment |
| [Observability](spec/observability.md) | Management endpoints, Prometheus metrics, alerts and the dashboard |
| [Testing, milestones and open questions](spec/testing-and-roadmap.md) | What is tested, the delivery milestones and the questions still open: [Testing](spec/testing-and-roadmap.md#testing), [Milestones](spec/testing-and-roadmap.md#milestones), [Open questions](spec/testing-and-roadmap.md#open-questions) |
