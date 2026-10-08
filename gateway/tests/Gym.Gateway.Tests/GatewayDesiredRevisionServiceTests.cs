using System.Security.Cryptography;
using Gym.Gateway.Adapters;
using Gym.Gateway.Execution;
using Microsoft.Extensions.Logging;
using Xunit;

namespace Gym.Gateway.Tests;

public class GatewayDesiredRevisionServiceTests : IDisposable
{
    private readonly string _directory = Path.Combine(Path.GetTempPath(), "gym-svc-" + Guid.NewGuid().ToString("N"));
    private static readonly DateTimeOffset ValidFrom = new(2026, 10, 8, 0, 0, 0, TimeSpan.FromHours(5.5));
    private static readonly DateTimeOffset ValidTo = new(2026, 10, 8, 23, 59, 59, TimeSpan.FromHours(5.5));
    private static readonly byte[] Face = [1, 2, 3, 4, 5];

    public GatewayDesiredRevisionServiceTests()
    {
        Directory.CreateDirectory(_directory);
    }

    [Fact]
    public async Task Live_gateway_path_invokes_the_worker_and_acks_after_read_back()
    {
        var fake = new FakeReader();
        var device = ConnectedMock("reader-1");
        var desired = new RecordingDesiredState(Member("1"));
        using var gateway = Start(new SuppliedReaderFactory(_ => fake), desired, device);
        var commanded = false;

        var handled = await InboundDispatch.RouteAsync(
            Notice("reader-1", 1),
            _ =>
            {
                commanded = true;
                return Task.CompletedTask;
            },
            gateway.ReceiveDesiredAsync,
            CancellationToken.None);

        Assert.True(handled);
        Assert.False(commanded);
        Assert.Equal(new[] { "CreateUser 1", "InsertFace 1" }, fake.Writes);
        var ack = Assert.Single(desired.Acknowledgements);
        Assert.Equal("1", ack.DeviceUserId);
        Assert.Equal(Convert.ToHexString(SHA256.HashData(Face)).ToLowerInvariant(), ack.FaceSha256);
        Assert.Empty(device.ListUsers());
    }

    [Fact]
    public async Task Live_gateway_path_uses_the_connected_device_adapter()
    {
        var device = ConnectedMock("reader-1");
        var desired = new RecordingDesiredState(Member("1"));
        using var gateway = Start(new ConnectedReaderAdapterFactory(), desired, device);

        await gateway.ReceiveDesiredAsync(new DesiredRevisionNotice("reader-1", 1), CancellationToken.None);

        var ack = Assert.Single(desired.Acknowledgements);
        Assert.Equal("1", ack.DeviceUserId);
        Assert.Equal("Asha Shah", device.GetUser("1")!.Name);
        Assert.Equal(Face, device.GetFace("1").Photo);
    }

    [Fact]
    public async Task Missing_reader_wiring_does_not_acknowledge()
    {
        var desired = new RecordingDesiredState(Member("1"));
        using var gateway = Start(new ConnectedReaderAdapterFactory(), desired, connected: null);
        var commanded = false;

        var handled = await InboundDispatch.RouteAsync(
            Notice("reader-1", 1),
            _ =>
            {
                commanded = true;
                return Task.CompletedTask;
            },
            gateway.ReceiveDesiredAsync,
            CancellationToken.None);

        Assert.True(handled);
        Assert.False(commanded);
        Assert.Empty(desired.Acknowledgements);
        Assert.Empty(desired.Occupied);
    }

    [Fact]
    public async Task A_reader_factory_that_supplies_nothing_does_not_acknowledge()
    {
        var fake = new FakeReader();
        var desired = new RecordingDesiredState(Member("1"));
        using var gateway = Start(new SuppliedReaderFactory(_ => null), desired, ConnectedMock("reader-1"));

        await gateway.ReceiveDesiredAsync(new DesiredRevisionNotice("reader-1", 1), CancellationToken.None);

        Assert.Empty(desired.Acknowledgements);
        Assert.Empty(fake.Writes);
    }

    [Fact]
    public void Production_reader_factory_uses_the_connected_adapter_and_not_a_fake_reader()
    {
        var factory = new ConnectedReaderAdapterFactory();
        Assert.Null(factory.Open("reader-1", null));
        var device = ConnectedMock("reader-1");
        var reader = factory.Open("reader-1", device);
        Assert.IsType<DeviceReaderAdapter>(reader);

        reader.CreateUser(new ReaderUser("1", "Already there", null, 0, ValidFrom, ValidTo, "Customer", 1, 1));
        var occupied = reader.CreateUser(new ReaderUser("1", "Asha Shah", null, 0, ValidFrom, ValidTo, "Customer", 1, 1));
        Assert.Equal(FakeReader.FailOccupied, occupied.FailCode);
        Assert.Equal("Already there", device.GetUser("1")!.Name);
    }

    public void Dispose()
    {
        if (Directory.Exists(_directory))
        {
            Directory.Delete(_directory, recursive: true);
        }
    }

    private GatewayWorker Start(IReaderAdapterFactory readers, RecordingDesiredState desired, MockDeviceAdapter? connected)
    {
        var options = new GatewayOptions
        {
            Id = "gw-1",
            Token = "token",
            BackendUrl = "http://127.0.0.1:9",
            UseWebSocket = false,
            Devices = [new DeviceEndpointOptions { DeviceId = "reader-1", Ip = "10.0.0.8", Port = 37777, Username = "admin", Password = "admin" }]
        };
        var logs = LoggerFactory.Create(_ => { });
        var store = new DurableOutboundStore(
            logs.CreateLogger<DurableOutboundStore>(),
            Path.Combine(_directory, "outbox"));
        var link = new BackendLink(options, logs.CreateLogger<BackendLink>(), store);
        var gateway = new GatewayWorker(
            options,
            link,
            logs.CreateLogger<GatewayWorker>(),
            logs,
            readers,
            desired,
            Path.Combine(_directory, "journals"));
        if (connected != null)
        {
            gateway.UseConnectedAdapter(connected);
        }

        gateway.AttachReaders();
        return gateway;
    }

    private static MockDeviceAdapter ConnectedMock(string deviceId)
    {
        var device = new MockDeviceAdapter();
        device.Connect(new DeviceConnectionConfig(deviceId, "10.0.0.8", 37777, "admin", "admin"));
        return device;
    }

    private static string Notice(string deviceId, long revision) =>
        $$"""{"type":"DESIRED_REVISION","deviceId":"{{deviceId}}","revision":{{revision}}}""";

    private static DesiredPullItem Member(string deviceUserId) => new(
        1,
        deviceUserId,
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

    private sealed class SuppliedReaderFactory(Func<IDeviceAdapter?, IReaderAdapter?> open) : IReaderAdapterFactory
    {
        public IReaderAdapter? Open(string deviceId, IDeviceAdapter? connected) => open(connected);
    }

    private sealed class RecordingDesiredState(DesiredPullItem item) : IDesiredStateClient
    {
        public List<DesiredAcknowledgement> Acknowledgements { get; } = [];

        public List<string> Occupied { get; } = [];

        public Task<DesiredPull> PullAsync(string deviceId, long after, CancellationToken cancellationToken)
        {
            IReadOnlyList<DesiredPullItem> items = after < item.Revision ? [item] : [];
            return Task.FromResult(new DesiredPull(item.Revision, 0, items));
        }

        public Task AcknowledgeAsync(DesiredAcknowledgement ack, CancellationToken cancellationToken)
        {
            Acknowledgements.Add(ack);
            return Task.CompletedTask;
        }

        public Task ReportOccupiedAsync(string deviceId, long revision, string deviceUserId, CancellationToken cancellationToken)
        {
            Occupied.Add(deviceUserId);
            return Task.CompletedTask;
        }
    }
}
