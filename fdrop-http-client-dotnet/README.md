# Fdrop.Client

A .NET client for [fdrop](https://github.com/chrisjstevo/fdrop)'s REST API. `UploadAsync` looks like one call, but uses fdrop's
resumable upload protocol underneath: the file goes up in chunks (three at a time), each failed chunk is retried, and
the server checks the whole file's SHA-256 at the end. Requests that keep failing throw
`FdropRetriesExhaustedException` after `MaxAttempts` tries; refusals throw `FdropApiException` with fdrop's problem
type and detail.

Targets `net10.0`, portable (no runtime identifier, no native code), trimming and Native AOT compatible. Its only
dependency is `Microsoft.Extensions.Logging.Abstractions`.

```csharp
using Fdrop.Client;

using var cert = FdropClientOptions.LoadPemCertificate("app.crt", "app.key");
using var fdrop = new FdropClient(new FdropClientOptions
{
    BaseAddress = new Uri("https://fdrop.example.com:8444"),   // the application mTLS port
    ClientCertificate = cert,
    TrustedCaCertificates = FdropClientOptions.LoadPemCa("ca.crt"),
    MaxAttempts = 5,                                           // per request, and per chunk
});

FileEntry file = await fdrop.UploadAsync("export.csv", "/shared/Finance/",
    new UploadOptions(ExpiresIn: "30 days", Progress: new Progress<UploadProgress>(p => Console.WriteLine(p))));
```

People and scripts can use `BearerToken = ct => GetTokenAsync(ct)` instead, and application accounts with a password
`BasicUsername` and `BasicPassword`. To use an `HttpClient` from `IHttpClientFactory`, pass it as the second
constructor argument; its handler is used as it is.

## Building

Part of fdrop's Maven build (`./mvnw verify`), which also starts a real fdrop server for the integration tests. In this
folder, `dotnet build` and `dotnet test --filter Category=Unit` work on their own. For the integration tests, start the
test server in another terminal and leave it running:

```bash
../mvnw -pl fdrop-http-client-dotnet test-compile exec:exec@test-server
```

then run `FDROP_TEST_SERVER=target/test-server/server.json dotnet test --filter Category=Integration`.
