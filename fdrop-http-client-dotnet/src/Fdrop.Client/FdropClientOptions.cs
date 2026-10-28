using System.Net.Security;
using System.Security.Cryptography.X509Certificates;

namespace Fdrop.Client;

/// <summary>
/// Settings for a <see cref="FdropClient"/>. <see cref="BaseAddress"/> and one way to authenticate are required: a
/// bearer token, an application account's username and password, or a client certificate on fdrop's mTLS port.
/// </summary>
public sealed class FdropClientOptions
{
    /// <summary>The server, such as <c>https://fdrop.example.com</c> (or the application mTLS port, <c>:8444</c>).</summary>
    public Uri? BaseAddress { get; init; }

    /// <summary>
    /// An OAuth access token with the <c>fdrop-api</c> audience, asked for before every request so it can be refreshed.
    /// </summary>
    public Func<CancellationToken, ValueTask<string>>? BearerToken { get; init; }

    /// <summary>An application account's username, with <see cref="BasicPassword"/> (HTTP Basic, HTTPS only).</summary>
    public string? BasicUsername { get; init; }

    /// <summary>An application account's password.</summary>
    public string? BasicPassword { get; init; }

    /// <summary>A client certificate with its private key, for fdrop's mTLS port. See <see cref="LoadPemCertificate"/>.</summary>
    public X509Certificate2? ClientCertificate { get; init; }

    /// <summary>
    /// The CAs to trust for the server's certificate; when set, nothing else is trusted. Null for the system's store.
    /// See <see cref="LoadPemCa"/>.
    /// </summary>
    public X509Certificate2Collection? TrustedCaCertificates { get; init; }

    /// <summary>Tries per request (per chunk, for uploads) before giving up; at least 1.</summary>
    public int MaxAttempts { get; init; } = 5;

    /// <summary>The first wait after a failure; it doubles up to <see cref="MaxBackoff"/>, each wait with jitter.</summary>
    public TimeSpan InitialBackoff { get; init; } = TimeSpan.FromSeconds(1);

    /// <summary>The longest wait between tries.</summary>
    public TimeSpan MaxBackoff { get; init; } = TimeSpan.FromSeconds(60);

    /// <summary>How long connecting may take.</summary>
    public TimeSpan ConnectTimeout { get; init; } = TimeSpan.FromSeconds(30);

    /// <summary>How long one request may take, including one whole chunk.</summary>
    public TimeSpan RequestTimeout { get; init; } = TimeSpan.FromMinutes(5);

    /// <summary>Chunks sent at once.</summary>
    public int ParallelChunks { get; init; } = 3;

    /// <summary>Sent with every request; fdrop records it in the audit log.</summary>
    public string UserAgent { get; init; } = "fdrop-http-client-dotnet/" + ClientVersion.Value;

    /// <summary>
    /// Loads a PEM certificate and its private key (PKCS#8, or a traditional RSA or EC key). Errors name the files,
    /// never their contents.
    /// </summary>
    public static X509Certificate2 LoadPemCertificate(string certificatePath, string keyPath)
    {
        try
        {
            using var pem = X509Certificate2.CreateFromPemFile(certificatePath, keyPath);
            // an ephemeral key cannot be used for TLS client authentication on every platform: export and re-import
            return X509CertificateLoader.LoadPkcs12(pem.Export(X509ContentType.Pkcs12), null);
        }
        catch (Exception e) when (e is System.Security.Cryptography.CryptographicException or IOException)
        {
            throw new FdropClientException($"cannot load the client certificate {certificatePath} with key {keyPath}: {e.Message}", e);
        }
    }

    /// <summary>Loads the CA certificates in a PEM file, for <see cref="TrustedCaCertificates"/>.</summary>
    public static X509Certificate2Collection LoadPemCa(string path)
    {
        try
        {
            var cas = new X509Certificate2Collection();
            cas.ImportFromPemFile(path);
            if (cas.Count == 0)
            {
                throw new FdropClientException($"no certificate in {path}");
            }

            return cas;
        }
        catch (Exception e) when (e is System.Security.Cryptography.CryptographicException or IOException)
        {
            throw new FdropClientException($"cannot read the CA certificates in {path}: {e.Message}", e);
        }
    }

    /// <summary>Checks the settings; throws <see cref="ArgumentException"/> naming the one at fault.</summary>
    internal void Validate()
    {
        if (BaseAddress is null)
        {
            throw new ArgumentException("BaseAddress is required", nameof(BaseAddress));
        }

        if (BaseAddress.Scheme != Uri.UriSchemeHttps)
        {
            throw new ArgumentException("BaseAddress must be https://: fdrop serves HTTPS only", nameof(BaseAddress));
        }

        int ways = (BearerToken is null ? 0 : 1) + (BasicUsername is null ? 0 : 1) + (ClientCertificate is null ? 0 : 1);
        if (ways > 1)
        {
            throw new ArgumentException("give one of BearerToken, BasicUsername/BasicPassword or ClientCertificate", nameof(BearerToken));
        }

        if (BasicUsername is not null && BasicPassword is null)
        {
            throw new ArgumentException("BasicPassword is required with BasicUsername", nameof(BasicPassword));
        }

        if (ClientCertificate is not null && !ClientCertificate.HasPrivateKey)
        {
            throw new ArgumentException("ClientCertificate needs its private key", nameof(ClientCertificate));
        }

        if (MaxAttempts < 1)
        {
            throw new ArgumentException("MaxAttempts must be at least 1", nameof(MaxAttempts));
        }

        if (ParallelChunks < 1)
        {
            throw new ArgumentException("ParallelChunks must be at least 1", nameof(ParallelChunks));
        }

        if (InitialBackoff < TimeSpan.Zero || MaxBackoff < InitialBackoff)
        {
            throw new ArgumentException("backoff must be InitialBackoff >= 0 and MaxBackoff >= InitialBackoff", nameof(MaxBackoff));
        }
    }

    /// <summary>The handler the client owns when the application gives it no <see cref="HttpClient"/>.</summary>
    internal SocketsHttpHandler CreateHandler()
    {
        var ssl = new SslClientAuthenticationOptions
        {
            EnabledSslProtocols = System.Security.Authentication.SslProtocols.Tls12 | System.Security.Authentication.SslProtocols.Tls13,
        };
        if (ClientCertificate is not null)
        {
            ssl.ClientCertificates = [ClientCertificate];
        }

        if (TrustedCaCertificates is not null)
        {
            var roots = TrustedCaCertificates;
            ssl.RemoteCertificateValidationCallback = (_, certificate, _, errors) => TrustedBy(roots, certificate, errors);
        }

        return new SocketsHttpHandler
        {
            ConnectTimeout = ConnectTimeout,
            PooledConnectionLifetime = TimeSpan.FromMinutes(5),
            AllowAutoRedirect = false,
            SslOptions = ssl,
        };
    }

    /// <summary>The server's chain ends at one of <paramref name="roots"/>, and its name matches.</summary>
    internal static bool TrustedBy(X509Certificate2Collection roots, X509Certificate? certificate, SslPolicyErrors errors)
    {
        if (certificate is null || (errors & (SslPolicyErrors.RemoteCertificateNameMismatch | SslPolicyErrors.RemoteCertificateNotAvailable)) != 0)
        {
            return false;
        }

        using var chain = new X509Chain();
        chain.ChainPolicy.TrustMode = X509ChainTrustMode.CustomRootTrust;
        chain.ChainPolicy.CustomTrustStore.AddRange(roots);
        chain.ChainPolicy.RevocationMode = X509RevocationMode.NoCheck;
        using var leaf = new X509Certificate2(certificate);
        return chain.Build(leaf);
    }
}

/// <summary>The library's version, for the User-Agent.</summary>
internal static class ClientVersion
{
    public static readonly string Value =
        typeof(ClientVersion).Assembly.GetName().Version?.ToString(3) ?? "0.0.0";
}
