namespace Gym.Gateway.Adapters;

/// <summary>
/// The reader clock measured on the gym readers: India Standard Time, UTC+05:30, no daylight saving.
/// Validity fields are this wall clock. Punch timestamps are a separate UTC clock and are not converted here.
/// </summary>
public static class ReaderLocalClock
{
    public static readonly TimeSpan Offset = TimeSpan.FromHours(5.5);

    public static readonly TimeZoneInfo Zone = TimeZoneInfo.FindSystemTimeZoneById("Asia/Kolkata");

    /// <summary>The same instant expressed on the reader clock.</summary>
    public static DateTimeOffset Now(DateTimeOffset instant) => TimeZoneInfo.ConvertTime(instant, Zone);

    /// <summary>Wall-clock components to store in a reader time field. Not UTC.</summary>
    public static DateTime Wall(DateTimeOffset instant)
    {
        var local = Now(instant);
        return DateTime.SpecifyKind(local.DateTime, DateTimeKind.Unspecified);
    }

    /// <summary>23:59:59 on the reader-local calendar date of <paramref name="instant"/>.</summary>
    public static DateTime EndOfDay(DateTimeOffset instant)
    {
        var wall = Wall(instant);
        return new DateTime(wall.Year, wall.Month, wall.Day, 23, 59, 59, DateTimeKind.Unspecified);
    }

    public static DateTimeOffset At(DateTime wall) =>
        new(DateTime.SpecifyKind(wall, DateTimeKind.Unspecified), Offset);
}
