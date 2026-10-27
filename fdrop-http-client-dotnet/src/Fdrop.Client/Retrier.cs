using System.Net;
using System.Net.Http.Headers;
using System.Net.Sockets;
using System.Security.Authentication;
using Microsoft.Extensions.Logging;

namespace Fdrop.Client;

/// <summary>What went wrong with one try of a request, in the terms of the shared retry table.</summary>
internal enum Failure
{
    /// <summary>The connection could not be made: refused, unreachable.</summary>
    ConnectFailed,

    /// <summary>The host name did not resolve.</summary>
    DnsFailed,

    /// <summary>Connecting took longer than <c>ConnectTimeout</c>.</summary>
    ConnectTimeout,

    /// <summary>The connection broke after the request may have reached the server.</summary>
    ConnectionDropped,

    /// <summary>The request took longer than <c>RequestTimeout</c>.</summary>
    RequestTimeout,

    /// <summary>The TLS handshake failed over a certificate either side refused.</summary>
    TlsRefused,

    /// <summary>The connection dropped during the TLS handshake.</summary>
    TlsConnectionLost,

    /// <summary>The server answered with a status (see the status code).</summary>
    Status,
}

/// <summary>What to do about a failure.</summary>
internal enum Verdict
{
    /// <summary>Try again, if tries are left.</summary>
    Retry,

    /// <summary>Stop: a <see cref="FdropClientException"/>.</summary>
    Error,

    /// <summary>Stop: hand the response to the caller, who turns it into a result or an <see cref="FdropApiException"/>.</summary>
    Api,
}

/// <summary>
/// Runs a request up to <c>MaxAttempts</c> times with exponential backoff and jitter, or the server's
/// <c>Retry-After</c>. Failures where the server did not act (connecting, 429, 503) are always retried; failures after
/// which the request may have taken effect only for requests that are safe to repeat.
/// </summary>
internal sealed partial class Retrier(int maxAttempts, TimeSpan initial, TimeSpan max, TimeProvider time, ILogger logger)
{
    public int MaxAttempts => maxAttempts;

    /// <summary>The retry rules; the shared table in <c>client-retry-cases.json</c> tests exactly this.</summary>
    public static Verdict Classify(Failure failure, int status, bool safeToRepeat) => failure switch
    {
        Failure.ConnectFailed or Failure.DnsFailed or Failure.ConnectTimeout => Verdict.Retry,
        Failure.ConnectionDropped or Failure.RequestTimeout or Failure.TlsConnectionLost => safeToRepeat ? Verdict.Retry : Verdict.Error,
        Failure.TlsRefused => Verdict.Error,
        _ => status is 429 or 503 || (safeToRepeat && status is 500 or 502 or 504) ? Verdict.Retry : Verdict.Api,
    };

    /// <summary>Which <see cref="Failure"/> an exception from <see cref="HttpClient"/> is.</summary>
    public static Failure Of(Exception e, bool requestTimedOut)
    {
        if (requestTimedOut)
        {
            return Failure.RequestTimeout;
        }

        if (Find<AuthenticationException>(e) is not null)
        {
            return Find<IOException>(e.InnerException) is not null || Find<SocketException>(e) is not null
                ? Failure.TlsConnectionLost
                : Failure.TlsRefused;
        }

        if (e is HttpRequestException h)
        {
            switch (h.HttpRequestError)
            {
                case HttpRequestError.NameResolutionError:
                    return Failure.DnsFailed;
                case HttpRequestError.ConnectionError:
                    return Find<SocketException>(e) is { } s && s.SocketErrorCode == SocketError.TimedOut
                        ? Failure.ConnectTimeout
                        : Failure.ConnectFailed;
                case HttpRequestError.SecureConnectionError:
                    return Failure.TlsConnectionLost;
                default:
                    break;
            }
        }

        // SocketsHttpHandler's ConnectTimeout surfaces as a cancellation caused by a TimeoutException
        if (e is OperationCanceledException && Find<TimeoutException>(e) is not null)
        {
            return Failure.ConnectTimeout;
        }

        return Failure.ConnectionDropped;
    }

    private static T? Find<T>(Exception? e)
        where T : Exception
    {
        for (var x = e; x is not null; x = x.InnerException)
        {
            if (x is T t)
            {
                return t;
            }
        }

        return null;
    }

    /// <summary>
    /// The first response not worth retrying. <paramref name="attempt"/> gets a token that also ends when the request
    /// times out.
    /// </summary>
    public async Task<HttpResponseMessage> RunAsync(string operation, bool safeToRepeat, TimeSpan requestTimeout,
        Func<CancellationToken, Task<HttpResponseMessage>> attempt, CancellationToken ct)
    {
        var step = initial;
        Exception? last = null;
        for (int i = 1; i <= maxAttempts; i++)
        {
            var wait = step;
            using var timeout = new CancellationTokenSource(requestTimeout, time);
            using var linked = CancellationTokenSource.CreateLinkedTokenSource(ct, timeout.Token);
            try
            {
                var r = await attempt(linked.Token).ConfigureAwait(false);
                int status = (int)r.StatusCode;
                if (Classify(Failure.Status, status, safeToRepeat) != Verdict.Retry)
                {
                    return r;
                }

                wait = RetryAfter(r.Headers.RetryAfter) ?? step;
                r.Dispose();
                last = new HttpRequestException($"HTTP {status}", null, (HttpStatusCode)status);
            }
            catch (Exception e) when (e is HttpRequestException or IOException or OperationCanceledException or AuthenticationException)
            {
                ct.ThrowIfCancellationRequested();
                var failure = Of(e, timeout.IsCancellationRequested);
                switch (Classify(failure, 0, safeToRepeat))
                {
                    case Verdict.Retry:
                        last = e;
                        break;
                    case Verdict.Error when failure == Failure.TlsRefused:
                        throw new FdropClientException($"{operation}: the TLS handshake failed (is the server's certificate trusted, and the client certificate accepted?): {Describe(e)}", e);
                    default:
                        throw new FdropClientException($"{operation}: the connection failed and the request may have taken effect, so it is not repeated: {Describe(e)}", e);
                }
            }

            if (i == maxAttempts)
            {
                break;
            }

            var pause = Jitter(wait);
            LogRetry(logger, operation, i, maxAttempts, pause.TotalMilliseconds, Describe(last!));
            await Task.Delay(pause, time, ct).ConfigureAwait(false);
            step = step * 2 > max ? max : step * 2;
        }

        throw new FdropRetriesExhaustedException(operation, maxAttempts, last!);
    }

    /// <summary>Between half and all of the step, so many clients failing together do not retry together.</summary>
    internal static TimeSpan Jitter(TimeSpan d)
    {
        long ms = (long)d.TotalMilliseconds;
        long half = ms / 2;
#pragma warning disable CA5394 // jitter, not security
        return TimeSpan.FromMilliseconds(half + (ms - half <= 0 ? 0 : Random.Shared.NextInt64(ms - half)));
#pragma warning restore CA5394
    }

    /// <summary><c>Retry-After</c> in seconds or as a date, capped at the maximum backoff.</summary>
    private TimeSpan? RetryAfter(RetryConditionHeaderValue? h)
    {
        TimeSpan? d = h?.Delta ?? (h?.Date is { } date ? date - time.GetUtcNow() : null);
        if (d is null)
        {
            return null;
        }

        return d < TimeSpan.Zero ? TimeSpan.Zero : d > max ? max : d;
    }

    internal static string Describe(Exception e)
        => string.IsNullOrWhiteSpace(e.Message) ? e.GetType().Name : $"{e.GetType().Name}: {e.Message}";

    [LoggerMessage(Level = LogLevel.Warning, Message = "{Operation}: attempt {Attempt} of {MaxAttempts} failed ({Failure}); trying again in {WaitMs:0} ms")]
    private static partial void LogRetry(ILogger logger, string operation, int attempt, int maxAttempts, double waitMs, string failure);
}
