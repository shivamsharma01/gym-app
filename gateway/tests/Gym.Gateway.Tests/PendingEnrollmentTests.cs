using Gym.Gateway;
using Gym.Gateway.Adapters;
using Gym.Gateway.Execution;
using Xunit;

namespace Gym.Gateway.Tests;

public class PendingEnrollmentTests : IDisposable
{
    private readonly string _directory = Path.Combine(Path.GetTempPath(), "gym-pending-" + Guid.NewGuid().ToString("N"));
    private static readonly DateTimeOffset ValidFrom = new(2026, 10, 8, 0, 0, 0, TimeSpan.FromHours(5.5));
    private static readonly DateTimeOffset ValidTo = new(2026, 10, 8, 23, 59, 59, TimeSpan.FromHours(5.5));
    private static readonly byte[] Face = [1, 2, 3, 4, 5];

    public PendingEnrollmentTests()
    {
        Directory.CreateDirectory(_directory);
    }

    [Fact]
    public async Task A_new_reader_id_is_uploaded_and_the_sibling_receives_no_write()
    {
        var reader = new FakeReader();
        var sibling = new FakeReader();
        reader.CreateUser(Person("7", "Walk In"));
        var writes = reader.Writes.Count;
        var sink = new RecordingUpload();
        using var worker = new ReaderWorker("reader-a", reader, Path.Combine(_directory, "a.sqlite"));
        var path = new DesiredRevisionPath("reader-a", worker, reader, new EmptyPull(), sink);

        await path.ReconnectAsync(CancellationToken.None);
        await path.ReconnectAsync(CancellationToken.None);

        Assert.Equal(new[] { "7", "7" }, sink.Ids);
        Assert.Equal(new[] { 1, 1 }, sink.TrustedRosterTotals);
        Assert.Equal(new[] { "7", "7" }, sink.RosterIds);
        Assert.Equal(writes, reader.Writes.Count);
        Assert.Empty(sibling.Writes);
        Assert.Empty(sibling.Calls);
    }

    [Fact]
    public async Task A_trusted_scan_uploads_without_pulling_desired_state()
    {
        var reader = new FakeReader();
        var sibling = new FakeReader();
        reader.CreateUser(Person("7", "Walk In"));
        var writes = reader.Writes.Count;
        var sink = new RecordingUpload();
        var client = new EmptyPull();
        using var worker = new ReaderWorker("reader-a", reader, Path.Combine(_directory, "scan.sqlite"));
        var path = new DesiredRevisionPath("reader-a", worker, reader, client, sink);

        await path.ObserveAsync(CancellationToken.None);
        await path.ObserveAsync(CancellationToken.None);

        Assert.Equal(new[] { "7", "7" }, sink.Ids);
        Assert.Equal(0, client.Pulls);
        Assert.Equal(writes, reader.Writes.Count);
        Assert.Empty(sibling.Writes);
        Assert.Empty(sibling.Calls);
    }

    [Fact]
    public async Task Only_the_photos_the_server_asks_for_are_uploaded_even_when_cached()
    {
        var reader = new FakeReader();
        byte[] other = [9, 8, 7];
        reader.CreateUser(Person("7", "Walk In"));
        reader.UpdateFace("7", Face);
        reader.CreateUser(Person("8", "Other"));
        reader.UpdateFace("8", other);
        var writes = reader.Writes.Count;
        var sink = new RecordingUpload();
        using var worker = new ReaderWorker("reader-a", reader, Path.Combine(_directory, "photos.sqlite"));
        var path = new DesiredRevisionPath("reader-a", worker, reader, new EmptyPull(), sink);

        await path.ObserveAsync(CancellationToken.None);
        Assert.Empty(sink.Photos);

        sink.Wanted.Add(FaceHash.Sha256Hex(Face));
        await path.ObserveAsync(CancellationToken.None);
        await path.ObserveAsync(CancellationToken.None);

        Assert.Equal(Face, Assert.Single(sink.Photos));
        Assert.Equal(writes, reader.Writes.Count);
    }

    [Fact]
    public async Task A_renamed_mapped_user_is_uploaded_and_not_written_back()
    {
        var reader = new FakeReader();
        var sibling = new FakeReader();
        using var worker = new ReaderWorker("reader-a", reader, Path.Combine(_directory, "rename.sqlite"));
        Assert.Equal(MemberApplyKind.Applied, worker.ApplyMember(new DesiredMember(
            1, "1", "Asha Shah", null, 0, ValidFrom, ValidTo, "Customer", 1, 1, Face)).Kind);
        var writes = reader.Writes.ToArray();
        var renamed = Person("1", "Asha Reader", "ADMIN");
        reader.ScriptList(1, renamed);
        reader.ScriptList(1, renamed);
        var sink = new RecordingUpload();
        var path = new DesiredRevisionPath("reader-a", worker, reader, new EmptyPull(), sink);

        await path.ObserveAsync(CancellationToken.None);
        await path.ObserveAsync(CancellationToken.None);

        Assert.Equal(new[] { "1", "1" }, sink.Ids);
        Assert.Equal(writes, reader.Writes);
        Assert.Empty(sibling.Writes);
        Assert.Empty(sibling.Calls);
    }

    [Fact]
    public async Task Two_readers_send_both_names_and_neither_writes_the_other()
    {
        var left = new FakeReader();
        var right = new FakeReader();
        left.CreateUser(Person("1", "Left"));
        right.CreateUser(Person("1", "Right"));
        var leftWrites = left.Writes.ToArray();
        var rightWrites = right.Writes.ToArray();
        var sink = new RecordingUpload();
        using var leftWorker = new ReaderWorker("reader-a", left, Path.Combine(_directory, "left.sqlite"));
        using var rightWorker = new ReaderWorker("reader-b", right, Path.Combine(_directory, "right.sqlite"));
        var leftPath = new DesiredRevisionPath("reader-a", leftWorker, left, new EmptyPull(), sink);
        var rightPath = new DesiredRevisionPath("reader-b", rightWorker, right, new EmptyPull(), sink);

        await leftPath.ObserveAsync(CancellationToken.None);
        await rightPath.ObserveAsync(CancellationToken.None);

        Assert.Equal(new[] { "1", "1" }, sink.Ids);
        Assert.Equal(new[] { "Left", "Right" }, sink.Names);
        Assert.Equal(leftWrites, left.Writes);
        Assert.Equal(rightWrites, right.Writes);
        Assert.Equal("Left", left.GetUser("1").User!.Name);
        Assert.Equal("Right", right.GetUser("1").User!.Name);
    }

    [Fact]
    public async Task A_mapped_id_missing_from_a_trusted_list_is_an_absence_and_removes_nobody()
    {
        var reader = new FakeReader();
        var sibling = new FakeReader();
        using var worker = new ReaderWorker("reader-a", reader, Path.Combine(_directory, "absent.sqlite"));
        Assert.Equal(MemberApplyKind.Applied, worker.ApplyMember(new DesiredMember(
            1, "1", "Asha Shah", null, 0, ValidFrom, ValidTo, "Customer", 1, 1, Face)).Kind);
        var writes = reader.Writes.ToArray();
        var stillHere = Person("2", "Still Here");
        reader.ScriptList(1, stillHere);
        reader.ScriptList(1, stillHere);
        var sink = new RecordingUpload();
        var path = new DesiredRevisionPath("reader-a", worker, reader, new EmptyPull(), sink);

        await path.ObserveAsync(CancellationToken.None);
        await path.ObserveAsync(CancellationToken.None);

        Assert.Equal(new[] { "2", "2" }, sink.Ids);
        Assert.Equal(new[] { "1", "1" }, sink.AbsentIds);
        Assert.Equal(writes, reader.Writes);
        Assert.Empty(sibling.Writes);
        Assert.Empty(sibling.Calls);
        Assert.Equal("Asha Shah", reader.GetUser("1").User!.Name);
    }

    [Fact]
    public async Task A_bad_list_emits_nothing_and_the_next_trusted_list_still_does()
    {
        var reader = new FakeReader();
        var sibling = new FakeReader();
        using var worker = new ReaderWorker("reader-a", reader, Path.Combine(_directory, "bad-list.sqlite"));
        Assert.Equal(MemberApplyKind.Applied, worker.ApplyMember(new DesiredMember(
            1, "1", "Asha Shah", null, 0, ValidFrom, ValidTo, "Customer", 1, 1, Face)).Kind);
        var writes = reader.Writes.ToArray();
        var stillHere = Person("2", "Still Here");
        reader.ScriptList(1, stillHere);
        reader.ScriptList(2, stillHere);
        reader.ScriptList(0);
        reader.ScriptList(1, stillHere, Person("3", "Extra"));
        reader.ScriptList(1, stillHere);
        var sink = new RecordingUpload();
        var path = new DesiredRevisionPath("reader-a", worker, reader, new EmptyPull(), sink);

        await path.ObserveAsync(CancellationToken.None);
        Assert.Equal(new[] { "1" }, sink.AbsentIds);

        await path.ObserveAsync(CancellationToken.None);
        Assert.Equal(new[] { "1" }, sink.AbsentIds);
        Assert.Equal(new[] { "2" }, sink.Ids);

        await path.ObserveAsync(CancellationToken.None);
        Assert.Equal(new[] { "1" }, sink.AbsentIds);
        Assert.Equal(new[] { "2" }, sink.Ids);

        await path.ObserveAsync(CancellationToken.None);
        Assert.Equal(new[] { "1" }, sink.AbsentIds);
        Assert.Equal(new[] { "2" }, sink.Ids);

        await path.ObserveAsync(CancellationToken.None);
        Assert.Equal(new[] { "1", "1" }, sink.AbsentIds);
        Assert.Equal(new[] { "2", "2" }, sink.Ids);
        Assert.Equal(writes, reader.Writes);
        Assert.Empty(sibling.Writes);
        Assert.Empty(sibling.Calls);
    }

    [Fact]
    public async Task The_same_face_on_two_ids_is_uploaded_twice()
    {
        var reader = new FakeReader();
        var sibling = new FakeReader();
        var face = new byte[] { 9, 9, 9, 9 };
        reader.CreateUser(Person("11", "Left"));
        reader.CreateUser(Person("12", "Right"));
        reader.InsertFace("11", face);
        reader.InsertFace("12", face);
        var writes = reader.Writes.ToArray();
        var sink = new RecordingUpload();
        using var worker = new ReaderWorker("reader-a", reader, Path.Combine(_directory, "same-face.sqlite"));
        var path = new DesiredRevisionPath("reader-a", worker, reader, new EmptyPull(), sink);

        await path.ObserveAsync(CancellationToken.None);
        await path.ObserveAsync(CancellationToken.None);
        await path.ObserveAsync(CancellationToken.None);

        var hash = FaceHash.Sha256Hex(face);
        Assert.Equal(new[] { "11", "12", "11", "12", "11", "12" }, sink.Ids);
        Assert.Equal(Enumerable.Repeat(hash, 6), sink.FaceHashes);
        Assert.Equal(writes, reader.Writes);
        Assert.Empty(sibling.Writes);
        Assert.Empty(sibling.Calls);
    }

    [Fact]
    public async Task A_short_list_uploads_nothing()
    {
        var reader = new FakeReader();
        var sibling = new FakeReader();
        reader.ScriptList(2, Person("7", "Walk In"));
        var sink = new RecordingUpload();
        using var worker = new ReaderWorker("reader-a", reader, Path.Combine(_directory, "short.sqlite"));
        var path = new DesiredRevisionPath("reader-a", worker, reader, new EmptyPull(), sink);

        await path.ReconnectAsync(CancellationToken.None);

        Assert.Empty(sink.Ids);
        Assert.Empty(reader.Writes);
        Assert.Empty(sibling.Writes);
    }

    [Fact]
    public async Task An_unreachable_server_does_not_write_the_sibling()
    {
        var reader = new FakeReader();
        var sibling = new FakeReader();
        reader.CreateUser(Person("7", "Walk In"));
        var writes = reader.Writes.Count;
        var sink = new RecordingUpload { Offline = true };
        using var worker = new ReaderWorker("reader-a", reader, Path.Combine(_directory, "offline.sqlite"));
        var path = new DesiredRevisionPath("reader-a", worker, reader, new EmptyPull(), sink);

        await path.ReconnectAsync(CancellationToken.None);

        Assert.Empty(sink.Ids);
        Assert.Equal(writes, reader.Writes.Count);
        Assert.Empty(sibling.Writes);
        Assert.Empty(sibling.Calls);
    }

    public void Dispose()
    {
        if (Directory.Exists(_directory))
        {
            Directory.Delete(_directory, recursive: true);
        }
    }

    private static ReaderUser Person(string id, string name) => Person(id, name, "Customer");

    private static ReaderUser Person(string id, string name, string authority) =>
        new(id, name, null, 0, null, null, authority, 1, 1);

    internal sealed class RecordingUpload : IReaderObservationUpload
    {
        public List<string> Ids { get; } = [];

        public List<string> Names { get; } = [];

        public List<string> AbsentIds { get; } = [];

        public List<string> FaceHashes { get; } = [];

        public List<int> TrustedRosterTotals { get; } = [];

        public List<string> RosterIds { get; } = [];

        public bool Offline { get; set; }

        /// <summary>What the server answers to an awaited list.</summary>
        public bool RosterAccepted { get; set; }

        /// <summary>The list upload throws something other than a network error.</summary>
        public bool FailList { get; set; }

        public List<string> Calls { get; } = [];

        /// <summary>The scan each upload named, in upload order.</summary>
        public List<ScanStamp> Stamps { get; } = [];

        /// <summary>The face hashes each list carried, in upload order.</summary>
        public List<Dictionary<string, string>> RosterHashes { get; } = [];

        /// <summary>The next per-user upload throws something other than a network error.</summary>
        public bool FailUsersOnce { get; set; }

        /// <summary>Every message id handed out, in order.</summary>
        public List<string> MessageIds { get; } = [];

        /// <summary>The photo hashes the server answers it still needs.</summary>
        public List<string> Wanted { get; } = [];

        public List<byte[]> Photos { get; } = [];

        public Task<IReadOnlyList<string>> WantedFacesAsync(string deviceId, CancellationToken cancellationToken) =>
            Task.FromResult<IReadOnlyList<string>>(Wanted.ToList());

        public Task<bool> UploadFaceAsync(byte[] jpegBytes, CancellationToken cancellationToken)
        {
            Photos.Add(jpegBytes);
            Wanted.Remove(FaceHash.Sha256Hex(jpegBytes));
            return Task.FromResult(true);
        }

        private string NextId(string kind)
        {
            var id = kind + "-" + (MessageIds.Count + 1);
            MessageIds.Add(id);
            return id;
        }

        public Task<IReadOnlyList<string>> UploadAsync(
            string deviceId,
            ScanStamp scan,
            IReadOnlyList<ReaderUser> users,
            IReadOnlyDictionary<string, string> faceHashes,
            CancellationToken cancellationToken)
        {
            if (Offline)
            {
                throw new HttpRequestException("unreachable");
            }

            if (FailUsersOnce)
            {
                FailUsersOnce = false;
                throw new InvalidOperationException("users failed");
            }

            Calls.Add("users");
            Stamps.Add(scan);
            Ids.AddRange(users.Select(user => user.DeviceUserId));
            Names.AddRange(users.Select(user => user.Name ?? ""));
            FaceHashes.AddRange(users.Select(user =>
                faceHashes.TryGetValue(user.DeviceUserId, out var hash) ? hash : ""));
            return Task.FromResult<IReadOnlyList<string>>(users.Select(_ => NextId("user")).ToList());
        }

        public Task<RosterUpload> UploadTrustedRosterAsync(
            string deviceId,
            ScanStamp scan,
            int announcedTotal,
            IReadOnlyList<ReaderUser> users,
            IReadOnlyDictionary<string, string> faceHashes,
            bool awaitAcceptance,
            CancellationToken cancellationToken)
        {
            if (Offline)
            {
                throw new HttpRequestException("unreachable");
            }

            if (FailList)
            {
                throw new InvalidOperationException("list failed");
            }

            Calls.Add(awaitAcceptance ? "list-awaited" : "list");
            Stamps.Add(scan);
            RosterHashes.Add(new Dictionary<string, string>(faceHashes));
            TrustedRosterTotals.Add(announcedTotal);
            RosterIds.AddRange(users.Select(user => user.DeviceUserId));
            return Task.FromResult(new RosterUpload(awaitAcceptance && RosterAccepted, NextId("list")));
        }

        public Task<IReadOnlyList<string>> UploadAbsencesAsync(
            string deviceId, ScanStamp scan, IReadOnlyList<string> deviceUserIds, CancellationToken cancellationToken)
        {
            if (Offline)
            {
                throw new HttpRequestException("unreachable");
            }

            Calls.Add("absences");
            Stamps.Add(scan);
            AbsentIds.AddRange(deviceUserIds);
            return Task.FromResult<IReadOnlyList<string>>(deviceUserIds.Select(_ => NextId("absence")).ToList());
        }
    }

    internal sealed class EmptyPull : IDesiredStateClient
    {
        public int Pulls { get; private set; }

        public Task<DesiredPull> PullAsync(string deviceId, long after, CancellationToken cancellationToken)
        {
            Pulls++;
            return Task.FromResult(new DesiredPull(0, after, []));
        }

        public Task AcknowledgeAsync(DesiredAcknowledgement ack, CancellationToken cancellationToken) =>
            Task.CompletedTask;

        public Task ReportOccupiedAsync(
            string deviceId, long revision, string deviceUserId, CancellationToken cancellationToken) =>
            Task.CompletedTask;

        public Task<bool> ObservationProcessedAsync(string deviceId, IReadOnlyList<string> messageIds,
            CancellationToken cancellationToken) => Task.FromResult(true);

        public Task ReportFailedAsync(string deviceId, RetryState attempt, CancellationToken cancellationToken) =>
            Task.CompletedTask;

        public Task ReportOccupantAsync(string deviceId, long revision, string deviceUserId, ReaderOccupant occupant,
            CancellationToken cancellationToken) =>
            throw new InvalidOperationException("Unexpected occupant at " + deviceUserId);
    }
}
