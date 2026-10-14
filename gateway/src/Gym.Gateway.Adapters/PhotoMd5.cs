using System.Security.Cryptography;

namespace Gym.Gateway.Adapters;

/// <summary>
/// The checksum the reader's photo index reports, in its upper-case hex form. It only tells whether
/// a photo changed; nothing security-related depends on it.
/// </summary>
public static class PhotoMd5
{
    public static string Hex(byte[] photo) =>
        Convert.ToHexString(MD5.HashData(photo)); // NOSONAR S4790: must match the reader's MD5
}
