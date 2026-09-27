using System.Net;
using System.Text.Json;
using Gym.Gateway.Adapters;
using Xunit;

namespace Gym.Gateway.Tests;

public sealed class RemoteDeviceAdapterTests : IDisposable
{
    private readonly HttpListener _listener;
    private readonly int _port;
    private readonly Thread _serverThread;
    private readonly RemoteDeviceAdapter _adapter;
    private volatile bool _running = true;

    // Simulated device in-memory state
    private readonly List<object> _users = [];
    private byte[]? _photoBytes;

    public RemoteDeviceAdapterTests()
    {
        // Pick an ephemeral port for testing
        var rnd = new Random();
        _port = rnd.Next(10000, 20000);
        _listener = new HttpListener();
        _listener.Prefixes.Add($"http://127.0.0.1:{_port}/");
        _listener.Start();

        _serverThread = new Thread(ListenLoop) { IsBackground = true };
        _serverThread.Start();

        _adapter = new RemoteDeviceAdapter();
    }

    [Fact]
    public void Connect_ReturnsOnline_WhenServerResponds()
    {
        var status = _adapter.Connect(new DeviceConnectionConfig("dev-test-1", "127.0.0.1", (ushort)_port, "admin", "pass"));
        Assert.True(status.Ok);
        Assert.Equal("ONLINE", status.ConnectionState);

        var info = _adapter.GetDeviceInfo();
        Assert.Equal("MOCK-REMOTE-SERIAL", info.SerialNumber);
    }

    [Fact]
    public void UserCrud_SyncsWithRemoteServer()
    {
        _adapter.Connect(new DeviceConnectionConfig("dev-test-1", "127.0.0.1", (ushort)_port, "admin", "pass"));

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

        // Read Face
        var readFace = _adapter.GetFace("1001");
        Assert.True(readFace.Ok);
        Assert.NotNull(readFace.Photo);
        Assert.Equal(dummyFace.Length, readFace.Photo!.Length);

        // Delete
        var delRes = _adapter.DeleteUser("1001");
        Assert.True(delRes.Ok);
        Assert.Empty(_adapter.ListUsers());
    }

    private void ListenLoop()
    {
        while (_running)
        {
            try
            {
                var ctx = _listener.GetContext();
                var req = ctx.Request;
                var res = ctx.Response;

                if (req.Url!.AbsolutePath == "/device/info")
                {
                    WriteJson(res, new { serialNumber = "MOCK-REMOTE-SERIAL", channelCount = 1 });
                }
                else if (req.Url.AbsolutePath == "/device/users" && req.HttpMethod == "GET")
                {
                    WriteJson(res, _users);
                }
                else if (req.Url.AbsolutePath == "/device/users" && req.HttpMethod == "POST")
                {
                    using var reader = new StreamReader(req.InputStream);
                    var body = reader.ReadToEnd();
                    var doc = JsonDocument.Parse(body);
                    _users.Add(new
                    {
                        deviceUserId = doc.RootElement.GetProperty("deviceUserId").GetString(),
                        name = doc.RootElement.GetProperty("name").GetString(),
                        enabled = true
                    });
                    res.StatusCode = 201;
                    WriteJson(res, new { ok = true });
                }
                else if (req.Url.AbsolutePath.StartsWith("/device/users/") && req.Url.AbsolutePath.EndsWith("/face") && req.HttpMethod == "PUT")
                {
                    using var ms = new MemoryStream();
                    req.InputStream.CopyTo(ms);
                    _photoBytes = ms.ToArray();
                    WriteJson(res, new { ok = true });
                }
                else if (req.Url.AbsolutePath.StartsWith("/device/users/") && req.Url.AbsolutePath.EndsWith("/face") && req.HttpMethod == "GET")
                {
                    if (_photoBytes != null)
                    {
                        res.ContentType = "image/jpeg";
                        res.OutputStream.Write(_photoBytes);
                        res.Close();
                    }
                    else
                    {
                        res.StatusCode = 404;
                        res.Close();
                    }
                }
                else if (req.Url.AbsolutePath.StartsWith("/device/users/") && req.HttpMethod == "PUT")
                {
                    WriteJson(res, new { ok = true });
                }
                else if (req.Url.AbsolutePath.StartsWith("/device/users/") && req.HttpMethod == "DELETE")
                {
                    _users.Clear();
                    _photoBytes = null;
                    WriteJson(res, new { ok = true });
                }
                else
                {
                    res.StatusCode = 200;
                    WriteJson(res, new { ok = true });
                }
            }
            catch
            {
                break;
            }
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
}
