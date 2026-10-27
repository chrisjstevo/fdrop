using System.Diagnostics;
using System.Globalization;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization.Metadata;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace Fdrop.Client;

/// <summary>The <see cref="IFdropClient"/> over <see cref="HttpClient"/>.</summary>
public sealed class FdropClient : IFdropClient
{
    /// <summary>Activities for each call and chunk, for OpenTelemetry.</summary>
    internal static readonly ActivitySource Source = new("Fdrop.Client", ClientVersion.Value);

    private readonly HttpClient http;
    private readonly bool ownsHttp;
    private readonly Uri baseAddress;
    private readonly FdropClientOptions options;
    private readonly AuthenticationHeaderValue? basic;

    /// <summary>A client with its own HTTP handler, built from <paramref name="options"/>.</summary>
    public FdropClient(FdropClientOptions options, ILogger<FdropClient>? logger = null)
        : this(options, null, logger, TimeProvider.System)
    {
    }

    /// <summary>
    /// A client using an <see cref="HttpClient"/> the application owns (from <c>IHttpClientFactory</c>, say). Its
    /// handler is used as it is, so TLS settings belong there; the client never disposes it.
    /// </summary>
    public FdropClient(FdropClientOptions options, HttpClient httpClient, ILogger<FdropClient>? logger = null)
        : this(options, httpClient ?? throw new ArgumentNullException(nameof(httpClient)), logger, TimeProvider.System)
    {
    }

    internal FdropClient(FdropClientOptions options, HttpClient? httpClient, ILogger? logger, TimeProvider time)
    {
        ArgumentNullException.ThrowIfNull(options);
        options.Validate();
        this.options = options;
        baseAddress = new Uri(options.BaseAddress!.ToString().TrimEnd('/') + "/");
        Logger = logger ?? NullLogger.Instance;
        Time = time;
        if (httpClient is null)
        {
            http = new HttpClient(options.CreateHandler(), disposeHandler: true) { Timeout = Timeout.InfiniteTimeSpan };
            ownsHttp = true;
        }
        else
        {
            http = httpClient;
        }

        if (options.BasicUsername is not null)
        {
            basic = new AuthenticationHeaderValue("Basic",
                Convert.ToBase64String(Encoding.UTF8.GetBytes(options.BasicUsername + ":" + options.BasicPassword)));
        }

        Retrier = new Retrier(options.MaxAttempts, options.InitialBackoff, options.MaxBackoff, time, Logger);
    }

    internal ILogger Logger { get; }

    internal TimeProvider Time { get; }

    internal Retrier Retrier { get; }

    internal int ParallelChunks => options.ParallelChunks;

    // ---- requests ----

    internal static string Q(string value) => Uri.EscapeDataString(value);

    /// <summary>A request with the User-Agent and credentials; built afresh for every try, so tokens stay current.</summary>
    internal async Task<HttpRequestMessage> RequestAsync(HttpMethod method, string pathAndQuery, HttpContent? content, CancellationToken ct)
    {
        var r = new HttpRequestMessage(method, new Uri(baseAddress, pathAndQuery.TrimStart('/'))) { Content = content };
        r.Headers.UserAgent.ParseAdd(options.UserAgent);
        r.Headers.Accept.ParseAdd("application/json");
        if (basic is not null)
        {
            r.Headers.Authorization = basic;
        }
        else if (options.BearerToken is not null)
        {
            r.Headers.Authorization = new AuthenticationHeaderValue("Bearer", await options.BearerToken(ct).ConfigureAwait(false));
        }

        return r;
    }

    /// <summary>Sends with retries; <paramref name="request"/> builds the request for each try.</summary>
    internal Task<HttpResponseMessage> SendAsync(string operation, bool safeToRepeat,
        Func<CancellationToken, Task<HttpRequestMessage>> request, CancellationToken ct,
        HttpCompletionOption completion = HttpCompletionOption.ResponseContentRead)
        => Retrier.RunAsync(operation, safeToRepeat, options.RequestTimeout, async token =>
        {
            using var req = await request(token).ConfigureAwait(false);
            return await http.SendAsync(req, completion, token).ConfigureAwait(false);
        }, ct);

    internal static HttpContent Json<T>(T body, JsonTypeInfo<T> type) => JsonContent.Create(body, type);

    /// <summary>The body as <typeparamref name="T"/> on a 2xx answer, otherwise fdrop's problem as an exception.</summary>
    internal static async Task<T> ExpectAsync<T>(string operation, HttpResponseMessage r, JsonTypeInfo<T> type, CancellationToken ct)
    {
        using (r)
        {
            await CheckAsync(operation, r, ct).ConfigureAwait(false);
            try
            {
                return await r.Content.ReadFromJsonAsync(type, ct).ConfigureAwait(false)
                    ?? throw new FdropClientException($"{operation}: the response was empty");
            }
            catch (JsonException e)
            {
                throw new FdropClientException($"{operation}: unexpected response: {e.Message}", e);
            }
        }
    }

    internal static async Task CheckAsync(string operation, HttpResponseMessage r, CancellationToken ct)
    {
        if (r.IsSuccessStatusCode)
        {
            return;
        }

        throw await ProblemAsync(operation, r, ct).ConfigureAwait(false);
    }

    /// <summary>fdrop's RFC 9457 problem body, or just the status.</summary>
    internal static async Task<FdropApiException> ProblemAsync(string operation, HttpResponseMessage r, CancellationToken ct)
    {
        Problem? p = null;
        try
        {
            byte[] body = await r.Content.ReadAsByteArrayAsync(ct).ConfigureAwait(false);
            if (body.Length > 0)
            {
                p = JsonSerializer.Deserialize(body, FdropJson.Default.Problem);
            }
        }
        catch (JsonException)
        {
            // not JSON: the status says enough
        }

        return new FdropApiException(operation, (int)r.StatusCode, p?.Type, p?.Title, p?.Detail);
    }

    private async Task<T> GetAsync<T>(string operation, string pathAndQuery, JsonTypeInfo<T> type, CancellationToken ct)
    {
        using var activity = Source.StartActivity(operation);
        var r = await SendAsync(operation, true, t => RequestAsync(HttpMethod.Get, pathAndQuery, null, t), ct).ConfigureAwait(false);
        return await ExpectAsync(operation, r, type, ct).ConfigureAwait(false);
    }

    private async Task<T> CallAsync<TBody, T>(string operation, HttpMethod method, string pathAndQuery, TBody body,
        JsonTypeInfo<TBody> bodyType, bool safeToRepeat, JsonTypeInfo<T> type, CancellationToken ct)
    {
        using var activity = Source.StartActivity(operation);
        var r = await SendAsync(operation, safeToRepeat, t => RequestAsync(method, pathAndQuery, Json(body, bodyType), t), ct).ConfigureAwait(false);
        return await ExpectAsync(operation, r, type, ct).ConfigureAwait(false);
    }

    private async Task CallAsync(string operation, HttpMethod method, string pathAndQuery, bool safeToRepeat, CancellationToken ct)
    {
        using var activity = Source.StartActivity(operation);
        using var r = await SendAsync(operation, safeToRepeat, t => RequestAsync(method, pathAndQuery, null, t), ct).ConfigureAwait(false);
        await CheckAsync(operation, r, ct).ConfigureAwait(false);
    }

    // ---- account ----

    /// <inheritdoc/>
    public Task<Account> GetMeAsync(CancellationToken ct = default)
        => GetAsync("reading the account", "/api/v1/me", FdropJson.Default.Account, ct);

    /// <inheritdoc/>
    public Task<UploadHistory> GetUploadHistoryAsync(string? cursor = null, int limit = 50, CancellationToken ct = default)
        => GetAsync("reading the upload history", string.Create(CultureInfo.InvariantCulture,
            $"/api/v1/me/uploads?limit={limit}{(cursor is null ? "" : "&cursor=" + Q(cursor))}"), FdropJson.Default.UploadHistory, ct);

    // ---- files ----

    /// <inheritdoc/>
    public Task<Listing> ListAsync(string path, CancellationToken ct = default)
        => GetAsync("listing " + path, "/api/v1/files?path=" + Q(path), FdropJson.Default.Listing, ct);

    /// <inheritdoc/>
    public async Task<FileEntry> UploadAsync(string localPath, string remotePath, UploadOptions? options = null, CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(localPath);
        ArgumentNullException.ThrowIfNull(remotePath);
        var info = new FileInfo(localPath);
        if (!info.Exists)
        {
            throw new FdropClientException($"cannot read {localPath}: not a readable file");
        }

        string target = remotePath.EndsWith('/') ? remotePath + info.Name : remotePath;
        try
        {
            var file = new FileStream(localPath, FileMode.Open, FileAccess.Read, FileShare.Read, 1 << 16,
                FileOptions.SequentialScan | FileOptions.Asynchronous);
            await using (file.ConfigureAwait(false))
            {
                return await new ChunkedUpload(this, target, file.Length, info.LastWriteTimeUtc, options ?? UploadOptions.Default)
                    .RunAsync(file, ct).ConfigureAwait(false);
            }
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            throw new FdropClientException($"cannot read {localPath}: {e.Message}", e);
        }
    }

    /// <inheritdoc/>
    public Task<FileEntry> UploadAsync(Stream source, long size, string remotePath, UploadOptions? options = null, CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(source);
        ArgumentNullException.ThrowIfNull(remotePath);
        if (size < 0)
        {
            size = source.CanSeek
                ? source.Length - source.Position
                : throw new ArgumentException("size must be known for a stream that cannot seek: fdrop reserves quota for it up front", nameof(size));
        }

        if (remotePath.EndsWith('/'))
        {
            throw new ArgumentException("give the full remote path, including the file name", nameof(remotePath));
        }

        return new ChunkedUpload(this, remotePath, size, null, options ?? UploadOptions.Default).RunAsync(source, ct);
    }

    /// <inheritdoc/>
    public async Task DownloadAsync(string remotePath, string localPath, CancellationToken ct = default)
    {
        string op = "downloading " + remotePath;
        using var activity = Source.StartActivity(op);
        string dir = Path.GetDirectoryName(Path.GetFullPath(localPath))!;
        // a failure part-way through is an IOException from the copy, so the whole download is tried again
        using var r = await Retrier.RunAsync(op, true, options.RequestTimeout, async token =>
        {
            using var req = await RequestAsync(HttpMethod.Get, "/api/v1/files/content?path=" + Q(remotePath), null, token).ConfigureAwait(false);
            req.Headers.Accept.Clear();
            req.Headers.Accept.ParseAdd("application/octet-stream");
            var resp = await http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, token).ConfigureAwait(false);
            if (resp.IsSuccessStatusCode)
            {
                string tmp = Path.Combine(dir, ".fdrop-download-" + Guid.NewGuid().ToString("N") + ".part");
                try
                {
                    var file = new FileStream(tmp, FileMode.CreateNew, FileAccess.Write, FileShare.None, 1 << 16, FileOptions.Asynchronous);
                    await using (file.ConfigureAwait(false))
                    {
                        await resp.Content.CopyToAsync(file, token).ConfigureAwait(false);
                    }

                    File.Move(tmp, localPath, overwrite: true);
                }
                catch
                {
                    resp.Dispose();
                    throw;
                }
                finally
                {
                    File.Delete(tmp);
                }
            }

            return resp;
        }, ct).ConfigureAwait(false);
        await CheckAsync(op, r, ct).ConfigureAwait(false);
    }

    /// <inheritdoc/>
    public async Task<Stream> OpenDownloadAsync(string remotePath, CancellationToken ct = default)
    {
        string op = "downloading " + remotePath;
        var r = await SendAsync(op, true, async t =>
        {
            var req = await RequestAsync(HttpMethod.Get, "/api/v1/files/content?path=" + Q(remotePath), null, t).ConfigureAwait(false);
            req.Headers.Accept.Clear();
            req.Headers.Accept.ParseAdd("application/octet-stream");
            return req;
        }, ct, HttpCompletionOption.ResponseHeadersRead).ConfigureAwait(false);
        if (!r.IsSuccessStatusCode)
        {
            using (r)
            {
                throw await ProblemAsync(op, r, ct).ConfigureAwait(false);
            }
        }

        return new OwnedStream(await r.Content.ReadAsStreamAsync(ct).ConfigureAwait(false), r);
    }

    /// <inheritdoc/>
    public Task<FileEntry> CreateDirectoryAsync(string path, CancellationToken ct = default)
        => CallAsync("creating " + path, HttpMethod.Post, "/api/v1/files/mkdir", new PathBody(path),
            FdropJson.Default.PathBody, false, FdropJson.Default.FileEntry, ct);

    /// <inheritdoc/>
    public Task<FileEntry> MoveAsync(string source, string destination, CancellationToken ct = default)
        => CallAsync($"moving {source} to {destination}", HttpMethod.Post, "/api/v1/files/move", new MoveBody(source, destination),
            FdropJson.Default.MoveBody, false, FdropJson.Default.FileEntry, ct);

    /// <inheritdoc/>
    public Task DeleteAsync(string path, bool recursive = false, CancellationToken ct = default)
        => CallAsync("deleting " + path, HttpMethod.Delete, "/api/v1/files?path=" + Q(path) + (recursive ? "&recursive=true" : ""), true, ct);

    /// <inheritdoc/>
    public Task<FileEntry> SetExpiryAsync(string path, string expiresIn, CancellationToken ct = default)
        => CallAsync("setting the expiry of " + path, HttpMethod.Patch, "/api/v1/files?path=" + Q(path),
            new ExpiresInBody(expiresIn), FdropJson.Default.ExpiresInBody, true, FdropJson.Default.FileEntry, ct);

    /// <inheritdoc/>
    public Task<FileEntry> SetExpiryAtAsync(string path, DateTimeOffset expiresAt, CancellationToken ct = default)
        => CallAsync("setting the expiry of " + path, HttpMethod.Patch, "/api/v1/files?path=" + Q(path),
            new ExpiresAtBody(expiresAt.UtcDateTime.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'", CultureInfo.InvariantCulture)),
            FdropJson.Default.ExpiresAtBody, true, FdropJson.Default.FileEntry, ct);

    /// <inheritdoc/>
    public Task<FileEntry> ClearExpiryAsync(string path, CancellationToken ct = default)
        => CallAsync("clearing the expiry of " + path, HttpMethod.Patch, "/api/v1/files?path=" + Q(path),
            new ClearExpiryBody(null), FdropJson.Default.ClearExpiryBody, true, FdropJson.Default.FileEntry, ct);

    /// <inheritdoc/>
    public Task<SearchResult> SearchAsync(string query, string? cursor = null, int limit = 20, CancellationToken ct = default)
        => GetAsync("searching", string.Create(CultureInfo.InvariantCulture,
            $"/api/v1/search?q={Q(query)}&limit={limit}{(cursor is null ? "" : "&cursor=" + Q(cursor))}"), FdropJson.Default.SearchResult, ct);

    // ---- folders ----

    /// <inheritdoc/>
    public async Task<IReadOnlyList<Folder>> GetFoldersAsync(CancellationToken ct = default)
        => await GetAsync("listing folders", "/api/v1/folders", FdropJson.Default.ListFolder, ct).ConfigureAwait(false);

    /// <inheritdoc/>
    public Task<Folder> GetFolderAsync(string id, CancellationToken ct = default)
        => GetAsync("reading folder " + id, "/api/v1/folders/" + Q(id), FdropJson.Default.Folder, ct);

    /// <inheritdoc/>
    public Task<Folder> CreateSharedFolderAsync(string name, CancellationToken ct = default)
        => CallAsync("creating folder " + name, HttpMethod.Post, "/api/v1/folders", new CreateFolderBody("shared", name, null, null),
            FdropJson.Default.CreateFolderBody, false, FdropJson.Default.Folder, ct);

    /// <inheritdoc/>
    public Task<Folder> CreateForwardFolderAsync(string name, string destinationId, int? keepDays = null, CancellationToken ct = default)
        => CallAsync("creating folder " + name, HttpMethod.Post, "/api/v1/folders", new CreateFolderBody("forward", name, destinationId, keepDays),
            FdropJson.Default.CreateFolderBody, false, FdropJson.Default.Folder, ct);

    /// <inheritdoc/>
    public Task DeleteFolderAsync(string id, CancellationToken ct = default)
        => CallAsync("deleting folder " + id, HttpMethod.Delete, "/api/v1/folders/" + Q(id), true, ct);

    /// <inheritdoc/>
    public Task<Folder> PutMemberAsync(string folderId, string username, Permission permission, CancellationToken ct = default)
        => CallAsync($"adding {username} to folder {folderId}", HttpMethod.Put, $"/api/v1/folders/{Q(folderId)}/members/{Q(username)}",
            new MemberBody(permission == Permission.Read ? "READ" : "WRITE"), FdropJson.Default.MemberBody, true, FdropJson.Default.Folder, ct);

    /// <inheritdoc/>
    public Task RemoveMemberAsync(string folderId, string username, CancellationToken ct = default)
        => CallAsync($"removing {username} from folder {folderId}", HttpMethod.Delete, $"/api/v1/folders/{Q(folderId)}/members/{Q(username)}", true, ct);

    /// <inheritdoc/>
    public async Task<IReadOnlyList<ForwardJob>> GetForwardJobsAsync(string folderId, CancellationToken ct = default)
        => await GetAsync("reading forward jobs", $"/api/v1/folders/{Q(folderId)}/forward-jobs", FdropJson.Default.ListForwardJob, ct).ConfigureAwait(false);

    /// <inheritdoc/>
    public Task RetryForwardJobAsync(string folderId, string jobId, CancellationToken ct = default)
        => CallAsync("retrying forward job " + jobId, HttpMethod.Post, $"/api/v1/folders/{Q(folderId)}/forward-jobs/{Q(jobId)}/retry", true, ct);

    /// <inheritdoc/>
    public async Task<IReadOnlyList<ForwardDestination>> GetForwardDestinationsAsync(CancellationToken ct = default)
        => await GetAsync("listing forward destinations", "/api/v1/forward-destinations", FdropJson.Default.ListForwardDestination, ct).ConfigureAwait(false);

    /// <summary>Disposes the HTTP client, unless the application gave it.</summary>
    public void Dispose()
    {
        if (ownsHttp)
        {
            http.Dispose();
        }
    }

    /// <summary>A response body that disposes its response with it.</summary>
    private sealed class OwnedStream(Stream inner, HttpResponseMessage response) : Stream
    {
        public override bool CanRead => true;

        public override bool CanSeek => false;

        public override bool CanWrite => false;

        public override long Length => throw new NotSupportedException();

        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }

        public override int Read(byte[] buffer, int offset, int count) => inner.Read(buffer, offset, count);

        public override int Read(Span<byte> buffer) => inner.Read(buffer);

        public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
            => inner.ReadAsync(buffer, cancellationToken);

        public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken cancellationToken)
            => inner.ReadAsync(buffer, offset, count, cancellationToken);

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
                response.Dispose();
            }

            base.Dispose(disposing);
        }
    }
}
