using Gym.Gateway;
using Gym.Gateway.Adapters;
using Gym.Gateway.Execution;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

namespace Gym.Gateway.Tests;

/// <summary>
/// A scan reads the reader's photo checksums instead of every photo, and tells the server only what
/// changed since the list it last acknowledged.
/// </summary>
public sealed class ChangeOnlyScanTests : IDisposable
{
    private static readonly DateTimeOffset ValidFrom = new(2026, 10, 8, 0, 0, 0, TimeSpan.FromHours(5.5));
    private static readonly DateTimeOffset ValidTo = new(2026, 10, 8, 23, 59, 59, TimeSpan.FromHours(5.5));
    private static readonly byte[] PhotoA = [0xFF, 0xD8, 0x0A];
    private static readonly byte[] PhotoB = [0xFF, 0xD8, 0x0B];
    private readonly string _directory = Path.Combine(Path.GetTempPath(), "gym-scan-" + Guid.NewGuid().ToString("N"));

    public ChangeOnlyScanTests()
    {
        Directory.CreateDirectory(_directory);
    }

    public void Dispose()
    {
        try
        {
            Directory.Delete(_directory, recursive: true);
        }
        catch (IOException)
        {
            // Best effort.
        }
    }


    [Fact]
    public async Task A_photo_is_downloaded_once_while_its_reader_checksum_stays_the_same()
    {
        var reader = ReaderWithPhoto();
        var sink = new PendingEnrollmentTests.RecordingUpload();
        using var worker = Worker("once");
        var path = Scanner(worker, reader, sink);

        for (var scan = 0; scan < 3; scan++)
        {
            await path.ReconnectAsync(CancellationToken.None);
        }

        Assert.Equal(1, Downloads(reader));
        Assert.Equal(3, reader.Calls.Count(call => call == "ListFaceChecksums"));
        Assert.All(sink.RosterHashes, hashes =>
        {
            Assert.Equal(FaceHash.Sha256Hex(PhotoA), hashes["7"]);
            Assert.False(hashes.ContainsKey("8"));
        });
    }

    [Fact]
    public async Task A_changed_photo_is_downloaded_again_and_reported_with_its_new_hash()
    {
        var reader = ReaderWithPhoto();
        var sink = new PendingEnrollmentTests.RecordingUpload();
        using var worker = Worker("changed");
        var path = Scanner(worker, reader, sink);

        await path.ReconnectAsync(CancellationToken.None);
        reader.UpdateFace("7", PhotoB);
        await path.ReconnectAsync(CancellationToken.None);
        await path.ReconnectAsync(CancellationToken.None);

        Assert.Equal(2, Downloads(reader));
        Assert.Equal(FaceHash.Sha256Hex(PhotoB), sink.RosterHashes[^1]["7"]);
    }

    [Fact]
    public async Task Two_users_sharing_a_reader_checksum_keep_distinct_hashes_when_photos_differ()
    {
        var reader = new FakeReader();
        reader.CreateUser(Person("57"));
        reader.CreateUser(Person("58"));
        reader.InsertFace("57", PhotoA);
        reader.InsertFace("58", PhotoB);
        reader.ScriptFaceChecksums(new Dictionary<string, string>
        {
            ["57"] = "same-md5",
            ["58"] = "same-md5",
        });
        var sink = new PendingEnrollmentTests.RecordingUpload();
        using var worker = Worker("shared-md5");

        await Scanner(worker, reader, sink).ReconnectAsync(CancellationToken.None);

        Assert.Equal(FaceHash.Sha256Hex(PhotoA), sink.RosterHashes[0]["57"]);
        Assert.Equal(FaceHash.Sha256Hex(PhotoB), sink.RosterHashes[0]["58"]);
        Assert.NotEqual(sink.RosterHashes[0]["57"], sink.RosterHashes[0]["58"]);
        Assert.Equal(2, Downloads(reader));
    }

    [Fact]
    public async Task Without_the_checksum_list_every_photo_is_downloaded_and_the_hashes_are_the_same()
    {
        var listed = ReaderWithPhoto();
        var unlisted = ReaderWithPhoto();
        unlisted.ScriptFailure(FakeReaderOperation.ListFaceChecksums);
        unlisted.ScriptFailure(FakeReaderOperation.ListFaceChecksums);
        var withList = new PendingEnrollmentTests.RecordingUpload();
        var withoutList = new PendingEnrollmentTests.RecordingUpload();
        using var listedWorker = Worker("listed");
        using var unlistedWorker = Worker("unlisted");

        for (var scan = 0; scan < 2; scan++)
        {
            await Scanner(listedWorker, listed, withList).ReconnectAsync(CancellationToken.None);
        }

        var fallback = Scanner(unlistedWorker, unlisted, withoutList);
        for (var scan = 0; scan < 2; scan++)
        {
            await fallback.ReconnectAsync(CancellationToken.None);
        }

        Assert.Equal(4, Downloads(unlisted));
        Assert.Equal(withList.RosterHashes, withoutList.RosterHashes);
        Assert.Equal(withList.FaceHashes, withoutList.FaceHashes);
    }

    [Fact]
    public async Task A_failed_photo_read_is_reported_without_a_hash_and_read_again_next_scan()
    {
        var reader = ReaderWithPhoto();
        reader.ScriptFailure(FakeReaderOperation.GetFace);
        var sink = new PendingEnrollmentTests.RecordingUpload();
        using var worker = Worker("retry");
        var path = Scanner(worker, reader, sink);

        await path.ReconnectAsync(CancellationToken.None);
        await path.ReconnectAsync(CancellationToken.None);
        await path.ReconnectAsync(CancellationToken.None);

        Assert.False(sink.RosterHashes[0].ContainsKey("7"));
        Assert.Equal(FaceHash.Sha256Hex(PhotoA), sink.RosterHashes[1]["7"]);
        Assert.Equal(2, Downloads(reader));
    }

    [Fact]
    public async Task The_photo_hashes_survive_a_gateway_restart()
    {
        var reader = ReaderWithPhoto();
        var file = System.IO.Path.Combine(_directory, "reader-a.faces.json");
        var sink = new PendingEnrollmentTests.RecordingUpload();
        using (var worker = Worker("restart"))
        {
            await Scanner(worker, reader, sink, Memory(file)).ReconnectAsync(CancellationToken.None);
        }

        using var restarted = Worker("restart");
        await Scanner(restarted, reader, sink, Memory(file)).ReconnectAsync(CancellationToken.None);

        Assert.Equal(1, Downloads(reader));
        Assert.Equal(sink.RosterHashes[0], sink.RosterHashes[1]);
    }

    [Fact]
    public async Task An_unreadable_photo_cache_starts_empty()
    {
        var reader = ReaderWithPhoto();
        var file = System.IO.Path.Combine(_directory, "broken.faces.json");
        await File.WriteAllTextAsync(file, "{ not json");
        var sink = new PendingEnrollmentTests.RecordingUpload();
        using var worker = Worker("broken");

        await Scanner(worker, reader, sink, Memory(file)).ReconnectAsync(CancellationToken.None);

        Assert.Equal(1, Downloads(reader));
        Assert.Equal(FaceHash.Sha256Hex(PhotoA), sink.RosterHashes[0]["7"]);
    }

    [Fact]
    public void The_production_reader_chain_passes_the_checksum_list_through()
    {
        using var device = new MockDeviceAdapter();
        device.Connect(new DeviceConnectionConfig("reader-a", "127.0.0.1", 37777, "admin", "secret", null));
        device.CreateUser(new DeviceUserMutation("7", "Walk In", true, ValidFrom, ValidTo, "Customer", null));
        device.UpsertFace("7", PhotoA);
        var chain = new LockedReaderAdapter(
            "reader-a",
            new DeviceReaderAdapter(new TimedDeviceAdapter(device, NullLogger.Instance)),
            new DeviceLocks());

        var list = chain.ListFaceChecksums();

        Assert.True(list.Ok);
        Assert.Equal(PhotoMd5.Hex(PhotoA), Assert.Single(list.ByUserId).Value);
    }


    [Fact]
    public async Task An_acknowledged_list_is_not_sent_again_while_nothing_changes()
    {
        var reader = ReaderWithPhoto();
        var sink = new PendingEnrollmentTests.RecordingUpload { RosterAccepted = true };
        using var worker = Worker("quiet");
        var path = Scanner(worker, reader, sink);

        for (var scan = 0; scan < 4; scan++)
        {
            await path.BootstrapAsync(CancellationToken.None);
        }

        Assert.Equal(new[] { "list-awaited" }, sink.Calls);
        Assert.Equal(1, Downloads(reader));
    }

    [Fact]
    public async Task A_rename_a_new_photo_or_a_new_absence_sends_the_list_again()
    {
        var reader = ReaderWithPhoto();
        var sink = new PendingEnrollmentTests.RecordingUpload { RosterAccepted = true };
        using var worker = Worker("changes");
        var path = Scanner(worker, reader, sink);
        await path.BootstrapAsync(CancellationToken.None);

        reader.ReplaceUser(Person("8") with { Name = "Renamed" });
        await path.BootstrapAsync(CancellationToken.None);
        reader.UpdateFace("7", PhotoB);
        await path.BootstrapAsync(CancellationToken.None);
        Assert.Equal(MemberApplyKind.Applied, worker.ApplyMember(new DesiredMember(
            1, "1", "Asha Shah", null, 0, ValidFrom, ValidTo, "Customer", 1, 1, PhotoA)).Kind);
        await path.BootstrapAsync(CancellationToken.None);
        await path.BootstrapAsync(CancellationToken.None);

        Assert.Equal(
            new[] { "list-awaited", "list-awaited", "list-awaited", "absences", "list-awaited" },
            sink.Calls);
        Assert.Equal(new[] { "1" }, sink.AbsentIds);
    }

    [Fact]
    public async Task An_unchanged_list_is_sent_again_once_the_refresh_period_has_passed()
    {
        var reader = ReaderWithPhoto();
        var clock = new ManualClock();
        var sink = new PendingEnrollmentTests.RecordingUpload { RosterAccepted = true };
        using var worker = Worker("refresh");
        var path = Scanner(worker, reader, sink, ReaderScanMemory.InMemory(clock));

        await path.BootstrapAsync(CancellationToken.None);
        clock.Advance(ReaderScanMemory.DefaultRefresh - TimeSpan.FromMinutes(1));
        await path.BootstrapAsync(CancellationToken.None);
        clock.Advance(TimeSpan.FromMinutes(2));
        await path.BootstrapAsync(CancellationToken.None);

        Assert.Equal(new[] { "list-awaited", "list-awaited" }, sink.Calls);
        Assert.Equal(1, Downloads(reader));
    }

    [Fact]
    public async Task An_unsaved_list_is_resent_on_refresh_without_sending_people_one_by_one()
    {
        var reader = ReaderWithPhoto();
        var clock = new ManualClock();
        var sink = new PendingEnrollmentTests.RecordingUpload { RosterAccepted = false };
        using var worker = Worker("unacked");
        var path = new DesiredRevisionPath(
            "reader-a", worker, reader, new UnsavedPull(), sink, ReaderScanMemory.InMemory(clock));

        await path.BootstrapAsync(CancellationToken.None);
        await path.BootstrapAsync(CancellationToken.None);
        reader.ReplaceUser(Person("8") with { Name = "Renamed" });
        await path.BootstrapAsync(CancellationToken.None);
        clock.Advance(ReaderScanMemory.DefaultRefresh);
        await path.BootstrapAsync(CancellationToken.None);

        Assert.Equal(
            new[] { "list-awaited", "list-awaited", "list-awaited", "list-awaited" },
            sink.Calls);
        Assert.Empty(sink.Ids);
        Assert.Equal(ObservationGate.Fenced, path.Gate);
    }

    [Fact]
    public async Task Users_whose_upload_failed_are_sent_on_the_next_scan()
    {
        var reader = ReaderWithPhoto();
        var sink = new PendingEnrollmentTests.RecordingUpload { FailList = true, FailUsersOnce = true };
        using var worker = Worker("failed-users");
        var path = Scanner(worker, reader, sink);

        await path.BootstrapAsync(CancellationToken.None);
        await path.BootstrapAsync(CancellationToken.None);

        Assert.Equal(new[] { "7", "8" }, sink.Ids);
    }

    [Fact]
    public async Task An_acknowledged_list_is_sent_again_after_a_reconnect()
    {
        var reader = ReaderWithPhoto();
        var sink = new PendingEnrollmentTests.RecordingUpload { RosterAccepted = true };
        using var worker = Worker("reconnect");
        var path = Scanner(worker, reader, sink);

        await path.BootstrapAsync(CancellationToken.None);
        await path.ReconnectAsync(CancellationToken.None);
        await path.BootstrapAsync(CancellationToken.None);
        await path.BootstrapAsync(CancellationToken.None);

        Assert.Equal(new[] { "list-awaited", "users", "list", "list-awaited" }, sink.Calls);
    }

    [Fact]
    public async Task An_acknowledgement_for_a_scan_that_began_before_a_reconnect_is_not_recorded()
    {
        var reader = ReaderWithPhoto();
        var sink = new HeldUpload();
        using var worker = Worker("race");
        var path = Scanner(worker, reader, sink);

        var scan = path.BootstrapAsync(CancellationToken.None);
        await sink.Waiting.Task;
        await path.ReconnectAsync(CancellationToken.None);
        sink.Release.SetResult(true);
        await scan;
        await path.BootstrapAsync(CancellationToken.None);

        Assert.Equal(new[] { "list-awaited", "users", "list", "list-awaited" }, sink.Calls);
    }

    [Fact]
    public async Task A_requested_scan_sends_the_list_even_when_it_was_acknowledged()
    {
        var reader = ReaderWithPhoto();
        var sink = new PendingEnrollmentTests.RecordingUpload { RosterAccepted = true };
        using var worker = Worker("scan-now");
        var path = Scanner(worker, reader, sink);

        await path.BootstrapAsync(CancellationToken.None);
        Assert.True(await path.ScanNowAsync(CancellationToken.None));
        await path.BootstrapAsync(CancellationToken.None);

        Assert.Equal(new[] { "list-awaited", "list-awaited" }, sink.Calls);
        Assert.Equal(1, Downloads(reader));
    }

    [Fact]
    public async Task A_requested_scan_of_a_short_list_uploads_nothing_and_says_so()
    {
        var reader = ReaderWithPhoto();
        reader.ScriptList(3, Person("7"));
        var sink = new PendingEnrollmentTests.RecordingUpload { RosterAccepted = true };
        using var worker = Worker("scan-now-short");

        Assert.False(await Scanner(worker, reader, sink).ScanNowAsync(CancellationToken.None));
        Assert.Empty(sink.Calls);
    }

    [Fact]
    public async Task A_short_list_is_still_never_uploaded()
    {
        var reader = ReaderWithPhoto();
        reader.ScriptList(3, Person("7"));
        var sink = new PendingEnrollmentTests.RecordingUpload { RosterAccepted = true };
        using var worker = Worker("short");
        var path = Scanner(worker, reader, sink);

        await path.BootstrapAsync(CancellationToken.None);

        Assert.Empty(sink.Calls);
        Assert.Equal(0, Downloads(reader));
    }

    private DesiredRevisionPath Scanner(
        ReaderWorker worker, FakeReader reader, IReaderObservationUpload sink, ReaderScanMemory? memory = null) =>
        new("reader-a", worker, reader, new PendingEnrollmentTests.EmptyPull(), sink, memory);

    /// <summary>The observation endpoint says the list is not saved yet.</summary>
    private sealed class UnsavedPull : IDesiredStateClient
    {
        public Task<DesiredPull> PullAsync(string deviceId, long after, CancellationToken cancellationToken) =>
            Task.FromResult(new DesiredPull(0, after, []));

        public Task AcknowledgeAsync(DesiredAcknowledgement ack, CancellationToken cancellationToken) =>
            Task.CompletedTask;

        public Task ReportOccupiedAsync(
            string deviceId, long revision, string deviceUserId, CancellationToken cancellationToken) =>
            Task.CompletedTask;

        public Task<bool> ObservationProcessedAsync(string deviceId, IReadOnlyList<string> messageIds,
            CancellationToken cancellationToken) => Task.FromResult(false);

        public Task ReportFailedAsync(string deviceId, RetryState attempt, CancellationToken cancellationToken) =>
            Task.CompletedTask;

        public Task ReportOccupantAsync(string deviceId, long revision, string deviceUserId, ReaderOccupant occupant,
            CancellationToken cancellationToken) =>
            throw new InvalidOperationException("Unexpected occupant at " + deviceUserId);
    }

    private static ReaderScanMemory Memory(string file) =>
        new(new FaceChecksumCache(file), new ScanUploadState(ReaderScanMemory.DefaultRefresh));

    private ReaderWorker Worker(string name) =>
        new("reader-a", new FakeReader(), System.IO.Path.Combine(_directory, name + ".sqlite"));

    private static FakeReader ReaderWithPhoto()
    {
        var reader = new FakeReader();
        reader.CreateUser(Person("7"));
        reader.CreateUser(Person("8"));
        reader.InsertFace("7", PhotoA);
        return reader;
    }

    private static int Downloads(FakeReader reader) => reader.Calls.Count(call => call == "GetFace");

    private static ReaderUser Person(string id) => new(id, "Person " + id, null, 0, ValidFrom, ValidTo, "Customer", 1, 1);

    private sealed class ManualClock : TimeProvider
    {
        private DateTimeOffset _now = new(2026, 10, 14, 6, 0, 0, TimeSpan.Zero);

        public override DateTimeOffset GetUtcNow() => _now;

        public void Advance(TimeSpan by) => _now += by;
    }

    /// <summary>Holds the first awaited list until released, so a reconnect can run in between.</summary>
    private sealed class HeldUpload : IReaderObservationUpload
    {
        public List<string> Calls { get; } = [];

        public TaskCompletionSource Waiting { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);

        public TaskCompletionSource<bool> Release { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);

        private bool _held;

        public Task<IReadOnlyList<string>> UploadAsync(
            string deviceId,
            ScanStamp scan,
            IReadOnlyList<ReaderUser> users,
            IReadOnlyDictionary<string, string> faceHashes,
            CancellationToken cancellationToken)
        {
            Calls.Add("users");
            return Task.FromResult<IReadOnlyList<string>>(users.Select(user => "user-" + user.DeviceUserId).ToList());
        }

        public async Task<RosterUpload> UploadTrustedRosterAsync(
            string deviceId,
            ScanStamp scan,
            int announcedTotal,
            IReadOnlyList<ReaderUser> users,
            IReadOnlyDictionary<string, string> faceHashes,
            bool awaitAcceptance,
            CancellationToken cancellationToken)
        {
            Calls.Add(awaitAcceptance ? "list-awaited" : "list");
            var id = "list-" + Calls.Count;
            if (!awaitAcceptance || _held)
            {
                return new RosterUpload(awaitAcceptance, id);
            }

            _held = true;
            Waiting.SetResult();
            return new RosterUpload(await Release.Task, id);
        }

        public Task<IReadOnlyList<string>> UploadAbsencesAsync(
            string deviceId, ScanStamp scan, IReadOnlyList<string> deviceUserIds, CancellationToken cancellationToken)
        {
            Calls.Add("absences");
            return Task.FromResult<IReadOnlyList<string>>(deviceUserIds.Select(id => "absence-" + id).ToList());
        }
    }
}
