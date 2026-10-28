namespace Fdrop.Client;

/// <summary>
/// fdrop's REST API (<c>/api/v1</c>) for standard users and application accounts; admin endpoints are not included.
/// </summary>
/// <remarks>
/// <para>
/// <see cref="UploadAsync(string, string, UploadOptions?, CancellationToken)"/> looks like one request but uses fdrop's
/// resumable upload protocol underneath: it creates an upload session, sends the file in the server's chunk size
/// (several chunks at a time, each retried on its own), and completes the session with the whole file's SHA-256.
/// </para>
/// <para>
/// Failures retrying can fix (network errors, timeouts, 429, 5xx) are retried with exponential backoff up to
/// <see cref="FdropClientOptions.MaxAttempts"/> times per request (per chunk, for uploads); then the call throws
/// <see cref="FdropRetriesExhaustedException"/>. Other errors throw <see cref="FdropApiException"/> at once. Requests that
/// are not safe to repeat (create directory, move, create folder) are retried only when they never reached the server.
/// </para>
/// <para>Instances are thread safe; create one per server and account and share it.</para>
/// </remarks>
public interface IFdropClient : IDisposable
{
    /// <summary>The signed-in account.</summary>
    Task<Account> GetMeAsync(CancellationToken ct = default);

    /// <summary>The caller's upload history, newest first; <paramref name="cursor"/> from the previous page.</summary>
    Task<UploadHistory> GetUploadHistoryAsync(string? cursor = null, int limit = 50, CancellationToken ct = default);

    /// <summary>A directory's entries (<c>/</c> for the top level).</summary>
    Task<Listing> ListAsync(string path, CancellationToken ct = default);

    /// <summary>
    /// Uploads a local file to <paramref name="remotePath"/>: a full path, or a directory ending in <c>/</c>, which
    /// keeps the local file name.
    /// </summary>
    /// <exception cref="FdropApiException">Refused: no permission, quota, file exists without overwrite, …</exception>
    /// <exception cref="FdropRetriesExhaustedException">A request kept failing after <c>MaxAttempts</c> tries.</exception>
    Task<FileEntry> UploadAsync(string localPath, string remotePath, UploadOptions? options = null, CancellationToken ct = default);

    /// <summary>
    /// Uploads <paramref name="size"/> bytes read from <paramref name="source"/>, which is not closed. For a seekable
    /// stream, <c>-1</c> means <c>Length - Position</c>. Each chunk is held in memory while it is sent.
    /// </summary>
    Task<FileEntry> UploadAsync(Stream source, long size, string remotePath, UploadOptions? options = null, CancellationToken ct = default);

    /// <summary>Downloads a file to <paramref name="localPath"/>, replacing it; retried from the start.</summary>
    Task DownloadAsync(string remotePath, string localPath, CancellationToken ct = default);

    /// <summary>Opens a file for reading; failures while reading are not retried. Dispose the stream.</summary>
    Task<Stream> OpenDownloadAsync(string remotePath, CancellationToken ct = default);

    /// <summary>Creates a directory.</summary>
    Task<FileEntry> CreateDirectoryAsync(string path, CancellationToken ct = default);

    /// <summary>Renames or moves a file or directory; the expiry moves with it.</summary>
    Task<FileEntry> MoveAsync(string source, string destination, CancellationToken ct = default);

    /// <summary>Deletes a file, or a directory (<paramref name="recursive"/> for one that is not empty).</summary>
    Task DeleteAsync(string path, bool recursive = false, CancellationToken ct = default);

    /// <summary>Sets a file's expiry relative to now, in fdrop's syntax (<c>"30 days"</c>, <c>"never"</c>).</summary>
    Task<FileEntry> SetExpiryAsync(string path, string expiresIn, CancellationToken ct = default);

    /// <summary>Sets a file's expiry to an instant.</summary>
    Task<FileEntry> SetExpiryAtAsync(string path, DateTimeOffset expiresAt, CancellationToken ct = default);

    /// <summary>Clears a file's own expiry, so the folder's rules apply.</summary>
    Task<FileEntry> ClearExpiryAsync(string path, CancellationToken ct = default);

    /// <summary>Searches names and contents of every file the caller can see.</summary>
    Task<SearchResult> SearchAsync(string query, string? cursor = null, int limit = 20, CancellationToken ct = default);

    /// <summary>The shared and forward folders the caller owns or belongs to.</summary>
    Task<IReadOnlyList<Folder>> GetFoldersAsync(CancellationToken ct = default);

    /// <summary>One folder.</summary>
    Task<Folder> GetFolderAsync(string id, CancellationToken ct = default);

    /// <summary>Creates a shared folder owned by the caller.</summary>
    Task<Folder> CreateSharedFolderAsync(string name, CancellationToken ct = default);

    /// <summary>Creates a forward folder sending each file to every one of <paramref name="destinationIds"/>, chosen from <see cref="GetForwardDestinationsAsync"/>.</summary>
    Task<Folder> CreateForwardFolderAsync(string name, IReadOnlyList<string> destinationIds, int? keepDays = null, CancellationToken ct = default);

    /// <summary>Creates a forward folder sending files to one destination.</summary>
    Task<Folder> CreateForwardFolderAsync(string name, string destinationId, int? keepDays = null, CancellationToken ct = default)
        => CreateForwardFolderAsync(name, [destinationId], keepDays, ct);

    /// <summary>Changes a forward folder's destinations (owner). Added ones get new files, and with <paramref name="sendExisting"/> also the files still in the folder.</summary>
    Task<Folder> SetForwardDestinationsAsync(string folderId, IReadOnlyList<string> destinationIds, bool sendExisting = false, CancellationToken ct = default);

    /// <summary>Deletes a folder (owner).</summary>
    Task DeleteFolderAsync(string id, CancellationToken ct = default);

    /// <summary>Adds a member or changes their permission (owner).</summary>
    Task<Folder> PutMemberAsync(string folderId, string username, Permission permission, CancellationToken ct = default);

    /// <summary>Removes a member (owner), or the caller themselves.</summary>
    Task RemoveMemberAsync(string folderId, string username, CancellationToken ct = default);

    /// <summary>A forward folder's jobs.</summary>
    Task<IReadOnlyList<ForwardJob>> GetForwardJobsAsync(string folderId, CancellationToken ct = default);

    /// <summary>Retries a failed forward job (owner).</summary>
    Task RetryForwardJobAsync(string folderId, string jobId, CancellationToken ct = default);

    /// <summary>Stops sending one file to one destination whose job failed (owner); the reason is audited.</summary>
    Task CancelForwardJobAsync(string folderId, string jobId, string reason, CancellationToken ct = default);

    /// <summary>The destinations the caller may use for forward folders.</summary>
    Task<IReadOnlyList<ForwardDestination>> GetForwardDestinationsAsync(CancellationToken ct = default);
}
