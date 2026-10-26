using System.Globalization;
using Gym.Gateway.Adapters;

namespace Gym.Gateway.Execution;

/// <summary>
/// One desired member pulled after the applied revision. The id is allocated by the server.
/// <see cref="Expected"/> is what the reader must still show at the id before it is written; null
/// means no one.
/// </summary>
public sealed record DesiredMember(
    long Revision,
    string DeviceUserId,
    string Name,
    string? NameEx,
    int UserStatus,
    DateTimeOffset? ValidFrom,
    DateTimeOffset? ValidTo,
    string Authority,
    int DoorNum,
    int TimeSectionNum,
    byte[] Face,
    bool FacePresent = true,
    ExpectedOccupant? Expected = null);

/// <summary>
/// The record the server last knew at a reader id. A null field is not compared; dates are
/// reader-local text; an empty photo hash means no photo. Door and time section are not compared.
/// </summary>
public sealed record ExpectedOccupant(
    bool Present,
    string? Name = null,
    string? NameEx = null,
    int? UserStatus = null,
    string? ValidFrom = null,
    string? ValidTo = null,
    string? Authority = null,
    string? FaceSha256 = null)
{
    public static readonly ExpectedOccupant Absent = new(false);

    /// <summary>The reader shows this record at the id.</summary>
    public bool IsShownBy(ReaderOccupant occupant)
    {
        if (!Present || !occupant.Present || occupant.User == null)
        {
            return false;
        }

        var user = occupant.User;
        // Authority is not a precondition: staff change ADMIN↔Customer in place via ReplaceUser.
        // Comparing it here reported Occupied/OccupantChanged and remapped the member to a new id.
        return user.Name == Name
            && Blank(user.NameEx) == Blank(NameEx)
            && (UserStatus is null || user.UserStatus == UserStatus)
            && SameTime(user.ValidFrom, ValidFrom)
            && SameTime(user.ValidTo, ValidTo)
            && (FaceSha256 is null || string.Equals(occupant.FaceSha256, FaceSha256, StringComparison.OrdinalIgnoreCase));
    }

    private static string? Blank(string? value) => string.IsNullOrWhiteSpace(value) ? null : value;

    /// <summary>Validity is a day on the reader clock; the end is written as 23:59:59 of that day.</summary>
    private static bool SameTime(DateTimeOffset? read, string? expected) =>
        expected is null
        || (read is not null
            && DateTimeOffset.TryParse(expected, CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind, out var sent)
            && ReaderLocalClock.Wall(read.Value).Date == ReaderLocalClock.Wall(sent).Date);

}

/// <summary>What a reader id holds now: no one, or a user and its photo hash ("" for no photo).</summary>
public sealed record ReaderOccupant(bool Present, ReaderUser? User, string FaceSha256)
{
    public static readonly ReaderOccupant Nobody = new(false, null, "");

    /// <summary>The reader's newest scan when this was read; every scan up to it came before.</summary>
    public ScanStamp? ReadAfter { get; init; }
}

public enum MemberApplyKind
{
    Applied,
    AlreadyApplied,
    Occupied,
    OccupantChanged,
    Failed,
    Waiting
}

/// <summary><see cref="Occupant"/> is set for <see cref="MemberApplyKind.OccupantChanged"/>.</summary>
public sealed record MemberApplyResult(MemberApplyKind Kind, string? Detail, ReaderOccupant? Occupant = null);
