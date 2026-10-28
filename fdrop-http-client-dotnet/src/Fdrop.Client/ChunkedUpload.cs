using System.Buffers;
using System.Globalization;
using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using Microsoft.Extensions.Logging;

namespace Fdrop.Client;

/// <summary>
/// One upload through fdrop's resumable protocol (see "Resumable chunked uploads" in spec/resumable-uploads.md), hidden behind
/// <see cref="FdropClient.UploadAsync(Stream, long, string, UploadOptions?, CancellationToken)"/>: create a session,
/// read the source one chunk at a time in the server's chunk size, send up to <c>ParallelChunks</c> at once (each
/// retried on its own), then complete with the whole file's SHA-256. Any failure or cancellation discards the session.
/// </summary>
internal sealed partial class ChunkedUpload(FdropClient client, string target, long size, DateTime? lastModified, UploadOptions options)
{
    /// <summary>How long to wait for the server to assemble a large file after <c>complete</c> answers 202.</summary>
    private static readonly TimeSpan AssemblyLimit = TimeSpan.FromHours(1);

    /// <summary>A 409 on a chunk means another try of it is running, or it differs; checked this many times.</summary>
    private const int MaxConflicts = 3;

    private long acknowledged;

    private string Op(string what) => $"{what} (uploading {target})";

    public async Task<FileEntry> RunAsync(Stream source, CancellationToken ct)
    {
        using var activity = FdropClient.Source.StartActivity("upload");
        activity?.SetTag("fdrop.path", target);
        activity?.SetTag("fdrop.size", size);
        LogStart(client.Logger, target, size);
        var session = await CreateAsync(ct).ConfigureAwait(false);
        try
        {
            string sha256 = await SendAllAsync(session, source, ct).ConfigureAwait(false);
            var file = await CompleteAsync(session, sha256, ct).ConfigureAwait(false);
            options.Progress?.Report(new UploadProgress(size, size));
            LogDone(client.Logger, target, size, session.ChunkCount);
            return file;
        }
        catch
        {
            await AbortAsync(session).ConfigureAwait(false);
            throw;
        }
    }

    private async Task<UploadSession> CreateAsync(CancellationToken ct)
    {
        string op = "starting the upload of " + target;
        long? modified = lastModified is { } m ? new DateTimeOffset(m, TimeSpan.Zero).ToUnixTimeMilliseconds() : null;
        var body = new CreateUploadBody(target, size, modified, options.Overwrite, options.ExpiresIn);
        // safe to repeat: a session created by a try whose answer was lost is never used and expires on the server
        var r = await client.SendAsync(op, true, t => client.RequestAsync(HttpMethod.Post, "/api/v1/uploads",
            FdropClient.Json(body, FdropJson.Default.CreateUploadBody), t), ct).ConfigureAwait(false);
        return await FdropClient.ExpectAsync(op, r, FdropJson.Default.UploadSession, ct).ConfigureAwait(false);
    }

    /// <summary>Reads and sends every chunk; returns the whole file's SHA-256, computed on the way.</summary>
    private async Task<string> SendAllAsync(UploadSession s, Stream source, CancellationToken ct)
    {
        using var whole = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        using var slots = new SemaphoreSlim(client.ParallelChunks);
        using var stop = CancellationTokenSource.CreateLinkedTokenSource(ct);
        var sending = new List<Task>();
        Exception? failure = null;
        try
        {
            for (int i = 0; i < s.ChunkCount; i++)
            {
                await slots.WaitAsync(stop.Token).ConfigureAwait(false);
                long offset = i * s.ChunkSize;
                int length = (int)Math.Min(s.ChunkSize, size - offset);
                byte[] buffer = ArrayPool<byte>.Shared.Rent(length);
                try
                {
                    await ReadAsync(source, buffer.AsMemory(0, length), offset, stop.Token).ConfigureAwait(false);
                }
                catch
                {
                    ArrayPool<byte>.Shared.Return(buffer);
                    slots.Release();
                    throw;
                }

                whole.AppendData(buffer, 0, length);
                int index = i;
                sending.Add(Task.Run(async () =>
                {
                    try
                    {
                        await SendChunkAsync(s, index, buffer, length, stop.Token).ConfigureAwait(false);
                        long done = Interlocked.Add(ref acknowledged, length);
                        options.Progress?.Report(new UploadProgress(done, size));
                    }
                    catch (Exception e)
                    {
                        Interlocked.CompareExchange(ref failure, e, null);
                        await stop.CancelAsync().ConfigureAwait(false);
                    }
                    finally
                    {
                        ArrayPool<byte>.Shared.Return(buffer);
                        slots.Release();
                    }
                }, CancellationToken.None));
            }
        }
        catch (OperationCanceledException) when (failure is not null && !ct.IsCancellationRequested)
        {
            // a chunk failed and stopped the reading: its failure is reported below
        }
        finally
        {
            await Task.WhenAll(sending).ConfigureAwait(false);
        }

        ct.ThrowIfCancellationRequested();
        if (failure is not null)
        {
            System.Runtime.ExceptionServices.ExceptionDispatchInfo.Throw(failure);
        }

        return Convert.ToHexStringLower(whole.GetHashAndReset());
    }

    private async Task ReadAsync(Stream source, Memory<byte> into, long offset, CancellationToken ct)
    {
        int read = await source.ReadAtLeastAsync(into, into.Length, throwOnEndOfStream: false, ct).ConfigureAwait(false);
        if (read < into.Length)
        {
            throw new FdropClientException($"{Op("reading the source")}: it ended after {offset + read} of the {size} bytes given as its size");
        }
    }

    private async Task SendChunkAsync(UploadSession s, int index, byte[] buffer, int length, CancellationToken ct)
    {
        using var activity = FdropClient.Source.StartActivity("upload chunk");
        activity?.SetTag("fdrop.chunk", index);
        string op = Op($"sending chunk {index + 1} of {s.ChunkCount}");
        string sha = Convert.ToHexStringLower(SHA256.HashData(buffer.AsSpan(0, length)));
        string path = string.Create(CultureInfo.InvariantCulture, $"/api/v1/uploads/{FdropClient.Q(s.Id)}/chunks/{index}");
        for (int conflicts = 0; ; conflicts++)
        {
            using var r = await client.SendAsync(op, true, async t =>
            {
                var content = new ByteArrayContent(buffer, 0, length);
                content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
                var req = await client.RequestAsync(HttpMethod.Put, path, content, t).ConfigureAwait(false);
                req.Headers.Add("X-Chunk-SHA256", sha);
                return req;
            }, ct).ConfigureAwait(false);
            switch (r.StatusCode)
            {
                case HttpStatusCode.NoContent or HttpStatusCode.OK:
                    return;
                case HttpStatusCode.NotFound:
                    throw new FdropClientException($"{op}: the server no longer has the upload session (it expired or was discarded)");
                case HttpStatusCode.Conflict when conflicts < MaxConflicts:
                    // another try of this chunk may have got through after all: if the server has it, it is done
                    if (await ReceivedAsync(s, index, ct).ConfigureAwait(false))
                    {
                        return;
                    }

                    continue;
                default:
                    throw await FdropClient.ProblemAsync(op, r, ct).ConfigureAwait(false);
            }
        }
    }

    private async Task<bool> ReceivedAsync(UploadSession s, int index, CancellationToken ct)
    {
        var st = await StatusAsync(s, ct).ConfigureAwait(false);
        return st.Received?.Any(range => index >= range[0] && index <= range[1]) == true;
    }

    private async Task<UploadSession> StatusAsync(UploadSession s, CancellationToken ct)
    {
        string op = Op("checking the upload");
        var r = await client.SendAsync(op, true, t => client.RequestAsync(HttpMethod.Get, "/api/v1/uploads/" + FdropClient.Q(s.Id), null, t), ct)
            .ConfigureAwait(false);
        return await FdropClient.ExpectAsync(op, r, FdropJson.Default.UploadSession, ct).ConfigureAwait(false);
    }

    /// <summary>Asks the server to assemble the file, waiting while it does; complete is idempotent on the server.</summary>
    private async Task<FileEntry> CompleteAsync(UploadSession s, string sha256, CancellationToken ct)
    {
        string op = Op("completing the upload");
        var r = await client.SendAsync(op, true, t => client.RequestAsync(HttpMethod.Post, $"/api/v1/uploads/{FdropClient.Q(s.Id)}/complete",
            FdropClient.Json(new CompleteUploadBody(sha256), FdropJson.Default.CompleteUploadBody), t), ct).ConfigureAwait(false);
        var done = await FdropClient.ExpectAsync(op, r, FdropJson.Default.UploadSession, ct).ConfigureAwait(false);
        var deadline = client.Time.GetUtcNow() + AssemblyLimit;
        var wait = TimeSpan.FromMilliseconds(250);
        while (done.State != "COMPLETED")
        {
            if (done.State == "FAILED")
            {
                throw new FdropClientException($"{op}: the server could not assemble the file: {done.Error ?? "unknown error"}");
            }

            if (client.Time.GetUtcNow() > deadline)
            {
                throw new FdropClientException($"{op}: the server was still assembling the file after {AssemblyLimit.TotalMinutes:0} minutes");
            }

            await Task.Delay(wait, client.Time, ct).ConfigureAwait(false);
            wait = wait * 2 > TimeSpan.FromSeconds(5) ? TimeSpan.FromSeconds(5) : wait * 2;
            done = await StatusAsync(s, ct).ConfigureAwait(false);
        }

        return done.File ?? await StatAsync(ct).ConfigureAwait(false);
    }

    /// <summary>The stored file, when completion was reported by the status call (which carries no file entry).</summary>
    private async Task<FileEntry> StatAsync(CancellationToken ct)
    {
        int slash = target.LastIndexOf('/');
        string parent = slash <= 0 ? "/" : target[..slash];
        var listing = await client.ListAsync(parent, ct).ConfigureAwait(false);
        return listing.Entries.FirstOrDefault(e => e.Path == target)
            ?? throw new FdropClientException($"{Op("reading the uploaded file")}: it is not in {parent}");
    }

    /// <summary>
    /// One try to discard the session, with a short timeout of its own and not the caller's token (which may be the
    /// reason for the abort); if it fails, the server discards the session when it expires.
    /// </summary>
    private async Task AbortAsync(UploadSession s)
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10), client.Time);
        try
        {
            using var r = await client.SendAsync("discarding the upload of " + target, true,
                    t => client.RequestAsync(HttpMethod.Delete, "/api/v1/uploads/" + FdropClient.Q(s.Id), null, t),
                    timeout.Token)
                .ConfigureAwait(false);
        }
#pragma warning disable CA1031 // best effort: the original failure is what the caller must see
        catch (Exception)
#pragma warning restore CA1031
        {
            // the server's janitor removes it
        }
    }

    [LoggerMessage(Level = LogLevel.Information, Message = "uploading {Path} ({Size} bytes)")]
    private static partial void LogStart(ILogger logger, string path, long size);

    [LoggerMessage(Level = LogLevel.Information, Message = "uploaded {Path} ({Size} bytes in {Chunks} chunks)")]
    private static partial void LogDone(ILogger logger, string path, long size, int chunks);
}
