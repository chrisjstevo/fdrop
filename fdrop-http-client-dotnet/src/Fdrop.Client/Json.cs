using System.Text.Json;
using System.Text.Json.Serialization;

namespace Fdrop.Client;

/// <summary>Epoch milliseconds, as fdrop sends some times, as a <see cref="DateTimeOffset"/>.</summary>
internal sealed class EpochMillisConverter : JsonConverter<DateTimeOffset>
{
    public override DateTimeOffset Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
        => reader.TokenType == JsonTokenType.Number
            ? DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64())
            : DateTimeOffset.Parse(reader.GetString()!, System.Globalization.CultureInfo.InvariantCulture);

    public override void Write(Utf8JsonWriter writer, DateTimeOffset value, JsonSerializerOptions options)
        => writer.WriteNumberValue(value.ToUnixTimeMilliseconds());
}

/// <summary>An upload session as the server reports it.</summary>
internal sealed record UploadSession(string Id, long ChunkSize, int ChunkCount, string? State, string? Error, FileEntry? File,
    IReadOnlyList<IReadOnlyList<int>>? Received);

internal sealed record CreateUploadBody(string Path, long Size, long? LastModified, bool Overwrite, string? ExpiresIn);

internal sealed record CompleteUploadBody(string Sha256);

internal sealed record PathBody(string Path);

internal sealed record MoveBody(string From, string To);

internal sealed record ExpiresInBody(string ExpiresIn);

internal sealed record ExpiresAtBody(string ExpiresAt);

/// <summary>Sends <c>"expiresIn": null</c>, which clears the expiry; the property must not be left out.</summary>
internal sealed record ClearExpiryBody([property: JsonIgnore(Condition = JsonIgnoreCondition.Never)] string? ExpiresIn);

internal sealed record CreateFolderBody(string Type, string Name, IReadOnlyList<string>? DestinationIds, int? KeepDays);

internal sealed record DestinationsBody(IReadOnlyList<string> DestinationIds, bool SendExisting);

internal sealed record CancelJobBody(string Reason);

internal sealed record MemberBody(string Permission);

/// <summary>An RFC 9457 problem body.</summary>
internal sealed record Problem(string? Type, string? Title, string? Detail);

/// <summary>Source-generated JSON for every type the client reads or writes, so it is trimming and AOT safe.</summary>
[JsonSourceGenerationOptions(JsonSerializerDefaults.Web, DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull)]
[JsonSerializable(typeof(Account))]
[JsonSerializable(typeof(Listing))]
[JsonSerializable(typeof(FileEntry))]
[JsonSerializable(typeof(Folder))]
[JsonSerializable(typeof(List<Folder>))]
[JsonSerializable(typeof(List<ForwardJob>))]
[JsonSerializable(typeof(List<ForwardDestination>))]
[JsonSerializable(typeof(UploadHistory))]
[JsonSerializable(typeof(SearchResult))]
[JsonSerializable(typeof(UploadSession))]
[JsonSerializable(typeof(Problem))]
[JsonSerializable(typeof(CreateUploadBody))]
[JsonSerializable(typeof(CompleteUploadBody))]
[JsonSerializable(typeof(PathBody))]
[JsonSerializable(typeof(MoveBody))]
[JsonSerializable(typeof(ExpiresInBody))]
[JsonSerializable(typeof(ExpiresAtBody))]
[JsonSerializable(typeof(ClearExpiryBody))]
[JsonSerializable(typeof(CreateFolderBody))]
[JsonSerializable(typeof(DestinationsBody))]
[JsonSerializable(typeof(CancelJobBody))]
[JsonSerializable(typeof(MemberBody))]
internal sealed partial class FdropJson : JsonSerializerContext;
