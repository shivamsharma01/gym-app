using Gym.Gateway.Adapters;

namespace Gym.Gateway.Execution;

/// <summary>
/// One desired member pulled after the applied revision. The id is allocated by the server.
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
    byte[] Face);

public enum MemberApplyKind
{
    Applied,
    AlreadyApplied,
    Occupied,
    Failed,
    Waiting,
    NotReady
}

public sealed record MemberApplyResult(MemberApplyKind Kind, string? Detail);
