using System.Runtime.Versioning;
using System.Security.Cryptography;
using System.Text;

namespace Gym.Gateway.Security;

/// <summary>Windows DPAPI LocalMachine scope — usable by the service and elevated configurator.</summary>
[SupportedOSPlatform("windows")]
public sealed class DpapiSecretProtector : ISecretProtector
{
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("GymGateway.v1");

    public string Protect(string plaintext)
    {
        var bytes = Encoding.UTF8.GetBytes(plaintext);
        var protectedBytes = ProtectedData.Protect(bytes, Entropy, DataProtectionScope.LocalMachine);
        return "dpapi:" + Convert.ToBase64String(protectedBytes);
    }

    public string Unprotect(string protectedPayload)
    {
        if (!protectedPayload.StartsWith("dpapi:", StringComparison.Ordinal))
        {
            throw new CryptographicException("Unexpected DPAPI payload");
        }

        var protectedBytes = Convert.FromBase64String(protectedPayload["dpapi:".Length..]);
        var bytes = ProtectedData.Unprotect(protectedBytes, Entropy, DataProtectionScope.LocalMachine);
        return Encoding.UTF8.GetString(bytes);
    }
}
