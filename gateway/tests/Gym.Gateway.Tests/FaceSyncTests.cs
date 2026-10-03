using System.Text.Json;
using Gym.Gateway.Adapters;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

namespace Gym.Gateway.Tests;

public class FaceSyncTests
{
    private static readonly byte[] PhotoA = [0xFF, 0xD8, 0xFF, 0xE0, 0x01, 0x02, 0x03];
    private static readonly byte[] PhotoB = [0xFF, 0xD8, 0xFF, 0xE0, 0x09, 0x08, 0x07];

    [Fact]
    public async Task Upsert_face_downloads_checks_sha_and_writes_to_device()
    {
        var (adapter, adapters) = Device();
        adapter.CreateUser(new DeviceUserMutation("1001", "Ada"));
        var faces = new FakeFaceTransfer { Download = PhotoA };
        var dispatcher = new CommandDispatcher(adapters, NullLogger<CommandDispatcher>.Instance, faces);

        var ok = await dispatcher.DispatchAsync(UpsertFace("1001", FaceHash.Sha256Hex(PhotoA)));
        Assert.True(ok.Ok);
        Assert.Contains("\"faceVersion\":3", JsonSerializer.Serialize(ok.Payload));
        Assert.Equal(PhotoA, adapter.GetFace("1001").Photo);

        var mismatch = await dispatcher.DispatchAsync(UpsertFace("1001", FaceHash.Sha256Hex(PhotoB)));
        Assert.False(mismatch.Ok);
        Assert.Contains("sha256", JsonSerializer.Serialize(mismatch.Payload));

        var delete = await dispatcher.DispatchAsync(Command("DELETE_FACE", new { deviceUserId = "1001" }));
        Assert.True(delete.Ok);
        Assert.Null(adapter.GetFace("1001").Photo);
    }

    [Fact]
    public async Task First_scan_is_a_silent_baseline_then_device_changes_are_reported()
    {
        var (adapter, adapters) = Device();
        adapter.SimulateLocalUserChange("1001", "Existing", PhotoA, emitEvent: false);
        var faces = new FakeFaceTransfer();
        var published = new List<JsonElement>();
        var watcher = Watcher(adapters, new RosterStateStore(null), faces, published);

        Assert.Equal(0, await watcher.ScanDeviceAsync("dev-1", null, faceSweep: false, CancellationToken.None));
        Assert.Empty(published);

        adapter.SimulateLocalUserChange("2001", "Ravi Kumar", PhotoB, emitEvent: false);
        Assert.Equal(1, await watcher.ScanDeviceAsync("dev-1", null, faceSweep: false, CancellationToken.None));
        var change = Assert.Single(published);
        Assert.Equal("2001", change.GetProperty("deviceUserId").GetString());
        Assert.True(change.GetProperty("isNew").GetBoolean());
        Assert.True(change.GetProperty("faceChanged").GetBoolean());
        Assert.Equal(FaceHash.Sha256Hex(PhotoB), change.GetProperty("faceSha256").GetString());
        Assert.Equal("upload-1", change.GetProperty("faceUploadId").GetString());
        Assert.Equal(PhotoB, Assert.Single(faces.Uploaded));

        // The baseline did not read photos. The face pass uploads the one that was already there.
        Assert.Equal(1, await watcher.ScanDeviceAsync("dev-1", null, faceSweep: true, CancellationToken.None));
        Assert.Equal("1001", published[^1].GetProperty("deviceUserId").GetString());

        // Nothing changed: nothing reported.
        Assert.Equal(0, await watcher.ScanDeviceAsync("dev-1", null, faceSweep: true, CancellationToken.None));

        // A face replaced on the device is picked up by the event focus.
        adapter.SimulateLocalUserChange("1001", "Existing", PhotoB, emitEvent: false);
        Assert.Equal(1, await watcher.ScanDeviceAsync("dev-1", ["1001"], faceSweep: false, CancellationToken.None));
        Assert.Equal("1001", published[^1].GetProperty("deviceUserId").GetString());
        Assert.False(published[^1].GetProperty("isNew").GetBoolean());
    }

    [Fact]
    public async Task Face_pass_does_not_download_photos_it_already_knows()
    {
        var (adapter, adapters) = Device();
        adapter.SimulateLocalUserChange("5001", "Known", PhotoA, emitEvent: false);
        var roster = new RosterStateStore(null);
        var watcher = Watcher(adapters, roster, new FakeFaceTransfer(), new List<JsonElement>());
        await watcher.ScanDeviceAsync("dev-1", null, faceSweep: false, CancellationToken.None);

        Assert.Equal(1, await watcher.ScanDeviceAsync("dev-1", null, faceSweep: true, CancellationToken.None));
        Assert.NotNull(roster.LastFaceSweepUtc("dev-1"));
        Assert.Equal(0, roster.FaceCursor("dev-1"));

        // Without a reader event the next pass leaves a known photo alone.
        adapter.SimulateLocalUserChange("5001", "Known", PhotoB, emitEvent: false);
        Assert.Equal(0, await watcher.ScanDeviceAsync("dev-1", null, faceSweep: true, CancellationToken.None));
        Assert.Equal(FaceHash.Sha256Hex(PhotoA), roster.Find("dev-1", "5001")!.FaceSha256);
    }

    [Fact]
    public async Task Sync_now_reads_every_photo_again_and_reports_only_changes()
    {
        var (adapter, adapters) = Device();
        adapter.SimulateLocalUserChange("5101", "Same", PhotoA, emitEvent: false);
        adapter.SimulateLocalUserChange("5102", "Edited", PhotoA, emitEvent: false);
        var roster = new RosterStateStore(null);
        var published = new List<JsonElement>();
        var watcher = Watcher(adapters, roster, new FakeFaceTransfer(), published);
        var dispatcher = new CommandDispatcher(adapters, NullLogger<CommandDispatcher>.Instance, null, roster);
        await watcher.ScanDeviceAsync("dev-1", null, faceSweep: false, CancellationToken.None);
        Assert.Equal(2, await watcher.ScanDeviceAsync("dev-1", null, faceSweep: true, CancellationToken.None));

        adapter.SimulateLocalUserChange("5102", "Edited", PhotoB, emitEvent: false);
        Assert.True((await dispatcher.DispatchAsync(Command("RECONCILE_DEVICE", new { refreshFaces = true }))).Ok);
        Assert.True(roster.FaceRefreshRequested("dev-1"));

        Assert.Equal(1, await watcher.ScanDeviceAsync("dev-1", null, faceSweep: true, CancellationToken.None));
        Assert.Equal("5102", published[^1].GetProperty("deviceUserId").GetString());
        Assert.False(published[^1].GetProperty("initialSample").GetBoolean());
        Assert.False(roster.FaceRefreshRequested("dev-1"));
    }

    [Fact]
    public async Task Reconnect_reconcile_does_not_start_a_photo_refresh()
    {
        var (_, adapters) = Device();
        var roster = new RosterStateStore(null);
        var dispatcher = new CommandDispatcher(adapters, NullLogger<CommandDispatcher>.Instance, null, roster);
        Assert.True((await dispatcher.DispatchAsync(Command("RECONCILE_DEVICE", new { }))).Ok);
        Assert.False(roster.FaceRefreshRequested("dev-1"));
    }

    [Fact]
    public async Task Read_from_device_reports_an_unchanged_photo_with_its_original_time()
    {
        var (adapter, adapters) = Device();
        adapter.SimulateLocalUserChange("5201", "Known", PhotoA, emitEvent: false);
        var roster = new RosterStateStore(null);
        var published = new List<JsonElement>();
        var watcher = Watcher(adapters, roster, new FakeFaceTransfer(), published);
        await watcher.ScanDeviceAsync("dev-1", null, faceSweep: false, CancellationToken.None);
        await watcher.ScanDeviceAsync("dev-1", null, faceSweep: true, CancellationToken.None);
        var firstReportAt = published[^1].GetProperty("deviceChangedAt").GetString();

        await Task.Delay(20);
        var (ok, _) = await watcher.ReportUserAsync("dev-1", "5201", CancellationToken.None);
        Assert.True(ok);
        var report = published[^1];
        Assert.Equal(FaceHash.Sha256Hex(PhotoA), report.GetProperty("faceSha256").GetString());
        Assert.True(report.GetProperty("initialSample").GetBoolean());
        Assert.Equal(firstReportAt, report.GetProperty("deviceChangedAt").GetString());
    }

    [Fact]
    public async Task Photos_uploaded_together_are_still_reported_in_queue_order()
    {
        var (adapter, adapters) = Device();
        var faces = new FakeFaceTransfer();
        var published = new List<JsonElement>();
        var watcher = Watcher(adapters, new RosterStateStore(null), faces, published);
        await watcher.ScanDeviceAsync("dev-1", null, faceSweep: false, CancellationToken.None);

        string[] ids = ["7001", "7002", "7003", "7004", "7005", "7006"];
        foreach (var id in ids)
        {
            adapter.SimulateLocalUserChange(id, "User " + id, [.. PhotoA, (byte)id[^1]], emitEvent: false);
        }

        Assert.Equal(ids.Length, await watcher.ScanDeviceAsync("dev-1", null, faceSweep: false, CancellationToken.None));
        Assert.Equal(ids, published.Select(p => p.GetProperty("deviceUserId").GetString()).ToArray());
        Assert.All(published, p => Assert.NotNull(p.GetProperty("faceUploadId").GetString()));
    }

    [Fact]
    public void Deferred_roster_saves_are_written_once_when_the_scope_ends()
    {
        var dir = Path.Combine(Path.GetTempPath(), "gym-roster-" + Guid.NewGuid().ToString("N"));
        try
        {
            var store = new RosterStateStore(dir);
            using (store.DeferSaves("dev-1"))
            {
                store.RecordFace("dev-1", "6001", "abc");
                using (store.DeferSaves("dev-1"))
                {
                    store.RecordFace("dev-1", "6002", "def");
                }

                Assert.Null(new RosterStateStore(dir).Find("dev-1", "6001"));
            }

            var reopened = new RosterStateStore(dir);
            Assert.Equal("abc", reopened.Find("dev-1", "6001")!.FaceSha256);
            Assert.Equal("def", reopened.Find("dev-1", "6002")!.FaceSha256);
        }
        finally
        {
            Directory.Delete(dir, recursive: true);
        }
    }

    [Fact]
    public void Face_pass_progress_survives_a_restart()
    {
        var dir = Path.Combine(Path.GetTempPath(), "gym-roster-" + Guid.NewGuid().ToString("N"));
        try
        {
            var at = new DateTimeOffset(2026, 10, 1, 8, 0, 0, TimeSpan.Zero);
            var first = new RosterStateStore(dir);
            first.MarkBaseline("dev-1");
            first.SetFaceCursor("dev-1", 40);
            first.CompleteFaceSweep("dev-2", at);

            var reopened = new RosterStateStore(dir);
            Assert.Equal(40, reopened.FaceCursor("dev-1"));
            Assert.Null(reopened.LastFaceSweepUtc("dev-1"));
            Assert.Equal(at, reopened.LastFaceSweepUtc("dev-2"));
            Assert.Equal(0, reopened.FaceCursor("dev-2"));
        }
        finally
        {
            Directory.Delete(dir, recursive: true);
        }
    }

    [Fact]
    public async Task Writes_made_by_the_gateway_are_not_reported_back()
    {
        var (adapter, adapters) = Device();
        var roster = new RosterStateStore(null);
        var faces = new FakeFaceTransfer { Download = PhotoA };
        var published = new List<JsonElement>();
        var locks = new DeviceLocks();
        var dispatcher = new CommandDispatcher(adapters, NullLogger<CommandDispatcher>.Instance, faces, roster, locks);
        var watcher = Watcher(adapters, roster, faces, published, locks);
        await watcher.ScanDeviceAsync("dev-1", null, faceSweep: false, CancellationToken.None);

        Assert.True((await dispatcher.DispatchAsync(Command("CREATE_USER", new { deviceUserId = "3001", name = "Server Made" }))).Ok);
        Assert.True((await dispatcher.DispatchAsync(UpsertFace("3001", FaceHash.Sha256Hex(PhotoA)))).Ok);
        Assert.True((await dispatcher.DispatchAsync(Command("UPDATE_USER", new { deviceUserId = "3001", name = "Renamed" }))).Ok);

        Assert.Equal(0, await watcher.ScanDeviceAsync("dev-1", ["3001"], faceSweep: true, CancellationToken.None));
        Assert.Empty(published);
        Assert.Empty(faces.Uploaded);
    }

    [Fact]
    public async Task Failed_face_upload_is_retried_on_the_next_scan()
    {
        var (adapter, adapters) = Device();
        var faces = new FakeFaceTransfer { FailUploads = true };
        var published = new List<JsonElement>();
        var watcher = Watcher(adapters, new RosterStateStore(null), faces, published);
        await watcher.ScanDeviceAsync("dev-1", null, faceSweep: false, CancellationToken.None);

        adapter.SimulateLocalUserChange("4001", "Later", PhotoA, emitEvent: false);
        Assert.Equal(0, await watcher.ScanDeviceAsync("dev-1", null, faceSweep: false, CancellationToken.None));
        Assert.Empty(published);

        faces.FailUploads = false;
        Assert.Equal(1, await watcher.ScanDeviceAsync("dev-1", null, faceSweep: false, CancellationToken.None));
        Assert.Equal("4001", Assert.Single(published).GetProperty("deviceUserId").GetString());
    }

    [Fact]
    public async Task Face_changed_during_an_outage_is_retried_on_every_scan_not_only_the_sweep()
    {
        var (adapter, adapters) = Device();
        adapter.SimulateLocalUserChange("4101", "Known", PhotoA, emitEvent: false);
        var faces = new FakeFaceTransfer();
        var published = new List<JsonElement>();
        var watcher = Watcher(adapters, new RosterStateStore(null), faces, published);
        await watcher.ScanDeviceAsync("dev-1", null, faceSweep: false, CancellationToken.None);

        faces.FailUploads = true;
        adapter.SimulateLocalUserChange("4101", "Known", PhotoB, emitEvent: false);
        Assert.Equal(0, await watcher.ScanDeviceAsync("dev-1", ["4101"], faceSweep: false, CancellationToken.None));
        Assert.Equal(0, await watcher.ScanDeviceAsync("dev-1", null, faceSweep: false, CancellationToken.None));
        Assert.Equal(2, faces.Attempts);

        // Server back: the plain periodic scan (no event, no sweep) delivers the photo.
        faces.FailUploads = false;
        Assert.Equal(1, await watcher.ScanDeviceAsync("dev-1", null, faceSweep: false, CancellationToken.None));
        Assert.Equal(PhotoB, Assert.Single(faces.Uploaded));
        Assert.Equal(FaceHash.Sha256Hex(PhotoB), published[^1].GetProperty("faceSha256").GetString());
    }

    [Fact]
    public async Task Rejected_face_does_not_block_other_changes_and_is_not_retried()
    {
        var (adapter, adapters) = Device();
        adapter.SimulateLocalUserChange("4201", "Before", PhotoA, emitEvent: false);
        var faces = new FakeFaceTransfer();
        var published = new List<JsonElement>();
        var watcher = Watcher(adapters, new RosterStateStore(null), faces, published);
        await watcher.ScanDeviceAsync("dev-1", null, faceSweep: false, CancellationToken.None);

        faces.RejectUploads = true;
        adapter.SimulateLocalUserChange("4201", "After", PhotoB, emitEvent: false);
        Assert.Equal(1, await watcher.ScanDeviceAsync("dev-1", ["4201"], faceSweep: false, CancellationToken.None));
        var change = Assert.Single(published);
        Assert.True(change.GetProperty("nameChanged").GetBoolean());
        Assert.False(change.GetProperty("faceChanged").GetBoolean());

        Assert.Equal(0, await watcher.ScanDeviceAsync("dev-1", null, faceSweep: true, CancellationToken.None));
        Assert.Equal(1, faces.Attempts);
    }

    [Fact]
    public async Task Report_device_user_sends_current_face_even_if_unchanged()
    {
        var (adapter, adapters) = Device();
        adapter.SimulateLocalUserChange("5001", "Old Timer", PhotoA, emitEvent: false);
        var faces = new FakeFaceTransfer();
        var published = new List<JsonElement>();
        var watcher = Watcher(adapters, new RosterStateStore(null), faces, published);
        await watcher.ScanDeviceAsync("dev-1", null, faceSweep: false, CancellationToken.None);

        var dispatcher = new CommandDispatcher(adapters, NullLogger<CommandDispatcher>.Instance, faces,
            reportUser: (d, u) => watcher.ReportUserAsync(d, u, CancellationToken.None));
        Assert.True((await dispatcher.DispatchAsync(Command("REPORT_DEVICE_USER", new { deviceUserId = "5001" }))).Ok);
        var change = Assert.Single(published);
        Assert.Equal(FaceHash.Sha256Hex(PhotoA), change.GetProperty("faceSha256").GetString());
        Assert.False((await dispatcher.DispatchAsync(Command("REPORT_DEVICE_USER", new { deviceUserId = "nope" }))).Ok);
    }

    [Fact]
    public async Task Device_edits_report_only_the_fields_that_changed_and_deletions()
    {
        var (adapter, adapters) = Device();
        adapter.SimulateLocalUserChange("6001", "Asha", null, emitEvent: false);
        adapter.SimulateLocalUserChange("6002", "Bina", null, emitEvent: false);
        var published = new List<JsonElement>();
        var watcher = Watcher(adapters, new RosterStateStore(null), new FakeFaceTransfer(), published);
        await watcher.ScanDeviceAsync("dev-1", null, faceSweep: false, CancellationToken.None);

        adapter.DisableUser("6001");
        Assert.Equal(1, await watcher.ScanDeviceAsync("dev-1", null, faceSweep: false, CancellationToken.None));
        var freeze = published[^1];
        Assert.True(freeze.GetProperty("frozenChanged").GetBoolean());
        Assert.False(freeze.GetProperty("nameChanged").GetBoolean());
        Assert.False(freeze.GetProperty("validityChanged").GetBoolean());
        Assert.False(freeze.GetProperty("deleted").GetBoolean());

        adapter.DeleteUser("6002");
        Assert.Equal(1, await watcher.ScanDeviceAsync("dev-1", null, faceSweep: false, CancellationToken.None));
        Assert.Equal("6002", published[^1].GetProperty("deviceUserId").GetString());
        Assert.True(published[^1].GetProperty("deleted").GetBoolean());

        // Reported once; the user is no longer in the roster.
        Assert.Equal(0, await watcher.ScanDeviceAsync("dev-1", null, faceSweep: false, CancellationToken.None));
    }

    [Fact]
    public async Task Mass_disappearance_is_not_reported_as_deletions()
    {
        var (adapter, adapters) = Device();
        for (var i = 0; i < 10; i++)
        {
            adapter.SimulateLocalUserChange($"70{i:D2}", $"User {i}", null, emitEvent: false);
        }

        var published = new List<JsonElement>();
        var watcher = Watcher(adapters, new RosterStateStore(null), new FakeFaceTransfer(), published);
        await watcher.ScanDeviceAsync("dev-1", null, faceSweep: false, CancellationToken.None);

        for (var i = 0; i < 7; i++)
        {
            adapter.DeleteUser($"70{i:D2}");
        }

        Assert.Equal(0, await watcher.ScanDeviceAsync("dev-1", null, faceSweep: false, CancellationToken.None));
        Assert.Empty(published);
    }

    private static (MockDeviceAdapter Adapter, Dictionary<string, IDeviceAdapter> Adapters) Device()
    {
        var adapter = new MockDeviceAdapter();
        adapter.Connect(new DeviceConnectionConfig("dev-1", "127.0.0.1", 37777, "admin", "x"));
        return (adapter, new Dictionary<string, IDeviceAdapter> { ["dev-1"] = adapter });
    }

    private static DeviceChangeWatcher Watcher(
        IReadOnlyDictionary<string, IDeviceAdapter> adapters,
        RosterStateStore roster,
        IFaceTransfer faces,
        List<JsonElement> published,
        DeviceLocks? locks = null) =>
        new(adapters, roster, locks ?? new DeviceLocks(), faces,
            (_, payload) =>
            {
                published.Add(JsonSerializer.SerializeToElement(payload));
                return Task.CompletedTask;
            },
            NullLogger.Instance);

    private static GatewayEnvelope UpsertFace(string userId, string sha) =>
        Command("UPSERT_FACE", new { deviceUserId = userId, memberId = "m-1", faceVersion = 3, sha256 = sha });

    private static GatewayEnvelope Command(string type, object payload) =>
        new()
        {
            Type = type,
            DeviceId = "dev-1",
            CorrelationId = Guid.NewGuid().ToString(),
            Payload = JsonSerializer.SerializeToElement(payload)
        };

    private sealed class FakeFaceTransfer : IFaceTransfer
    {
        public byte[]? Download { get; init; }
        public bool FailUploads { get; set; }
        public bool RejectUploads { get; set; }
        public int Attempts { get; private set; }
        public List<byte[]> Uploaded { get; } = [];

        public Task<FaceDownload> DownloadFaceAsync(string memberId, int version, CancellationToken cancellationToken) =>
            Task.FromResult(Download == null
                ? new FaceDownload(false, null, "not found")
                : new FaceDownload(true, Download, null));

        public Task<FaceUpload> UploadFaceAsync(byte[] jpegBytes, CancellationToken cancellationToken)
        {
            Attempts++;
            if (RejectUploads)
            {
                return Task.FromResult(new FaceUpload(null, Rejected: true));
            }

            if (FailUploads)
            {
                return Task.FromResult(FaceUpload.Transient);
            }

            Uploaded.Add(jpegBytes);
            return Task.FromResult(new FaceUpload("upload-" + Uploaded.Count));
        }
    }
}
