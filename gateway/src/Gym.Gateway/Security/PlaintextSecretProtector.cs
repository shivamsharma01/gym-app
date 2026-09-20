using System.Security.Cryptography;
using System.Text;

namespace Gym.Gateway.Security;

/// <summary>
/// Dev/test protector (reversible, not for production MSI). Used on Linux CI and unit tests.
/// </summary>
public sealed class PlaintextSecretProtector : ISecretProtector
{
    private static readonly byte[] Key = Encoding.UTF8.GetBytes("GymGatewayDevOnlyKey!!");

    public string Protect(string plaintext)
    {
        var bytes = Encoding.UTF8.GetBytes(plaintext);
        var mixed = new byte[bytes.Length];
        for (var i = 0; i < bytes.Length; i++)
        {
            mixed[i] = (byte)(bytes[i] ^ Key[i % Key.Length]);
        }

        return "plain:" + Convert.ToBase64String(mixed);
    }

    public string Unprotect(string protectedPayload)
    {
        if (!protectedPayload.StartsWith("plain:", StringComparison.Ordinal))
        {
            throw new CryptographicException("Unexpected protector payload");
        }

        var mixed = Convert.FromBase64String(protectedPayload["plain:".Length..]);
        var bytes = new byte[mixed.Length];
        for (var i = 0; i < mixed.Length; i++)
        {
            bytes[i] = (byte)(mixed[i] ^ Key[i % Key.Length]);
        }

        return Encoding.UTF8.GetString(bytes);
    }
}
