using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Gym.Gateway;
using Gym.Gateway.Adapters;
using Gym.Gateway.Execution;

namespace Gym.Gateway.V1Harness;

internal static class Program
{
    public static async Task<int> Main(string[] args)
    {
        var arguments = Arguments.Parse(args);
        try
        {
            return await Harness.RunAsync(arguments);
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine(ex);
            return 1;
        }
    }
}

internal static class Harness
{
    public static async Task<int> RunAsync(Arguments arguments)
    {
        using var http = DesiredStateClient.CreateHttp(arguments.Token);
        var client = new DesiredStateClient(http, arguments.BaseUrl);
        var reader = new FakeReader();
        if (arguments.Mode == "occupied")
        {
            var seeded = reader.CreateUser(new ReaderUser(
                arguments.Occupy, "Already there", null, 0,
                new DateTimeOffset(2026, 10, 8, 0, 0, 0, TimeSpan.FromHours(5.5)),
                new DateTimeOffset(2026, 10, 8, 23, 59, 59, TimeSpan.FromHours(5.5)),
                "Customer", 1, 1));
            if (!seeded.Ok)
            {
                throw new InvalidOperationException(seeded.Error ?? "pre-seed failed");
            }

            var face = reader.InsertFace(arguments.Occupy, [9, 9, 9, 9]);
            if (!face.Ok)
            {
                throw new InvalidOperationException(face.Error ?? "pre-seed face failed");
            }
        }

        if (arguments.Mode == "face")
        {
            reader.ScriptFaceReadBack([7, 7, 7, 7]);
        }

        if (arguments.Mode == "held")
        {
            reader.ScriptReportedStatus(0);
        }

        var baseline = reader.Writes.Count;
        var journalDirectory = Path.GetDirectoryName(arguments.Journal);
        if (!string.IsNullOrEmpty(journalDirectory))
        {
            Directory.CreateDirectory(journalDirectory);
        }

        using var socket = new ClientWebSocket();
        socket.Options.SetRequestHeader("Authorization", "Bearer " + arguments.Token);
        using var lifetime = new CancellationTokenSource(TimeSpan.FromSeconds(60));
        await socket.ConnectAsync(WebSocketUri(arguments.BaseUrl), lifetime.Token).ConfigureAwait(false);

        var receiving = ReceiveTextAsync(socket, lifetime.Token);
        Console.WriteLine("READY");
        await Console.Out.FlushAsync().ConfigureAwait(false);
        var noticeJson = await receiving.ConfigureAwait(false);

        var legacy = false;
        var executedAgain = false;
        long applied;
        int pending;
        using (var first = Start(arguments, reader))
        {
            if (!await RouteAsync(noticeJson, arguments, first, reader, client, () => legacy = true, lifetime.Token)
                    .ConfigureAwait(false))
            {
                throw new InvalidOperationException("The revision notice was not routed to the reader path");
            }

            if (arguments.Mode != "restart")
            {
                (applied, pending) = Snapshot(first);
            }
            else
            {
                applied = 0;
                pending = 0;
            }
        }

        if (arguments.Mode == "restart")
        {
            var beforeRestart = reader.Writes.Count;
            using var restarted = Start(arguments, reader);
            if (!await RouteAsync(noticeJson, arguments, restarted, reader, client, () => legacy = true, lifetime.Token)
                    .ConfigureAwait(false))
            {
                throw new InvalidOperationException("The revision notice was not routed to the reader path");
            }

            executedAgain = reader.Writes.Count != beforeRestart;
            (applied, pending) = Snapshot(restarted);
        }
        else
        {
            (applied, pending) = await DrainAsync(
                socket, arguments, reader, client, () => legacy = true, (applied, pending), lifetime.Token)
                .ConfigureAwait(false);
        }

        return await Finish(
            arguments,
            reader,
            client,
            baseline,
            new RunOutcome(applied, pending, executedAgain, legacy)).ConfigureAwait(false);
    }

    private static (long Applied, int Pending) Snapshot(ReaderWorker worker) =>
        (worker.AppliedRevision, worker.PendingAcks.Count);

    private static async Task<bool> RouteAsync(
        string json,
        Arguments arguments,
        ReaderWorker worker,
        FakeReader reader,
        DesiredStateClient client,
        Action onLegacy,
        CancellationToken cancellationToken)
    {
        var path = new DesiredRevisionPath(arguments.DeviceId, worker, reader, client);
        return await InboundDispatch.RouteAsync(
            json,
            _ =>
            {
                onLegacy();
                return Task.FromException(new InvalidOperationException("legacy command dispatcher received the revision"));
            },
            (notice, token) => path.HandleAsync(notice, token),
            cancellationToken).ConfigureAwait(false);
    }

    private readonly record struct RunOutcome(long Applied, int Pending, bool ExecutedAgain, bool Legacy);

    private static async Task<int> Finish(
        Arguments arguments,
        FakeReader reader,
        DesiredStateClient client,
        int baseline,
        RunOutcome outcome)
    {
        var writes = reader.Writes.Skip(baseline).ToArray();
        var users = reader.ListUsers().Users.Select(user =>
        {
            var face = reader.GetFace(user.DeviceUserId);
            var bytes = face.Bytes ?? [];
            return new
            {
                id = user.DeviceUserId,
                name = user.Name,
                nameEx = user.NameEx,
                status = user.UserStatus,
                validFrom = user.ValidFrom is DateTimeOffset from ? ReaderLocalTime.Format(from) : null,
                validTo = user.ValidTo is DateTimeOffset to ? ReaderLocalTime.Format(to) : null,
                faceHex = Convert.ToHexString(bytes).ToLowerInvariant(),
                faceSha256 = bytes.Length == 0
                    ? ""
                    : Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant()
            };
        }).ToArray();
        var created = writes.LastOrDefault(line => line.StartsWith("CreateUser ", StringComparison.Ordinal));
        var createdId = created == null ? null : created["CreateUser ".Length..];
        var publicIdLeaked = arguments.PublicId.Length > 0 && Leaked(
            arguments.PublicId,
            writes,
            client.PullTranscript,
            client.OccupiedIds,
            users.Select(user => (user.id, user.name, user.nameEx)));
        var acked = client.AcknowledgementPosts > 0;
        var ok = arguments.Mode switch
        {
            "face" => !acked && outcome.Applied == 0 && outcome.Pending == 0 && !publicIdLeaked && !outcome.Legacy,
            "occupied" => acked
                && client.OccupiedIds.Count == 1
                && client.OccupiedIds[0] == arguments.Occupy
                && !string.IsNullOrWhiteSpace(createdId)
                && createdId != arguments.Occupy
                && createdId != arguments.PublicId
                && !publicIdLeaked
                && !outcome.ExecutedAgain
                && !outcome.Legacy,
            _ => acked && !publicIdLeaked && !outcome.ExecutedAgain && !outcome.Legacy
        };
        var report = JsonSerializer.Serialize(new
        {
            ok,
            acked,
            ackCount = client.AcknowledgementPosts,
            publicIdLeaked,
            pullTranscript = client.PullTranscript,
            executedAgain = outcome.ExecutedAgain,
            legacyDispatched = outcome.Legacy,
            occupiedId = client.OccupiedIds.FirstOrDefault(),
            createdId,
            appliedLocal = outcome.Applied,
            pendingAcks = outcome.Pending,
            writes,
            users
        });
        if (!string.IsNullOrWhiteSpace(arguments.ResultPath))
        {
            await File.WriteAllTextAsync(arguments.ResultPath, report).ConfigureAwait(false);
        }

        Console.WriteLine(report);
        await Console.Out.FlushAsync().ConfigureAwait(false);
        return ok ? 0 : 1;
    }

    private static bool Leaked(
        string publicId,
        IReadOnlyList<string> writes,
        string transcript,
        IReadOnlyList<string> occupiedIds,
        IEnumerable<(string Id, string? Name, string? NameEx)> users)
    {
        if (writes.Any(line => line.Contains(publicId, StringComparison.Ordinal)))
        {
            return true;
        }

        if (transcript.Contains(publicId, StringComparison.Ordinal))
        {
            return true;
        }

        if (occupiedIds.Any(id => string.Equals(id, publicId, StringComparison.Ordinal)))
        {
            return true;
        }

        return users.Any(user =>
            string.Equals(user.Id, publicId, StringComparison.Ordinal)
            || (user.Name != null && user.Name.Contains(publicId, StringComparison.Ordinal))
            || (user.NameEx != null && user.NameEx.Contains(publicId, StringComparison.Ordinal)));
    }

    private static async Task<(long Applied, int Pending)> DrainAsync(
        ClientWebSocket socket,
        Arguments arguments,
        FakeReader reader,
        DesiredStateClient client,
        Action onLegacy,
        (long Applied, int Pending) cursor,
        CancellationToken cancellationToken)
    {
        var idle = arguments.Mode is "freeze" or "held" or "edit" or "photo"
            ? TimeSpan.FromSeconds(20)
            : TimeSpan.FromSeconds(1);
        while (socket.State == WebSocketState.Open)
        {
            using var extra = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            extra.CancelAfter(idle);
            string json;
            try
            {
                json = await ReceiveTextAsync(socket, extra.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return cursor;
            }

            using var worker = Start(arguments, reader);
            var handled = await RouteAsync(json, arguments, worker, reader, client, onLegacy, cancellationToken)
                .ConfigureAwait(false);
            if (!handled)
            {
                onLegacy();
                throw new InvalidOperationException("Follow-up frame was not a desired revision");
            }

            cursor = Snapshot(worker);
            if (AccessSettled(arguments.Mode, reader, worker))
            {
                return cursor;
            }
        }

        return cursor;
    }

    private static bool AccessSettled(string mode, FakeReader reader, ReaderWorker worker)
    {
        var replacements = reader.Writes.Count(line => line.StartsWith("ReplaceUser ", StringComparison.Ordinal));
        var faceUpdates = reader.Writes.Count(line => line.StartsWith("UpdateFace ", StringComparison.Ordinal));
        return mode switch
        {
            "freeze" => replacements >= 2 && worker.AppliedRevision >= 3,
            "held" => replacements >= 1 && worker.AppliedRevision == 1 && worker.Retry != null,
            "edit" => replacements >= 3 && worker.AppliedRevision >= 4,
            "photo" => faceUpdates >= 1 && worker.AppliedRevision >= 2,
            _ => false
        };
    }

    private static ReaderWorker Start(Arguments arguments, FakeReader reader) =>
        new(arguments.DeviceId, reader, arguments.Journal, TimeSpan.Zero);

    private static Uri WebSocketUri(string baseUrl)
    {
        var http = new Uri(baseUrl);
        var builder = new UriBuilder(http)
        {
            Scheme = http.Scheme == "https" ? "wss" : "ws",
            Path = "gateway",
            Query = ""
        };
        return builder.Uri;
    }

    private static async Task<string> ReceiveTextAsync(ClientWebSocket socket, CancellationToken cancellationToken)
    {
        var buffer = new byte[64 * 1024];
        using var message = new MemoryStream();
        WebSocketReceiveResult result;
        do
        {
            result = await socket.ReceiveAsync(buffer, cancellationToken).ConfigureAwait(false);
            if (result.MessageType == WebSocketMessageType.Close)
            {
                throw new InvalidOperationException("WebSocket closed before the revision notice");
            }

            await message.WriteAsync(buffer.AsMemory(0, result.Count), cancellationToken).ConfigureAwait(false);
        } while (!result.EndOfMessage);

        return Encoding.UTF8.GetString(message.ToArray());
    }
}

internal sealed class Arguments
{
    public required string BaseUrl { get; init; }
    public required string Token { get; init; }
    public required string DeviceId { get; init; }
    public required string PublicId { get; init; }
    public required string Mode { get; init; }
    public required string Journal { get; init; }
    public string Occupy { get; init; } = "";
    public string? ResultPath { get; init; }

    public static Arguments Parse(string[] args)
    {
        var values = new Dictionary<string, string>(StringComparer.Ordinal);
        var i = 0;
        while (i < args.Length)
        {
            if (!args[i].StartsWith("--", StringComparison.Ordinal) || i + 1 >= args.Length)
            {
                throw new InvalidOperationException("Expected --name value pairs");
            }

            values[args[i][2..]] = args[i + 1];
            i += 2;
        }

        string Required(string name) =>
            values.TryGetValue(name, out var value) && value.Length > 0
                ? value
                : throw new InvalidOperationException("Missing --" + name);

        return new Arguments
        {
            BaseUrl = Required("base"),
            Token = Required("token"),
            DeviceId = Required("device"),
            PublicId = values.GetValueOrDefault("public-id") ?? "",
            Mode = Required("mode"),
            Journal = Required("journal"),
            Occupy = values.GetValueOrDefault("occupy") ?? "",
            ResultPath = values.GetValueOrDefault("result")
        };
    }
}
