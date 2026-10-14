using System.Collections.Concurrent;
using System.Diagnostics;
using System.Net;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using Gym.Gateway.Adapters;
using Gym.Gateway.Execution;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

namespace Gym.Gateway.Tests;

/// <summary>
/// A scan sends the per-user observations only when the server did not acknowledge the whole list.
/// Acceptance is the server's ACK naming the list, sent after it processed and committed it. It is
/// not the frame being written, and it says nothing about what a reader applied.
/// </summary>
public sealed class RosterAcceptanceTests : IDisposable
{
    private static readonly DateTimeOffset ValidFrom = new(2026, 10, 8, 0, 0, 0, TimeSpan.FromHours(5.5));
    private static readonly DateTimeOffset ValidTo = new(2026, 10, 8, 23, 59, 59, TimeSpan.FromHours(5.5));
    private static readonly byte[] Face = [1, 2, 3, 4, 5];
    private readonly string _directory = Path.Combine(Path.GetTempPath(), "gym-acceptance-" + Guid.NewGuid().ToString("N"));

    public RosterAcceptanceTests()
    {
        Directory.CreateDirectory(_directory);
    }

    public void Dispose()
    {
        if (Directory.Exists(_directory))
        {
            Directory.Delete(_directory, recursive: true);
        }
    }

    // --- the scan, with a recording upload ------------------------------------------------------

    [Fact]
    public async Task An_acknowledged_list_on_a_scan_sends_no_per_user_messages()
    {
        var sink = new PendingEnrollmentTests.RecordingUpload { RosterAccepted = true };
        using var worker = Worker("accepted");
        var path = new DesiredRevisionPath("reader-a", worker, ReaderWith("7", "8"), new PendingEnrollmentTests.EmptyPull(), sink);

        Assert.True(await path.BootstrapAsync(CancellationToken.None));

        Assert.Equal(new[] { "list-awaited" }, sink.Calls);
        Assert.Empty(sink.Ids);
        Assert.Equal(new[] { "7", "8" }, sink.RosterIds);
    }

    [Fact]
    public async Task A_list_that_is_not_acknowledged_is_followed_by_every_per_user_message()
    {
        var sink = new PendingEnrollmentTests.RecordingUpload { RosterAccepted = false };
        using var worker = Worker("not-accepted");
        var path = new DesiredRevisionPath("reader-a", worker, ReaderWith("7", "8"), new PendingEnrollmentTests.EmptyPull(), sink);

        await path.BootstrapAsync(CancellationToken.None);

        Assert.Equal(new[] { "list-awaited", "users" }, sink.Calls);
        Assert.Equal(new[] { "7", "8" }, sink.Ids);
    }

    [Fact]
    public async Task A_list_upload_that_fails_still_sends_every_per_user_message()
    {
        var sink = new PendingEnrollmentTests.RecordingUpload { RosterAccepted = true, FailList = true };
        using var worker = Worker("list-fails");
        var path = new DesiredRevisionPath("reader-a", worker, ReaderWith("7"), new PendingEnrollmentTests.EmptyPull(), sink);

        await path.BootstrapAsync(CancellationToken.None);

        Assert.Equal(new[] { "users" }, sink.Calls);
        Assert.Equal(new[] { "7" }, sink.Ids);
    }

    [Fact]
    public async Task Reconnect_sends_the_per_user_messages_before_the_list_even_when_lists_are_acknowledged()
    {
        var sink = new PendingEnrollmentTests.RecordingUpload { RosterAccepted = true };
        using var worker = Worker("reconnect");
        var path = new DesiredRevisionPath("reader-a", worker, ReaderWith("7"), new PendingEnrollmentTests.EmptyPull(), sink);

        await path.ReconnectAsync(CancellationToken.None);

        Assert.Equal(new[] { "users", "list" }, sink.Calls);
        Assert.Equal(new[] { "7" }, sink.Ids);
    }

    [Fact]
    public async Task Absences_are_sent_whether_or_not_the_list_is_acknowledged()
    {
        var reader = new FakeReader();
        using var worker = Worker("absences");
        Assert.Equal(MemberApplyKind.Applied, worker.ApplyMember(new DesiredMember(
            1, "1", "Asha Shah", null, 0, ValidFrom, ValidTo, "Customer", 1, 1, Face)).Kind);
        var stillHere = Person("2");
        reader.ScriptList(1, stillHere);
        reader.ScriptList(1, stillHere);
        var sink = new PendingEnrollmentTests.RecordingUpload { RosterAccepted = false };
        var path = new DesiredRevisionPath("reader-a", worker, reader, new PendingEnrollmentTests.EmptyPull(), sink);

        await path.BootstrapAsync(CancellationToken.None);
        sink.RosterAccepted = true;
        await path.BootstrapAsync(CancellationToken.None);

        Assert.Equal(new[] { "absences", "list-awaited", "users", "absences", "list-awaited" }, sink.Calls);
        Assert.Equal(new[] { "1", "1" }, sink.AbsentIds);
        Assert.Equal(new[] { "2" }, sink.Ids);
    }

    [Fact]
    public async Task An_empty_list_sends_no_per_user_messages_either_way()
    {
        var reader = new FakeReader();
        reader.ScriptList(0);
        reader.ScriptList(0);
        var sink = new PendingEnrollmentTests.RecordingUpload { RosterAccepted = false };
        using var worker = Worker("empty");
        var path = new DesiredRevisionPath("reader-a", worker, reader, new PendingEnrollmentTests.EmptyPull(), sink);

        await path.BootstrapAsync(CancellationToken.None);
        sink.RosterAccepted = true;
        await path.BootstrapAsync(CancellationToken.None);

        Assert.Equal(new[] { "list-awaited", "list-awaited" }, sink.Calls);
        Assert.Empty(sink.Ids);
    }

    // --- the whole chain over REST, against a server-like handler -------------------------------

    [Fact]
    public async Task Initial_sync_against_a_server_that_acknowledges_delivers_only_the_list()
    {
        var server = new ServerLike();
        var (path, store) = Chain("initial", server, ReaderWith("7", "8"));

        await path.BootstrapAsync(CancellationToken.None);

        var delivered = Assert.Single(server.Delivered);
        Assert.Equal(2, delivered.Payload.GetProperty("announcedTotal").GetInt32());
        Assert.Empty(store.LoadPending());
    }

    [Fact]
    public async Task A_list_the_server_rejects_is_followed_by_the_per_user_messages()
    {
        var server = new ServerLike { Answer = envelope => IsList(envelope) ? Answer.Error : Answer.Ack };
        var (path, store) = Chain("rejected", server, ReaderWith("7", "8"));

        await path.BootstrapAsync(CancellationToken.None);

        Assert.Equal(new[] { "list", "7", "8" }, server.Delivered.Select(Describe));
        Assert.Empty(store.LoadPending());
    }

    [Fact]
    public async Task A_list_answered_with_a_server_error_stays_queued_and_the_per_user_messages_are_sent()
    {
        var server = new ServerLike { Answer = envelope => IsList(envelope) ? Answer.Status500 : Answer.Ack };
        var (path, store) = Chain("status-500", server, ReaderWith("7"));

        await path.BootstrapAsync(CancellationToken.None);

        Assert.Equal(new[] { "list", "7" }, server.Delivered.Select(Describe));
        Assert.Equal(new[] { "list" }, store.LoadPending().Select(Describe));
    }

    [Fact]
    public async Task A_reply_that_names_no_ack_is_not_acceptance()
    {
        var server = new ServerLike { Answer = envelope => IsList(envelope) ? Answer.Empty : Answer.Ack };
        var (path, _) = Chain("no-ack", server, ReaderWith("7"));

        await path.BootstrapAsync(CancellationToken.None);

        Assert.Equal(new[] { "list", "7" }, server.Delivered.Select(Describe));
    }

    [Fact]
    public async Task A_list_over_the_size_limit_is_not_sent_and_the_per_user_messages_are()
    {
        var reader = new FakeReader();
        var longName = new string('a', 2_000);
        for (var i = 1; i <= 600; i++)
        {
            reader.CreateUser(new ReaderUser(i.ToString(), "Person", longName, 0, ValidFrom, ValidTo, "Customer", 1, 1));
        }

        var server = new ServerLike();
        var (path, store) = Chain("oversized", server, reader);

        await path.BootstrapAsync(CancellationToken.None);

        Assert.DoesNotContain(server.Delivered, IsList);
        Assert.Equal(600, server.Delivered.Count);
        Assert.Empty(store.LoadPending());
    }

    [Fact]
    public async Task After_a_restart_per_user_messages_are_skipped_only_after_a_fresh_acknowledgement()
    {
        var server = new ServerLike { Answer = _ => Answer.Unreachable };
        var (before, beforeStore) = Chain("restart", server, ReaderWith("7", "8"));

        await before.BootstrapAsync(CancellationToken.None);

        Assert.Empty(server.Delivered);
        Assert.Equal(new[] { "7", "8", "list" }, beforeStore.LoadPending().Select(Describe).Order());

        server.Answer = _ => Answer.Ack;
        var (after, afterStore, link) = ChainWithLink("restart", server, ReaderWith("7", "8"));
        await link.ReplayPendingAsync(CancellationToken.None);
        Assert.Equal(new[] { "7", "8", "list" }, server.Delivered.Select(Describe).Order());

        await after.BootstrapAsync(CancellationToken.None);

        Assert.Equal(4, server.Delivered.Count);
        Assert.Equal("list", Describe(server.Delivered[^1]));
        Assert.Empty(afterStore.LoadPending());
    }

    // --- the backend link over a WebSocket ------------------------------------------------------

    [Fact]
    public async Task An_ack_over_the_socket_is_acceptance()
    {
        using var backend = new SocketBackend(_ => Answer.Ack);
        await using var session = await SocketSession.OpenAsync(backend, Path.Combine(_directory, "ws-ack"));

        var list = List("reader-a");
        Assert.True(await session.Link.SendAndAwaitAckAsync(list, TimeSpan.FromSeconds(10), CancellationToken.None));

        Assert.False(session.Store.IsPending(list.MessageId));
    }

    [Fact]
    public async Task A_list_without_an_ack_before_the_timeout_is_not_accepted_and_stays_queued()
    {
        using var backend = new SocketBackend(_ => Answer.Silent);
        await using var session = await SocketSession.OpenAsync(backend, Path.Combine(_directory, "ws-silent"));

        var list = List("reader-a");
        Assert.False(await session.Link.SendAndAwaitAckAsync(list, TimeSpan.FromMilliseconds(300), CancellationToken.None));

        Assert.True(session.Store.IsPending(list.MessageId));
    }

    [Fact]
    public async Task A_list_the_server_rejects_over_the_socket_is_not_accepted_without_waiting_for_the_timeout()
    {
        using var backend = new SocketBackend(_ => Answer.Error);
        await using var session = await SocketSession.OpenAsync(backend, Path.Combine(_directory, "ws-error"));

        var clock = Stopwatch.StartNew();
        Assert.False(await session.Link.SendAndAwaitAckAsync(List("reader-a"), TimeSpan.FromSeconds(30), CancellationToken.None));

        Assert.True(clock.Elapsed < TimeSpan.FromSeconds(10));
    }

    [Fact]
    public async Task A_list_replaced_by_a_newer_list_of_the_same_reader_is_not_accepted()
    {
        var answered = 0;
        using var backend = new SocketBackend(_ => Interlocked.Increment(ref answered) == 1 ? Answer.Silent : Answer.Ack);
        await using var session = await SocketSession.OpenAsync(backend, Path.Combine(_directory, "ws-replaced"));

        var older = List("reader-a");
        var clock = Stopwatch.StartNew();
        var waiting = session.Link.SendAndAwaitAckAsync(older, TimeSpan.FromSeconds(30), CancellationToken.None);
        await backend.WaitForAsync(1);
        var newer = List("reader-a");
        var newerAccepted = await session.Link.SendAndAwaitAckAsync(newer, TimeSpan.FromSeconds(10), CancellationToken.None);

        Assert.False(await waiting);
        Assert.True(clock.Elapsed < TimeSpan.FromSeconds(20));
        Assert.True(newerAccepted);
        Assert.False(session.Store.IsPending(older.MessageId));
        Assert.False(session.Store.IsPending(newer.MessageId));
    }

    [Fact]
    public async Task A_connection_dropped_before_the_ack_is_not_acceptance_and_the_list_is_replayed()
    {
        var answered = 0;
        using var backend = new SocketBackend(_ => Interlocked.Increment(ref answered) == 1 ? Answer.Drop : Answer.Ack);
        await using var session = await SocketSession.OpenAsync(backend, Path.Combine(_directory, "ws-drop"));

        var list = List("reader-a");
        Assert.False(await session.Link.SendAndAwaitAckAsync(list, TimeSpan.FromMilliseconds(500), CancellationToken.None));

        var deadline = DateTime.UtcNow.AddSeconds(15);
        while (session.Store.IsPending(list.MessageId) && DateTime.UtcNow < deadline)
        {
            await Task.Delay(100);
        }

        Assert.True(!session.Store.IsPending(list.MessageId),
            $"received={backend.Received.Count} connections={backend.Connections} live={session.Link.WebSocketLive}");
        Assert.Equal(2, backend.Received.Count(envelope => envelope.MessageId == list.MessageId));
    }

    // --- helpers -------------------------------------------------------------------------------

    private ReaderWorker Worker(string name) => new("reader-a", new FakeReader(), Path.Combine(_directory, name + ".sqlite"));

    private static FakeReader ReaderWith(params string[] ids)
    {
        var reader = new FakeReader();
        foreach (var id in ids)
        {
            reader.CreateUser(Person(id));
        }

        return reader;
    }

    private static ReaderUser Person(string id) => new(id, "Person " + id, null, 0, ValidFrom, ValidTo, "Customer", 1, 1);

    private (DesiredRevisionPath Path, DurableOutboundStore Store) Chain(string name, ServerLike server, FakeReader reader)
    {
        var (path, store, _) = ChainWithLink(name, server, reader);
        return (path, store);
    }

    private (DesiredRevisionPath Path, DurableOutboundStore Store, BackendLink Link) ChainWithLink(
        string name, ServerLike server, FakeReader reader)
    {
        var store = new DurableOutboundStore(NullLogger<DurableOutboundStore>.Instance, Path.Combine(_directory, name + "-outbox"));
        var link = new BackendLink(
            new GatewayOptions { Id = "gw-1", Token = "token", BackendUrl = "http://backend", UseWebSocket = false },
            NullLogger<BackendLink>.Instance, store, new HttpClient(server));
        var upload = new ReaderObservationUpload("gw-1", link, NullLogger<ReaderObservationUpload>.Instance);
        var worker = new ReaderWorker("reader-a", reader, Path.Combine(_directory, name + "-" + Guid.NewGuid().ToString("N") + ".sqlite"));
        return (new DesiredRevisionPath("reader-a", worker, reader, new PendingEnrollmentTests.EmptyPull(), upload), store, link);
    }

    private static GatewayEnvelope List(string reader) =>
        GatewayEnvelope.Create("gw-1", ProtocolTypes.DeviceUserChanged, new { announcedTotal = 0, users = Array.Empty<object>() }, reader);

    private static bool IsList(GatewayEnvelope envelope) => DurableOutboundStore.SnapshotKey(envelope) != null;

    private static string Describe(GatewayEnvelope envelope) =>
        IsList(envelope) ? "list" : envelope.Payload.GetProperty("deviceUserId").GetString()!;

    private enum Answer
    {
        Ack,
        Error,
        Status500,
        Empty,
        Unreachable,
        Silent,
        Drop
    }

    private static string Reply(GatewayEnvelope envelope, Answer answer) => answer == Answer.Ack
        ? JsonSerializer.Serialize(new
        {
            messageId = Guid.NewGuid().ToString(),
            type = "ACK",
            correlationId = envelope.CorrelationId,
            payload = new { messageId = envelope.MessageId }
        })
        : JsonSerializer.Serialize(new
        {
            messageId = Guid.NewGuid().ToString(),
            type = "ERROR",
            correlationId = envelope.CorrelationId,
            payload = new { error = "processing failed" }
        });

    /// <summary>Answers like the server's REST ingest: 200 with an ACK or ERROR envelope.</summary>
    private sealed class ServerLike : HttpMessageHandler
    {
        public Func<GatewayEnvelope, Answer> Answer { get; set; } = _ => RosterAcceptanceTests.Answer.Ack;

        public List<GatewayEnvelope> Delivered { get; } = [];

        protected override async Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken cancellationToken)
        {
            var body = await request.Content!.ReadAsStringAsync(cancellationToken);
            var envelope = JsonSerializer.Deserialize<GatewayEnvelope>(body, JsonOptions.Inbound)!;
            var answer = Answer(envelope);
            if (answer == RosterAcceptanceTests.Answer.Unreachable)
            {
                throw new HttpRequestException("backend unreachable");
            }

            Delivered.Add(envelope);
            return answer switch
            {
                RosterAcceptanceTests.Answer.Status500 => new HttpResponseMessage(HttpStatusCode.InternalServerError)
                {
                    Content = new StringContent("{}")
                },
                RosterAcceptanceTests.Answer.Empty => new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent("{}", Encoding.UTF8, "application/json")
                },
                _ => new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(Reply(envelope, answer), Encoding.UTF8, "application/json")
                }
            };
        }
    }

    /// <summary>A WebSocket endpoint at /gateway that answers each message as told.</summary>
    private sealed class SocketBackend : IDisposable
    {
        private readonly HttpListener _listener = new();
        private readonly CancellationTokenSource _stop = new();
        private readonly Func<GatewayEnvelope, Answer> _answer;

        public SocketBackend(Func<GatewayEnvelope, Answer> answer)
        {
            _answer = answer;
            var probe = new System.Net.Sockets.TcpListener(IPAddress.Loopback, 0);
            probe.Start();
            var port = ((IPEndPoint)probe.LocalEndpoint).Port;
            probe.Stop();
            BaseUrl = $"http://127.0.0.1:{port}";
            _listener.Prefixes.Add(BaseUrl + "/");
            _listener.Start();
            _ = Task.Run(AcceptAsync);
        }

        private int _connections;

        public string BaseUrl { get; }

        public int Connections => _connections;

        public ConcurrentQueue<GatewayEnvelope> Received { get; } = new();

        public async Task WaitForAsync(int count)
        {
            var deadline = DateTime.UtcNow.AddSeconds(10);
            while (Received.Count < count && DateTime.UtcNow < deadline)
            {
                await Task.Delay(20);
            }
        }

        public void Dispose()
        {
            _stop.Cancel();
            _listener.Close();
        }

        private async Task AcceptAsync()
        {
            while (!_stop.IsCancellationRequested)
            {
                HttpListenerContext context;
                try
                {
                    context = await _listener.GetContextAsync().WaitAsync(_stop.Token);
                }
                catch (Exception)
                {
                    return;
                }

                if (!context.Request.IsWebSocketRequest)
                {
                    context.Response.StatusCode = 400;
                    context.Response.Close();
                    continue;
                }

                var socket = (await context.AcceptWebSocketAsync(null)).WebSocket;
                Interlocked.Increment(ref _connections);
                _ = Task.Run(() => ServeAsync(socket));
            }
        }

        private async Task ServeAsync(WebSocket socket)
        {
            var buffer = new byte[64 * 1024];
            try
            {
                while (socket.State == WebSocketState.Open && !_stop.IsCancellationRequested)
                {
                    using var message = new MemoryStream();
                    WebSocketReceiveResult result;
                    do
                    {
                        result = await socket.ReceiveAsync(buffer, _stop.Token);
                        if (result.MessageType == WebSocketMessageType.Close)
                        {
                            return;
                        }

                        message.Write(buffer, 0, result.Count);
                    } while (!result.EndOfMessage);

                    var envelope = JsonSerializer.Deserialize<GatewayEnvelope>(message.ToArray(), JsonOptions.Inbound)!;
                    Received.Enqueue(envelope);
                    var answer = _answer(envelope);
                    if (answer == Answer.Drop)
                    {
                        await socket.CloseAsync(WebSocketCloseStatus.EndpointUnavailable, "going away", CancellationToken.None);
                        return;
                    }

                    if (answer == Answer.Silent)
                    {
                        continue;
                    }

                    var reply = Encoding.UTF8.GetBytes(Reply(envelope, answer));
                    await socket.SendAsync(reply, WebSocketMessageType.Text, true, _stop.Token);
                }
            }
            catch (Exception)
            {
                // The gateway closed or the test ended.
            }
        }
    }

    private sealed class SocketSession : IAsyncDisposable
    {
        private readonly CancellationTokenSource _stop = new();
        private Task _running = Task.CompletedTask;

        private SocketSession(BackendLink link, DurableOutboundStore store)
        {
            Link = link;
            Store = store;
        }

        public BackendLink Link { get; }

        public DurableOutboundStore Store { get; }

        public static async Task<SocketSession> OpenAsync(SocketBackend backend, string outbox)
        {
            var store = new DurableOutboundStore(NullLogger<DurableOutboundStore>.Instance, outbox);
            var link = new BackendLink(
                new GatewayOptions
                {
                    Id = "gw-1",
                    Token = "token",
                    BackendUrl = backend.BaseUrl,
                    UseWebSocket = true,
                    ReconnectBaseSeconds = 1,
                    ReconnectMaxSeconds = 1
                },
                NullLogger<BackendLink>.Instance, store);
            var session = new SocketSession(link, store);
            session._running = Task.Run(() => link.RunWebSocketAsync(_ => Task.CompletedTask, session._stop.Token));
            var deadline = DateTime.UtcNow.AddSeconds(10);
            while (!link.WebSocketLive && DateTime.UtcNow < deadline)
            {
                await Task.Delay(20);
            }

            Assert.True(link.WebSocketLive);
            return session;
        }

        public async ValueTask DisposeAsync()
        {
            _stop.Cancel();
            try
            {
                await _running;
            }
            catch (Exception)
            {
                // Stopping.
            }

            await Link.DisposeAsync();
        }
    }
}
