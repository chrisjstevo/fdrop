using System.Net;
using System.Net.Http.Headers;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Text.Json;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Time.Testing;

namespace Fdrop.Client.Tests;

/// <summary>The retry rules, from the table shared with the Java client, and the retry loop; nothing sleeps.</summary>
[Trait("Category", "Unit")]
public class RetryTests
{
    private static CancellationToken Ct => TestContext.Current.CancellationToken;

    public static TheoryData<string, bool, string> SharedCases()
    {
        using var doc = JsonDocument.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "client-retry-cases.json")));
        var data = new TheoryData<string, bool, string>();
        foreach (var c in doc.RootElement.GetProperty("cases").EnumerateArray())
        {
            data.Add(c.GetProperty("failure").GetString()!, c.GetProperty("safeToRepeat").GetBoolean(), c.GetProperty("expect").GetString()!);
        }

        return data;
    }

    /// <summary>The exception <see cref="HttpClient"/> throws for each kind of failure in the table.</summary>
    private static Exception Thrown(string failure) => failure switch
    {
        "connect-failed" => new HttpRequestException(HttpRequestError.ConnectionError, "Connection refused", new SocketException((int)SocketError.ConnectionRefused)),
        "dns-failed" => new HttpRequestException(HttpRequestError.NameResolutionError, "Name or service not known", new SocketException((int)SocketError.HostNotFound)),
        "connect-timeout" => new HttpRequestException(HttpRequestError.ConnectionError, "connect timed out", new TaskCanceledException("timeout", new TimeoutException())),
        "connection-dropped" => new HttpRequestException(HttpRequestError.ResponseEnded, "The response ended prematurely", new IOException("Connection reset by peer")),
        "request-timeout" => new TaskCanceledException("The operation was canceled."),
        "tls-refused" => new HttpRequestException(HttpRequestError.SecureConnectionError, "SSL connection could not be established",
            new AuthenticationException("The remote certificate is invalid according to the validation procedure")),
        "tls-connection-lost" => new HttpRequestException(HttpRequestError.SecureConnectionError, "SSL connection could not be established",
            new AuthenticationException("Authentication failed", new IOException("Received an unexpected EOF or 0 bytes from the transport stream."))),
        _ => throw new ArgumentException("unknown failure " + failure),
    };

    [Theory]
    [MemberData(nameof(SharedCases))]
    public void SharedRetryCase(string failure, bool safeToRepeat, string expect)
    {
        Verdict verdict = failure.StartsWith("status:", StringComparison.Ordinal)
            ? Retrier.Classify(Failure.Status, int.Parse(failure[7..], System.Globalization.CultureInfo.InvariantCulture), safeToRepeat)
            : Retrier.Classify(Retrier.Of(Thrown(failure), failure == "request-timeout"), 0, safeToRepeat);
        Assert.Equal(expect, verdict.ToString().ToLowerInvariant());
    }

    private static Retrier NewRetrier(TimeProvider time, int attempts = 4)
        => new(attempts, TimeSpan.FromSeconds(1), TimeSpan.FromSeconds(8), time, NullLogger.Instance);

    private static HttpResponseMessage Status(int status) => new((HttpStatusCode)status);

    /// <summary>Runs a call, moving the fake clock on while it waits.</summary>
    private static async Task<T> Advancing<T>(FakeTimeProvider time, Task<T> task)
    {
        while (!task.IsCompleted)
        {
            time.Advance(TimeSpan.FromMilliseconds(100));
            await Task.Delay(1, Ct);
        }

        return await task;
    }

    [Fact]
    public async Task RetriesUntilItWorks()
    {
        var time = new FakeTimeProvider();
        int tries = 0;
        var start = time.GetUtcNow();
        using var r = await Advancing(time, NewRetrier(time).RunAsync("op", true, TimeSpan.FromHours(1),
            _ => Task.FromResult(++tries < 3 ? Status(503) : Status(200)), Ct));
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        Assert.Equal(3, tries);
        // jittered waits: 0.5–1 s, then 1–2 s
        var waited = time.GetUtcNow() - start;
        Assert.InRange(waited, TimeSpan.FromSeconds(1.4), TimeSpan.FromSeconds(3.2));
    }

    [Fact]
    public async Task GivesUpAfterMaxAttemptsWithTheLastFailure()
    {
        var time = new FakeTimeProvider();
        int tries = 0;
        var e = await Assert.ThrowsAsync<FdropRetriesExhaustedException>(() => Advancing(time,
            NewRetrier(time).RunAsync("sending chunk 3", true, TimeSpan.FromHours(1), _ =>
            {
                tries++;
                throw new HttpRequestException(HttpRequestError.ResponseEnded, "Connection reset");
            }, Ct)));
        Assert.Equal(4, tries);
        Assert.Equal(4, e.Attempts);
        Assert.Contains("sending chunk 3", e.Message, StringComparison.Ordinal);
        Assert.Contains("gave up after 4 attempts", e.Message, StringComparison.Ordinal);
        Assert.IsType<HttpRequestException>(e.InnerException);
    }

    [Fact]
    public async Task UnsafeRequestsAreNotRepeatedAfterTheyMayHaveArrived()
    {
        var time = new FakeTimeProvider();
        int tries = 0;
        var e = await Assert.ThrowsAsync<FdropClientException>(() => NewRetrier(time).RunAsync("moving", false, TimeSpan.FromHours(1), _ =>
        {
            tries++;
            throw new HttpRequestException(HttpRequestError.ResponseEnded, "Connection reset");
        }, Ct));
        Assert.Equal(1, tries);
        Assert.Contains("may have taken effect", e.Message, StringComparison.Ordinal);
    }

    [Fact]
    public async Task ACertificateProblemFailsAtOnce()
    {
        var time = new FakeTimeProvider();
        int tries = 0;
        var e = await Assert.ThrowsAsync<FdropClientException>(() => NewRetrier(time).RunAsync("listing", true, TimeSpan.FromHours(1), _ =>
        {
            tries++;
            throw Thrown("tls-refused");
        }, Ct));
        Assert.Equal(1, tries);
        Assert.Contains("TLS handshake failed", e.Message, StringComparison.Ordinal);
    }

    [Fact]
    public async Task HonoursRetryAfterUpToTheMaximum()
    {
        var time = new FakeTimeProvider();
        int tries = 0;
        var start = time.GetUtcNow();
        using var r = await Advancing(time, NewRetrier(time).RunAsync("op", true, TimeSpan.FromHours(1), _ =>
        {
            if (++tries > 1)
            {
                return Task.FromResult(Status(200));
            }

            var limited = Status(429);
            limited.Headers.RetryAfter = new RetryConditionHeaderValue(TimeSpan.FromSeconds(30));
            return Task.FromResult(limited);
        }, Ct));
        // 30 s asked for, capped at 8 s, then jittered to 4–8 s
        Assert.InRange(time.GetUtcNow() - start, TimeSpan.FromSeconds(4), TimeSpan.FromSeconds(8.2));
    }

    [Fact]
    public async Task ARequestTimeoutIsRetriedForSafeRequests()
    {
        var time = new FakeTimeProvider();
        int tries = 0;
        using var r = await Advancing(time, NewRetrier(time).RunAsync("op", true, TimeSpan.FromSeconds(2), async t =>
        {
            if (++tries == 1)
            {
                await Task.Delay(Timeout.InfiniteTimeSpan, time, t);
            }

            return Status(200);
        }, Ct));
        Assert.Equal(2, tries);
    }

    [Fact]
    public async Task TheCallersCancellationIsNotRetried()
    {
        using var cts = new CancellationTokenSource();
        int tries = 0;
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => NewRetrier(TimeProvider.System).RunAsync("op", true, TimeSpan.FromHours(1), async t =>
        {
            tries++;
            await cts.CancelAsync();
            t.ThrowIfCancellationRequested();
            return Status(200);
        }, cts.Token));
        Assert.Equal(1, tries);
    }

    [Fact]
    public void JitterIsBetweenHalfAndAllOfTheStep()
    {
        for (int i = 0; i < 200; i++)
        {
            Assert.InRange(Retrier.Jitter(TimeSpan.FromSeconds(4)), TimeSpan.FromSeconds(2), TimeSpan.FromSeconds(4));
        }

        Assert.Equal(TimeSpan.Zero, Retrier.Jitter(TimeSpan.Zero));
    }
}
