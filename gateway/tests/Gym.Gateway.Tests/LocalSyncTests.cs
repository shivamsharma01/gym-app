using System.Text.Json;
using Gym.Gateway.Adapters;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

namespace Gym.Gateway.Tests;

/// <summary>
/// Devices on one gateway stay in sync through the gateway's local state, with or without the
/// server; server commands and device edits are merged with "latest change wins".
/// </summary>
public class LocalSyncTests
{
    private static readonly byte[] PhotoA = [0xFF, 0xD8, 0xFF, 0xE0, 0x01, 0x02, 0x03];
    private static readonly byte[] PhotoB = [0xFF, 0xD8, 0xFF, 0xE0, 0x09, 0x08, 0x07];

    [Fact]
    public async Task User_enrolled_offline_on_entrance_reaches_exit_immediately_and_server_later()
    {
        var gw = await Gateway.StartAsync();
        gw.Faces.FailUploads = true; // no internet

        gw.Entrance.SimulateLocalUserChange("9001", "Walk In", PhotoA, emitEvent: false);
        Assert.Equal(0, await gw.Scan("entrance"));

        var onExit = gw.Exit.GetUser("9001");
        Assert.NotNull(onExit);
        Assert.Equal("Walk In", onExit!.Name);
        Assert.Equal(PhotoA, gw.Exit.GetFace("9001").Photo);
        Assert.Empty(gw.Published);

        // The exit's next scan does not report the user again (it was written by the gateway).
        Assert.Equal(0, await gw.Scan("exit"));

        gw.Faces.FailUploads = false; // internet back
        Assert.Equal(1, await gw.Scan("entrance"));
        var report = Assert.Single(gw.Published);
        Assert.Equal("entrance", report.DeviceId);
        Assert.True(report.Payload.GetProperty("isNew").GetBoolean());
        Assert.Equal("upload-1", report.Payload.GetProperty("faceUploadId").GetString());
    }

    [Fact]
    public async Task Edits_on_either_device_are_copied_to_the_other()
    {
        var gw = await Gateway.StartAsync(("1001", "Asha"), ("2000", "Other"));

        gw.Exit.SimulateLocalUserChange("1001", "Asha Rao", emitEvent: false);
        await gw.Scan("exit");
        Assert.Equal("Asha Rao", gw.Entrance.GetUser("1001")!.Name);

        gw.Entrance.DisableUser("1001");
        await gw.Scan("entrance");
        Assert.True(gw.Exit.GetUser("1001")!.Frozen);

        gw.Entrance.SimulateLocalUserChange("1001", null, PhotoB, emitEvent: false);
        await gw.Watcher.ScanDeviceAsync("entrance", ["1001"], faceSweep: false, CancellationToken.None);
        Assert.Equal(PhotoB, gw.Exit.GetFace("1001").Photo);

        gw.Exit.DeleteUser("1001");
        await gw.Scan("exit");
        Assert.Null(gw.Entrance.GetUser("1001"));

        Assert.Equal(4, gw.Published.Count);
        Assert.True(gw.Published[^1].Payload.GetProperty("deleted").GetBoolean());
    }

    [Fact]
    public async Task Server_command_older_than_a_device_edit_is_skipped_and_newer_one_applies_everywhere()
    {
        var gw = await Gateway.StartAsync(("1002", "Kiran"));
        gw.Entrance.SimulateLocalUserChange("1002", "Kiran Device", emitEvent: false);
        await gw.Scan("entrance");

        var stale = await gw.Dispatcher.DispatchAsync(Command("UPDATE_USER", "entrance", new
        {
            deviceUserId = "1002", name = "Kiran Old Server", nameChangedAt = Iso(DateTimeOffset.UtcNow.AddHours(-1))
        }));
        Assert.True(stale.Ok);
        Assert.Contains("\"skipped\":true", JsonSerializer.Serialize(stale.Payload));
        Assert.Equal("Kiran Device", gw.Entrance.GetUser("1002")!.Name);
        Assert.Equal("Kiran Device", gw.Exit.GetUser("1002")!.Name);

        var newer = await gw.Dispatcher.DispatchAsync(Command("UPDATE_USER", "entrance", new
        {
            deviceUserId = "1002", name = "Kiran Server", nameChangedAt = Iso(DateTimeOffset.UtcNow.AddMinutes(1))
        }));
        Assert.True(newer.Ok);
        Assert.DoesNotContain("skipped", JsonSerializer.Serialize(newer.Payload));
        Assert.Equal("Kiran Server", gw.Entrance.GetUser("1002")!.Name);
        Assert.Equal("Kiran Server", gw.Exit.GetUser("1002")!.Name);

        // The same command for the exit is now a no-op success.
        var exitCopy = await gw.Dispatcher.DispatchAsync(Command("UPDATE_USER", "exit", new
        {
            deviceUserId = "1002", name = "Kiran Server", nameChangedAt = Iso(DateTimeOffset.UtcNow.AddMinutes(1))
        }));
        Assert.True(exitCopy.Ok);

        // Gateway writes are never reported back as device edits.
        var before = gw.Published.Count;
        await gw.Scan("entrance");
        await gw.Scan("exit");
        Assert.Equal(before, gw.Published.Count);
    }

    [Fact]
    public async Task Undetected_device_edit_is_merged_before_a_server_write_not_overwritten()
    {
        var gw = await Gateway.StartAsync(("1003", "Meera"));
        gw.Exit.SimulateLocalUserChange("1003", "Meera Local", emitEvent: false); // not scanned yet

        var result = await gw.Dispatcher.DispatchAsync(Command("UPDATE_VALIDITY", "exit", new
        {
            deviceUserId = "1003", enabled = true, validFrom = "2026-01-01", validTo = "2026-12-31",
            accessChangedAt = Iso(DateTimeOffset.UtcNow.AddMinutes(-5))
        }));

        Assert.True(result.Ok);
        var user = gw.Exit.GetUser("1003")!;
        Assert.Equal("Meera Local", user.Name);
        Assert.Equal(new DateTimeOffset(2026, 12, 31, 0, 0, 0, TimeSpan.Zero), user.ValidTo);
        Assert.Equal("Meera Local", gw.Entrance.GetUser("1003")!.Name);
        Assert.Equal(new DateTimeOffset(2026, 12, 31, 0, 0, 0, TimeSpan.Zero), gw.Entrance.GetUser("1003")!.ValidTo);
        Assert.True(Assert.Single(gw.Published).Payload.GetProperty("nameChanged").GetBoolean());
    }

    [Fact]
    public async Task Device_that_was_offline_catches_up_when_it_returns()
    {
        var gw = await Gateway.StartAsync(("1004", "Om"));
        gw.Exit.Disconnect();

        gw.Entrance.SimulateLocalUserChange("1004", "Om Prakash", emitEvent: false);
        gw.Entrance.SimulateLocalUserChange("9002", "New Person", PhotoA, emitEvent: false);
        await gw.Scan("entrance");

        gw.Exit.Connect(Config("exit"));
        await gw.Scan("exit");
        Assert.Equal("Om Prakash", gw.Exit.GetUser("1004")!.Name);
        Assert.Equal("New Person", gw.Exit.GetUser("9002")!.Name);
        Assert.Equal(PhotoA, gw.Exit.GetFace("9002").Photo);
    }

    [Fact]
    public async Task Edit_made_on_a_cut_off_device_counts_from_when_the_gateway_sees_it()
    {
        var gw = await Gateway.StartAsync(("1005", "Nia"), ("2000", "Other"));
        gw.Exit.DeleteUser("1005"); // done on the exit while it is cut off from the gateway
        gw.Exit.Disconnect();

        gw.Entrance.SimulateLocalUserChange("1005", "Nia Renamed", emitEvent: false);
        await gw.Scan("entrance");

        // Devices do not record when a user was edited or deleted, so the deletion is timed when the
        // exit reconnects: it is the later change and removes the user from the entrance as well.
        gw.Exit.Connect(Config("exit"));
        await gw.Scan("exit");
        Assert.Null(gw.Entrance.GetUser("1005"));

        // A server re-create with a newer time wins over that deletion on both devices.
        await gw.Dispatcher.DispatchAsync(Command("CREATE_USER", "entrance", new
        {
            deviceUserId = "1005", name = "Nia", nameChangedAt = Iso(DateTimeOffset.UtcNow.AddMinutes(1))
        }));
        Assert.Equal("Nia", gw.Entrance.GetUser("1005")!.Name);
        Assert.Equal("Nia", gw.Exit.GetUser("1005")!.Name);
    }

    [Fact]
    public async Task Pending_reports_and_local_state_survive_a_gateway_restart()
    {
        var dir = Path.Combine(Path.GetTempPath(), "gym-gw-test-" + Guid.NewGuid().ToString("N"));
        try
        {
            var gw = await Gateway.StartAsync(store: new LocalMemberStore(dir));
            gw.Faces.FailUploads = true;
            gw.Entrance.SimulateLocalUserChange("9003", "Offline", PhotoA, emitEvent: false);
            await gw.Scan("entrance");
            Assert.Empty(gw.Published);

            var restarted = new LocalMemberStore(dir);
            Assert.Single(restarted.Reports());
            Assert.Equal(PhotoA, restarted.GetFace(restarted.Find("9003")!.FaceSha256!));
        }
        finally
        {
            Directory.Delete(dir, recursive: true);
        }
    }

    // --- harness ---------------------------------------------------------------------------------

    private static DeviceConnectionConfig Config(string id) => new(id, "127.0.0.1", 37777, "admin", "x");

    private static string Iso(DateTimeOffset t) => t.UtcDateTime.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'");

    private static GatewayEnvelope Command(string type, string deviceId, object payload) =>
        new()
        {
            Type = type,
            DeviceId = deviceId,
            CorrelationId = Guid.NewGuid().ToString(),
            Payload = JsonSerializer.SerializeToElement(payload)
        };

    private sealed record Report(string DeviceId, JsonElement Payload);

    private sealed class Gateway
    {
        public required MockDeviceAdapter Entrance { get; init; }
        public required MockDeviceAdapter Exit { get; init; }
        public required DeviceChangeWatcher Watcher { get; init; }
        public required CommandDispatcher Dispatcher { get; init; }
        public required FakeFaceTransfer Faces { get; init; }
        public required List<Report> Published { get; init; }

        public Task<int> Scan(string deviceId) =>
            Watcher.ScanDeviceAsync(deviceId, null, faceSweep: true, CancellationToken.None);

        public static Task<Gateway> StartAsync(params (string Id, string Name)[] existing) =>
            StartAsync(null, existing);

        public static async Task<Gateway> StartAsync(LocalMemberStore? store, params (string Id, string Name)[] existing)
        {
            var entrance = new MockDeviceAdapter();
            var exit = new MockDeviceAdapter();
            entrance.Connect(Config("entrance"));
            exit.Connect(Config("exit"));
            foreach (var (id, name) in existing)
            {
                entrance.SimulateLocalUserChange(id, name, emitEvent: false);
                exit.SimulateLocalUserChange(id, name, emitEvent: false);
            }

            var adapters = new Dictionary<string, IDeviceAdapter> { ["entrance"] = entrance, ["exit"] = exit };
            var roster = new RosterStateStore(null);
            var locks = new DeviceLocks();
            var faces = new FakeFaceTransfer();
            var published = new List<Report>();
            var watcher = new DeviceChangeWatcher(adapters, roster, locks, faces,
                (deviceId, payload) =>
                {
                    published.Add(new Report(deviceId, JsonSerializer.SerializeToElement(payload)));
                    return Task.CompletedTask;
                },
                NullLogger.Instance, store: store);
            var dispatcher = new CommandDispatcher(adapters, NullLogger<CommandDispatcher>.Instance, faces, roster,
                locks, memberSync: watcher);
            var gateway = new Gateway
            {
                Entrance = entrance, Exit = exit, Watcher = watcher, Dispatcher = dispatcher, Faces = faces,
                Published = published
            };
            await gateway.Scan("entrance"); // baselines
            await gateway.Scan("exit");
            published.Clear();
            return gateway;
        }
    }

    private sealed class FakeFaceTransfer : IFaceTransfer
    {
        public bool FailUploads { get; set; }
        public List<byte[]> Uploaded { get; } = [];

        public Task<FaceDownload> DownloadFaceAsync(string memberId, int version, CancellationToken cancellationToken) =>
            Task.FromResult(new FaceDownload(false, null, "not used"));

        public Task<FaceUpload> UploadFaceAsync(byte[] jpegBytes, CancellationToken cancellationToken)
        {
            if (FailUploads)
            {
                return Task.FromResult(FaceUpload.Transient);
            }

            Uploaded.Add(jpegBytes);
            return Task.FromResult(new FaceUpload("upload-" + Uploaded.Count));
        }
    }
}
