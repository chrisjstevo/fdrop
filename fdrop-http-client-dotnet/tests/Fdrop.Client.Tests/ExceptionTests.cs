using System.Text.Json;

namespace Fdrop.Client.Tests;

/// <summary>The exception types' constructors, and times as fdrop writes them.</summary>
[Trait("Category", "Unit")]
public class ExceptionTests
{
    [Fact]
    public void ExceptionsHaveTheStandardConstructors()
    {
        var inner = new IOException("cause");
        Assert.Equal("m", new FdropClientException("m").Message);
        Assert.Same(inner, new FdropClientException("m", inner).InnerException);
        Assert.NotNull(new FdropClientException().Message);
        Assert.Equal("m", new FdropApiException("m").Message);
        Assert.Same(inner, new FdropApiException("m", inner).InnerException);
        Assert.Equal(0, new FdropApiException().StatusCode);
        Assert.Equal("m", new FdropRetriesExhaustedException("m").Message);
        Assert.Same(inner, new FdropRetriesExhaustedException("m", inner).InnerException);
        Assert.Equal(0, new FdropRetriesExhaustedException().Attempts);
        Assert.Equal("op: gave up after 1 attempt: cause", new FdropRetriesExhaustedException("op", 1, inner).Message);
        Assert.Equal("op: HTTP 404", new FdropApiException("op", 404, null, null, " ").Message);
    }

    [Fact]
    public void FolderTimesAreEpochMilliseconds()
    {
        const string json = """{"id":"f1","type":"SHARED","name":"F","path":"/shared/F","owner":"alice","role":"OWNER","paused":false,"members":[{"username":"bob","permission":"READ","kind":"STANDARD"}],"createdAt":1761130000000}""";
        var f = JsonSerializer.Deserialize(json, FdropJson.Default.Folder)!;
        Assert.Equal(DateTimeOffset.FromUnixTimeMilliseconds(1761130000000), f.CreatedAt);
        Assert.Equal(Permission.Read, f.Members[0].Permission);
        string back = JsonSerializer.Serialize(f, FdropJson.Default.Folder);
        Assert.Contains("\"createdAt\":1761130000000", back, StringComparison.Ordinal);
        var iso = JsonSerializer.Deserialize(json.Replace("1761130000000", "\"2025-10-22T10:46:40Z\"", StringComparison.Ordinal), FdropJson.Default.Folder)!;
        Assert.Equal(f.CreatedAt, iso.CreatedAt);
    }
}
