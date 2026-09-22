using Gym.Gateway.Adapters;
using Xunit;

namespace Gym.Gateway.Tests;

public class MockDeviceAdapterTests
{
    [Fact]
    public void Create_update_disable_and_delete_users()
    {
        var adapter = Connected();
        Assert.True(adapter.CreateUser(new DeviceUserMutation("1001", "Ada")).Ok);
        Assert.Contains("1001", adapter.KnownUserIds);
        Assert.True(adapter.DisableUser("1001").Ok);
        Assert.True(adapter.EnableUser("1001").Ok);
        Assert.True(adapter.DeleteUser("1001").Ok);
        Assert.Empty(adapter.KnownUserIds);
    }

    [Fact]
    public void ListUsers_and_face_probe_are_available()
    {
        var adapter = Connected();
        var from = new DateTimeOffset(2024, 1, 1, 0, 0, 0, TimeSpan.Zero);
        var to = new DateTimeOffset(2025, 12, 31, 23, 59, 59, TimeSpan.Zero);
        Assert.True(adapter.CreateUser(new DeviceUserMutation("1001", "Ada", ValidFrom: from, ValidTo: to)).Ok);
        var listed = adapter.ListUsers();
        Assert.Contains(listed, u =>
            u.DeviceUserId == "1001"
            && u.Name == "Ada"
            && !u.Frozen
            && u.ValidFrom == from
            && u.ValidTo == to);

        Assert.True(adapter.CreateUser(new DeviceUserMutation("1002", "NoDates")).Ok);
        listed = adapter.ListUsers();
        Assert.Contains(listed, u => u.DeviceUserId == "1002" && u.ValidFrom is null && u.ValidTo is null);

        var probe = adapter.ProbeRemoteFaceInsert("1001", [0xFF, 0xD8, 0xFF]);
        Assert.False(probe.SdkCallReturnedTrue);
        Assert.True(probe.MatchesKnownFirmwareReject);
        Assert.DoesNotContain("0xFF", probe.Detail); // must not echo image bytes
    }

    [Fact]
    public void Face_enrollment_is_never_success()
    {
        var adapter = Connected();
        adapter.CreateUser(new DeviceUserMutation("1001", "Ada"));
        var outcome = adapter.StartFaceEnrollment("1001");
        Assert.Equal("GUIDED_PENDING", outcome.Status);
        Assert.Contains("UNVERIFIED", outcome.Error);
        Assert.False(adapter.DeleteFace("1001").Ok);
    }

    [Fact]
    public void Emits_normalized_access_events()
    {
        var adapter = Connected();
        NormalizedDeviceEvent? received = null;
        adapter.RegisterEventListener(new DelegateListener(e => received = e));
        adapter.EmitAccessEvent("1001", granted: true, recNo: 42);
        Assert.NotNull(received);
        Assert.Equal("ACCESS", received!.Kind);
        Assert.Equal("1001", received.DeviceUserId);
        Assert.True(received.Granted);
        Assert.Equal(42, received.RecNo);
        Assert.Equal("FACE", received.Method);
    }

    [Fact]
    public void Commands_fail_when_disconnected()
    {
        var adapter = new MockDeviceAdapter();
        var result = adapter.CreateUser(new DeviceUserMutation("1"));
        Assert.False(result.Ok);
    }

    private static MockDeviceAdapter Connected()
    {
        var adapter = new MockDeviceAdapter();
        var status = adapter.Connect(new DeviceConnectionConfig("dev-1", "127.0.0.1", 37777, "admin", "x"));
        Assert.True(status.Ok);
        return adapter;
    }

    private sealed class DelegateListener : IDeviceEventListener
    {
        private readonly Action<NormalizedDeviceEvent> _onEvent;

        public DelegateListener(Action<NormalizedDeviceEvent> onEvent) => _onEvent = onEvent;

        public void OnNormalizedEvent(NormalizedDeviceEvent evt) => _onEvent(evt);
    }
}
