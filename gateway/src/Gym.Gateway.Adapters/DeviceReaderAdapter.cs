namespace Gym.Gateway.Adapters;

/// <summary>
/// V1 calls on the gateway's existing device adapter. An id that is already present is occupied and
/// is not overwritten. A face that is already stored is not replaced.
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
            var live = _device.GetUser(deviceUserId);
            if (live == null)
            {
                _written.Remove(deviceUserId);
                return ReaderUserResult.NoRecord();
            }

            if (_written.TryGetValue(deviceUserId, out var written) && Agrees(live, written))
            {
                return ReaderUserResult.Found(written);
            }

            return ReaderUserResult.Found(Map(live));
        }
        catch (DeviceReadException ex)
        {
            return ReaderUserResult.Failed(ex.Message);
        }
    }

    public ReaderCallResult CreateUser(ReaderUser user)
    {
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

        var created = _device.CreateUser(new DeviceUserMutation(
            user.DeviceUserId,
            user.Name,
            user.UserStatus == 0,
            user.ValidFrom,
            user.ValidTo,
            user.Authority));
        if (!created.Ok)
        {
            return ReaderCallResult.Failed(created.Error ?? "create failed");
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
        if (!string.Equals(live.DeviceUserId, written.DeviceUserId, StringComparison.Ordinal))
        {
            return false;
        }

        if (written.NameEx != null)
        {
            return false;
        }

        if (!string.Equals(live.Name, written.Name, StringComparison.Ordinal))
        {
            return false;
        }

        if ((live.Frozen ? 1 : 0) != written.UserStatus)
        {
            return false;
        }

        if (live.ValidFrom != written.ValidFrom || live.ValidTo != written.ValidTo)
        {
            return false;
        }

        return AuthorityAgrees(live.Authority, written.Authority);
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

    private static bool NameConflicts(DeviceUserSnapshot live, ReaderUser user)
    {
        if (string.IsNullOrEmpty(live.Name))
        {
            return false;
        }

        return !string.Equals(live.Name, user.Name, StringComparison.Ordinal)
            && !string.Equals(live.Name, user.NameEx, StringComparison.Ordinal);
    }

    private static ReaderUser Map(DeviceUserSnapshot user) => new(
        user.DeviceUserId,
        user.Name,
        null,
        user.Frozen ? 1 : 0,
        user.ValidFrom,
        user.ValidTo,
        string.IsNullOrWhiteSpace(user.Authority) ? "Customer" : user.Authority,
        1,
        1);
}
