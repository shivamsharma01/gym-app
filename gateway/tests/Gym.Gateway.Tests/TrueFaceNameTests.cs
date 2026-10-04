using Gym.Gateway.Adapters;
using NetSDKCS;
using Xunit;

namespace Gym.Gateway.Tests;

/// <summary>
/// Readers keep the shown name in szNameEx (bUseNameEx is set on every stored user) and ignore a write
/// to szName alone, as measured on the gym readers. These pin how the adapter reads and writes names.
/// </summary>
public class TrueFaceNameTests
{
    private static NET_ACCESS_USER_INFO ReaderUser(string shortName, string? extendedName, bool useExtended = true) =>
        new()
        {
            szUserID = "648",
            szName = shortName,
            szNameEx = extendedName ?? "",
            bUseNameEx = useExtended,
            nUserStatus = 0,
            nUserTime = 200,
            stuValidBeginTime = NET_TIME.FromDateTime(new DateTime(2018, 1, 1)),
            stuValidEndTime = NET_TIME.FromDateTime(new DateTime(2026, 6, 5)),
            emAuthority = EM_ATTENDANCE_AUTHORITY.Customer
        };

    [Fact]
    public void Reads_the_extended_name_the_reader_shows()
    {
        var user = ReaderUser("Old Short", "Annu Rana");

        Assert.Equal("Annu Rana", TrueFaceDeviceAdapter.DeviceName(user));
        Assert.Equal("Annu Rana", TrueFaceDeviceAdapter.ToSnapshot(user).Name);
    }

    [Fact]
    public void Falls_back_to_the_short_name_when_the_extended_name_is_off_or_empty()
    {
        Assert.Equal("Annu Rana", TrueFaceDeviceAdapter.DeviceName(ReaderUser("Annu Rana", "Other", useExtended: false)));
        Assert.Equal("Annu Rana", TrueFaceDeviceAdapter.DeviceName(ReaderUser("Annu Rana", "  ")));
    }

    [Fact]
    public void Rename_writes_both_name_fields_and_keeps_everything_else()
    {
        var user = ReaderUser("Annu Rana", "Annu Rana");
        var mutation = new DeviceUserMutation("648", Name: "Annu Sharma");
        Assert.False(TrueFaceDeviceAdapter.UserMatchesDesired(user, mutation));

        var written = TrueFaceDeviceAdapter.ApplyMutation(user, mutation);

        Assert.Equal("Annu Sharma", written.szName);
        Assert.Equal("Annu Sharma", written.szNameEx);
        Assert.True(written.bUseNameEx);
        Assert.Equal(200, written.nUserTime);
        Assert.Equal(0u, written.nUserStatus);
        Assert.Equal(user.stuValidEndTime.ToDateTime(), written.stuValidEndTime.ToDateTime());
        Assert.True(TrueFaceDeviceAdapter.UserMatchesDesired(written, mutation));
    }

    [Fact]
    public void A_rename_the_reader_ignored_is_not_reported_as_done()
    {
        // What the reader returned after a write to szName alone: the extended name is unchanged.
        var ignored = ReaderUser("Annu Sharma", "Annu Rana");

        Assert.False(TrueFaceDeviceAdapter.UserMatchesDesired(ignored, new DeviceUserMutation("648", Name: "Annu Sharma")));
    }

    [Fact]
    public void Long_names_keep_their_full_length_in_the_extended_field()
    {
        var name = "Rajkumar Venkataraman Subramaniam Iyer";
        var written = TrueFaceDeviceAdapter.ApplyMutation(ReaderUser("x", "x"), new DeviceUserMutation("648", Name: name));

        Assert.Equal(name[..31].TrimEnd(), written.szName);
        Assert.Equal(name, written.szNameEx);
        Assert.Equal(name, TrueFaceDeviceAdapter.DeviceName(written));
        Assert.True(TrueFaceDeviceAdapter.UserMatchesDesired(written, new DeviceUserMutation("648", Name: name)));
    }

    [Fact]
    public void A_cut_that_ends_on_a_space_still_matches_after_the_reader_trims_it()
    {
        var name = new string('A', 30) + " Bcd";
        var written = TrueFaceDeviceAdapter.ApplyMutation(ReaderUser("x", "x"), new DeviceUserMutation("648", Name: name));

        Assert.Equal(new string('A', 30), written.szName);
        Assert.True(TrueFaceDeviceAdapter.UserMatchesDesired(written, new DeviceUserMutation("648", Name: name)));
    }

    [Fact]
    public void Surrounding_spaces_from_the_server_do_not_cause_a_rewrite()
    {
        var user = ReaderUser("Annu Rana", "Annu Rana");

        Assert.True(TrueFaceDeviceAdapter.UserMatchesDesired(user, new DeviceUserMutation("648", Name: "  Annu Rana ")));
    }

    [Fact]
    public void Changes_without_a_name_leave_both_name_fields_alone()
    {
        var user = ReaderUser("Old Short", "Annu Rana");

        var written = TrueFaceDeviceAdapter.ApplyMutation(user, new DeviceUserMutation("648", Enabled: false));

        Assert.Equal("Old Short", written.szName);
        Assert.Equal("Annu Rana", written.szNameEx);
        Assert.Equal(1u, written.nUserStatus);
    }

    [Fact]
    public void New_users_get_the_extended_name_and_fall_back_to_their_id()
    {
        var named = TrueFaceDeviceAdapter.BuildUser(new DeviceUserMutation("990001", Name: "New Member"), freeze: false);
        var unnamed = TrueFaceDeviceAdapter.BuildUser(new DeviceUserMutation("990002", Name: "  "), freeze: true);

        Assert.True(named.bUseNameEx);
        Assert.Equal("New Member", named.szNameEx);
        Assert.Equal("New Member", named.szName);
        Assert.Equal("990002", TrueFaceDeviceAdapter.DeviceName(unnamed));
        Assert.Equal(1u, unnamed.nUserStatus);
    }
}
