using System.Net;
using System.Text;
using Gym.Gateway;
using Gym.Gateway.Config;
using Gym.Gateway.Security;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

namespace Gym.Gateway.Tests;

public sealed class SecretProtectorTests
{
    [Fact]
    public void Plaintext_protector_round_trips()
    {
        var protector = new PlaintextSecretProtector();
        var protectedPayload = protector.Protect("secret-token-abc");
        Assert.StartsWith("plain:", protectedPayload);
        Assert.DoesNotContain("secret-token-abc", protectedPayload);
        Assert.Equal("secret-token-abc", protector.Unprotect(protectedPayload));
    }
}

public sealed class GatewayConfigStoreTests
{
    [Fact]
    public void Round_trip_two_devices_with_protected_secrets()
    {
        var path = Path.Combine(Path.GetTempPath(), "gym-gateway-test-" + Guid.NewGuid().ToString("N"), "config.json");
        try
        {
            var store = new GatewayConfigStore(new PlaintextSecretProtector(), NullLogger<GatewayConfigStore>.Instance, path);
            var options = new GatewayOptions
            {
                Id = "gw-1",
                Token = "ops-cred-1",
                BackendUrl = "https://backend.example",
                Adapter = "TrueFace",
                CredentialExpiresAt = DateTimeOffset.Parse("2030-01-01T00:00:00Z"),
                RenewBefore = TimeSpan.FromDays(7),
                Devices =
                [
                    new DeviceEndpointOptions
                    {
                        DeviceId = "dev-a", Ip = "192.168.1.10", Port = 37777, Username = "admin",
                        Password = "pass-a"
                    },
                    new DeviceEndpointOptions
                    {
                        DeviceId = "dev-b", Ip = "192.168.1.11", Port = 37777, Username = "admin",
                        Password = "pass-b"
                    }
                ]
            };

            store.Save(store.FromOptions(options));
            Assert.True(store.Exists());

            var raw = File.ReadAllText(path);
            Assert.DoesNotContain("ops-cred-1", raw);
            Assert.DoesNotContain("pass-a", raw);
            Assert.DoesNotContain("pass-b", raw);

            var loaded = new GatewayOptions();
            store.ApplyTo(loaded, store.TryLoad()!);
            Assert.Equal("gw-1", loaded.Id);
            Assert.Equal("ops-cred-1", loaded.Token);
            Assert.Equal(2, loaded.Devices.Count);
            Assert.Equal("pass-a", loaded.Devices[0].Password);
            Assert.Equal("pass-b", loaded.Devices[1].Password);
            Assert.Equal("192.168.1.11", loaded.Devices[1].Ip);
        }
        finally
        {
            var dir = Path.GetDirectoryName(path);
            if (dir != null && Directory.Exists(dir))
            {
                Directory.Delete(dir, recursive: true);
            }
        }
    }
}

public sealed class CredentialRotationServiceTests
{
    [Fact]
    public void ShouldRenew_respects_window()
    {
        var options = new GatewayOptions
        {
            CredentialExpiresAt = DateTimeOffset.Parse("2030-06-01T00:00:00Z"),
            RenewBefore = TimeSpan.FromDays(7)
        };
        var service = CreateService(options, new StubHandler(_ => new HttpResponseMessage(HttpStatusCode.OK)));
        Assert.False(service.ShouldRenew(DateTimeOffset.Parse("2030-05-20T00:00:00Z")));
        Assert.True(service.ShouldRenew(DateTimeOffset.Parse("2030-05-25T00:00:00Z")));
        Assert.True(service.ShouldRenew(DateTimeOffset.Parse("2030-06-02T00:00:00Z")));
    }

    [Fact]
    public async Task Rotate_persists_v2_then_applies_credential()
    {
        var path = Path.Combine(Path.GetTempPath(), "gym-gateway-rot-" + Guid.NewGuid().ToString("N"), "config.json");
        try
        {
            var store = new GatewayConfigStore(new PlaintextSecretProtector(), NullLogger<GatewayConfigStore>.Instance, path);
            var options = new GatewayOptions
            {
                Id = "gw-1",
                Token = "v1-token",
                BackendUrl = "http://127.0.0.1:9",
                CredentialExpiresAt = DateTimeOffset.UtcNow.AddDays(1),
                RenewBefore = TimeSpan.FromDays(7),
                Devices =
                [
                    new DeviceEndpointOptions
                    {
                        DeviceId = "d1", Ip = "10.0.0.1", Username = "admin", Password = "p"
                    }
                ]
            };
            store.Save(store.FromOptions(options));

            var handler = new StubHandler(req =>
            {
                Assert.Equal(HttpMethod.Post, req.Method);
                Assert.Contains("/internal/gateway/credentials/rotate", req.RequestUri!.AbsoluteUri);
                Assert.Equal("Bearer", req.Headers.Authorization?.Scheme);
                Assert.Equal("v1-token", req.Headers.Authorization?.Parameter);
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(
                        """{"gatewayId":"gw-1","credential":"v2-token","expiresAt":"2031-01-01T00:00:00Z"}""",
                        Encoding.UTF8,
                        "application/json")
                };
            });

            var outbox = new DurableOutboundStore(
                NullLogger<DurableOutboundStore>.Instance,
                Path.Combine(Path.GetTempPath(), "gym-outbox-" + Guid.NewGuid().ToString("N")));
            var link = new BackendLink(options, NullLogger<BackendLink>.Instance, outbox, new HttpClient(handler));
            var client = new GatewayEnrollmentClient(new HttpClient(handler));
            var service = new CredentialRotationService(
                options, store, link, client, NullLogger<CredentialRotationService>.Instance, TimeSpan.FromHours(1));

            await service.MaybeRotateAsync(CancellationToken.None);

            Assert.Equal("v2-token", options.Token);
            Assert.Equal(DateTimeOffset.Parse("2031-01-01T00:00:00Z"), options.CredentialExpiresAt);

            var reloaded = new GatewayOptions();
            store.ApplyTo(reloaded, store.TryLoad()!);
            Assert.Equal("v2-token", reloaded.Token);
            Assert.Equal("p", reloaded.Devices[0].Password);
        }
        finally
        {
            var dir = Path.GetDirectoryName(path);
            if (dir != null && Directory.Exists(dir))
            {
                Directory.Delete(dir, recursive: true);
            }
        }
    }

    private static CredentialRotationService CreateService(GatewayOptions options, HttpMessageHandler handler)
    {
        var path = Path.Combine(Path.GetTempPath(), "gym-gateway-idle-" + Guid.NewGuid().ToString("N"), "config.json");
        var store = new GatewayConfigStore(new PlaintextSecretProtector(), NullLogger<GatewayConfigStore>.Instance, path);
        var outbox = new DurableOutboundStore(
            NullLogger<DurableOutboundStore>.Instance,
            Path.Combine(Path.GetTempPath(), "gym-outbox-" + Guid.NewGuid().ToString("N")));
        var link = new BackendLink(options, NullLogger<BackendLink>.Instance, outbox, new HttpClient(handler));
        var client = new GatewayEnrollmentClient(new HttpClient(handler));
        return new CredentialRotationService(
            options, store, link, client, NullLogger<CredentialRotationService>.Instance, TimeSpan.FromHours(1));
    }
}

public sealed class GatewayEnrollmentClientTests
{
    [Fact]
    public async Task Enroll_maps_error_detail()
    {
        var handler = new StubHandler(_ => new HttpResponseMessage(HttpStatusCode.Conflict)
        {
            Content = new StringContent("""{"detail":"Enrollment already consumed"}""", Encoding.UTF8, "application/json")
        });
        var client = new GatewayEnrollmentClient(new HttpClient(handler));
        var ex = await Assert.ThrowsAsync<InvalidOperationException>(() =>
            client.EnrollAsync("http://127.0.0.1:9", "gw", "tok"));
        Assert.Contains("Enrollment already consumed", ex.Message);
    }

    [Fact]
    public async Task Enroll_returns_credential()
    {
        var handler = new StubHandler(_ => new HttpResponseMessage(HttpStatusCode.OK)
        {
            Content = new StringContent(
                """{"gatewayId":"gw-1","credential":"ops","expiresAt":"2030-01-01T00:00:00Z"}""",
                Encoding.UTF8,
                "application/json")
        });
        var client = new GatewayEnrollmentClient(new HttpClient(handler));
        var result = await client.EnrollAsync("http://backend/", "gw-1", "enroll-tok");
        Assert.Equal("ops", result.Credential);
        Assert.Equal("gw-1", result.GatewayId);
    }
}

file sealed class StubHandler : HttpMessageHandler
{
    private readonly Func<HttpRequestMessage, HttpResponseMessage> _respond;

    public StubHandler(Func<HttpRequestMessage, HttpResponseMessage> respond) => _respond = respond;

    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) =>
        Task.FromResult(_respond(request));
}
