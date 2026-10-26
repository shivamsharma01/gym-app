using System.Net;
using System.Text;
using System.Text.Json;
using Gym.Gateway.Adapters;
using Gym.Gateway.Execution;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

namespace Gym.Gateway.Tests;

/// <summary>
/// A complete reader list is a snapshot in the outbound journal: only the newest unsent list of a
/// reader is kept. Per-user observations, absences, and every other envelope are kept until acked.
/// </summary>
public sealed class OutboundSnapshotTests : IDisposable
{
    private static readonly DateTimeOffset Start = new(2026, 10, 14, 9, 0, 0, TimeSpan.Zero);
    private readonly string _directory = Path.Combine(Path.GetTempPath(), "gym-outbox-" + Guid.NewGuid().ToString("N"));

    public void Dispose()
    {
        if (Directory.Exists(_directory))
        {
            Directory.Delete(_directory, recursive: true);
        }
    }

    [Fact]
    public void A_newer_list_replaces_only_the_earlier_list_of_the_same_reader()
    {
        var store = Store();
        var firstA = Roster("reader-a", 0);
        store.Persist(firstA);
        var observation = Observation("reader-a", "7", 1);
        store.Persist(observation);
        var listB = Roster("reader-b", 2);
        store.Persist(listB);
        store.Persist(Roster("reader-a", 3));
        var absence = Absence("reader-a", "8", 4);
        store.Persist(absence);
        var lastA = Roster("reader-a", 5);
        store.Persist(lastA);

        Assert.False(store.IsPending(firstA.MessageId));
        var reloaded = Store().LoadPending().Select(e => e.MessageId).ToList();
        // Complete lists are ordered before per-user rows; among each group, oldest first.
        Assert.Equal([listB.MessageId, lastA.MessageId, observation.MessageId, absence.MessageId], reloaded);
        Assert.Equal(4, Directory.GetFiles(_directory, "*.json").Length);
    }

    [Fact]
    public void Single_user_messages_are_never_coalesced()
    {
        var store = Store();
        var ids = new List<string>();
        for (var i = 0; i < 5; i++)
        {
            var seen = Observation("reader-a", "7", i);
            store.Persist(seen);
            ids.Add(seen.MessageId);
        }

        for (var i = 5; i < 8; i++)
        {
            var gone = Absence("reader-a", "7", i);
            store.Persist(gone);
            ids.Add(gone.MessageId);
        }

        Assert.Equal(ids, Store().LoadPending().Select(e => e.MessageId).ToList());
    }

    [Fact]
    public void After_a_crash_that_left_two_lists_of_a_reader_only_the_newer_is_replayed()
    {
        var older = Roster("reader-a", 0);
        Store().Persist(older);
        var newer = Roster("reader-a", 1);
        Store().Persist(newer);
        Assert.Equal(2, Directory.GetFiles(_directory, "*.json").Length);

        var restarted = Store();
        var pending = restarted.LoadPending();

        Assert.Equal([newer.MessageId], pending.Select(e => e.MessageId));
        Assert.Single(Directory.GetFiles(_directory, "*.json"));
        var next = Roster("reader-a", 2);
        restarted.Persist(next);
        Assert.Equal([next.MessageId], Store().LoadPending().Select(e => e.MessageId));
    }

    [Fact]
    public void An_ack_for_a_replaced_list_in_flight_does_not_drop_the_newer_list()
    {
        var store = Store();
        var inFlight = Roster("reader-a", 0);
        store.Persist(inFlight);
        var newer = Roster("reader-a", 1);
        store.Persist(newer);

        store.Acknowledge(inFlight.MessageId);

        Assert.True(store.IsPending(newer.MessageId));
        Assert.Equal([newer.MessageId], Store().LoadPending().Select(e => e.MessageId));
        store.Acknowledge(newer.MessageId);
        Assert.Empty(Directory.GetFiles(_directory, "*.json"));
        var later = Roster("reader-a", 2);
        store.Persist(later);
        Assert.True(store.IsPending(later.MessageId));
    }

    [Fact]
    public void TryGetPendingListMessageId_returns_the_newest_queued_list_for_the_reader()
    {
        var store = Store();
        var older = Roster("reader-a", 0);
        store.Persist(older);
        var newer = Roster("reader-a", 1);
        store.Persist(newer);
        store.Persist(Observation("reader-a", "7", 2));
        store.Persist(Roster("reader-b", 3));

        Assert.Equal(newer.MessageId, store.TryGetPendingListMessageId("reader-a"));
        Assert.Null(store.TryGetPendingListMessageId("reader-c"));

        store.Acknowledge(newer.MessageId);
        Assert.Null(store.TryGetPendingListMessageId("reader-a"));
    }

    [Fact]
    public void A_corrupt_outbox_file_is_quarantined_and_not_reloaded()
    {
        Directory.CreateDirectory(_directory);
        var badPath = Path.Combine(_directory, "corrupt.json");
        File.WriteAllText(badPath, "{not-json");
        var good = Observation("reader-a", "7", 1);
        Store().Persist(good);

        var pending = Store().LoadPending().Select(e => e.MessageId).ToList();

        Assert.Equal([good.MessageId], pending);
        Assert.False(File.Exists(badPath));
        Assert.True(File.Exists(badPath + ".bad"));
        Assert.Equal([good.MessageId], Store().LoadPending().Select(e => e.MessageId).ToList());
    }

    /// <summary>
    /// An hour without the server at one scan every 15 seconds, two readers of three users each.
    /// Every per-user message survives; of 480 lists only the last of each reader is replayed.
    /// </summary>
    [Fact]
    public async Task An_hour_long_outage_keeps_every_user_message_and_only_the_last_list_per_reader()
    {
        const int scans = 240;
        var backend = new SwitchableBackend();
        var store = Store();
        var link = Link(store, backend);
        var upload = new ReaderObservationUpload("gw-1", link, NullLogger<ReaderObservationUpload>.Instance);
        string[] readers = ["reader-a", "reader-b"];

        for (var scan = 0; scan < scans; scan++)
        {
            foreach (var reader in readers)
            {
                var users = Users(reader, scan);
                var stamp = new ScanStamp("journal-" + reader, scan + 1);
                await upload.UploadAsync(reader, stamp, users, new Dictionary<string, string>(), CancellationToken.None);
                await upload.UploadTrustedRosterAsync(reader, stamp, users.Count, users, new Dictionary<string, string>(),
                    false, CancellationToken.None);
            }
        }

        Assert.Empty(backend.Delivered);
        var files = Directory.GetFiles(_directory, "*.json");
        Assert.Equal(scans * readers.Length * 3 + readers.Length, files.Length);

        backend.Online = true;
        var restarted = Store();
        await Link(restarted, backend).ReplayPendingAsync(CancellationToken.None);

        var lists = backend.Delivered.Where(IsList).ToList();
        Assert.Equal(2, lists.Count);
        foreach (var reader in readers)
        {
            var list = Assert.Single(lists, e => e.DeviceId == reader);
            var names = list.Payload.GetProperty("users").EnumerateArray()
                .Select(u => u.GetProperty("name").GetString()).ToList();
            Assert.All(names, name => Assert.EndsWith("scan " + (scans - 1), name));
        }

        Assert.Equal(scans * readers.Length * 3, backend.Delivered.Count(e => !IsList(e)));
        Assert.Equal(backend.Delivered.Count, backend.Delivered.Select(e => e.MessageId).Distinct().Count());
        Assert.Empty(Directory.GetFiles(_directory, "*.json"));
    }

    [Fact]
    public async Task A_list_replaced_while_the_replay_runs_is_not_sent_after_its_replacement()
    {
        var backend = new SwitchableBackend();
        var store = Store();
        var first = Observation("reader-a", "7", 0);
        store.Persist(first);
        var stale = Roster("reader-a", 1);
        store.Persist(stale);
        var fresh = Roster("reader-a", 2);
        backend.Online = true;
        // Lists are delivered before per-user rows, so the first write is the list; replacing it
        // mid-replay must leave only the newer list pending and must not deliver that newer list in
        // the same pass (it was not in the snapshot).
        backend.OnFirstDelivery = () => store.Persist(fresh);

        await Link(store, backend).ReplayPendingAsync(CancellationToken.None);

        Assert.Equal([stale.MessageId, first.MessageId], backend.Delivered.Select(e => e.MessageId));
        Assert.True(store.IsPending(fresh.MessageId));
        Assert.False(store.IsPending(stale.MessageId));
        Assert.DoesNotContain(backend.Delivered, e => e.MessageId == fresh.MessageId);
    }

    [Fact]
    public async Task Pending_lists_are_redelivered_before_per_user_rows()
    {
        var backend = new SwitchableBackend { Online = true };
        var store = Store();
        var seen = Observation("reader-a", "7", 0);
        store.Persist(seen);
        var list = Roster("reader-a", 1);
        store.Persist(list);

        await Link(store, backend).ReplayPendingAsync(CancellationToken.None);

        Assert.Equal([list.MessageId, seen.MessageId], backend.Delivered.Select(e => e.MessageId));
    }

    [Fact]
    public async Task TryRedeliver_resends_the_same_pending_list_messageId()
    {
        var backend = new SwitchableBackend { Online = true, Status = HttpStatusCode.InternalServerError };
        var store = Store();
        var list = Roster("reader-a", 0);
        store.Persist(list);
        var link = Link(store, backend);

        Assert.NotNull(store.TryReadPending(list.MessageId));
        Assert.True(await link.TryRedeliverAsync(list.MessageId, CancellationToken.None));
        Assert.True(await link.TryRedeliverAsync(list.MessageId, CancellationToken.None));
        Assert.True(store.IsPending(list.MessageId));
    }

    [Fact]
    public async Task A_server_that_rejects_a_message_keeps_it_for_the_next_replay()
    {
        var backend = new SwitchableBackend { Online = true, Status = HttpStatusCode.InternalServerError };
        var store = Store();
        var list = Roster("reader-a", 0);
        store.Persist(list);
        var seen = Observation("reader-a", "7", 1);
        store.Persist(seen);

        await Link(store, backend).ReplayPendingAsync(CancellationToken.None);

        Assert.True(store.IsPending(list.MessageId));
        Assert.True(store.IsPending(seen.MessageId));
        backend.Status = HttpStatusCode.OK;
        await Link(Store(), backend).ReplayPendingAsync(CancellationToken.None);
        Assert.Empty(Directory.GetFiles(_directory, "*.json"));
    }

    private static bool IsList(GatewayEnvelope envelope) => DurableOutboundStore.SnapshotKey(envelope) != null;

    private DurableOutboundStore Store() => new(NullLogger<DurableOutboundStore>.Instance, _directory);

    private static BackendLink Link(DurableOutboundStore store, HttpMessageHandler handler) =>
        new(new GatewayOptions { Id = "gw-1", Token = "token", BackendUrl = "http://backend", UseWebSocket = false },
            NullLogger<BackendLink>.Instance, store, new HttpClient(handler));

    private static List<ReaderUser> Users(string reader, int scan) =>
        Enumerable.Range(1, 3)
            .Select(i => new ReaderUser(i.ToString(), $"{reader} user {i} scan {scan}", null, 0, null, null, "Customer", 1, 1))
            .ToList();

    private static GatewayEnvelope Roster(string reader, int second) =>
        At(GatewayEnvelope.Create("gw-1", ProtocolTypes.DeviceUserChanged,
            new { announcedTotal = 0, users = Array.Empty<object>() }, reader), second);

    private static GatewayEnvelope Observation(string reader, string user, int second) =>
        At(GatewayEnvelope.Create("gw-1", ProtocolTypes.DeviceUserChanged,
            new { deviceUserId = user, isNew = true, deleted = false }, reader), second);

    private static GatewayEnvelope Absence(string reader, string user, int second) =>
        At(GatewayEnvelope.Create("gw-1", ProtocolTypes.DeviceUserChanged,
            new { deviceUserId = user, isNew = false, deleted = true }, reader), second);

    private static GatewayEnvelope At(GatewayEnvelope envelope, int second)
    {
        envelope.Timestamp = Start.AddSeconds(second).UtcDateTime.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'");
        return envelope;
    }

    private sealed class SwitchableBackend : HttpMessageHandler
    {
        public bool Online { get; set; }

        public HttpStatusCode Status { get; set; } = HttpStatusCode.OK;

        public Action? OnFirstDelivery { get; set; }

        public List<GatewayEnvelope> Delivered { get; } = [];

        protected override async Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken cancellationToken)
        {
            if (!Online)
            {
                throw new HttpRequestException("backend unreachable");
            }

            if (Status != HttpStatusCode.OK)
            {
                return new HttpResponseMessage(Status) { Content = new StringContent("{}") };
            }

            var body = await request.Content!.ReadAsStringAsync(cancellationToken);
            var envelope = JsonSerializer.Deserialize<GatewayEnvelope>(body, JsonOptions.Inbound)!;
            Delivered.Add(envelope);
            var hook = OnFirstDelivery;
            OnFirstDelivery = null;
            hook?.Invoke();
            return new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new StringContent("{}", Encoding.UTF8, "application/json")
            };
        }
    }
}
