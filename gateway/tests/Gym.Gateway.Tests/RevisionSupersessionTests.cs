using Gym.Gateway.Adapters;
using Gym.Gateway.Execution;
using Xunit;

namespace Gym.Gateway.Tests;

/// <summary>
/// A failed revision does not block newer ones, is retried with backoff across restarts, and is
/// dropped only when the server no longer desires it. Desired state waits until the server has
/// processed the reader's latest observation.
/// </summary>
public class RevisionSupersessionTests : IDisposable
{
    private readonly string _directory = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "gym-supersede-" + Guid.NewGuid().ToString("N"));
    private static readonly DateTimeOffset ValidFrom = new(2026, 10, 8, 0, 0, 0, TimeSpan.FromHours(5.5));
    private static readonly DateTimeOffset ValidTo = new(2026, 10, 8, 23, 59, 59, TimeSpan.FromHours(5.5));
    private static readonly byte[] Face = [1, 2, 3, 4, 5];
    private static readonly byte[] BobFace = [9, 9, 9];
    private DateTimeOffset _now = new(2026, 10, 15, 6, 0, 0, TimeSpan.Zero);

    public RevisionSupersessionTests() => Directory.CreateDirectory(_directory);

    public void Dispose()
    {
        if (Directory.Exists(_directory))
        {
            Directory.Delete(_directory, true);
        }
    }

    [Fact]
    public async Task A_failed_write_does_not_block_a_newer_revision_and_its_late_retry_does_not_overwrite_the_replacement()
    {
        var reader = new FakeReader();
        reader.ScriptFailure(FakeReaderOperation.CreateUser, "create failed");
        var server = new Server { Items = [Item(1, "1", "Asha"), Item(2, "2", "Ravi")] };
        using var worker = Worker(reader, "write");
        var path = Path(worker, reader, server);

        await path.HandleAsync(Notice(2), CancellationToken.None);

        Assert.Equal(FakeReader.FailNoRecord, reader.GetUser("1").FailCode);
        Assert.Equal("Ravi", reader.GetUser("2").User!.Name);
        Assert.Equal([2L], server.AckedRevisions);
        Assert.Equal(1, worker.Retry!.Revision);
        Assert.Equal(0, worker.AppliedRevision);

        server.Items = [Item(2, "2", "Ravi"), Item(3, "1", "Asha Menon")];
        await path.HandleAsync(Notice(3), CancellationToken.None);

        Assert.Equal("Asha Menon", reader.GetUser("1").User!.Name);
        Assert.Empty(worker.Attempts);
        Assert.Equal(3, worker.AppliedRevision);

        _now += TimeSpan.FromHours(1);
        await path.RetryAsync(CancellationToken.None);
        await path.HandleAsync(Notice(3), CancellationToken.None);

        Assert.Equal("Asha Menon", reader.GetUser("1").User!.Name);
        Assert.Equal([2L, 3L], server.AckedRevisions);
    }

    [Fact]
    public async Task A_verified_write_whose_ack_was_lost_is_not_acknowledged_after_the_server_replaced_it()
    {
        var reader = new FakeReader();
        var server = new Server { Items = [Item(1, "1", "Asha")], FailAcks = 1 };
        using var worker = Worker(reader, "late-ack");
        var path = Path(worker, reader, server);
        await Assert.ThrowsAnyAsync<HttpRequestException>(() => path.HandleAsync(Notice(1), CancellationToken.None));
        Assert.Single(worker.PendingAcks);

        server.Items = [Item(2, "1", "Asha Menon", expected: Verified("Asha", Face))];
        await path.HandleAsync(Notice(2), CancellationToken.None);

        Assert.Equal([2L], server.AckedRevisions);
        Assert.Equal("Asha Menon", reader.GetUser("1").User!.Name);
        Assert.Empty(worker.PendingAcks);
        Assert.Equal(2, worker.AppliedRevision);
    }

    [Fact]
    public async Task A_failed_removal_does_not_run_after_the_server_restored_the_member()
    {
        var reader = new FakeReader();
        var server = new Server { Items = [Item(1, "1", "Asha")] };
        using var worker = Worker(reader, "removal");
        var path = Path(worker, reader, server);
        await path.HandleAsync(Notice(1), CancellationToken.None);

        reader.ScriptFailure(FakeReaderOperation.RemoveUser, "remove failed");
        server.Items = [Item(2, "1", "Asha", present: false, expected: Verified("Asha", Face)), Item(3, "2", "Ravi")];
        await path.HandleAsync(Notice(3), CancellationToken.None);

        Assert.Equal("Asha", reader.GetUser("1").User!.Name);
        Assert.Equal("Ravi", reader.GetUser("2").User!.Name);
        Assert.Equal(2, worker.Retry!.Revision);

        server.Items = [Item(3, "2", "Ravi"), Item(4, "1", "Asha", expected: Verified("Asha", Face))];
        await path.HandleAsync(Notice(4), CancellationToken.None);
        _now += TimeSpan.FromHours(1);
        await path.RetryAsync(CancellationToken.None);

        Assert.Equal("Asha", reader.GetUser("1").User!.Name);
        Assert.Empty(worker.Attempts);
        Assert.Equal(4, worker.AppliedRevision);
        Assert.Contains(4L, server.AckedRevisions);
    }

    [Fact]
    public async Task A_failed_removal_that_is_still_desired_is_retried_after_its_backoff()
    {
        var reader = new FakeReader();
        var server = new Server { Items = [Item(1, "1", "Asha")] };
        using var worker = Worker(reader, "removal-retry");
        var path = Path(worker, reader, server);
        await path.HandleAsync(Notice(1), CancellationToken.None);

        reader.ScriptFailure(FakeReaderOperation.RemoveUser, "remove failed");
        server.Items = [Item(2, "1", "Asha", present: false, expected: Verified("Asha", Face))];
        await path.HandleAsync(Notice(2), CancellationToken.None);
        await path.RetryAsync(CancellationToken.None);
        Assert.Equal("Asha", reader.GetUser("1").User!.Name);

        _now += TimeSpan.FromMinutes(1);
        await path.RetryAsync(CancellationToken.None);

        Assert.Equal(FakeReader.FailNoRecord, reader.GetUser("1").FailCode);
        Assert.Equal([1L, 2L], server.AckedRevisions);
        Assert.Equal(2, worker.AppliedRevision);
    }

    [Fact]
    public async Task A_failure_survives_a_restart_and_a_newer_revision_still_applies()
    {
        var reader = new FakeReader();
        reader.ScriptFailure(FakeReaderOperation.CreateUser, "create failed");
        var server = new Server { Items = [Item(1, "1", "Asha")] };
        using (var worker = Worker(reader, "restart"))
        {
            await Path(worker, reader, server).HandleAsync(Notice(1), CancellationToken.None);
            Assert.Equal(1, worker.Retry!.Revision);
        }

        server.Items = [Item(1, "1", "Asha"), Item(2, "2", "Ravi")];
        using var restarted = Worker(reader, "restart");
        var path = Path(restarted, reader, server);
        await path.ReconnectAsync(CancellationToken.None);

        Assert.Equal("Ravi", reader.GetUser("2").User!.Name);
        Assert.Equal(FakeReader.FailNoRecord, reader.GetUser("1").FailCode);
        Assert.Equal(1, restarted.Retry!.AttemptCount);

        _now += TimeSpan.FromMinutes(1);
        await path.RetryAsync(CancellationToken.None);

        Assert.Equal("Asha", reader.GetUser("1").User!.Name);
        Assert.Empty(restarted.Attempts);
        Assert.Equal(2, restarted.AppliedRevision);
        Assert.Equal([2L, 1L], server.AckedRevisions);
    }

    [Fact]
    public async Task Repeated_failures_back_off_report_exhaustion_once_and_still_recover()
    {
        var reader = new FakeReader();
        for (var i = 0; i < ReaderWorker.ExhaustAfter; i++)
        {
            reader.ScriptFailure(FakeReaderOperation.CreateUser, "create failed");
        }

        var server = new Server { Items = [Item(1, "1", "Asha")] };
        using (var worker = Worker(reader, "exhaust"))
        {
            var path = Path(worker, reader, server);
            await path.HandleAsync(Notice(1), CancellationToken.None);
            var waits = new List<TimeSpan>();
            for (var attempt = 2; attempt <= ReaderWorker.ExhaustAfter; attempt++)
            {
                var wait = worker.Retry!.NextAttempt - _now;
                waits.Add(wait);
                _now += wait - TimeSpan.FromSeconds(1);
                await path.RetryAsync(CancellationToken.None);
                Assert.Equal(attempt - 1, worker.Retry!.AttemptCount);
                _now += TimeSpan.FromSeconds(1);
                await path.RetryAsync(CancellationToken.None);
            }

            Assert.Equal([1, 2, 4, 8, 10], waits.Select(wait => (int)wait.TotalMinutes));
            var exhausted = worker.Retry!;
            Assert.Equal((AttemptState.Exhausted, ReaderWorker.ExhaustAfter, true), (exhausted.State, exhausted.AttemptCount, exhausted.Reported));
            var report = Assert.Single(server.Failures);
            Assert.Equal((1L, "1", false, ReaderWorker.ExhaustAfter), (report.Revision, report.DeviceUserId, report.State == AttemptState.Rejected, report.AttemptCount));
            Assert.Equal(ReaderWorker.MaxRetryDelay, exhausted.NextAttempt - _now);
            Assert.Empty(server.AckedRevisions);
        }

        using var restarted = Worker(reader, "exhaust");
        Assert.Equal(_now, restarted.Retry!.NextAttempt);
        await Path(restarted, reader, server).RetryAsync(CancellationToken.None);

        Assert.Equal("Asha", reader.GetUser("1").User!.Name);
        Assert.Equal([1L], server.AckedRevisions);
        Assert.Empty(restarted.Attempts);
        Assert.Single(server.Failures);
    }

    [Fact]
    public async Task An_unusable_item_is_rejected_and_reported_without_stopping_later_revisions()
    {
        var reader = new FakeReader();
        var server = new Server
        {
            Items = [DesiredPullItem.Unusable(1, "1", "face is missing"), Item(2, "2", "Ravi")]
        };
        using var worker = Worker(reader, "unusable");
        var path = Path(worker, reader, server);

        await path.HandleAsync(Notice(2), CancellationToken.None);
        await path.HandleAsync(Notice(2), CancellationToken.None);

        Assert.Equal("Ravi", reader.GetUser("2").User!.Name);
        Assert.Equal(FakeReader.FailNoRecord, reader.GetUser("1").FailCode);
        var report = Assert.Single(server.Failures);
        Assert.Equal((1L, AttemptState.Rejected, "face is missing"), (report.Revision, report.State, report.LastError));
        Assert.Equal(0, worker.AppliedRevision);

        server.Items = [Item(2, "2", "Ravi"), Item(3, "1", "Asha")];
        await path.HandleAsync(Notice(3), CancellationToken.None);

        Assert.Equal("Asha", reader.GetUser("1").User!.Name);
        Assert.Empty(worker.Attempts);
        Assert.Equal(3, worker.AppliedRevision);
    }

    [Fact]
    public async Task A_refused_acknowledgement_is_not_counted_as_applied_and_is_retried()
    {
        var reader = new FakeReader();
        var server = new Server { Items = [Item(1, "1", "Asha")], RefuseAcks = 1 };
        using var worker = Worker(reader, "refused");
        var path = Path(worker, reader, server);

        await path.HandleAsync(Notice(1), CancellationToken.None);

        Assert.Empty(server.AckedRevisions);
        Assert.Empty(worker.PendingAcks);
        Assert.Equal(0, worker.AppliedRevision);
        Assert.Contains("refused", worker.Retry!.LastError);

        _now += TimeSpan.FromMinutes(1);
        await path.RetryAsync(CancellationToken.None);

        Assert.Equal([1L], server.AckedRevisions);
        Assert.Equal(1, worker.AppliedRevision);
        Assert.Empty(worker.Attempts);
    }

    [Fact]
    public async Task A_report_that_did_not_reach_the_server_is_repeated_and_a_newer_revision_does_not_bypass_the_person_found()
    {
        var reader = new FakeReader();
        var server = new Server { Items = [Item(1, "7", "Asha")] };
        using var worker = Worker(reader, "hold");
        var path = Path(worker, reader, server);
        await path.HandleAsync(Notice(1), CancellationToken.None);
        Assert.True(reader.RemoveUser("7").Ok);
        Enrol(reader, "7", "Bob");

        server.FailReports = 1;
        server.Items = [Item(2, "7", "Asha", expected: Verified("Asha", Face))];
        await path.HandleAsync(Notice(2), CancellationToken.None);
        Assert.Empty(server.Reports);
        Assert.Equal(2, worker.Retry!.Revision);

        server.Items = [Item(3, "7", "Asha Menon", expected: Verified("Asha", Face))];
        await path.HandleAsync(Notice(3), CancellationToken.None);

        Assert.Equal("Bob", reader.GetUser("7").User!.Name);
        Assert.Equal(BobFace, reader.GetFace("7").Bytes);
        var report = Assert.Single(server.Reports);
        Assert.Equal((3L, "Bob"), (report.Revision, report.Occupant.User!.Name));
        Assert.Equal([1L], server.AckedRevisions);
        Assert.Empty(worker.Attempts);
    }

    [Fact]
    public async Task On_reconnect_nothing_is_pulled_until_the_server_has_processed_the_reader_observation()
    {
        var reader = new FakeReader();
        var server = new Server { Items = [Item(1, "1", "Asha")] };
        var upload = new PendingEnrollmentTests.RecordingUpload();
        using var worker = Worker(reader, "fence");
        var path = Path(worker, reader, server, upload);
        await path.ReconnectAsync(CancellationToken.None);
        Assert.Equal([1L], server.AckedRevisions);

        Assert.True(reader.ReplaceUser(Person("1", "Asha At Desk")).Ok);
        server.Items = [Item(2, "1", "Asha Menon", expected: Verified("Asha", Face))];
        server.Processing = false;
        var pullsBefore = server.Pulls;

        await path.ReconnectAsync(CancellationToken.None);
        await path.HandleAsync(Notice(2), CancellationToken.None);
        await path.RetryAsync(CancellationToken.None);

        Assert.Equal(pullsBefore, server.Pulls);
        Assert.Equal(ObservationGate.Fenced, path.Gate);
        Assert.Equal("Asha At Desk", reader.GetUser("1").User!.Name);
        Assert.Empty(server.Reports);

        // The server holds the row for staff once it processes the changed record.
        server.Items = [];
        server.Desired = 2;
        server.Processing = true;
        await path.RetryAsync(CancellationToken.None);

        Assert.Equal(ObservationGate.Open, path.Gate);
        Assert.Equal("Asha At Desk", reader.GetUser("1").User!.Name);
        Assert.Equal([1L], server.AckedRevisions);

        server.Items = [Item(3, "1", "Asha Menon", expected: Verified("Asha At Desk", Face))];
        await path.HandleAsync(Notice(3), CancellationToken.None);

        Assert.Equal("Asha Menon", reader.GetUser("1").User!.Name);
        Assert.Equal([1L, 3L], server.AckedRevisions);
    }

    [Fact]
    public async Task A_saved_list_opens_the_gate_without_waiting_for_per_user_ids_from_the_same_scan()
    {
        var reader = new FakeReader();
        Enrol(reader, "1", "Asha");
        var server = new Server { Items = [Item(1, "2", "Ravi")], Processing = true };
        var upload = new PendingEnrollmentTests.RecordingUpload { RosterAccepted = false };
        using var worker = Worker(reader, "list-only-fence");
        var path = Path(worker, reader, server, upload);

        await path.ReconnectAsync(CancellationToken.None);

        Assert.Equal(ObservationGate.Open, path.Gate);
        Assert.Equal("Ravi", reader.GetUser("2").User!.Name);
        Assert.Contains("1", upload.Ids);
        Assert.Contains(upload.MessageIds, id => id.StartsWith("list-", StringComparison.Ordinal));
    }

    [Fact]
    public async Task A_lost_check_or_an_unprocessed_observation_is_sent_again_without_applying_meanwhile()
    {
        var reader = new FakeReader();
        Enrol(reader, "1", "Asha");
        var server = new Server { Items = [Item(1, "2", "Ravi")], Processing = false, FailChecks = 1 };
        var upload = new PendingEnrollmentTests.RecordingUpload();
        using var worker = Worker(reader, "lost");
        var path = new DesiredRevisionPath("r", worker, reader, server, upload, null,
            new DesiredPathOptions
            {
                ReconnectWait = TimeSpan.Zero,
                FenceTimeout = TimeSpan.FromMinutes(2),
                FenceResendAfter = TimeSpan.FromMinutes(5)
            });

        await path.ReconnectAsync(CancellationToken.None);
        var firstIds = upload.MessageIds.ToList();
        Assert.NotEmpty(firstIds);
        Assert.True(server.Checks > 0);

        _now += TimeSpan.FromMinutes(1);
        await path.RetryAsync(CancellationToken.None);
        Assert.Equal(firstIds, upload.MessageIds);
        Assert.Equal(ObservationGate.Fenced, path.Gate);

        _now += TimeSpan.FromMinutes(2);
        await path.RetryAsync(CancellationToken.None);
        Assert.Equal(ObservationGate.Fenced, path.Gate);
        Assert.Equal(firstIds, upload.MessageIds);

        _now += TimeSpan.FromMinutes(3);
        await path.RetryAsync(CancellationToken.None);
        Assert.Equal(ObservationGate.Unobserved, path.Gate);
        await path.RetryAsync(CancellationToken.None);
        Assert.True(upload.MessageIds.Count > firstIds.Count);
        Assert.Equal(0, server.Pulls);
        Assert.Equal(FakeReader.FailNoRecord, reader.GetUser("2").FailCode);

        server.Processing = true;
        await path.RetryAsync(CancellationToken.None);
        await path.RetryAsync(CancellationToken.None);

        Assert.Equal("Ravi", reader.GetUser("2").User!.Name);
        Assert.Equal([1L], server.AckedRevisions);
        Assert.Equal("Asha", reader.GetUser("1").User!.Name);
        Assert.Empty(server.Reports);
    }

    [Fact]
    public async Task A_fence_past_resend_age_stays_while_the_list_message_is_still_queued()
    {
        var reader = new FakeReader();
        Enrol(reader, "1", "Asha");
        var server = new Server { Items = [Item(1, "2", "Ravi")], Processing = false };
        var upload = new PendingEnrollmentTests.RecordingUpload { RosterAccepted = false };
        using var worker = Worker(reader, "pending-fence");
        var pending = new HashSet<string>(StringComparer.Ordinal);
        var path = new DesiredRevisionPath("r", worker, reader, server, upload, null,
            new DesiredPathOptions
            {
                ReconnectWait = TimeSpan.Zero,
                FenceTimeout = TimeSpan.FromMinutes(2),
                FenceResendAfter = TimeSpan.FromMinutes(5),
                IsMessagePending = id => pending.Contains(id)
            });

        await path.ReconnectAsync(CancellationToken.None);
        var firstIds = upload.MessageIds.ToList();
        Assert.NotEmpty(firstIds);
        foreach (var id in firstIds)
        {
            pending.Add(id);
        }

        Assert.Equal(ObservationGate.Fenced, path.Gate);

        _now += TimeSpan.FromMinutes(6);
        await path.RetryAsync(CancellationToken.None);

        Assert.Equal(ObservationGate.Fenced, path.Gate);
        Assert.Equal(firstIds, upload.MessageIds);

        pending.Clear();
        await path.RetryAsync(CancellationToken.None);
        Assert.Equal(ObservationGate.Unobserved, path.Gate);
        await path.RetryAsync(CancellationToken.None);
        Assert.True(upload.MessageIds.Count > firstIds.Count);
    }

    [Fact]
    public async Task A_reconnect_does_not_replace_a_queued_list_fence()
    {
        var reader = new FakeReader();
        Enrol(reader, "1", "Asha");
        var server = new Server { Items = [Item(1, "2", "Ravi")], Processing = false };
        var upload = new PendingEnrollmentTests.RecordingUpload { RosterAccepted = false };
        using var worker = Worker(reader, "reconnect-pending");
        var pending = new HashSet<string>(StringComparer.Ordinal);
        var path = new DesiredRevisionPath("r", worker, reader, server, upload, null,
            new DesiredPathOptions
            {
                ReconnectWait = TimeSpan.Zero,
                IsMessagePending = id => pending.Contains(id),
                PendingListMessageId = _ => pending
                    .FirstOrDefault(id => id.StartsWith("list-", StringComparison.Ordinal))
            });

        await path.ReconnectAsync(CancellationToken.None);
        var firstIds = upload.MessageIds.ToList();
        Assert.NotEmpty(firstIds);
        foreach (var id in firstIds)
        {
            pending.Add(id);
        }

        Assert.Equal(ObservationGate.Fenced, path.Gate);

        await path.ReconnectAsync(CancellationToken.None);

        Assert.Equal(ObservationGate.Fenced, path.Gate);
        Assert.Equal(firstIds, upload.MessageIds);
    }

    [Fact]
    public async Task A_reconnect_reuses_a_queued_list_already_saved_on_the_server()
    {
        var reader = new FakeReader();
        Enrol(reader, "1", "Asha");
        var server = new Server { Items = [Item(1, "2", "Ravi")], Processing = false };
        var upload = new PendingEnrollmentTests.RecordingUpload { RosterAccepted = false };
        using var worker = Worker(reader, "reconnect-saved");
        var pending = new HashSet<string>(StringComparer.Ordinal);
        var acknowledged = new List<string>();
        var path = new DesiredRevisionPath("r", worker, reader, server, upload, null,
            new DesiredPathOptions
            {
                ReconnectWait = TimeSpan.Zero,
                IsMessagePending = id => pending.Contains(id),
                PendingListMessageId = _ => pending
                    .FirstOrDefault(id => id.StartsWith("list-", StringComparison.Ordinal)),
                AcknowledgeMessage = id =>
                {
                    pending.Remove(id);
                    acknowledged.Add(id);
                }
            });

        await path.ReconnectAsync(CancellationToken.None);
        var firstIds = upload.MessageIds.ToList();
        var listId = Assert.Single(firstIds.Where(id => id.StartsWith("list-", StringComparison.Ordinal)));
        foreach (var id in firstIds)
        {
            pending.Add(id);
        }

        Assert.Equal(ObservationGate.Fenced, path.Gate);

        server.Processing = true;
        await path.ReconnectAsync(CancellationToken.None);

        Assert.Equal(ObservationGate.Open, path.Gate);
        Assert.Equal(firstIds, upload.MessageIds);
        Assert.Equal([listId], acknowledged);
        Assert.Equal("Ravi", reader.GetUser("2").User!.Name);
    }

    [Fact]
    public async Task A_reconnect_whose_list_fails_applies_nothing_until_a_list_is_processed()
    {
        var reader = new FakeReader();
        reader.ScriptFailure(FakeReaderOperation.ListUsers, "list failed");
        var server = new Server { Items = [Item(1, "1", "Asha")] };
        var upload = new PendingEnrollmentTests.RecordingUpload();
        using var worker = Worker(reader, "list-failed");
        var path = Path(worker, reader, server, upload);

        await path.ReconnectAsync(CancellationToken.None);
        await path.HandleAsync(Notice(1), CancellationToken.None);

        Assert.Equal(0, server.Pulls);
        Assert.Equal(FakeReader.FailNoRecord, reader.GetUser("1").FailCode);

        _now += TimeSpan.FromMinutes(1);
        await path.RetryAsync(CancellationToken.None);

        Assert.Equal("Asha", reader.GetUser("1").User!.Name);
        Assert.Equal([1L], server.AckedRevisions);
    }

    [Fact]
    public async Task A_normal_reconnect_converges_in_one_call()
    {
        var reader = new FakeReader();
        Enrol(reader, "5", "Bob");
        var server = new Server { Items = [Item(1, "1", "Asha"), Item(2, "2", "Ravi", present: false)] };
        var upload = new PendingEnrollmentTests.RecordingUpload();
        using var worker = Worker(reader, "converge");
        var path = Path(worker, reader, server, upload);

        await path.ReconnectAsync(CancellationToken.None);

        Assert.Equal("Asha", reader.GetUser("1").User!.Name);
        Assert.Equal("Bob", reader.GetUser("5").User!.Name);
        Assert.Equal([1L, 2L], server.AckedRevisions);
        Assert.Equal(2, worker.AppliedRevision);
        Assert.Equal(ObservationGate.Open, path.Gate);
        Assert.Contains("5", upload.Ids);
    }

    [Fact]
    public async Task A_page_of_failures_does_not_hide_later_pages()
    {
        var reader = new FakeReader();
        for (var i = 0; i < 25; i++)
        {
            reader.ScriptFailure(FakeReaderOperation.CreateUser, "create failed");
        }

        var items = Enumerable.Range(1, 26).Select(i => Item(i, i.ToString(), "Person " + i)).ToList();
        var server = new Server { Items = items, PageSize = 20 };
        using var worker = Worker(reader, "pages");

        await Path(worker, reader, server).HandleAsync(Notice(26), CancellationToken.None);

        Assert.Equal("Person 26", reader.GetUser("26").User!.Name);
        Assert.Equal(25, worker.Attempts.Count);
        Assert.Equal(0, worker.AppliedRevision);
    }

    [Fact]
    public void An_old_single_retry_row_is_moved_into_the_attempts()
    {
        var journal = System.IO.Path.Combine(_directory, "old.sqlite");
        using (var connection = new Microsoft.Data.Sqlite.SqliteConnection("Data Source=" + journal))
        {
            connection.Open();
            using var command = connection.CreateCommand();
            command.CommandText = """
                CREATE TABLE applied_revision (reader_id TEXT PRIMARY KEY, revision INTEGER NOT NULL);
                CREATE TABLE retry_state (reader_id TEXT PRIMARY KEY, revision INTEGER NOT NULL, attempt_count INTEGER NOT NULL, next_attempt TEXT NOT NULL, last_error TEXT NOT NULL);
                INSERT INTO applied_revision VALUES ('r', 4);
                INSERT INTO retry_state VALUES ('r', 5, 3, '2026-10-15T06:00:00.0000000+00:00', 'create failed');
                """;
            command.ExecuteNonQuery();
        }

        Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();
        using var worker = new ReaderWorker("r", new FakeReader(), journal, TimeSpan.FromMinutes(1), () => _now);

        Assert.Equal(4, worker.AppliedRevision);
        var moved = worker.Retry!;
        Assert.Equal((5L, 3, "create failed", AttemptState.Retry), (moved.Revision, moved.AttemptCount, moved.LastError, moved.State));
    }

    [Fact]
    public async Task An_offline_reader_spends_no_attempt_reports_nothing_and_is_written_as_soon_as_it_is_back()
    {
        var reader = new FakeReader();
        var server = new Server { Items = [Item(1, "1", "Asha")] };
        using var worker = Worker(reader, "offline", () => _online);
        var path = Path(worker, reader, server);
        _online = false;

        await path.HandleAsync(Notice(1), CancellationToken.None);
        for (var tick = 0; tick < 20; tick++)
        {
            _now += TimeSpan.FromMinutes(5);
            await path.RetryAsync(CancellationToken.None);
        }

        var waiting = worker.Retry!;
        Assert.Equal((AttemptState.Retry, 0, false), (waiting.State, waiting.AttemptCount, waiting.Reported));
        Assert.Contains("offline", waiting.LastError);
        Assert.Empty(server.Failures);
        Assert.False(worker.HasDueAttempt());
        Assert.Equal(FakeReader.FailNoRecord, reader.GetUser("1").FailCode);

        _online = true;
        await path.RetryAsync(CancellationToken.None);

        Assert.Equal("Asha", reader.GetUser("1").User!.Name);
        Assert.Equal([1L], server.AckedRevisions);
        Assert.Empty(worker.Attempts);
    }

    [Fact]
    public async Task A_reader_that_drops_during_a_write_does_not_spend_an_attempt()
    {
        var inner = new FakeReader();
        var reader = new DropsOnCreate(inner, () => _online = false);
        var server = new Server { Items = [Item(1, "1", "Asha")] };
        using var worker = new ReaderWorker("r", reader, System.IO.Path.Combine(_directory, "drop.sqlite"),
            TimeSpan.FromMinutes(1), () => _now, () => _online);
        var path = new DesiredRevisionPath("r", worker, reader, server, null, null,
            new DesiredPathOptions { ReconnectWait = TimeSpan.Zero });

        await path.HandleAsync(Notice(1), CancellationToken.None);

        var dropped = worker.Retry!;
        Assert.Equal((AttemptState.Retry, 0), (dropped.State, dropped.AttemptCount));
        Assert.Contains("went offline", dropped.LastError);
        Assert.Empty(server.Failures);

        _online = true;
        await path.RetryAsync(CancellationToken.None);

        Assert.Equal("Asha", inner.GetUser("1").User!.Name);
        Assert.Equal([1L], server.AckedRevisions);
    }

    [Fact]
    public async Task An_exhausted_revision_keeps_its_desired_state_and_is_retried_at_once_when_the_reader_returns()
    {
        var reader = new FakeReader();
        for (var i = 0; i < ReaderWorker.ExhaustAfter; i++)
        {
            reader.ScriptFailure(FakeReaderOperation.CreateUser, "device busy");
        }

        var server = new Server { Items = [Item(1, "1", "Asha")] };
        using var worker = Worker(reader, "exhausted-offline", () => _online);
        var path = Path(worker, reader, server);
        await path.HandleAsync(Notice(1), CancellationToken.None);
        while (worker.Retry is { State: AttemptState.Retry } retry)
        {
            _now = retry.NextAttempt;
            await path.RetryAsync(CancellationToken.None);
        }

        var exhausted = worker.Retry!;
        Assert.Equal((AttemptState.Exhausted, ReaderWorker.ExhaustAfter), (exhausted.State, exhausted.AttemptCount));
        Assert.Single(server.Failures);
        Assert.False(worker.HasDueAttempt());

        _online = false;
        await path.RetryAsync(CancellationToken.None);
        Assert.Equal(ReaderWorker.ExhaustAfter, worker.Retry!.AttemptCount);
        _online = true;
        await path.RetryAsync(CancellationToken.None);

        Assert.Equal("Asha", reader.GetUser("1").User!.Name);
        Assert.Equal([1L], server.AckedRevisions);
        Assert.Empty(worker.Attempts);
        Assert.Single(server.Failures);
        Assert.Equal(1, worker.AppliedRevision);
    }

    [Fact]
    public async Task A_permanent_rejection_is_not_retried_by_a_reader_coming_back_online()
    {
        var reader = new FakeReader();
        var server = new Server { Items = [DesiredPullItem.Unusable(1, "1", "face is missing")] };
        using var worker = Worker(reader, "rejected-offline", () => _online);
        var path = Path(worker, reader, server);
        await path.HandleAsync(Notice(1), CancellationToken.None);
        Assert.Equal(AttemptState.Rejected, worker.Retry!.State);

        _online = false;
        await path.RetryAsync(CancellationToken.None);
        _online = true;
        await path.RetryAsync(CancellationToken.None);

        Assert.Equal(AttemptState.Rejected, worker.Retry!.State);
        Assert.Equal(DateTimeOffset.MaxValue, worker.Retry!.NextAttempt);
        Assert.False(worker.HasDueAttempt());
        Assert.Single(server.Failures);
    }

    private bool _online = true;

    /// <summary>The session drops while a user is being created: the call fails and the reader is offline.</summary>
    private sealed class DropsOnCreate(FakeReader inner, Action drop) : IReaderAdapter
    {
        private bool _dropped;

        public ReaderListResult ListUsers() => inner.ListUsers();
        public ReaderUserResult GetUser(string deviceUserId) => inner.GetUser(deviceUserId);

        public ReaderCallResult CreateUser(ReaderUser user)
        {
            if (_dropped)
            {
                return inner.CreateUser(user);
            }

            _dropped = true;
            drop();
            return ReaderCallResult.Failed("connection lost");
        }

        public ReaderCallResult ReplaceUser(ReaderUser user) => inner.ReplaceUser(user);
        public ReaderFaceResult GetFace(string deviceUserId) => inner.GetFace(deviceUserId);
        public ReaderCallResult InsertFace(string deviceUserId, byte[]? jpeg) => inner.InsertFace(deviceUserId, jpeg);
        public ReaderCallResult UpdateFace(string deviceUserId, byte[]? jpeg) => inner.UpdateFace(deviceUserId, jpeg);
        public ReaderCallResult RemoveFace(string deviceUserId) => inner.RemoveFace(deviceUserId);
        public ReaderCallResult RemoveUser(string deviceUserId) => inner.RemoveUser(deviceUserId);
        public ReaderPunchResult QueryAttendance(DateTimeOffset fromUtc, DateTimeOffset toUtc) => inner.QueryAttendance(fromUtc, toUtc);
    }

    private ReaderWorker Worker(FakeReader reader, string name, Func<bool>? online = null) =>
        new("r", reader, System.IO.Path.Combine(_directory, name + ".sqlite"), TimeSpan.FromMinutes(1), () => _now, online);

    private static DesiredRevisionPath Path(
        ReaderWorker worker, FakeReader reader, Server server, IReaderObservationUpload? upload = null) =>
        new("r", worker, reader, server, upload, null, new DesiredPathOptions { ReconnectWait = TimeSpan.Zero });

    private static DesiredRevisionNotice Notice(long revision) => new("r", revision);

    private static string Text(DateTimeOffset value) => ReaderLocalTime.Format(value);

    private static ExpectedOccupant Verified(string name, byte[] face) =>
        new(true, name, null, 0, Text(ValidFrom), Text(ValidTo), "Customer", FaceHash.Sha256Hex(face));

    private static DesiredPullItem Item(long revision, string id, string name, bool present = true,
        ExpectedOccupant? expected = null) => new(
        revision, id, name, null, 0, Text(ValidFrom), Text(ValidTo), "Customer", 1, 1,
        present ? Face : [], present, present, expected ?? ExpectedOccupant.Absent);

    private static ReaderUser Person(string id, string name) =>
        new(id, name, null, 0, ValidFrom, ValidTo, "Customer", 1, 1);

    private static void Enrol(FakeReader reader, string id, string name)
    {
        Assert.True(reader.CreateUser(Person(id, name)).Ok);
        Assert.True(reader.InsertFace(id, name == "Asha" ? Face : BobFace).Ok);
    }

    private sealed record Report(long Revision, string DeviceUserId, ReaderOccupant Occupant);

    /// <summary>
    /// The server side. A report holds the row, so it is no longer pulled. <see cref="Processing"/>
    /// says whether observations have been processed yet.
    /// </summary>
    private sealed class Server : IDesiredStateClient
    {
        public List<DesiredPullItem> Items { get; set; } = [];
        public List<Report> Reports { get; } = [];
        public List<string> Occupied { get; } = [];
        public List<long> AckedRevisions { get; } = [];
        public List<RetryState> Failures { get; } = [];
        public long? Desired { get; set; }
        public int PageSize { get; set; } = 100;
        public int FailAcks { get; set; }
        public int RefuseAcks { get; set; }
        public int FailReports { get; set; }
        public int FailChecks { get; set; }
        public bool Processing { get; set; } = true;
        public int Pulls { get; private set; }
        public int Checks { get; private set; }

        public Task<DesiredPull> PullAsync(string deviceId, long after, CancellationToken cancellationToken)
        {
            Pulls++;
            var items = Items.Where(item => item.Revision > after).OrderBy(item => item.Revision).Take(PageSize).ToList();
            var desired = Desired ?? (Items.Count == 0 ? 0 : Items.Max(item => item.Revision));
            return Task.FromResult(new DesiredPull(desired, after, items));
        }

        public Task AcknowledgeAsync(DesiredAcknowledgement ack, CancellationToken cancellationToken)
        {
            if (FailAcks > 0)
            {
                FailAcks--;
                throw new HttpRequestException("network down");
            }

            if (RefuseAcks > 0)
            {
                RefuseAcks--;
                throw new DesiredRefusedException("Read-back does not match the desired member");
            }

            if (Items.All(item => item.Revision != ack.Revision))
            {
                throw new DesiredRefusedException("Desired revision is superseded");
            }

            AckedRevisions.Add(ack.Revision);
            return Task.CompletedTask;
        }

        public Task ReportOccupiedAsync(string deviceId, long revision, string deviceUserId, CancellationToken cancellationToken)
        {
            Occupied.Add(deviceUserId);
            Items = Items.Where(item => item.Revision != revision).ToList();
            return Task.CompletedTask;
        }

        public Task ReportOccupantAsync(string deviceId, long revision, string deviceUserId, ReaderOccupant occupant,
            CancellationToken cancellationToken)
        {
            if (FailReports > 0)
            {
                FailReports--;
                throw new HttpRequestException("network down");
            }

            Reports.Add(new Report(revision, deviceUserId, occupant));
            Items = Items.Where(item => item.Revision != revision).ToList();
            return Task.CompletedTask;
        }

        public Task<bool> ObservationProcessedAsync(string deviceId, IReadOnlyList<string> messageIds,
            CancellationToken cancellationToken)
        {
            Checks++;
            if (FailChecks > 0)
            {
                FailChecks--;
                throw new HttpRequestException("response lost");
            }

            return Task.FromResult(Processing);
        }

        public Task ReportFailedAsync(string deviceId, RetryState attempt, CancellationToken cancellationToken)
        {
            Failures.Add(attempt);
            return Task.CompletedTask;
        }
    }
}
