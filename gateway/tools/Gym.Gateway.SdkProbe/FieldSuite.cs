using NetSDKCS;

namespace Gym.Gateway.SdkProbe;

internal sealed record FieldCase(string Fields, string What, Func<NET_ACCESS_USER_INFO, NET_ACCESS_USER_INFO> Apply);

internal sealed record FieldOutcome(string Fields, string What, string Write, string Verdict, string Sent, string ReadBack, string SideEffects, bool UpdateTimeChanged);

/// <summary>
/// Writes every user field the SDK exposes on a test user and reads it back (script to reader), then
/// optionally lets the operator edit the user on the reader screen and shows every field that changed
/// (reader to script). The test user is deleted at the end.
/// </summary>
internal sealed class FieldSuite(ProbeOptions o, Report r, DeviceFindings target, List<string> answers)
{
    private const string NamePrefix = "SDKPROBE";
    private static readonly string[] Ignored = ["stuUpdateTime"];
    private readonly ReaderSession _s = target.Session;
    private readonly List<FieldOutcome> _outcomes = [];
    private string Id => o.TestUser;

    public void Run()
    {
        r.Section($"Field test on {_s.Name} with test user {Id}");
        if (!PrepareTestUser())
        {
            return;
        }

        try
        {
            var current = _s.GetUser(Id);
            if (current == null)
            {
                r.Line("  test user could not be read back after creating it; field test stopped.");
                return;
            }

            r.Line("  fields the reader filled in for a freshly created user:");
            foreach (var line in UserFields.NonEmpty(UserFields.Dump(current.Value)))
            {
                r.Line("    " + line);
            }

            r.Sub("Script to reader: write one field, read the user back");
            foreach (var c in Cases())
            {
                current = RunCase(c, current.Value) ?? current;
            }

            r.Sub("Minimal write: send a user record with only ID and name over the existing user");
            current = RunCase(new FieldCase("(whole record)", "minimal record, everything else left empty", _ => Minimal(NamePrefix + " Minimal")), current.Value) ?? current;

            r.Csv("fields.csv", ["fields", "what", "write", "verdict", "sent", "readBack", "otherFieldsChanged", "updateTimeChanged"],
                _outcomes.Select(x => new[] { x.Fields, x.What, x.Write, x.Verdict, x.Sent, x.ReadBack, x.SideEffects, x.UpdateTimeChanged ? "yes" : "no" }));

            if (o.Interactive)
            {
                ReaderToScript();
            }
        }
        finally
        {
            r.Line();
            r.Line($"  removing test user {Id}: {_s.RemoveFace(Id)} / {_s.RemoveUser(Id)}");
        }

        Summarize();
    }

    private bool PrepareTestUser()
    {
        var existing = _s.GetUser(Id);
        if (existing != null)
        {
            var name = existing.Value.szName?.Trim() ?? "";
            if (!name.StartsWith(NamePrefix, StringComparison.Ordinal))
            {
                r.Line($"  ABORTED: user {Id} already exists as \"{name}\". Pick a free ID with --test-user.");
                return false;
            }

            r.Line($"  removing leftover test user: {_s.RemoveUser(Id)}");
        }

        var created = _s.InsertUser(Minimal(NamePrefix + " Fields"));
        r.Line($"  create test user: {created}");
        return created == "ok";
    }

    private NET_ACCESS_USER_INFO? RunCase(FieldCase c, NET_ACCESS_USER_INFO before)
    {
        // The update time has one-second resolution; without the pause two writes can share a timestamp.
        Thread.Sleep(1100);
        var wanted = c.Apply(before);
        var write = _s.InsertUser(wanted);
        var after = _s.GetUser(Id);
        var targets = c.Fields.Split(',', StringSplitOptions.TrimEntries);
        var sentDump = UserFields.Dump(wanted);
        var beforeDump = UserFields.Dump(before);
        string verdict, sent, readBack, side;
        var timeChanged = false;
        if (after == null)
        {
            verdict = "USER GONE";
            sent = string.Join("; ", targets.Select(t => $"{t}={sentDump.GetValueOrDefault(t)}"));
            readBack = "(user not found)";
            side = "";
        }
        else
        {
            var afterDump = UserFields.Dump(after.Value);
            timeChanged = beforeDump["stuUpdateTime"] != afterDump["stuUpdateTime"];
            if (c.Fields == "(whole record)")
            {
                var lost = UserFields.Diff(beforeDump, afterDump, Ignored);
                verdict = lost.Count == 0 ? "nothing else changed" : $"{lost.Count} field(s) changed";
                sent = "ID + name only";
                readBack = string.Join("; ", lost);
                side = "";
            }
            else
            {
                var ok = targets.All(t => sentDump.GetValueOrDefault(t) == afterDump.GetValueOrDefault(t));
                verdict = write != "ok" ? "WRITE FAILED" : ok ? "SAVED" : "NOT SAVED AS SENT";
                sent = string.Join("; ", targets.Select(t => $"{t}={sentDump.GetValueOrDefault(t)}"));
                readBack = string.Join("; ", targets.Select(t => $"{t}={afterDump.GetValueOrDefault(t)}"));
                side = string.Join("; ", UserFields.Diff(beforeDump, afterDump, [.. targets, .. Ignored]));
            }
        }

        _outcomes.Add(new FieldOutcome(c.Fields, c.What, write, verdict, sent, readBack, side, timeChanged));
        r.Line();
        r.Line($"  {c.What}  [{c.Fields}]  -> {verdict}{(write != "ok" ? " (" + write + ")" : "")}");
        r.Line($"     sent     : {sent}");
        r.Line($"     read back: {readBack}");
        if (side.Length > 0)
        {
            r.Line($"     ALSO CHANGED: {side}");
        }

        r.Line($"     update time {(timeChanged ? "changed" : "same")}");
        return after;
    }

    private void ReaderToScript()
    {
        r.Sub("Reader to script: edit the test user on the reader screen");
        _s.RemoveUser(Id);
        r.Line($"  re-created test user for this part: {_s.InsertUser(Minimal(NamePrefix + " Screen"))}");
        var current = _s.GetUser(Id);
        if (current == null)
        {
            r.Line("  test user not readable; skipped.");
            return;
        }

        r.Line($"  Walk to reader {_s.Name} ({_s.Target.Ip}) and open user {Id} (\"{NamePrefix} Screen\").");
        r.Line("  Change ONE thing at a time: name, password, card, validity dates, status/freeze, user type, admin level,");
        r.Line("  department, phone... any field the menu offers. After each change press Enter here and tell me what you changed.");
        r.Line("  Type q and Enter when you are finished.");
        var round = 0;
        _s.DrainAlarms();
        while (true)
        {
            Console.Write($"\n  [{++round}] What did you change on the reader? (q = finish): ");
            var said = Console.ReadLine()?.Trim() ?? "q";
            if (said.Equals("q", StringComparison.OrdinalIgnoreCase))
            {
                break;
            }

            Thread.Sleep(TimeSpan.FromSeconds(1));
            var after = _s.GetUser(Id);
            var alarms = _s.DrainAlarms();
            r.Line();
            r.Line($"  [{round}] operator changed: {said}");
            if (after == null)
            {
                r.Line("     user is GONE from the reader");
                answers.Add($"Reader edit \"{said}\": user deleted.");
                break;
            }

            var diff = UserFields.Diff(UserFields.Dump(current.Value), UserFields.Dump(after.Value));
            r.Line(diff.Count == 0 ? "     no field changed in the SDK record" : "     changed: " + string.Join("; ", diff));
            r.Line(alarms.Count == 0 ? "     events: none" : "     events: " + string.Join(" | ", alarms.Select(a => $"{a.Type} {a.Detail}")));
            answers.Add($"Reader edit \"{said}\": {(diff.Count == 0 ? "nothing visible in the SDK record" : string.Join("; ", diff))}; events: {(alarms.Count == 0 ? "none" : string.Join(",", alarms.Select(a => a.Type).Distinct()))}.");
            current = after;
        }

        r.Line("  final record of the test user:");
        foreach (var line in UserFields.NonEmpty(UserFields.Dump(current.Value)))
        {
            r.Line("    " + line);
        }
    }

    private void Summarize()
    {
        r.Section("Field test summary (script to reader)");
        foreach (var x in _outcomes)
        {
            r.Line($"  {x.Verdict,-18} {x.Fields,-34} {x.What}{(x.SideEffects.Length > 0 ? "  [also changed: " + x.SideEffects + "]" : "")}");
        }

        var saved = _outcomes.Count(x => x.Verdict == "SAVED");
        var tested = _outcomes.Count(x => x.Fields != "(whole record)");
        answers.Add($"Field round trip on {_s.Name}: {saved}/{tested} writes saved exactly as sent. Not saved: "
                    + string.Join("; ", _outcomes.Where(x => x.Fields != "(whole record)" && x.Verdict != "SAVED").Select(x => $"{x.What} ({x.Verdict})"))
                    + ".");
        var minimal = _outcomes.FirstOrDefault(x => x.Fields == "(whole record)");
        if (minimal != null)
        {
            answers.Add($"A minimal user write over an existing user: {minimal.Verdict}. {minimal.ReadBack}");
        }

        answers.Add($"Update time changed on {_outcomes.Count(x => x.UpdateTimeChanged)}/{_outcomes.Count} field writes.");
    }

    private NET_ACCESS_USER_INFO Minimal(string name)
    {
        var u = new NET_ACCESS_USER_INFO
        {
            szUserID = Id,
            szName = name,
            emUserType = EM_USER_TYPE.NORMAL,
            emAuthority = EM_ATTENDANCE_AUTHORITY.Customer,
            nDoorNum = 1,
            nDoors = new int[32],
            nTimeSectionNum = 1,
            nTimeSectionNo = new int[32],
            nSpecialDaysSchedule = new int[128],
            nFirstEnterDoors = new int[32]
        };
        return u;
    }

    private static NET_TIME At(int y, int mo, int d, int h = 0, int mi = 0, int s = 0) => NET_TIME.FromDateTime(new DateTime(y, mo, d, h, mi, s));

    private static IEnumerable<FieldCase> Cases()
    {
        var today = DateTime.UtcNow.Date;
        return
        [
            new("szName", "plain name", u => { u.szName = "SDKPROBE Plain Name"; return u; }),
            new("szName", "name of 31 characters (the field maximum)", u => { u.szName = "SDKPROBE ABCDEFGHIJKLMNOPQRSTUV"; return u; }),
            new("szName", "name with accented letters", u => { u.szName = "SDKPROBE José Müller"; return u; }),
            new("szName", "name in Hindi", u => { u.szName = "राहुल शर्मा"; return u; }),
            new("bUseNameEx,szNameEx", "long name (60 chars) in the extended name field", u => { u.bUseNameEx = true; u.szNameEx = "SDKPROBE " + new string('L', 51); return u; }),
            new("bUseNameEx,szNameEx", "Hindi name in the extended name field", u => { u.bUseNameEx = true; u.szNameEx = "राहुल कुमार शर्मा"; return u; }),
            new("szName,bUseNameEx,szNameEx", "back to a plain name, extended name off", u => { u.szName = "SDKPROBE Fields"; u.bUseNameEx = false; u.szNameEx = ""; return u; }),
            new("nUserStatus", "freeze", u => { u.nUserStatus = 1; return u; }),
            new("nUserStatus", "unfreeze", u => { u.nUserStatus = 0; return u; }),
            new("emUserType", "user type VIP", u => { u.emUserType = EM_USER_TYPE.VIP; return u; }),
            new("emUserType,nUserTime", "user type GUEST with 5 entries", u => { u.emUserType = EM_USER_TYPE.GUEST; u.nUserTime = 5; return u; }),
            new("emUserType,nUserTime", "user type back to NORMAL", u => { u.emUserType = EM_USER_TYPE.NORMAL; u.nUserTime = 0; return u; }),
            new("emAuthority", "admin level", u => { u.emAuthority = EM_ATTENDANCE_AUTHORITY.Administrators; return u; }),
            new("emAuthority", "back to normal user", u => { u.emAuthority = EM_ATTENDANCE_AUTHORITY.Customer; return u; }),
            new("szPsw", "password 4321", u => { u.szPsw = "4321"; return u; }),
            new("szPsw", "password cleared", u => { u.szPsw = ""; return u; }),
            new("stuValidBeginTime", "valid from with a time of day (2026-01-02 03:04:05)", u => { u.stuValidBeginTime = At(2026, 1, 2, 3, 4, 5); return u; }),
            new("stuValidEndTime", "valid to with a time of day (2027-06-07 21:22:23)", u => { u.stuValidEndTime = At(2027, 6, 7, 21, 22, 23); return u; }),
            new("stuValidEndTime", "valid to 2037-12-31 23:59:59", u => { u.stuValidEndTime = At(2037, 12, 31, 23, 59, 59); return u; }),
            new("stuValidEndTime", "valid to in the past (yesterday 23:59:59)", u => { u.stuValidEndTime = NET_TIME.FromDateTime(today.AddSeconds(-1)); return u; }),
            new("stuValidBeginTime,stuValidEndTime", "validity cleared (all zero)", u => { u.stuValidBeginTime = new NET_TIME(); u.stuValidEndTime = new NET_TIME(); return u; }),
            new("stuValidBeginTime,stuValidEndTime", "validity today to +30 days end of day", u =>
            {
                u.stuValidBeginTime = NET_TIME.FromDateTime(today);
                u.stuValidEndTime = NET_TIME.FromDateTime(today.AddDays(31).AddSeconds(-1));
                return u;
            }),
            new("szCitizenIDNo", "ID number", u => { u.szCitizenIDNo = "PROBEID12345"; return u; }),
            new("stuBirthDay", "birthday 1990-05-06", u => { u.stuBirthDay = At(1990, 5, 6); return u; }),
            new("emSex", "gender female", u => { u.emSex = NET_ACCESSCTLCARD_SEX.FEMALE; return u; }),
            new("szDepartment", "department", u => { u.szDepartment = "Probe Department"; return u; }),
            new("szPhoneNumber", "phone number", u => { u.szPhoneNumber = "9876543210"; return u; }),
            new("szCitizenAddress", "address", u => { u.szCitizenAddress = "12 Probe Street, Pune"; return u; }),
            new("szClassInfo", "class info", u => { u.szClassInfo = "Probe Class"; return u; }),
            new("szStudentNo", "student number", u => { u.szStudentNo = "STU-42"; return u; }),
            new("szSiteCode", "site code", u => { u.szSiteCode = "SITE7"; return u; }),
            new("szWorkClass", "work class", u => { u.szWorkClass = "Morning"; return u; }),
            new("szCountryOrAreaCode", "country code", u => { u.szCountryOrAreaCode = "IN"; return u; }),
            new("nRepeatEnterRouteTimeout", "repeat-entry timeout 30", u => { u.nRepeatEnterRouteTimeout = 30; return u; }),
            new("nHolidayGroupIndex", "holiday group 1", u => { u.nHolidayGroupIndex = 1; return u; }),
            new("nTimeSectionNum,nTimeSectionNo", "time schedule 255 (usually: always allowed)", u =>
            {
                u.nTimeSectionNum = 1;
                u.nTimeSectionNo = new int[32];
                u.nTimeSectionNo[0] = 255;
                return u;
            }),
            new("nTimeSectionNum,nTimeSectionNo", "time schedule back to 0", u =>
            {
                u.nTimeSectionNum = 1;
                u.nTimeSectionNo = new int[32];
                return u;
            })
        ];
    }
}
