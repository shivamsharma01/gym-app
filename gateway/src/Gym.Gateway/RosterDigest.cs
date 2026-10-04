using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using Gym.Gateway.Adapters;

namespace Gym.Gateway;

/// <summary>
/// Checksum of a reader's user list: user ID, name, status, validity and admin level, in ID order. The server
/// stores the value from its last full comparison and sends it back; when the reader still gives the same value,
/// the list is not sent again. The prefix changes whenever the fields or format change.
/// </summary>
public static class RosterDigest
{
    private const string Version = "v1:";

    public static string Compute(IEnumerable<DeviceUserSnapshot> users)
    {
        var text = new StringBuilder();
        foreach (var u in users.OrderBy(u => u.DeviceUserId, StringComparer.Ordinal))
        {
            text.Append(u.DeviceUserId).Append('\t')
                .Append(u.Name ?? "").Append('\t')
                .Append(u.Frozen ? '1' : '0').Append('\t')
                .Append(Time(u.ValidFrom)).Append('\t')
                .Append(Time(u.ValidTo)).Append('\t')
                .Append(u.Authority ?? "USER").Append('\n');
        }

        return Version + Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(text.ToString())));
    }

    private static string Time(DateTimeOffset? value) =>
        value?.UtcDateTime.ToString("yyyy-MM-dd'T'HH:mm:ss", CultureInfo.InvariantCulture) ?? "";
}
