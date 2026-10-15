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
        return user.Name == Name
            && Blank(user.NameEx) == Blank(NameEx)
            && (UserStatus is null || user.UserStatus == UserStatus)
            && SameTime(user.ValidFrom, ValidFrom)
            && SameTime(user.ValidTo, ValidTo)
            && (Authority is null || NormalAuthority(user.Authority) == NormalAuthority(Authority))
            && (FaceSha256 is null || string.Equals(occupant.FaceSha256, FaceSha256, StringComparison.OrdinalIgnoreCase));
    }

    private static string? Blank(string? value) => string.IsNullOrWhiteSpace(value) ? null : value;

    private static bool SameTime(DateTimeOffset? read, string? expected) =>
        expected is null || (read is not null && ReaderLocalTime.Format(read.Value) == expected);

    /// <summary>The reader reports an ordinary user as Customer or USER, or not at all.</summary>
    private static string NormalAuthority(string? value) =>
        string.IsNullOrWhiteSpace(value) || value.Trim().Equals("USER", StringComparison.OrdinalIgnoreCase)
            ? "Customer"
            : value.Trim();
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
