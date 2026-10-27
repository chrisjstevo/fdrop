using System.Text.Json.Serialization;

namespace Fdrop.Client;

/// <summary>A file or directory in fdrop's virtual tree. Expiry is only set for files.</summary>
/// <param name="Type"><c>FILE</c> or <c>DIRECTORY</c>.</param>
/// <param name="ExpirySource">Where the expiry came from: <c>upload</c>, <c>folderDefault</c>, <c>homeDefault</c>, …</param>
public sealed record FileEntry(
    string Name,
    string Path,
    string Type,
    long Size,
    DateTimeOffset? Modified,
    bool Writable,
    string? FolderId,
    DateTimeOffset? ExpiresAt,
    string? ExpiresIn,
    string? ExpirySource)
{
    /// <summary>Whether this is a directory.</summary>
    [JsonIgnore]
    public bool IsDirectory => Type == "DIRECTORY";
}

/// <summary>A directory's contents, and what an upload into it gets for expiry (null for files).</summary>
public sealed record Listing(string Path, FileEntry Self, IReadOnlyList<FileEntry> Entries, FolderExpiry? FolderExpiry);

/// <summary>A directory's expiry rules; <c>Default</c> and <c>Max</c> are canonical expiries or null.</summary>
public sealed record FolderExpiry(
    bool Enabled,
    bool Allowed,
    [property: JsonPropertyName("default")] string? Default,
    string? Max,
    string? ServerMax);

/// <summary>The signed-in account: who it is, its quota, and the server's upload settings.</summary>
/// <param name="Kind"><c>user</c> or <c>application</c>.</param>
public sealed record Account(
    string Sub,
    string Username,
    string? Email,
    string Kind,
    IReadOnlyDictionary<string, bool> Roles,
    long QuotaBytes,
    long UsedBytes,
    long ReservedBytes,
    ServerSettings? Settings)
{
    /// <summary>Whether this is an application account.</summary>
    [JsonIgnore]
    public bool IsApplication => Kind == "application";
}

/// <summary>Upload limits the server announces.</summary>
public sealed record ServerSettings(long SimpleUploadMax, long MaxFileSize, int MaxParallelChunks, long ClientHashMax);

/// <summary>A folder member's access.</summary>
[System.Diagnostics.CodeAnalysis.SuppressMessage("Naming", "CA1711", Justification = "fdrop's API calls it the member's permission")]
[JsonConverter(typeof(JsonStringEnumConverter<Permission>))]
public enum Permission
{
    /// <summary>May list and download.</summary>
    Read,

    /// <summary>May also upload, move and delete.</summary>
    Write,
}

/// <summary>A shared or forward folder as the caller sees it.</summary>
/// <param name="Type"><c>SHARED</c> or <c>FORWARD</c>.</param>
/// <param name="Role">The caller's own role: <c>OWNER</c>, <c>WRITE</c> or <c>READ</c>.</param>
public sealed record Folder(
    string Id,
    string Type,
    string Name,
    string Path,
    string Owner,
    string Role,
    string? DestinationId,
    string? DestinationName,
    int? KeepDays,
    bool Paused,
    IReadOnlyList<FolderMember> Members,
    [property: JsonConverter(typeof(EpochMillisConverter))] DateTimeOffset CreatedAt,
    string? DefaultExpiry,
    string? MaxExpiry);

/// <summary>A folder member; <c>Kind</c> is <c>STANDARD</c> or <c>APPLICATION</c>.</summary>
public sealed record FolderMember(string Username, Permission Permission, string Kind);

/// <summary>One file being sent on from a forward folder.</summary>
/// <param name="State">QUEUED, SENDING, RETRY_WAIT, SENT, FAILED or PAUSED.</param>
public sealed record ForwardJob(
    string Id,
    string FileName,
    long Size,
    string State,
    int Attempts,
    string? LastError,
    DateTimeOffset? CreatedAt,
    DateTimeOffset? SentAt,
    DateTimeOffset? NextAttemptAt);

/// <summary>A destination the caller may choose for a forward folder.</summary>
public sealed record ForwardDestination(string Id, string Name, string? Description);

/// <summary>One row of the caller's upload history.</summary>
public sealed record UploadRecord(
    string Id,
    string FileName,
    long Size,
    string WentTo,
    string? FolderId,
    string? FolderName,
    string? DestinationName,
    string? PathAtUpload,
    string? CurrentPath,
    DateTimeOffset? CompletedAt,
    DateTimeOffset? ExpiresAt,
    string Via,
    string? ClientIp,
    string Status,
    string? JobId);

/// <summary>A page of upload history; pass <c>NextCursor</c> back for the next page (null on the last).</summary>
public sealed record UploadHistory(long TotalCount, long TotalBytes, string? NextCursor, IReadOnlyList<UploadRecord> Rows);

/// <summary>Search results; names and snippets are runs of text with the matches marked, never HTML.</summary>
public sealed record SearchResult(long Total, string? NextCursor, IReadOnlyList<SearchHit> Results);

/// <summary>A run of text in a search result.</summary>
public sealed record TextSegment(string Text, bool Match);

/// <summary>One search result.</summary>
/// <param name="Contents">Whether the file's text was searched: ok, unsupported, too_large, protected, failed, pending.</param>
public sealed record SearchHit(
    string Path,
    IReadOnlyList<TextSegment> Name,
    string Location,
    string Type,
    bool Folder,
    long Size,
    DateTimeOffset? Modified,
    DateTimeOffset? ExpiresAt,
    IReadOnlyList<IReadOnlyList<TextSegment>> Snippets,
    string? Contents);

/// <summary>Bytes of an upload the server has acknowledged.</summary>
public readonly record struct UploadProgress(long Acknowledged, long Total);

/// <summary>How to upload.</summary>
/// <param name="Overwrite">Replace an existing file.</param>
/// <param name="ExpiresIn">The file's expiry in fdrop's syntax (<c>"30 days"</c>, <c>"never"</c>); null for the folder's default.</param>
/// <param name="Progress">Told the acknowledged bytes as chunks arrive; called from the upload's tasks.</param>
public sealed record UploadOptions(bool Overwrite = false, string? ExpiresIn = null, IProgress<UploadProgress>? Progress = null)
{
    /// <summary>No overwrite, the folder's expiry, no progress.</summary>
    public static UploadOptions Default { get; } = new();
}
