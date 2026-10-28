using System.Collections.Concurrent;
using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Fdrop.Client.Tests;

/// <summary>
/// Where the Maven build's fdrop test server is (<c>FDROP_TEST_SERVER</c>, written by <c>ClientTestServer</c>). Without
/// it the integration tests are skipped.
/// </summary>
internal sealed record TestServer(string Url, string MtlsUrl, string Ca, string ClientCert, string ClientKey)
{
    private static readonly Lazy<TestServer?> Current = new(() =>
    {
        string? file = Environment.GetEnvironmentVariable("FDROP_TEST_SERVER");
        if (string.IsNullOrEmpty(file) || !File.Exists(file))
        {
            return null;
        }

        using var doc = JsonDocument.Parse(File.ReadAllText(file));
        var r = doc.RootElement;
        return new TestServer(r.GetProperty("url").GetString()!, r.GetProperty("mtlsUrl").GetString()!, r.GetProperty("ca").GetString()!,
            r.GetProperty("clientCert").GetString()!, r.GetProperty("clientKey").GetString()!);
    });

    public static TestServer Get()
    {
        var s = Current.Value;
        if (s is null)
        {
            Assert.Skip("no fdrop test server: run through Maven, or start it with ./mvnw -pl fdrop-http-client-dotnet pre-integration-test");
        }

        return s;
    }

    /// <summary>The CA of the server's certificate: fdrop serves HTTPS only.</summary>
    public X509Certificate2Collection Roots() => FdropClientOptions.LoadPemCa(Ca);

    /// <summary>A handler trusting the server's certificate, for calls made beside the client.</summary>
    public SocketsHttpHandler Handler() => new FdropClientOptions { TrustedCaCertificates = Roots() }.CreateHandler();
}

/// <summary>Records requests and fails or delays the ones a test picks, in front of the real handler.</summary>
internal sealed class FaultHandler : DelegatingHandler
{
    private int inFlightChunks;

    public FaultHandler()
        : base(TestServer.Get().Handler())
    {
    }

    public ConcurrentQueue<string> Requests { get; } = new();

    /// <summary>Returns an exception to throw instead of sending, or null to send.</summary>
    public Func<HttpRequestMessage, Exception?> Fail { get; set; } = _ => null;

    /// <summary>Replaces the server's answer: gets the request and the real send, returns the response to use.</summary>
    public Func<HttpRequestMessage, Func<Task<HttpResponseMessage>>, Task<HttpResponseMessage>> Respond { get; set; } = (_, send) => send();

    /// <summary>Runs before a chunk is sent, while it counts as in flight.</summary>
    public Func<HttpRequestMessage, CancellationToken, Task> BeforeChunk { get; set; } = (_, _) => Task.CompletedTask;

    public int MaxChunksInFlight { get; private set; }

    /// <summary>The upload session the requests went to.</summary>
    public string? SessionId => Requests.Select(r => Regex.Match(r, "/api/v1/uploads/([^/]+)/chunks/")).FirstOrDefault(m => m.Success)?.Groups[1].Value;

    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        Requests.Enqueue($"{request.Method} {request.RequestUri!.AbsolutePath}");
        if (Fail(request) is { } e)
        {
            throw e;
        }

        bool chunk = request.Method == HttpMethod.Put && request.RequestUri.AbsolutePath.Contains("/chunks/", StringComparison.Ordinal);
        if (!chunk)
        {
            return await Respond(request, () => base.SendAsync(request, cancellationToken));
        }

        int now = Interlocked.Increment(ref inFlightChunks);
        lock (this)
        {
            MaxChunksInFlight = Math.Max(MaxChunksInFlight, now);
        }

        try
        {
            await BeforeChunk(request, cancellationToken);
            return await Respond(request, () => base.SendAsync(request, cancellationToken));
        }
        finally
        {
            Interlocked.Decrement(ref inFlightChunks);
        }
    }
}

/// <summary>The client against a real fdrop server, with faults put in by <see cref="FaultHandler"/>.</summary>
[Trait("Category", "Integration")]
public sealed class IntegrationTests : IDisposable
{
    private const int MiB = 1024 * 1024;
    private static readonly string[] Discarded = ["ABORTED", "GONE"];
    private readonly string dir = Directory.CreateTempSubdirectory("fdrop-client-it").FullName;
    private readonly string home = "/home/" + Guid.NewGuid().ToString("N")[..12];

    public void Dispose() => Directory.Delete(dir, recursive: true);

    private static CancellationToken Ct => TestContext.Current.CancellationToken;

    private static FdropClientOptions Options(string user, int maxAttempts = 3, int parallel = 3) => new()
    {
        BaseAddress = new Uri(TestServer.Get().Url),
        TrustedCaCertificates = TestServer.Get().Roots(),
        BearerToken = _ => ValueTask.FromResult("user:" + user),
        MaxAttempts = maxAttempts,
        InitialBackoff = TimeSpan.FromMilliseconds(10),
        MaxBackoff = TimeSpan.FromMilliseconds(50),
        ParallelChunks = parallel,
    };

    private static FdropClient Client(string user) => new(Options(user));

    private static FdropClient Client(FaultHandler handler, int maxAttempts = 3, int parallel = 3)
        => new(Options("alice", maxAttempts, parallel), new HttpClient(handler) { Timeout = Timeout.InfiniteTimeSpan });

    private string File(string name, int bytes)
    {
        var data = new byte[bytes];
        new Random(bytes).NextBytes(data);
        string path = Path.Combine(dir, name);
        System.IO.File.WriteAllBytes(path, data);
        return path;
    }

    /// <summary>The session's state as alice sees it; ABORTED, or gone, once the client discarded it.</summary>
    private static async Task<string> SessionState(string id)
    {
        using var http = new HttpClient(TestServer.Get().Handler());
        using var req = new HttpRequestMessage(HttpMethod.Get, TestServer.Get().Url + "/api/v1/uploads/" + id);
        req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", "user:alice");
        using var r = await http.SendAsync(req, Ct);
        if (r.StatusCode == HttpStatusCode.NotFound)
        {
            return "GONE";
        }

        using var doc = JsonDocument.Parse(await r.Content.ReadAsStringAsync(Ct));
        return doc.RootElement.GetProperty("state").GetString()!;
    }

    [Fact]
    public async Task UploadsAFileInChunksThatLooksLikeOneCall()
    {
        string big = File("big.bin", (12 * MiB) + 123);
        var progress = new ConcurrentBag<long>();
        using var fdrop = Client("alice");
        Assert.True((await fdrop.CreateDirectoryAsync(home, Ct)).IsDirectory);
        Assert.All((await fdrop.ListAsync("/", Ct)).Entries, e => Assert.True(e.IsDirectory));
        var f = await fdrop.UploadAsync(big, home + "/", new UploadOptions(ExpiresIn: "30 days", Progress: new SyncProgress(p => progress.Add(p.Acknowledged))), Ct);
        Assert.Equal(home + "/big.bin", f.Path);
        Assert.Equal(new FileInfo(big).Length, f.Size);
        Assert.Equal("30 days", f.ExpiresIn);
        Assert.NotNull(f.ExpiresAt);
        // 5 MiB chunks: three, then the final report
        Assert.Contains(new FileInfo(big).Length, progress);
        Assert.True(progress.Count >= 4);

        string back = Path.Combine(dir, "back.bin");
        await fdrop.DownloadAsync(home + "/big.bin", back, Ct);
        Assert.Equal(await System.IO.File.ReadAllBytesAsync(big, Ct), await System.IO.File.ReadAllBytesAsync(back, Ct));
        await using var stream = await fdrop.OpenDownloadAsync(home + "/big.bin", Ct);
        using var copy = new MemoryStream();
        await stream.CopyToAsync(copy, Ct);
        Assert.Equal(await System.IO.File.ReadAllBytesAsync(big, Ct), copy.ToArray());
    }

    [Fact]
    public async Task UploadsFromStreamsAndAnEmptyFile()
    {
        byte[] data = await System.IO.File.ReadAllBytesAsync(File("s.bin", 6 * MiB), Ct);
        using var fdrop = Client("alice");
        await fdrop.CreateDirectoryAsync(home, Ct);
        using var seekable = new MemoryStream(data);
        Assert.Equal(data.Length, (await fdrop.UploadAsync(seekable, -1, home + "/seekable.bin", ct: Ct)).Size);
        using var forward = new ForwardOnlyStream(new MemoryStream(data));
        Assert.Equal(data.Length, (await fdrop.UploadAsync(forward, data.Length, home + "/forward.bin", ct: Ct)).Size);
        Assert.Equal(0, (await fdrop.UploadAsync(File("empty.txt", 0), home + "/", ct: Ct)).Size);
        Assert.Equal(["empty.txt", "forward.bin", "seekable.bin"],
            (await fdrop.ListAsync(home, Ct)).Entries.Select(e => e.Name).Order(StringComparer.Ordinal));
        // arguments are checked before the call returns its task
        Assert.Throws<ArgumentException>(() => { _ = fdrop.UploadAsync(new ForwardOnlyStream(new MemoryStream(data)), -1, home + "/x", ct: Ct); });
    }

    [Fact]
    public async Task AShortStreamIsRefusedAndItsSessionDiscarded()
    {
        var handler = new FaultHandler();
        using var fdrop = Client(handler);
        await fdrop.CreateDirectoryAsync(home, Ct);
        using var source = new MemoryStream(new byte[(6 * MiB) + 100]);
        var e = await Assert.ThrowsAsync<FdropClientException>(() => fdrop.UploadAsync(source, 11 * MiB, home + "/short.bin", ct: Ct));
        Assert.Contains($"ended after {(6 * MiB) + 100} of the {11 * MiB} bytes", e.Message, StringComparison.Ordinal);
        Assert.Contains(handler.Requests, r => r.StartsWith("DELETE /api/v1/uploads/", StringComparison.Ordinal));
        Assert.Empty((await fdrop.ListAsync(home, Ct)).Entries);
    }

    [Fact]
    public async Task RefusalsAreApiExceptions()
    {
        string a = File("a.txt", 10);
        using var fdrop = Client("alice");
        await fdrop.CreateDirectoryAsync(home, Ct);
        await fdrop.UploadAsync(a, home + "/a.txt", ct: Ct);
        var exists = await Assert.ThrowsAsync<FdropApiException>(() => fdrop.UploadAsync(a, home + "/a.txt", ct: Ct));
        Assert.Equal(409, exists.StatusCode);
        Assert.Contains(home + "/a.txt", exists.Message, StringComparison.Ordinal);
        Assert.Equal(10, (await fdrop.UploadAsync(a, home + "/a.txt", new UploadOptions(Overwrite: true), Ct)).Size);
        Assert.Equal(404, (await Assert.ThrowsAsync<FdropApiException>(() => fdrop.ListAsync(home + "/nope", Ct))).StatusCode);
        Assert.Equal(404, (await Assert.ThrowsAsync<FdropApiException>(() => fdrop.DownloadAsync(home + "/nope.txt", Path.Combine(dir, "x"), Ct))).StatusCode);
        Assert.Equal(404, (await Assert.ThrowsAsync<FdropApiException>(() => fdrop.OpenDownloadAsync(home + "/nope.txt", Ct))).StatusCode);
    }

    [Fact]
    public async Task FilesExpiryAndFolders()
    {
        using var fdrop = Client("alice");
        var me = await fdrop.GetMeAsync(Ct);
        Assert.Equal("alice", me.Username);
        Assert.False(me.IsApplication);
        await fdrop.CreateDirectoryAsync(home, Ct);
        await fdrop.UploadAsync(File("r.txt", 5), home + "/r.txt", ct: Ct);
        Assert.Equal(home + "/report.txt", (await fdrop.MoveAsync(home + "/r.txt", home + "/report.txt", Ct)).Path);
        Assert.Equal("2 days", (await fdrop.SetExpiryAsync(home + "/report.txt", "2 days", Ct)).ExpiresIn);
        var at = new DateTimeOffset(DateTime.UtcNow.Date.AddDays(5).AddHours(9), TimeSpan.Zero);
        Assert.Equal(at, (await fdrop.SetExpiryAtAsync(home + "/report.txt", at, Ct)).ExpiresAt);
        Assert.Null((await fdrop.ClearExpiryAsync(home + "/report.txt", Ct)).ExpiresAt);

        string name = "Finance " + Guid.NewGuid().ToString("N")[..8];
        var folder = await fdrop.CreateSharedFolderAsync(name, Ct);
        Assert.Equal("SHARED", folder.Type);
        Assert.True(folder.CreatedAt > DateTimeOffset.UtcNow.AddMinutes(-5));
        var withBob = await fdrop.PutMemberAsync(folder.Id, "bob", Permission.Read, Ct);
        Assert.Contains(withBob.Members, m => m.Username == "bob" && m.Permission == Permission.Read);
        Assert.Contains(await fdrop.GetFoldersAsync(Ct), f => f.Name == name);
        Assert.Equal(name, (await fdrop.GetFolderAsync(folder.Id, Ct)).Name);
        await fdrop.UploadAsync(File("q3.txt", 3), folder.Path + "/", ct: Ct);
        using (var bob = Client("bob"))
        {
            Assert.Equal(["q3.txt"], (await bob.ListAsync(folder.Path, Ct)).Entries.Select(e => e.Name));
            var refused = await Assert.ThrowsAsync<FdropApiException>(() => bob.UploadAsync(File("b.txt", 1), folder.Path + "/", ct: Ct));
            Assert.Equal(403, refused.StatusCode);
        }

        await fdrop.RemoveMemberAsync(folder.Id, "bob", Ct);
        await fdrop.DeleteAsync(home + "/report.txt", ct: Ct);
        Assert.Empty((await fdrop.ListAsync(home, Ct)).Entries);
        Assert.Contains((await fdrop.GetUploadHistoryAsync(null, 50, Ct)).Rows, r => r.FileName == "q3.txt");
        Assert.Empty(await fdrop.GetForwardDestinationsAsync(Ct));
        await fdrop.DeleteFolderAsync(folder.Id, Ct);
    }

    [Fact]
    public async Task AnApplicationSignsInWithItsCertificate()
    {
        var server = TestServer.Get();
        string file = File("export.csv", 7 * MiB);
        using var cert = FdropClientOptions.LoadPemCertificate(server.ClientCert, server.ClientKey);
        using var fdrop = new FdropClient(new FdropClientOptions
        {
            BaseAddress = new Uri(server.MtlsUrl),
            ClientCertificate = cert,
            TrustedCaCertificates = FdropClientOptions.LoadPemCa(server.Ca),
        });
        Assert.True((await fdrop.GetMeAsync(Ct)).IsApplication);
        var e = await fdrop.UploadAsync(file, "/home/" + Guid.NewGuid().ToString("N")[..8] + ".csv", ct: Ct);
        Assert.Equal(7 * MiB, e.Size);
    }

    [Fact]
    public async Task AServerItDoesNotTrustIsAnErrorNotARetry()
    {
        var server = TestServer.Get();
        string otherCa = Path.Combine(dir, "other.pem");
        using (var key = System.Security.Cryptography.ECDsa.Create())
        {
            var req = new System.Security.Cryptography.X509Certificates.CertificateRequest("CN=someone else", key, System.Security.Cryptography.HashAlgorithmName.SHA256);
            using var ca = req.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddDays(1));
            await System.IO.File.WriteAllTextAsync(otherCa, ca.ExportCertificatePem(), Ct);
        }

        using var cert = FdropClientOptions.LoadPemCertificate(server.ClientCert, server.ClientKey);
        using var fdrop = new FdropClient(new FdropClientOptions
        {
            BaseAddress = new Uri(server.MtlsUrl),
            ClientCertificate = cert,
            TrustedCaCertificates = FdropClientOptions.LoadPemCa(otherCa),
            MaxAttempts = 3,
        });
        var e = await Assert.ThrowsAsync<FdropClientException>(() => fdrop.GetMeAsync(Ct));
        Assert.IsNotType<FdropRetriesExhaustedException>(e);
        Assert.Contains("TLS handshake failed", e.Message, StringComparison.Ordinal);
    }

    [Fact]
    public async Task FailedChunksAreSentAgain()
    {
        string big = File("flaky.bin", 11 * MiB);
        var failedOnce = new ConcurrentDictionary<string, bool>();
        var handler = new FaultHandler
        {
            // every chunk's first try is cut off
            Fail = r => r.Method == HttpMethod.Put && failedOnce.TryAdd(r.RequestUri!.AbsolutePath, true)
                ? new HttpRequestException(HttpRequestError.ResponseEnded, "Connection reset", new IOException("Connection reset by peer"))
                : null,
        };
        using var fdrop = Client(handler);
        await fdrop.CreateDirectoryAsync(home, Ct);
        var f = await fdrop.UploadAsync(big, home + "/", ct: Ct);
        Assert.Equal(new FileInfo(big).Length, f.Size);
        Assert.Equal(3, failedOnce.Count);
        Assert.Equal(6, handler.Requests.Count(r => r.StartsWith("PUT", StringComparison.Ordinal)));
        string back = Path.Combine(dir, "back");
        await fdrop.DownloadAsync(f.Path, back, Ct);
        Assert.Equal(await System.IO.File.ReadAllBytesAsync(big, Ct), await System.IO.File.ReadAllBytesAsync(back, Ct));
    }

    [Fact]
    public async Task GivesUpAfterMaxAttemptsAndLeavesNothingBehind()
    {
        string big = File("doomed.bin", 6 * MiB);
        int tries = 0;
        var handler = new FaultHandler
        {
            Fail = r => r.Method == HttpMethod.Put && r.RequestUri!.AbsolutePath.EndsWith("/chunks/1", StringComparison.Ordinal) && Interlocked.Increment(ref tries) > 0
                ? new HttpRequestException(HttpRequestError.ResponseEnded, "Connection reset")
                : null,
        };
        using var fdrop = Client(handler);
        await fdrop.CreateDirectoryAsync(home, Ct);
        var e = await Assert.ThrowsAsync<FdropRetriesExhaustedException>(() => fdrop.UploadAsync(big, home + "/", ct: Ct));
        Assert.Equal(3, e.Attempts);
        Assert.Equal(3, tries);
        Assert.Contains("sending chunk 2 of 2", e.Message, StringComparison.Ordinal);
        Assert.Contains(home + "/doomed.bin", e.Message, StringComparison.Ordinal);
        Assert.Contains("gave up after 3 attempts", e.Message, StringComparison.Ordinal);
        Assert.Contains(await SessionState(handler.SessionId!), Discarded);
        Assert.Empty((await fdrop.ListAsync(home, Ct)).Entries);
    }

    [Fact]
    public async Task CancellingStopsTheUploadAndDiscardsItsSession()
    {
        string big = File("cancelled.bin", 16 * MiB);
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(Ct);
        var handler = new FaultHandler
        {
            BeforeChunk = async (r, t) =>
            {
                if (r.RequestUri!.AbsolutePath.EndsWith("/chunks/1", StringComparison.Ordinal))
                {
                    await cts.CancelAsync();
                    await Task.Delay(Timeout.Infinite, t);
                }
            },
        };
        using var fdrop = Client(handler, parallel: 1);
        await fdrop.CreateDirectoryAsync(home, Ct);
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => fdrop.UploadAsync(big, home + "/", ct: cts.Token));
        Assert.Contains(await SessionState(handler.SessionId!), Discarded);
        Assert.Empty((await fdrop.ListAsync(home, Ct)).Entries);
    }

    [Fact]
    public async Task SendsAtMostParallelChunksAtOnce()
    {
        string big = File("wide.bin", 20 * MiB);
        var handler = new FaultHandler { BeforeChunk = (_, t) => Task.Delay(100, t) };
        using var fdrop = Client(handler, parallel: 2);
        await fdrop.CreateDirectoryAsync(home, Ct);
        await fdrop.UploadAsync(big, home + "/", ct: Ct);
        Assert.Equal(2, handler.MaxChunksInFlight);
    }

    [Fact]
    public async Task AChunkConflictIsCheckedWithTheServer()
    {
        string file = File("conflict.bin", 11 * MiB);
        var seen = new ConcurrentDictionary<string, int>();
        var handler = new FaultHandler
        {
            Respond = async (r, send) =>
            {
                string path = r.RequestUri!.AbsolutePath;
                if (r.Method != HttpMethod.Put || !path.Contains("/chunks/", StringComparison.Ordinal))
                {
                    return await send();
                }

                int n = seen.AddOrUpdate(path, 1, (_, v) => v + 1);
                if (path.EndsWith("/chunks/0", StringComparison.Ordinal) && n == 1)
                {
                    // the server stored it but its answer was a conflict: the status check finds it
                    using var stored = await send();
                    return new HttpResponseMessage(HttpStatusCode.Conflict);
                }

                if (path.EndsWith("/chunks/1", StringComparison.Ordinal) && n == 1)
                {
                    // a conflict before anything was stored: the chunk is sent again
                    return new HttpResponseMessage(HttpStatusCode.Conflict);
                }

                return await send();
            },
        };
        using var fdrop = Client(handler);
        await fdrop.CreateDirectoryAsync(home, Ct);
        Assert.Equal(11 * MiB, (await fdrop.UploadAsync(file, home + "/", ct: Ct)).Size);
        Assert.Equal(1, seen[seen.Keys.Single(k => k.EndsWith("/chunks/0", StringComparison.Ordinal))]);
        Assert.Equal(2, seen[seen.Keys.Single(k => k.EndsWith("/chunks/1", StringComparison.Ordinal))]);
        Assert.Equal(2, handler.Requests.Count(r => r.StartsWith("GET /api/v1/uploads/", StringComparison.Ordinal)));
    }

    [Fact]
    public async Task WaitsWhileTheServerAssemblesTheFile()
    {
        string file = File("assembled.bin", 6 * MiB);
        var handler = new FaultHandler
        {
            Respond = async (r, send) =>
            {
                var real = await send();
                if (r.Method != HttpMethod.Post || !r.RequestUri!.AbsolutePath.EndsWith("/complete", StringComparison.Ordinal))
                {
                    return real;
                }

                // as for a large file on local storage: 202, and the client polls the status
                string id = r.RequestUri.AbsolutePath.Split('/')[4];
                real.Dispose();
                return new HttpResponseMessage(HttpStatusCode.Accepted)
                {
                    Content = new StringContent($$"""{"id":"{{id}}","chunkSize":5242880,"chunkCount":2,"state":"ASSEMBLING"}""",
                        System.Text.Encoding.UTF8, "application/json"),
                };
            },
        };
        using var fdrop = Client(handler);
        await fdrop.CreateDirectoryAsync(home, Ct);
        var f = await fdrop.UploadAsync(file, home + "/", ct: Ct);
        Assert.Equal(home + "/assembled.bin", f.Path);
        Assert.Equal(6 * MiB, f.Size);
        Assert.Contains(handler.Requests, r => r.StartsWith("GET /api/v1/uploads/", StringComparison.Ordinal));
        Assert.Contains("GET /api/v1/files", handler.Requests);
    }

    [Fact]
    public async Task SearchesNamesAndContents()
    {
        string word = "zebra" + Guid.NewGuid().ToString("N")[..10];
        string file = Path.Combine(dir, "notes.txt");
        await System.IO.File.WriteAllTextAsync(file, "the quarterly " + word + " report", Ct);
        using var fdrop = Client("alice");
        await fdrop.CreateDirectoryAsync(home, Ct);
        await fdrop.UploadAsync(file, home + "/", ct: Ct);
        SearchResult? found = null;
        for (int i = 0; i < 100 && (found is null || found.Total == 0); i++)
        {
            await Task.Delay(100, Ct);
            found = await fdrop.SearchAsync(word, ct: Ct);
        }

        var hit = Assert.Single(found!.Results);
        Assert.Equal(home + "/notes.txt", hit.Path);
        Assert.Contains(hit.Snippets.SelectMany(s => s), s => s.Match && s.Text.Contains(word, StringComparison.OrdinalIgnoreCase));
        using var bob = Client("bob");
        Assert.Equal(0, (await bob.SearchAsync(word, ct: Ct)).Total);
    }

    [Fact]
    public async Task ForwardJobsBelongToForwardFolders()
    {
        using var fdrop = Client("alice");
        var folder = await fdrop.CreateSharedFolderAsync("jobs " + Guid.NewGuid().ToString("N")[..8], Ct);
        var notForward = await Assert.ThrowsAsync<FdropApiException>(() => fdrop.GetForwardJobsAsync(folder.Id, Ct));
        Assert.Equal(400, notForward.StatusCode);
        Assert.Contains("not a forward folder", notForward.Message, StringComparison.Ordinal);
        await Assert.ThrowsAsync<FdropApiException>(() => fdrop.RetryForwardJobAsync(folder.Id, "no-such-job", Ct));
        await Assert.ThrowsAsync<FdropApiException>(() => fdrop.CreateForwardFolderAsync("fwd", "no-such-destination", 3, Ct));
    }

    [Fact]
    public async Task ADownloadStreamReadsEveryWay()
    {
        byte[] data = await System.IO.File.ReadAllBytesAsync(File("read.bin", 100_000), Ct);
        using var fdrop = Client("alice");
        await fdrop.CreateDirectoryAsync(home, Ct);
        await fdrop.UploadAsync(new MemoryStream(data), data.Length, home + "/read.bin", ct: Ct);
        await using var s = await fdrop.OpenDownloadAsync(home + "/read.bin", Ct);
        Assert.True(s.CanRead);
        Assert.False(s.CanSeek);
        Assert.False(s.CanWrite);
        Assert.Throws<NotSupportedException>(() => s.Length);
        Assert.Throws<NotSupportedException>(() => s.Position);
        Assert.Throws<NotSupportedException>(() => s.Position = 1);
        Assert.Throws<NotSupportedException>(() => s.Seek(0, SeekOrigin.Begin));
        Assert.Throws<NotSupportedException>(() => s.SetLength(1));
        Assert.Throws<NotSupportedException>(() => s.Write([1], 0, 1));
        s.Flush();
        var got = new MemoryStream();
        var buf = new byte[1000];
        got.Write(buf, 0, s.Read(buf, 0, buf.Length));
        got.Write(buf, 0, s.Read(buf.AsSpan()));
#pragma warning disable CA1835 // the byte[] overload is the one being tested
        got.Write(buf, 0, await s.ReadAsync(buf, 0, buf.Length, Ct));
#pragma warning restore CA1835
        await s.CopyToAsync(got, Ct);
        Assert.Equal(data, got.ToArray());
    }

    /// <summary>Reports at once, on the reporting thread, unlike <see cref="Progress{T}"/>.</summary>
    private sealed class SyncProgress(Action<UploadProgress> report) : IProgress<UploadProgress>
    {
        public void Report(UploadProgress value) => report(value);
    }

    /// <summary>A stream that cannot seek, like a network stream.</summary>
    private sealed class ForwardOnlyStream(Stream inner) : Stream
    {
        public override bool CanRead => true;

        public override bool CanSeek => false;

        public override bool CanWrite => false;

        public override long Length => throw new NotSupportedException();

        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }

        public override int Read(byte[] buffer, int offset, int count) => inner.Read(buffer, offset, count);

        public override void Flush()
        {
        }

        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();

        public override void SetLength(long value) => throw new NotSupportedException();

        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();

        protected override void Dispose(bool disposing)
        {
            if (disposing)
            {
                inner.Dispose();
            }

            base.Dispose(disposing);
        }
    }
}
