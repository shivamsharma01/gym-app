using System.Net;
using System.Text;
using System.Text.Json;
using Gym.Gateway.Adapters;
using Gym.Gateway.Execution;
using Xunit;

namespace Gym.Gateway.Tests;

/// <summary>The JSON the backend sends for the expected record, and the occupant report it accepts.</summary>
public class DesiredStateClientContractTests
{
    [Fact]
    public async Task The_expected_record_is_read_from_the_pull()
    {
        var handler = new Backend("""
            {"desiredRevision":4,"appliedRevision":3,"items":[{"revision":4,"deviceUserId":"7","name":"Asha",
            "nameEx":null,"userStatus":0,"validFrom":"2026-10-08T00:00:00+05:30","validTo":"2026-10-08T23:59:59+05:30",
            "authority":"Customer","doorNum":1,"timeSectionNum":1,"faceSha256":"","faceBase64":"","present":false,
            "facePresent":true,"expected":{"present":true,"name":"Asha","nameEx":null,"userStatus":1,
            "validFrom":"2026-10-08T00:00:00+05:30","validTo":"2026-10-08T23:59:59+05:30","authority":"Customer",
            "faceSha256":"ab"}}]}
            """);
        var client = new DesiredStateClient(new HttpClient(handler), "http://backend");

        var item = Assert.Single((await client.PullAsync("r", 3, CancellationToken.None)).Items);

        Assert.Equal(
            new ExpectedOccupant(true, "Asha", null, 1, "2026-10-08T00:00:00+05:30", "2026-10-08T23:59:59+05:30", "Customer", "ab"),
            item.Expected);
    }

    [Fact]
    public async Task A_pull_without_an_expected_record_expects_no_one()
    {
        var handler = new Backend("""
            {"desiredRevision":1,"appliedRevision":0,"items":[{"revision":1,"deviceUserId":"7","name":"Asha",
            "userStatus":0,"validFrom":"2026-10-08T00:00:00+05:30","validTo":"2026-10-08T23:59:59+05:30",
            "authority":"Customer","doorNum":1,"timeSectionNum":1,"faceBase64":"AQI=","present":true}]}
            """);
        var client = new DesiredStateClient(new HttpClient(handler), "http://backend");

        var item = Assert.Single((await client.PullAsync("r", 0, CancellationToken.None)).Items);

        Assert.Equal(ExpectedOccupant.Absent, item.Expected);
    }

    [Fact]
    public async Task The_occupant_report_carries_what_the_reader_shows()
    {
        var handler = new Backend("{}");
        var client = new DesiredStateClient(new HttpClient(handler), "http://backend");
        var from = new DateTimeOffset(2026, 10, 8, 0, 0, 0, TimeSpan.FromHours(5.5));
        var bob = new ReaderUser("7", "Bob", null, 1, from, from.AddDays(30), "Customer", 1, 1);

        await client.ReportOccupantAsync("r", 4, "7", new ReaderOccupant(true, bob, "cd"), CancellationToken.None);
        await client.ReportOccupantAsync("r", 5, "8", ReaderOccupant.Nobody, CancellationToken.None);

        Assert.Equal("/internal/gateway/desired/occupant", handler.Paths[0]);
        using var present = JsonDocument.Parse(handler.Bodies[0]);
        var root = present.RootElement;
        Assert.Equal(("r", 4, "7", true), (root.GetProperty("deviceId").GetString(), root.GetProperty("revision").GetInt64(),
            root.GetProperty("deviceUserId").GetString(), root.GetProperty("present").GetBoolean()));
        Assert.Equal(("Bob", 1, "2026-10-08T00:00:00+05:30", "cd"), (root.GetProperty("name").GetString(),
            root.GetProperty("userStatus").GetInt32(), root.GetProperty("validFrom").GetString(),
            root.GetProperty("faceSha256").GetString()));
        using var absent = JsonDocument.Parse(handler.Bodies[1]);
        Assert.False(absent.RootElement.GetProperty("present").GetBoolean());
        Assert.Equal(JsonValueKind.Null, absent.RootElement.GetProperty("name").ValueKind);
    }

    private sealed class Backend(string reply) : HttpMessageHandler
    {
        public List<string> Paths { get; } = [];
        public List<string> Bodies { get; } = [];

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Paths.Add(request.RequestUri!.AbsolutePath);
            Bodies.Add(request.Content == null ? "" : await request.Content.ReadAsStringAsync(cancellationToken));
            return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(reply, Encoding.UTF8, "application/json") };
        }
    }
}
