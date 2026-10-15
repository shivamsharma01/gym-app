using System.Globalization;

namespace Gym.Gateway.Execution;

/// <summary>
/// Reader-local timestamps travel as <c>yyyy-MM-dd'T'HH:mm:ss+05:30</c>. The offset on the value is kept.
/// </summary>
public static class ReaderLocalTime
{
    public static DateTimeOffset Parse(string value) =>
        DateTimeOffset.Parse(value, CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind);

    public static string Format(DateTimeOffset value) =>
        value.ToString("yyyy-MM-dd'T'HH:mm:sszzz", CultureInfo.InvariantCulture);
}
