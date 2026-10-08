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
    public async Task A_flagged_reader_is_not_written_by_the_watcher_loop()
    {
        var gw = await Gateway.StartAsync();
        gw.Faces.FailUploads = true;
        gw.Watcher.DesiredWorkersExecute(["exit"]);

        gw.Entrance.SimulateLocalUserChange("9001", "Walk In", PhotoA, emitEvent: false);
        await gw.Scan("entrance");

        Assert.Null(gw.Exit.GetUser("9001"));
        await gw.Scan("exit");
        Assert.Null(gw.Exit.GetUser("9001"));
    }

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

        var newerAt = Iso(DateTimeOffset.UtcNow.AddMinutes(1));
        var newer = await gw.Dispatcher.DispatchAsync(Command("UPDATE_USER", "entrance", new
        {
            deviceUserId = "1002", name = "Kiran Server", nameChangedAt = newerAt
        }));
        Assert.True(newer.Ok);
        Assert.DoesNotContain("skipped", JsonSerializer.Serialize(newer.Payload));
        Assert.Equal("Kiran Server", gw.Entrance.GetUser("1002")!.Name);
        Assert.Equal("Kiran Server", gw.Exit.GetUser("1002")!.Name);

        // The same command for the exit was already fanned out: a plain success, not "skipped", so the
        // server marks the exit as holding it.
        var exitCopy = await gw.Dispatcher.DispatchAsync(Command("UPDATE_USER", "exit", new
        {
            deviceUserId = "1002", name = "Kiran Server", nameChangedAt = newerAt
        }));
        Assert.True(exitCopy.Ok);
        Assert.DoesNotContain("skipped", JsonSerializer.Serialize(exitCopy.Payload));

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

    [Fact]
    public async Task Admin_level_set_in_the_app_reaches_both_readers()
    {
        var gw = await Gateway.StartAsync(("1101", "Rajat"));

        var result = await gw.Dispatcher.DispatchAsync(Command("UPDATE_USER", "entrance", new
        {
            deviceUserId = "1101", name = "Rajat", authority = "ADMIN", nameChangedAt = Iso(DateTimeOffset.UtcNow.AddMinutes(1))
        }));

        Assert.True(result.Ok);
        Assert.DoesNotContain("skipped", JsonSerializer.Serialize(result.Payload));
        Assert.Equal("ADMIN", gw.Entrance.GetUser("1101")!.Authority);
        Assert.Equal("ADMIN", gw.Exit.GetUser("1101")!.Authority);
        Assert.Equal("Rajat", gw.Exit.GetUser("1101")!.Name);

        await gw.Scan("entrance");
        await gw.Scan("exit");
        Assert.Empty(gw.Published);
    }

    [Fact]
    public async Task Admin_level_changed_on_one_reader_is_copied_to_the_other_and_reported()
    {
        var gw = await Gateway.StartAsync(("1102", "Kuldeep"));

        gw.Entrance.SimulateLocalAuthorityChange("1102", "ADMIN");
        await gw.Scan("entrance");

        Assert.Equal("ADMIN", gw.Exit.GetUser("1102")!.Authority);
        Assert.Equal("Kuldeep", gw.Exit.GetUser("1102")!.Name);
        var report = Assert.Single(gw.Published);
        Assert.True(report.Payload.GetProperty("authorityChanged").GetBoolean());
        Assert.Equal("ADMIN", report.Payload.GetProperty("authority").GetString());
        Assert.True(report.Payload.GetProperty("siblingsUpdated").GetBoolean());
        Assert.Equal("exit", Assert.Single(report.Payload.GetProperty("siblingDeviceIds").EnumerateArray()).GetString());
    }

    [Fact]
    public async Task An_older_admin_level_from_the_server_loses_to_a_newer_reader_change()
    {
        var gw = await Gateway.StartAsync(("1103", "Om"));
        gw.Entrance.SimulateLocalAuthorityChange("1103", "ADMIN");
        await gw.Scan("entrance");

        var stale = await gw.Dispatcher.DispatchAsync(Command("UPDATE_USER", "exit", new
        {
            deviceUserId = "1103", name = "Om", authority = "USER", nameChangedAt = Iso(DateTimeOffset.UtcNow.AddHours(-1))
        }));

        Assert.True(stale.Ok);
        Assert.Contains("\"skipped\":true", JsonSerializer.Serialize(stale.Payload));
        Assert.Equal("ADMIN", gw.Entrance.GetUser("1103")!.Authority);
        Assert.Equal("ADMIN", gw.Exit.GetUser("1103")!.Authority);
    }

    [Fact]
    public async Task A_command_without_an_admin_level_never_changes_it_on_the_reader()
    {
        var gw = await Gateway.StartAsync(null, (entrance, exit) =>
        {
            foreach (var reader in new[] { entrance, exit })
            {
                reader.SimulateLocalUserChange("1104", "Annu", emitEvent: false);
                reader.SimulateLocalAuthorityChange("1104", "ADMIN");
            }
        });

        await gw.Dispatcher.DispatchAsync(Command("UPDATE_USER", "entrance", new
        {
            deviceUserId = "1104", name = "Annu Rana", nameChangedAt = Iso(DateTimeOffset.UtcNow.AddMinutes(1))
        }));
        await gw.Dispatcher.DispatchAsync(Command("UPDATE_VALIDITY", "entrance", new
        {
            deviceUserId = "1104", enabled = true, validFrom = "2026-01-01", validTo = "2026-12-31",
            accessChangedAt = Iso(DateTimeOffset.UtcNow.AddMinutes(1))
        }));

        foreach (var reader in new[] { gw.Entrance, gw.Exit })
        {
            var user = reader.GetUser("1104")!;
            Assert.Equal("Annu Rana", user.Name);
            Assert.Equal("ADMIN", user.Authority);
        }
    }

    [Fact]
    public async Task A_user_created_on_the_other_reader_takes_name_and_admin_level_from_the_reader_that_has_it()
    {
        var gw = await Gateway.StartAsync(null, (entrance, _) =>
        {
            entrance.SimulateLocalUserChange("1105", "Tushar Dahiya", emitEvent: false);
            entrance.SimulateLocalAuthorityChange("1105", "ADMIN");
        });

        var result = await gw.Dispatcher.DispatchAsync(Command("UPDATE_VALIDITY", "entrance", new
        {
            deviceUserId = "1105", enabled = true, validFrom = "2026-01-01", validTo = "2026-12-31",
            accessChangedAt = Iso(DateTimeOffset.UtcNow.AddMinutes(1))
        }));

        Assert.True(result.Ok);
        var created = gw.Exit.GetUser("1105")!;
        Assert.Equal("Tushar Dahiya", created.Name);
        Assert.Equal("ADMIN", created.Authority);
        Assert.False(created.Frozen);
        Assert.Equal(new DateTimeOffset(2026, 12, 31, 0, 0, 0, TimeSpan.Zero), created.ValidTo);
    }

    [Fact]
    public async Task A_photo_already_held_locally_is_not_downloaded_again()
    {
        var store = new LocalMemberStore(null);
        store.PutFace(FaceHash.Sha256Hex(PhotoB), PhotoB);
        var gw = await Gateway.StartAsync(store, ("1201", "Neha"));

        var result = await gw.Dispatcher.DispatchAsync(Command("UPSERT_FACE", "entrance", new
        {
            deviceUserId = "1201", memberId = "m-1201", faceVersion = 2, sha256 = FaceHash.Sha256Hex(PhotoB),
            faceChangedAt = Iso(DateTimeOffset.UtcNow.AddMinutes(1))
        }));

        Assert.True(result.Ok, JsonSerializer.Serialize(result.Payload));
        Assert.Equal(PhotoB, gw.Entrance.GetFace("1201").Photo);
        Assert.Equal(PhotoB, gw.Exit.GetFace("1201").Photo);
    }

    [Fact]
    public async Task A_first_photo_read_never_replaces_the_local_photo()
    {
        var store = new LocalMemberStore(null);
        store.PutFace(FaceHash.Sha256Hex(PhotoA), PhotoA);
        store.Merge(new MemberChange("1202", DateTimeOffset.UtcNow.AddMinutes(-5), FromServer: true, "server")
        {
            SetFace = true, FaceSha256 = FaceHash.Sha256Hex(PhotoA)
        });
        var gw = await Gateway.StartAsync(store, (entrance, exit) =>
        {
            entrance.SimulateLocalUserChange("1202", "Pooja", PhotoA, emitEvent: false);
            exit.SimulateLocalUserChange("1202", "Pooja", PhotoB, emitEvent: false);
        });

        await gw.Scan("entrance");
        await gw.Scan("exit");
        await gw.Scan("exit");

        Assert.Empty(gw.Published);
        Assert.Equal(PhotoA, gw.Entrance.GetFace("1202").Photo);
        Assert.Equal(PhotoA, gw.Exit.GetFace("1202").Photo);
        Assert.Equal(FaceHash.Sha256Hex(PhotoA), store.Find("1202")!.FaceSha256);
    }

    [Fact]
    public async Task When_the_gateway_is_added_both_readers_end_up_with_the_same_users()
    {
        var gw = await Gateway.StartAsync(null, (entrance, exit) =>
        {
            entrance.SimulateLocalUserChange("1301", "Only Entrance", PhotoA, emitEvent: false);
            entrance.SimulateLocalAuthorityChange("1301", "ADMIN");
            exit.SimulateLocalUserChange("1302", "Only Exit", emitEvent: false);
            entrance.SimulateLocalUserChange("1303", "Same", emitEvent: false);
            exit.SimulateLocalUserChange("1303", "Same", emitEvent: false);
            entrance.SimulateLocalUserChange("1304", "Ravi Kumar", emitEvent: false);
            exit.SimulateLocalUserChange("1304", "1304", emitEvent: false);
        });

        await gw.Scan("entrance");
        await gw.Scan("exit");
        await gw.Scan("entrance");

        foreach (var id in new[] { "1301", "1302", "1303", "1304" })
        {
            var a = gw.Entrance.GetUser(id)!;
            var b = gw.Exit.GetUser(id)!;
            Assert.Equal(a.Name, b.Name);
            Assert.Equal(a.Authority, b.Authority);
            Assert.Equal(a.Frozen, b.Frozen);
        }

        Assert.Equal("ADMIN", gw.Exit.GetUser("1301")!.Authority);
        Assert.Equal("Ravi Kumar", gw.Exit.GetUser("1304")!.Name);
        Assert.Equal(PhotoA, gw.Exit.GetFace("1301").Photo);
        Assert.DoesNotContain(gw.Published, r => r.Payload.GetProperty("isNew").GetBoolean());
    }

    [Fact]
    public async Task A_server_change_wins_over_a_copy_made_when_the_gateway_was_added()
    {
        var gw = await Gateway.StartAsync(null, (entrance, _) =>
            entrance.SimulateLocalUserChange("1305", "Old Name", emitEvent: false));

        await gw.Dispatcher.DispatchAsync(Command("UPDATE_USER", "entrance", new
        {
            deviceUserId = "1305", name = "Server Name", nameChangedAt = Iso(DateTimeOffset.UtcNow.AddDays(-30))
        }));

        Assert.Equal("Server Name", gw.Entrance.GetUser("1305")!.Name);
        Assert.Equal("Server Name", gw.Exit.GetUser("1305")!.Name);
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
        public required RosterStateStore Roster { get; init; }

        public Task<int> Scan(string deviceId) =>
            Watcher.ScanDeviceAsync(deviceId, null, faceSweep: true, CancellationToken.None);

        public static Task<Gateway> StartAsync(params (string Id, string Name)[] existing) =>
            StartAsync(null, existing);

        public static Task<Gateway> StartAsync(LocalMemberStore? store, params (string Id, string Name)[] existing) =>
            StartAsync(store, (entrance, exit) =>
            {
                foreach (var (id, name) in existing)
                {
                    entrance.SimulateLocalUserChange(id, name, emitEvent: false);
                    exit.SimulateLocalUserChange(id, name, emitEvent: false);
                }
            });

        /// <param name="setup">Fills both readers before the gateway records its first baseline.</param>
        public static async Task<Gateway> StartAsync(LocalMemberStore? store, Action<MockDeviceAdapter, MockDeviceAdapter> setup)
        {
            var entrance = new MockDeviceAdapter();
            var exit = new MockDeviceAdapter();
            entrance.Connect(Config("entrance"));
            exit.Connect(Config("exit"));
            setup(entrance, exit);

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
                Published = published, Roster = roster
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
