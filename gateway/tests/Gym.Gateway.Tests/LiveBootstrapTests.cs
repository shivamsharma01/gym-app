using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Gym.Gateway.Adapters;
using Gym.Gateway.Execution;
using Microsoft.Extensions.Logging;
using Xunit;

namespace Gym.Gateway.Tests;

public sealed class LiveBootstrapTests : IDisposable
{
    private readonly string _directory = Path.Combine(Path.GetTempPath(), "gym-v15-live-" + Guid.NewGuid().ToString("N"));
    private static readonly DateTimeOffset ValidFrom = new(2026, 10, 8, 0, 0, 0, TimeSpan.FromHours(5.5));
    private static readonly DateTimeOffset ValidTo = new(2026, 10, 8, 23, 59, 59, TimeSpan.FromHours(5.5));
    private static readonly byte[] Face = [1, 2, 3, 4, 5];

    public LiveBootstrapTests()
    {
        Directory.CreateDirectory(_directory);
    }

    [Fact]
    public async Task The_live_gateway_uploads_a_trusted_empty_roster_and_acks_the_seeded_member_after_read_back()
    {
        var empty = new FakeReader();
        empty.ScriptList(0);
        var sibling = new FakeReader();
        sibling.CreateUser(new ReaderUser("3", "Full", null, 0, ValidFrom, ValidTo, "Customer", 1, 1));
        using var backend = new RosterBackend();
        var desired = new SeededAfterRoster(backend);
        using var gateway = Start(backend.BaseUrl, _ => empty, desired);

        await gateway.ScanReadersAsync(CancellationToken.None);

        Assert.True(backend.SawTrustedEmptyRoster);
        Assert.Equal(new[] { "CreateUser 1", "InsertFace 1" }, empty.Writes);
        var ack = Assert.Single(desired.Acknowledgements);
        Assert.Equal("1", ack.DeviceUserId);
        Assert.Equal("Asha Shah", ack.Name);
        Assert.Equal(Convert.ToHexString(SHA256.HashData(Face)).ToLowerInvariant(), ack.FaceSha256);
        Assert.Equal("Asha Shah", empty.GetUser("1").User!.Name);
        Assert.Equal(new[] { "CreateUser 3" }, sibling.Writes);
        Assert.Equal(new[] { "CreateUser" }, sibling.Calls);
    }

    [Fact]
    public async Task A_short_failed_or_mismatched_read_does_not_upload_or_write()
    {
        var reader = new FakeReader();
        reader.ScriptList(4, new ReaderUser("7", "Short", null, 0, null, null, "Customer", 1, 1));
        reader.ScriptListFailure("read failed");
        reader.ScriptList(1, new ReaderUser("7", "One", null, 0, null, null, "Customer", 1, 1),
            new ReaderUser("8", "Two", null, 0, null, null, "Customer", 1, 1));
        using var backend = new RosterBackend();
        var desired = new SeededAfterRoster(backend);
        using var gateway = Start(backend.BaseUrl, _ => reader, desired);

        await gateway.ScanReadersAsync(CancellationToken.None);
        await gateway.ScanReadersAsync(CancellationToken.None);
        await gateway.ScanReadersAsync(CancellationToken.None);

        Assert.False(backend.SawTrustedEmptyRoster);
        Assert.Equal(0, backend.Messages);
        Assert.Empty(desired.Acknowledgements);
        Assert.Empty(reader.Writes);
    }

    public void Dispose()
    {
        if (Directory.Exists(_directory))
        {
            Directory.Delete(_directory, recursive: true);
        }
    }

    private GatewayWorker Start(string backendUrl, Func<string, IReaderAdapter?> open, IDesiredStateClient desired)
    {
        var options = new GatewayOptions
        {
            Id = "gw-1",
            Token = "token",
            BackendUrl = backendUrl,
            UseWebSocket = false,
            Devices =
            [
                new DeviceEndpointOptions
                {
                    DeviceId = "reader-empty",
                    Ip = "10.0.0.8",
                    Port = 37777,
                    Username = "admin",
                    Password = "admin",
                    ProjectionEnabled = true
                }
            ]
        };
        var logs = LoggerFactory.Create(_ => { });
        var link = new BackendLink(
            options,
            logs.CreateLogger<BackendLink>(),
            new DurableOutboundStore(logs.CreateLogger<DurableOutboundStore>(), Path.Combine(_directory, "outbox")));
        var gateway = new GatewayWorker(
            options,
            link,
            logs.CreateLogger<GatewayWorker>(),
            logs,
            new SuppliedReaderFactory(open),
            desired,
            Path.Combine(_directory, "journals"));
        gateway.AttachReaders();
        return gateway;
    }

    private static DesiredPullItem Member() => new(
        1,
        "1",
        "Asha Shah",
        null,
        0,
        ReaderLocalTime.Format(ValidFrom),
        ReaderLocalTime.Format(ValidTo),
        "Customer",
        1,
        1,
        Face,
        true);

    private sealed class SuppliedReaderFactory(Func<string, IReaderAdapter?> open) : IReaderAdapterFactory
    {
        public IReaderAdapter? Open(string deviceId, IDeviceAdapter? connected) => open(deviceId);
    }

    private sealed class SeededAfterRoster(RosterBackend backend) : IDesiredStateClient
    {
        public List<DesiredAcknowledgement> Acknowledgements { get; } = [];

        public Task<DesiredPull> PullAsync(string deviceId, long after, CancellationToken cancellationToken)
        {
            IReadOnlyList<DesiredPullItem> items = backend.SawTrustedEmptyRoster && after < 1 ? [Member()] : [];
            return Task.FromResult(new DesiredPull(backend.SawTrustedEmptyRoster ? 1 : 0, 0, items));
        }

        public Task AcknowledgeAsync(DesiredAcknowledgement ack, CancellationToken cancellationToken)
        {
            Acknowledgements.Add(ack);
            return Task.CompletedTask;
        }

        public Task ReportOccupiedAsync(
            string deviceId, long revision, string deviceUserId, CancellationToken cancellationToken) =>
            Task.CompletedTask;
    }

    private sealed class RosterBackend : IDisposable
    {
        private readonly HttpListener _listener = new();
        private readonly CancellationTokenSource _stop = new();

        public RosterBackend()
        {
            var port = FreePort();
            BaseUrl = $"http://127.0.0.1:{port}";
            _listener.Prefixes.Add(BaseUrl + "/");
            _listener.Start();
            _ = Task.Run(ListenAsync);
        }

        public string BaseUrl { get; }

        public bool SawTrustedEmptyRoster { get; private set; }

        public int Messages { get; private set; }

        public void Dispose()
        {
            _stop.Cancel();
            _listener.Close();
        }

        private async Task ListenAsync()
        {
            while (!_stop.IsCancellationRequested)
            {
                HttpListenerContext context;
                try
                {
                    context = await _listener.GetContextAsync().WaitAsync(_stop.Token).ConfigureAwait(false);
                }
                catch (Exception) when (_stop.IsCancellationRequested)
                {
                    return;
                }

                using var reader = new StreamReader(context.Request.InputStream, Encoding.UTF8);
                var body = await reader.ReadToEndAsync().ConfigureAwait(false);
                Messages++;
                if (IsTrustedEmptyRoster(body))
                {
                    SawTrustedEmptyRoster = true;
                }

                var bytes = "{}"u8.ToArray();
                context.Response.StatusCode = 200;
                context.Response.ContentType = "application/json";
                context.Response.ContentLength64 = bytes.Length;
                await context.Response.OutputStream.WriteAsync(bytes).ConfigureAwait(false);
                context.Response.Close();
            }
        }

        private static bool IsTrustedEmptyRoster(string body)
        {
            using var document = JsonDocument.Parse(body);
            if (!document.RootElement.TryGetProperty("payload", out var payload))
            {
                return false;
            }

            return payload.TryGetProperty("announcedTotal", out var announced)
                && announced.GetInt32() == 0
                && payload.TryGetProperty("users", out var users)
                && users.GetArrayLength() == 0;
        }

        private static int FreePort()
        {
            var listener = new System.Net.Sockets.TcpListener(IPAddress.Loopback, 0);
            listener.Start();
            var port = ((IPEndPoint)listener.LocalEndpoint).Port;
            listener.Stop();
            return port;
        }
    }
}
