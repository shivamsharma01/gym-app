namespace Gym.Gateway.Adapters;

/// <summary>
/// Calls on the gateway's existing device adapter. Creating an id that is already present does not
/// overwrite it. Replacing a user writes the whole record, including a new name. A stored photo is
/// updated, not inserted again. An empty photo update does not delete the stored bytes. A user
/// write without validity is refused.
/// </summary>
public sealed class DeviceReaderAdapter : IReaderAdapter
{
    private readonly IDeviceAdapter _device;
    private readonly Dictionary<string, ReaderUser> _written = new(StringComparer.Ordinal);

    public DeviceReaderAdapter(IDeviceAdapter device)
    {
        _device = device ?? throw new ArgumentNullException(nameof(device));
    }

    public ReaderUserResult GetUser(string deviceUserId)
    {
        if (!Online(out var error))
        {
            return ReaderUserResult.Failed(error);
        }

        try
        {
            if (_device is TrueFaceDeviceAdapter reader)
            {
                var read = reader.ReadUser(deviceUserId);
                if (read.Lookup != TrueFaceDeviceAdapter.UserLookup.Found || read.User == null)
                {
                    if (read.Lookup == TrueFaceDeviceAdapter.UserLookup.Missing)
                    {
                        _written.Remove(deviceUserId);
                    }

                    return FromLookup(read.Lookup);
                }

                return Found(deviceUserId, read.User);
            }

            var live = _device.GetUser(deviceUserId);
            if (live == null)
            {
                _written.Remove(deviceUserId);
                return ReaderUserResult.NoRecord();
            }

            return Found(deviceUserId, live);
        }
        catch (DeviceReadException ex)
        {
            return ReaderUserResult.Failed(ex.Message);
        }
    }

    public ReaderCallResult CreateUser(ReaderUser user)
    {
        if (user.ValidFrom is null || user.ValidTo is null)
        {
            return ReaderCallResult.Failed("validity is required");
        }

        if (!Online(out var error))
        {
            return ReaderCallResult.Failed(error);
        }

        DeviceUserSnapshot? existing;
        try
        {
            existing = _device.GetUser(user.DeviceUserId);
        }
        catch (DeviceReadException ex)
        {
            return ReaderCallResult.Failed(ex.Message);
        }

        if (existing != null)
        {
            return NameConflicts(existing, user)
                ? ReaderCallResult.Occupied(user.DeviceUserId)
                : ReaderCallResult.Failed("existing user was not overwritten");
        }

        var created = _device.CreateUser(Mutation(user));
        if (!created.Ok)
        {
            return ReaderCallResult.Failed(created.Error ?? "create failed");
        }

        _written[user.DeviceUserId] = user;
        return ReaderCallResult.Success();
    }

    public ReaderCallResult ReplaceUser(ReaderUser user)
    {
        if (user.ValidFrom is null || user.ValidTo is null)
        {
            return ReaderCallResult.Failed("validity is required");
        }

        if (!Online(out var error))
        {
            return ReaderCallResult.Failed(error);
        }

        DeviceUserSnapshot? existing;
        try
        {
            existing = _device.GetUser(user.DeviceUserId);
        }
        catch (DeviceReadException ex)
        {
            return ReaderCallResult.Failed(ex.Message);
        }

        if (existing == null)
        {
            return ReaderCallResult.NoRecord();
        }

        var updated = _device.UpdateUser(Mutation(user));
        if (!updated.Ok)
        {
            return ReaderCallResult.Failed(updated.Error ?? "user write failed");
        }

        _written[user.DeviceUserId] = user;
        return ReaderCallResult.Success();
    }

    public ReaderFaceResult GetFace(string deviceUserId)
    {
        if (!Online(out var error))
        {
            return ReaderFaceResult.Failed(error);
        }

        var face = _device.GetFace(deviceUserId);
        if (!face.Ok)
        {
            return ReaderFaceResult.Failed(face.Error ?? "face read failed");
        }

        return face.Photo is { Length: > 0 }
            ? ReaderFaceResult.Found(face.Photo)
            : ReaderFaceResult.NoPhoto();
    }

    public ReaderCallResult InsertFace(string deviceUserId, byte[]? jpeg)
    {
        if (jpeg is not { Length: > 0 })
        {
            return ReaderCallResult.Failed("face image is required");
        }

        var face = GetFace(deviceUserId);
        if (!face.Ok)
        {
            if (face.FailCode is not (FakeReader.FailUnknown or FakeReader.FailNoRecord))
            {
                return ReaderCallResult.Failed(face.Error ?? face.FailCode ?? "face read failed");
            }
        }
        else if (face.Bytes is { Length: > 0 })
        {
            return ReaderCallResult.PhotoExist();
        }

        var inserted = _device.UpsertFace(deviceUserId, jpeg);
        return inserted.Ok
            ? ReaderCallResult.Success()
            : ReaderCallResult.Failed(inserted.Error ?? "face insert failed");
    }

    public ReaderCallResult UpdateFace(string deviceUserId, byte[]? jpeg)
    {
        if (jpeg is not { Length: > 0 })
        {
            return ReaderCallResult.Success();
        }

        if (!Online(out var error))
        {
            return ReaderCallResult.Failed(error);
        }

        var updated = _device.UpsertFace(deviceUserId, jpeg);
        return updated.Ok
            ? ReaderCallResult.Success()
            : ReaderCallResult.Failed(updated.Error ?? "face update failed");
    }

    public ReaderCallResult RemoveFace(string deviceUserId)
    {
        if (!Online(out var error))
        {
            return ReaderCallResult.Failed(error);
        }

        var removed = _device.DeleteFace(deviceUserId);
        return removed.Ok
            ? ReaderCallResult.Success()
            : ReaderCallResult.Failed(removed.Error ?? "face remove failed");
    }

    public ReaderCallResult RemoveUser(string deviceUserId)
    {
        if (!Online(out var error))
        {
            return ReaderCallResult.Failed(error);
        }

        var removed = _device.DeleteUser(deviceUserId);
        if (!removed.Ok)
        {
            return ReaderCallResult.Failed(removed.Error ?? "user remove failed");
        }

        _written.Remove(deviceUserId);
        return ReaderCallResult.Success();
    }

    /// <summary>NO_RECORD is a missing user. UNKNOWN, including the shared SDK error, is not.</summary>
    internal static ReaderUserResult FromLookup(TrueFaceDeviceAdapter.UserLookup lookup) =>
        lookup switch
        {
            TrueFaceDeviceAdapter.UserLookup.Missing => ReaderUserResult.NoRecord(),
            TrueFaceDeviceAdapter.UserLookup.Unknown => ReaderUserResult.Unknown(),
            _ => ReaderUserResult.Failed("reader did not answer")
        };

    private ReaderUserResult Found(string deviceUserId, DeviceUserSnapshot live)
    {
        if (_written.TryGetValue(deviceUserId, out var written) && Agrees(live, written))
        {
            return ReaderUserResult.Found(written);
        }

        return ReaderUserResult.Found(Map(live));
    }

    private bool Online(out string error)
    {
        var health = _device.GetHealth();
        if (string.Equals(health.ConnectionState, "ONLINE", StringComparison.OrdinalIgnoreCase))
        {
            error = "";
            return true;
        }

        error = string.IsNullOrWhiteSpace(health.Detail)
            ? "reader is not connected"
            : health.Detail;
        return false;
    }

    private static bool Agrees(DeviceUserSnapshot live, ReaderUser written)
    {
        var read = Map(live);
        return read.DeviceUserId == written.DeviceUserId
            && read.Name == written.Name
            && read.NameEx == written.NameEx
            && read.UserStatus == written.UserStatus
            && read.ValidFrom == written.ValidFrom
            && read.ValidTo == written.ValidTo
            && AuthorityAgrees(live.Authority, written.Authority);
    }

    private static bool AuthorityAgrees(string? live, string? written)
    {
        if (string.Equals(live, written, StringComparison.OrdinalIgnoreCase))
        {
            return true;
        }

        return string.Equals(written, "Customer", StringComparison.OrdinalIgnoreCase)
            && (string.IsNullOrWhiteSpace(live) || string.Equals(live, "USER", StringComparison.OrdinalIgnoreCase));
    }

    private static DeviceUserMutation Mutation(ReaderUser user) => new(
        user.DeviceUserId,
        user.Name,
        user.UserStatus == 0,
        user.ValidFrom,
        user.ValidTo,
        user.Authority,
        user.NameEx);

    private static bool NameConflicts(DeviceUserSnapshot live, ReaderUser user)
    {
        if (string.IsNullOrEmpty(live.Name))
        {
            return false;
        }

        return !string.Equals(live.Name, user.Name, StringComparison.Ordinal)
            && !string.Equals(live.Name, user.NameEx, StringComparison.Ordinal);
    }

    private static ReaderUser Map(DeviceUserSnapshot user)
    {
        var name = string.IsNullOrWhiteSpace(user.ShortName) ? user.Name : user.ShortName;
        var nameEx = string.IsNullOrWhiteSpace(user.NameEx) ? null : user.NameEx;
        if (string.Equals(nameEx, name, StringComparison.Ordinal))
        {
            nameEx = null;
        }

        return new ReaderUser(
            user.DeviceUserId,
            name,
            nameEx,
            user.Frozen ? 1 : 0,
            user.ValidFrom,
            user.ValidTo,
            ReaderAuthority(user.Authority),
            1,
            1);
    }

    private static string ReaderAuthority(string? live)
    {
        if (string.IsNullOrWhiteSpace(live)
            || live.Equals("USER", StringComparison.OrdinalIgnoreCase)
            || live.Equals("Customer", StringComparison.OrdinalIgnoreCase))
        {
            return "Customer";
        }

        return live;
    }
}
