using System.Net;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;

namespace Fdrop.Client.Tests;

/// <summary>Settings checks, certificate loading, and fdrop's problem responses.</summary>
[Trait("Category", "Unit")]
public sealed class OptionsTests : IDisposable
{
    private readonly string dir = Directory.CreateTempSubdirectory("fdrop-client-tests").FullName;

    public void Dispose() => Directory.Delete(dir, recursive: true);

    private static CancellationToken Ct => TestContext.Current.CancellationToken;

    [Fact]
    public void ChecksTheSettings()
    {
        static string Fails(FdropClientOptions o) => Assert.Throws<ArgumentException>(() => new FdropClient(o)).Message;

        Assert.Contains("BaseAddress is required", Fails(new FdropClientOptions()), StringComparison.Ordinal);
        Assert.Contains("https://", Fails(new FdropClientOptions { BaseAddress = new Uri("http://fdrop.example.com") }), StringComparison.Ordinal);
        Assert.Contains("HTTPS only", Fails(new FdropClientOptions
        {
            BaseAddress = new Uri("http://localhost:1"), BasicUsername = "app", BasicPassword = "pw",
        }), StringComparison.Ordinal);
        Assert.Contains("give one of", Fails(new FdropClientOptions
        {
            BaseAddress = new Uri("https://x"), BasicUsername = "app", BasicPassword = "pw", BearerToken = _ => ValueTask.FromResult("t"),
        }), StringComparison.Ordinal);
        Assert.Contains("MaxAttempts", Fails(new FdropClientOptions { BaseAddress = new Uri("https://x"), MaxAttempts = 0 }), StringComparison.Ordinal);
        Assert.Contains("ParallelChunks", Fails(new FdropClientOptions { BaseAddress = new Uri("https://x"), ParallelChunks = 0 }), StringComparison.Ordinal);
        Assert.Contains("MaxBackoff", Fails(new FdropClientOptions
        {
            BaseAddress = new Uri("https://x"), InitialBackoff = TimeSpan.FromSeconds(10), MaxBackoff = TimeSpan.FromSeconds(1),
        }), StringComparison.Ordinal);

        using var ok = new FdropClient(new FdropClientOptions { BaseAddress = new Uri("https://fdrop.example.com/"), BearerToken = _ => ValueTask.FromResult("t") });
        Assert.StartsWith("fdrop-http-client-dotnet/", new FdropClientOptions().UserAgent, StringComparison.Ordinal);
    }

    private (string Cert, string Key) WritePem(string name, AsymmetricAlgorithm key, X509Certificate2 cert, bool pkcs8)
    {
        string c = Path.Combine(dir, name + ".crt");
        string k = Path.Combine(dir, name + ".key");
        File.WriteAllText(c, cert.ExportCertificatePem());
        File.WriteAllText(k, pkcs8 ? key.ExportPkcs8PrivateKeyPem() : ((ECDsa)key).ExportECPrivateKeyPem());
        return (c, k);
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public void LoadsAPemCertificateAndKey(bool pkcs8)
    {
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var req = new CertificateRequest("CN=app-client", key, HashAlgorithmName.SHA256);
        using var self = req.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddDays(30));
        var (c, k) = WritePem("app", key, self, pkcs8);
        using var loaded = FdropClientOptions.LoadPemCertificate(c, k);
        Assert.True(loaded.HasPrivateKey);
        Assert.Equal("CN=app-client", loaded.Subject);

        File.WriteAllText(k, "not a key");
        var e = Assert.Throws<FdropClientException>(() => FdropClientOptions.LoadPemCertificate(c, k));
        Assert.Contains(k, e.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void TrustsOnlyTheGivenCa()
    {
        using var caKey = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var caReq = new CertificateRequest("CN=test CA", caKey, HashAlgorithmName.SHA256);
        caReq.CertificateExtensions.Add(new X509BasicConstraintsExtension(true, false, 0, true));
        caReq.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.KeyCertSign, true));
        using var ca = caReq.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddDays(30));
        string caFile = Path.Combine(dir, "ca.pem");
        File.WriteAllText(caFile, ca.ExportCertificatePem());

        using var leafKey = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var leafReq = new CertificateRequest("CN=localhost", leafKey, HashAlgorithmName.SHA256);
        using var leaf = leafReq.Create(ca, DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddDays(10), [1, 2, 3]);
        using var stranger = leafReq.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddDays(10));

        var roots = FdropClientOptions.LoadPemCa(caFile);
        Assert.True(FdropClientOptions.TrustedBy(roots, leaf, System.Net.Security.SslPolicyErrors.RemoteCertificateChainErrors));
        Assert.False(FdropClientOptions.TrustedBy(roots, stranger, System.Net.Security.SslPolicyErrors.RemoteCertificateChainErrors));
        Assert.False(FdropClientOptions.TrustedBy(roots, leaf, System.Net.Security.SslPolicyErrors.RemoteCertificateNameMismatch));

        File.WriteAllText(caFile, "");
        Assert.Throws<FdropClientException>(() => FdropClientOptions.LoadPemCa(caFile));
    }

    [Fact]
    public async Task ProblemsBecomeApiExceptions()
    {
        using var r = new HttpResponseMessage(HttpStatusCode.Forbidden)
        {
            Content = new StringContent("""{"type":"two-factor-required","title":"Forbidden","status":403,"detail":"sign in with two factors"}""",
                Encoding.UTF8, "application/problem+json"),
        };
        var e = await FdropClient.ProblemAsync("listing /home", r, Ct);
        Assert.Equal(403, e.StatusCode);
        Assert.Equal("two-factor-required", e.ProblemType);
        Assert.Equal("sign in with two factors", e.Detail);
        Assert.Equal("listing /home: HTTP 403 Forbidden: sign in with two factors", e.Message);

        using var html = new HttpResponseMessage(HttpStatusCode.BadGateway) { Content = new StringContent("<html>bad gateway</html>") };
        var e2 = await FdropClient.ProblemAsync("op", html, Ct);
        Assert.Equal(502, e2.StatusCode);
        Assert.Null(e2.Title);
    }
}
