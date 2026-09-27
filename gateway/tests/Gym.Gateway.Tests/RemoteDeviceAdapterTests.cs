using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Text.Json;
using Gym.Gateway.Adapters;
using Xunit;

namespace Gym.Gateway.Tests;

public sealed class RemoteDeviceAdapterTests : IDisposable
{
    private static readonly DateTimeOffset FaceTime = new(2026, 9, 1, 10, 30, 0, TimeSpan.Zero);

    private readonly HttpListener _listener;
    private readonly int _port;
    private readonly Thread _serverThread;
    private readonly RemoteDeviceAdapter _adapter;
    private readonly RecordingListener _events = new();
    private volatile bool _running = true;
    private volatile bool _unavailable;

    // Simulated device in-memory state
    private readonly List<object> _users = [];
    private byte[]? _photoBytes;

    public RemoteDeviceAdapterTests()
    {
        _port = FreePort();
        _listener = new HttpListener();
        _listener.Prefixes.Add($"http://127.0.0.1:{_port}/");
        _listener.Start();

        _serverThread = new Thread(ListenLoop) { IsBackground = true };
        _serverThread.Start();

        _adapter = new RemoteDeviceAdapter(minRetryDelay: TimeSpan.FromMilliseconds(50), maxRetryDelay: TimeSpan.FromMilliseconds(200));
        _adapter.RegisterEventListener(_events);
    }

    private DeviceConnectionConfig Config => new("dev-test-1", "127.0.0.1", (ushort)_port, "admin", "pass");

    [Fact]
    public void Connect_ReturnsOnline_WhenServerResponds()
    {
        var status = _adapter.Connect(Config);
        Assert.True(status.Ok);
        Assert.Equal("ONLINE", status.ConnectionState);

        var info = _adapter.GetDeviceInfo();
        Assert.Equal("MOCK-REMOTE-SERIAL", info.SerialNumber);
    }

    [Fact]
    public void UserCrud_SyncsWithRemoteServer()
    {
        _adapter.Connect(Config);

        // Create
        var createRes = _adapter.CreateUser(new DeviceUserMutation("1001", "Alice", true));
        Assert.True(createRes.Ok);

        // List
        var users = _adapter.ListUsers();
        Assert.Single(users);
        Assert.Equal("Alice", users[0].Name);

        // Update / Disable
        var disableRes = _adapter.DisableUser("1001");
        Assert.True(disableRes.Ok);

        // Upsert Face
        var dummyFace = new byte[] { 0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46 };
        var faceRes = _adapter.UpsertFace("1001", dummyFace);
        Assert.True(faceRes.Ok);

        // Read Face: the device's change time comes through
        var readFace = _adapter.GetFace("1001");
        Assert.True(readFace.Ok);
        Assert.NotNull(readFace.Photo);
        Assert.Equal(dummyFace.Length, readFace.Photo!.Length);
        Assert.Equal(FaceTime, readFace.UpdatedAtUtc);

        // Delete
        var delRes = _adapter.DeleteUser("1001");
        Assert.True(delRes.Ok);
        Assert.Empty(_adapter.ListUsers());
    }

    [Fact]
    public void Device_that_stops_answering_is_reported_offline_and_back_online_when_it_returns()
    {
        _adapter.Connect(Config);
        _unavailable = true;

        var result = _adapter.CreateUser(new DeviceUserMutation("1002", "Bob", true));
        Assert.False(result.Ok);
        Assert.Equal("OFFLINE", _adapter.GetHealth().ConnectionState);
        Assert.True(WaitFor(() => _events.Status.Contains("OFFLINE")));

        // Commands fail fast while offline instead of waiting on the device.
        Assert.Equal("Device is offline", _adapter.EnableUser("1002").Error);

        _unavailable = false;
        Assert.True(WaitFor(() => _adapter.GetHealth().ConnectionState == "ONLINE"));
        Assert.True(WaitFor(() => _events.Status.Contains("ONLINE")));
        Assert.True(_adapter.CreateUser(new DeviceUserMutation("1002", "Bob", true)).Ok);
    }

    [Fact]
    public void Device_offline_at_start_is_connected_later_without_a_gateway_restart()
    {
        _unavailable = true;
        var status = _adapter.Connect(Config);
        Assert.False(status.Ok);
        Assert.Equal("OFFLINE", _adapter.GetHealth().ConnectionState);

        _unavailable = false;
        Assert.True(WaitFor(() => _adapter.GetHealth().ConnectionState == "ONLINE"));
        Assert.Contains("ONLINE", _events.Status);
    }

    private static bool WaitFor(Func<bool> condition)
    {
        var deadline = DateTime.UtcNow.AddSeconds(5);
        while (DateTime.UtcNow < deadline)
        {
            if (condition())
            {
                return true;
            }

            Thread.Sleep(20);
        }

        return condition();
    }

    private static int FreePort()
    {
        var probe = new TcpListener(IPAddress.Loopback, 0);
        probe.Start();
        var port = ((IPEndPoint)probe.LocalEndpoint).Port;
        probe.Stop();
        return port;
    }

    private void ListenLoop()
    {
        while (_running)
        {
            HttpListenerContext ctx;
            try
            {
                ctx = _listener.GetContext();
            }
            catch
            {
                break;
            }

            ThreadPool.QueueUserWorkItem(_ => Handle(ctx));
        }
    }

    private void Handle(HttpListenerContext ctx)
    {
        try
        {
            var req = ctx.Request;
            var res = ctx.Response;
            var path = req.Url!.AbsolutePath;

            if (_unavailable)
            {
                res.StatusCode = 503;
                res.Close();
            }
            else if (path == "/device/events/poll")
            {
                Thread.Sleep(100);
                WriteJson(res, Array.Empty<object>());
            }
            else if (path == "/device/info")
            {
                WriteJson(res, new { serialNumber = "MOCK-REMOTE-SERIAL", channelCount = 1 });
            }
            else if (path == "/device/users" && req.HttpMethod == "GET")
            {
                lock (_users)
                {
                    WriteJson(res, _users.ToList());
                }
            }
            else if (path == "/device/users" && req.HttpMethod == "POST")
            {
                using var reader = new StreamReader(req.InputStream);
                var doc = JsonDocument.Parse(reader.ReadToEnd());
                lock (_users)
                {
                    _users.Add(new
                    {
                        deviceUserId = doc.RootElement.GetProperty("deviceUserId").GetString(),
                        name = doc.RootElement.GetProperty("name").GetString(),
                        enabled = true
                    });
                }

                res.StatusCode = 201;
                WriteJson(res, new { ok = true });
            }
            else if (path.StartsWith("/device/users/") && path.EndsWith("/face") && req.HttpMethod == "PUT")
            {
                using var ms = new MemoryStream();
                req.InputStream.CopyTo(ms);
                _photoBytes = ms.ToArray();
                WriteJson(res, new { ok = true });
            }
            else if (path.StartsWith("/device/users/") && path.EndsWith("/face") && req.HttpMethod == "GET")
            {
                if (_photoBytes != null)
                {
                    res.ContentType = "image/jpeg";
                    res.Headers.Add("Last-Modified", FaceTime.ToString("R"));
                    res.OutputStream.Write(_photoBytes);
                    res.Close();
                }
                else
                {
                    res.StatusCode = 404;
                    res.Close();
                }
            }
            else if (path.StartsWith("/device/users/") && req.HttpMethod == "PUT")
            {
                WriteJson(res, new { ok = true });
            }
            else if (path.StartsWith("/device/users/") && req.HttpMethod == "DELETE")
            {
                lock (_users)
                {
                    _users.Clear();
                }

                _photoBytes = null;
                WriteJson(res, new { ok = true });
            }
            else
            {
                WriteJson(res, new { ok = true });
            }
        }
        catch
        {
            // listener stopped mid-request
        }
    }

    private static void WriteJson(HttpListenerResponse res, object data)
    {
        res.ContentType = "application/json";
        var bytes = JsonSerializer.SerializeToUtf8Bytes(data);
        res.OutputStream.Write(bytes);
        res.Close();
    }

    public void Dispose()
    {
        _running = false;
        _adapter.Dispose();
        _listener.Stop();
        _listener.Close();
    }

    private sealed class RecordingListener : IDeviceEventListener
    {
        private readonly ConcurrentQueue<string> _status = new();

        public IReadOnlyCollection<string> Status => _status.ToArray();

        public void OnNormalizedEvent(NormalizedDeviceEvent evt)
        {
            if (evt.Kind == "STATUS")
            {
                _status.Enqueue(evt.Granted ? "ONLINE" : "OFFLINE");
            }
        }
    }
}
