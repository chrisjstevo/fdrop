namespace Fdrop.Client;

/// <summary>Every failure the client reports.</summary>
public class FdropClientException : Exception
{
    /// <summary>Creates an exception.</summary>
    public FdropClientException()
    {
    }

    /// <summary>Creates an exception.</summary>
    public FdropClientException(string message)
        : base(message)
    {
    }

    /// <summary>Creates an exception with its cause.</summary>
    public FdropClientException(string message, Exception innerException)
        : base(message, innerException)
    {
    }
}

/// <summary>
/// fdrop answered with an error retrying will not fix: refused, not found, already exists, quota full, and so on.
/// </summary>
public class FdropApiException : FdropClientException
{
    /// <summary>Creates an exception from fdrop's problem response.</summary>
    public FdropApiException(string operation, int statusCode, string? problemType, string? title, string? detail)
        : base(Describe(operation, statusCode, title, detail))
    {
        StatusCode = statusCode;
        ProblemType = problemType;
        Title = title;
        Detail = detail;
    }

    /// <summary>Creates an exception.</summary>
    public FdropApiException()
    {
    }

    /// <summary>Creates an exception.</summary>
    public FdropApiException(string message)
        : base(message)
    {
    }

    /// <summary>Creates an exception with its cause.</summary>
    public FdropApiException(string message, Exception innerException)
        : base(message, innerException)
    {
    }

    /// <summary>The HTTP status.</summary>
    public int StatusCode { get; }

    /// <summary>fdrop's RFC 9457 problem type, such as <c>two-factor-required</c>, or null.</summary>
    public string? ProblemType { get; }

    /// <summary>The problem's title, or null.</summary>
    public string? Title { get; }

    /// <summary>The problem's detail, or null.</summary>
    public string? Detail { get; }

    private static string Describe(string operation, int status, string? title, string? detail)
        => $"{operation}: HTTP {status}{(title is null ? "" : " " + title)}{(string.IsNullOrWhiteSpace(detail) ? "" : ": " + detail)}";
}

/// <summary>
/// A request kept failing with errors worth retrying (network errors, timeouts, 429, 5xx) and the client gave up after
/// <see cref="Attempts"/> tries; <see cref="Exception.InnerException"/> is the last failure. For an upload, the client
/// then tries once to discard the unfinished session; nothing partial is ever visible as a file.
/// </summary>
public class FdropRetriesExhaustedException : FdropClientException
{
    /// <summary>Creates an exception for an operation that gave up.</summary>
    public FdropRetriesExhaustedException(string operation, int attempts, Exception lastFailure)
        : base($"{operation}: gave up after {attempts} attempt{(attempts == 1 ? "" : "s")}: {lastFailure.Message}", lastFailure)
    {
        Attempts = attempts;
    }

    /// <summary>Creates an exception.</summary>
    public FdropRetriesExhaustedException()
    {
    }

    /// <summary>Creates an exception.</summary>
    public FdropRetriesExhaustedException(string message)
        : base(message)
    {
    }

    /// <summary>Creates an exception with its cause.</summary>
    public FdropRetriesExhaustedException(string message, Exception innerException)
        : base(message, innerException)
    {
    }

    /// <summary>How many times the request was tried.</summary>
    public int Attempts { get; }
}
