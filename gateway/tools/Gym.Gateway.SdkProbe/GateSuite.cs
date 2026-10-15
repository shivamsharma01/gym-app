using System.Diagnostics;
using System.Text.RegularExpressions;
using NetSDKCS;

namespace Gym.Gateway.SdkProbe;

internal sealed record MutationTrial(bool WriteOk, bool? TimeChanged, string Codes);

/// <summary>
/// Third hardware POC: the P1–P12 checks from docs/architecture/device-sync-architecture.md,
/// run on one reader with throwaway SYNCPOC users. Each check ends OBSERVED, NOT OBSERVED or UNKNOWN by the
/// rules in <see cref="GateVerdicts"/>; see GATES.md for the operator steps.
/// </summary>
internal sealed class GateSuite
{
    private const string Prefix = "SYNCPOC";
    private const string Unreadable = "(unreadable)";
    private const string None = "(none)";
    private const string NoResult = "no result";
    private const int MaxPhotoBytes = 120 * 1024;
    private const int Trials = 2;
    private const int Page = 50;
    private static readonly TimeSpan MatchTimeout = TimeSpan.FromSeconds(1);
    private static readonly DateTime LogStart = new(2000, 1, 1, 0, 0, 0, DateTimeKind.Unspecified);
    private static readonly HashSet<string> NotUserChanges = new(StringComparer.Ordinal)
    {
        "ALARM_ACCESS_CTL_EVENT", "SDK_DISCONNECTED", "SDK_RECONNECTED"
    };

    private static readonly string[] Rules =
    [
        "OBSERVED     = the statement in the question was seen in this run (for how/what questions: one consistent answer was seen).",
        "NOT OBSERVED = the opposite was seen, consistently.",
        "UNKNOWN      = not enough clear evidence. A skipped step, a failed read, a single trial, or two signals that",
        "               disagree give UNKNOWN. An SDK call that returned success is not evidence on its own.",
        "Only SYNCPOC test users with ids that were free at the start are written; they are removed at the end."
    ];

    private readonly ProbeOptions _o;
    private readonly Report _r;
    private readonly ReaderSession _first;
    private readonly List<GateResult> _order = [];
    private readonly Dictionary<string, GateResult> _gates = new(StringComparer.Ordinal);
    private readonly Dictionary<int, Punch> _seenPunches = [];
    private readonly List<string> _cleanup = [];
    private readonly List<(string Name, Verdict Verdict)> _p5 = [];
    private ReaderSession _s;
    private HashSet<string> _startIds = new(StringComparer.Ordinal);
    private DateTime _runStart;
    private string _a = "", _b = "", _c = "";
    private byte[]? _photo;
    private byte[]? _photo2;
    private bool _faceOnA;
    private bool _lost;
    private string? _deleteNote;

    public GateSuite(ProbeOptions o, Report r, ReaderSession session)
    {
        _o = o;
        _r = r;
        _first = session;
        _s = session;
        Add("P1", "Does nUserStatus=1 actually prevent door access on target firmware?");
        Add("P2", "When device UI creates a user, how is szUserID chosen?");
        Add("P3", "Does INSERT of an existing szUserID update all required fields without zeroing omitted values?");
        Add("P4", "Does stuUpdateTime change predictably for every relevant mutation?");
        Add("P5", "Does each relevant alarm fire reliably, and what is its payload on this firmware?");
        Add("P6", "Does nRecNo remain monotonic/persistent across reboot, storage full, and time windows?");
        Add("P7", "Can attendance be queried strictly after recNo or only by time window?");
        Add("P8", "What is reader attendance retention?");
        Add("P9", "What happens when the same face is created under two device user IDs?");
        Add("P10", "What are safe roster sizes and concurrent SDK-operation limits?");
        Add("P11", "Exact validity boundary behavior and timezone handling on device.");
        Add("P12", "Reader replacement/factory reset signals or reliable empty-state detection.");
        Add("P13", "Does a live door event (ALARM_ACCESS_CTL_EVENT) arrive for each walk, carrying the stored record number?");
        Add("P14", "Does a photo read back exactly as written, and what do face INSERT over a photo and UPDATE without one return?");
        Add("P15", "What does the reader answer for a missing user or photo (GET, REMOVE), and is the answer consistent?");
        Add("P16", "Does a full user list return exactly the announced total, the same set on two reads?");
        Add("P17", "How are szName (31 chars) and szNameEx (127 chars) stored and read back?");
        Add("P18", "Are stored punch times in the reader's own clock?");
        Add("P19", "Does emAuthority=Administrators (and only that) open the reader's admin menu?");
        Add("P20", "After the reader drops off (power cycle or network unplug), does the SDK reconnect the same login by itself and keep delivering door events?");
        Add("P21", "What photo sizes does the reader accept, and what does it answer above its limit?");
    }

    private readonly List<(bool? Back, bool? Events)> _p20 = [];
    private bool _p20Unplugged;

    private sealed record WalkRecord(string Label, string UserId, DateTime Before, DateTime After, List<Punch> Mine, List<AlarmSeen> Alarms);

    private readonly List<WalkRecord> _walks = [];
    private readonly HashSet<int> _attributed = [];

    public void Run()
    {
        _r.Section($"Sync gates P1-P12 on {_s.Name} ({_s.Target.Ip}:{_s.Target.Port})");
        _r.Line($"  serial {_s.Info.sSerialNumber}, device type {_s.Info.nDVRType}, listening for events: {_s.Listening}");
        _r.Line($"  PC {DateTime.Now:yyyy-MM-dd HH:mm:ss} local, {DateTime.UtcNow:yyyy-MM-dd HH:mm:ss} UTC, zone {TimeZoneInfo.Local.Id}");
        _runStart = Clock();
        _r.Line($"  reader clock {_runStart:yyyy-MM-dd HH:mm:ss}");
        foreach (var line in Rules)
        {
            _r.Line("  " + line);
        }

        var start = _s.ListUsers(Page);
        if (!Trusted(start))
        {
            _r.Line($"  user list could not be read ({start.Error ?? "empty list"}); nothing was written.");
            Summarize();
            return;
        }

        _startIds = start.Users.Select(u => u.Id).ToHashSet(StringComparer.Ordinal);
        _gates["P10"].Evidence.Add($"full user list: {start.Users.Count} users (announced {start.Total}) in {start.Ms} ms over {start.Calls} calls of {Page}");

        try
        {
            if (Prepare())
            {
                Step("P1", P1);
                Step("P11", P11);
                Step("P3", P3);
                Step("P4/P5", Mutations);
                Step("P14", P14);
                Step("P21", P21);
                Step("P15", P15);
                Step("P17", P17);
                Step("P19", P19);
                Step("P2", P2);
                Step("P9", P9);
                Step("P10", P10);
                Step("P16", P16);
                Step("P7", P7);
                Step("P8", P8);
                Step("P6", P6);
                Step("P13/P18", DoorEvidence);
                Step("P20", P20);
            }
        }
        finally
        {
            Cleanup();
        }

        Step("P12", P12);
        Summarize();
        if (!ReferenceEquals(_s, _first))
        {
            _s.Dispose();
        }
    }

    private void Add(string id, string question)
    {
        var gate = new GateResult(id, question);
        _order.Add(gate);
        _gates[id] = gate;
    }

    private void Step(string label, Action check)
    {
        if (_lost)
        {
            _r.Line($"  {label}: not run, the reader is not reachable");
            return;
        }

        try
        {
            check();
        }
        catch (Exception ex)
        {
            _r.Line($"  {label} stopped: {ex.GetType().Name}: {ex.Message}");
            foreach (var id in label.Split('/').Select(x => x.StartsWith('P') ? x : "P" + x))
            {
                if (_gates.TryGetValue(id, out var gate))
                {
                    gate.Evidence.Add($"stopped by {ex.GetType().Name}: {ex.Message}");
                }
            }
        }
    }

    private bool Prepare()
    {
        _r.Sub("Test users");
        var ids = FreeIds(3);
        if (ids.Count < 3)
        {
            _r.Line("  could not find three unused user ids; nothing was written.");
            return false;
        }

        (_a, _b, _c) = (ids[0], ids[1], ids[2]);
        _r.Line($"  test users: A={_a} (main), B={_b} (duplicate face), C={_c} (delete trials)");
        _photo = LoadPhoto(_o.PhotoPath, "--photo");
        _photo2 = LoadPhoto(_o.Photo2Path, "--photo2");
        if (_photo != null && _photo2 != null && Probe.Md5(_photo) == Probe.Md5(_photo2))
        {
            _r.Line("  --photo2 is the same image as --photo; the face-replace step needs a different one.");
            _photo2 = null;
        }

        _cleanup.Add(_a);
        var created = _s.InsertUser(Baseline(_a, "A"));
        _r.Line($"  create A: {created}");
        if (created != "ok" || _s.GetUser(_a) == null)
        {
            _r.Line("  A could not be created and read back; no check was run.");
            return false;
        }

        if (_photo != null)
        {
            _r.Line($"  face on A: {_s.WriteFace(EM_NET_ACCESS_CTL_FACE_SERVICE.INSERT, _a, _photo)}");
        }
        else if (!_o.NoWalks && Ask($"  No --photo given. Enrol your own face for user {_a} on the reader screen now. d = done, k = skip: ", "dk") == 'd')
        {
            _photo = _s.GetFace(_a).Photo;
        }

        var face = _s.GetFace(_a);
        _faceOnA = face.Photo != null;
        _r.Line($"  A has a face on the reader: {(face.Photo != null ? $"yes, {face.Photo.Length / 1024} KB" : $"no ({face.FailCode} {face.Error})")}");
        return true;
    }

    private void P1()
    {
        var g = _gates["P1"];
        _r.Sub("P1 freeze: does nUserStatus=1 keep the door shut?");
        if (!_faceOnA)
        {
            g.Set(Verdict.Unknown, "user A has no face on the reader, so no walk could be judged");
            return;
        }

        var enabled = new List<Door?> { Walk("P1 enabled 1", _a, "enabled, nUserStatus=0") };
        var freeze = Rewrite(_a, u => WithStatus(u, 1));
        var status = _s.GetUser(_a)?.nUserStatus;
        Note("P1", $"freeze write {freeze}; nUserStatus read back {status?.ToString() ?? Unreadable}");
        if (status != 1)
        {
            Rewrite(_a, u => WithStatus(u, 0));
            g.Set(Verdict.Unknown, "nUserStatus did not read back as 1 after the freeze write");
            return;
        }

        var frozen = new List<Door?>
        {
            Walk("P1 frozen 1", _a, "frozen, nUserStatus=1"),
            Walk("P1 frozen 2", _a, "frozen, nUserStatus=1")
        };
        var unfreeze = Rewrite(_a, u => WithStatus(u, 0));
        Note("P1", $"unfreeze write {unfreeze}; nUserStatus read back {_s.GetUser(_a)?.nUserStatus.ToString() ?? Unreadable}");
        enabled.Add(Walk("P1 enabled 2", _a, "enabled again, nUserStatus=0"));
        Note("P1", $"enabled walks: {string.Join(", ", enabled.Select(Show))}; frozen walks: {string.Join(", ", frozen.Select(Show))}");

        var control = GateVerdicts.Repeated(enabled);
        var blocked = GateVerdicts.Repeated(frozen);
        if (control != Door.Opened)
        {
            g.Set(Verdict.Unknown, "the enabled walks did not both clearly open the door, so a shut door while frozen would prove nothing");
        }
        else if (blocked == Door.Shut)
        {
            g.Set(Verdict.Observed, "both frozen walks were refused and both enabled walks opened");
        }
        else if (blocked == Door.Opened)
        {
            g.Set(Verdict.NotObserved, "the door opened for the frozen user on both walks");
        }
        else
        {
            g.Set(Verdict.Unknown, "the frozen walks were skipped, unclear, or disagreed");
        }
    }

    private void P11()
    {
        var g = _gates["P11"];
        _r.Sub("P11 validity boundary and reader time");
        var clock = _s.DeviceTime();
        Note("P11", clock == null
            ? "reader clock unreadable"
            : $"reader clock {clock:yyyy-MM-dd HH:mm:ss}; PC UTC {DateTime.UtcNow:yyyy-MM-dd HH:mm:ss}; PC local {DateTime.Now:yyyy-MM-dd HH:mm:ss} ({TimeZoneInfo.Local.Id})");
        var today = Today();
        List<Door?> endsToday = Validity("ends today", today.AddDays(-1), EndOfDay(today))
            ? Pair("P11 ends today", "validity ends today")
            : [null, null];
        List<Door?> startsTomorrow = Validity("starts tomorrow", today.AddDays(1), EndOfDay(today.AddDays(30)))
            ? Pair("P11 starts tomorrow", "validity starts tomorrow")
            : [null, null];
        RestoreA();

        var a = GateVerdicts.Repeated(endsToday);
        var b = GateVerdicts.Repeated(startsTomorrow);
        if (a != null && b != null)
        {
            g.Set(Verdict.Observed, $"validity ending today (reader date {today:yyyy-MM-dd}): door {a}; validity starting tomorrow: door {b}");
        }
        else
        {
            g.Set(Verdict.Unknown, "the boundary walks were skipped, unclear, or disagreed; the stored dates are in the evidence");
        }
    }

    private bool Validity(string label, DateTime from, DateTime to)
    {
        var write = Rewrite(_a, u =>
        {
            u.stuValidBeginTime = NET_TIME.FromDateTime(from);
            u.stuValidEndTime = NET_TIME.FromDateTime(to);
            return u;
        });
        var back = _s.GetUser(_a);
        var sent = $"{ReaderSession.Raw(NET_TIME.FromDateTime(from))} .. {ReaderSession.Raw(NET_TIME.FromDateTime(to))}";
        var stored = back == null
            ? Unreadable
            : $"{ReaderSession.Raw(back.Value.stuValidBeginTime)} .. {ReaderSession.Raw(back.Value.stuValidEndTime)}";
        Note("P11", $"{label}: write {write}; sent {sent}; stored {stored}{(stored == sent ? "" : " (DIFFERENT)")}");
        return write == "ok" && back != null;
    }

    private void P3()
    {
        var g = _gates["P3"];
        _r.Sub("P3 partial INSERT: write only the ID and a name over an existing user");
        var emptied = new List<string?>();
        var nameSaved = new List<bool?>();
        for (var t = 1; t <= Trials; t++)
        {
            var (lost, saved) = P3Trial(t);
            emptied.Add(lost);
            nameSaved.Add(saved);
        }

        RestoreA();
        if (_photo != null && _s.GetFace(_a).Photo == null)
        {
            _r.Line($"  face put back on A: {PutFace(_a, _photo)}");
        }

        var lostBoth = GateVerdicts.Repeated(emptied);
        var savedBoth = GateVerdicts.Repeated(nameSaved);
        if (lostBoth == null || savedBoth == null)
        {
            g.Set(Verdict.Unknown, "a trial failed or the two trials differed");
        }
        else if (savedBoth == true && lostBoth.Length == 0)
        {
            g.Set(Verdict.Observed, "the name was saved and no omitted field was emptied, in both trials");
        }
        else
        {
            g.Set(Verdict.NotObserved, savedBoth == true
                ? $"the name was saved but omitted values were emptied in both trials: {lostBoth}"
                : "the name sent in the partial write was not saved, in both trials");
        }
    }

    private (string? Lost, bool? Saved) P3Trial(int t)
    {
        RestoreA();
        Thread.Sleep(1100);
        var before = _s.GetUser(_a);
        var faceBefore = _s.GetFace(_a).Photo != null;
        var name = $"{Prefix} P3 {t}";
        var write = _s.InsertUser(new NET_ACCESS_USER_INFO
        {
            szUserID = _a,
            szName = name,
            nDoors = new int[32],
            nTimeSectionNo = new int[32],
            nSpecialDaysSchedule = new int[128],
            nFirstEnterDoors = new int[32]
        });
        var after = _s.GetUser(_a);
        var faceAfter = _s.GetFace(_a).Photo != null;
        if (before == null || after == null || write != "ok")
        {
            Note("P3", $"trial {t}: write {write}; read before {before != null}, after {after != null} -> no result");
            return (null, null);
        }

        var dumpedBefore = UserFields.Dump(before.Value);
        var dumpedAfter = UserFields.Dump(after.Value);
        var lost = dumpedBefore.Where(kv => kv.Key != "stuUpdateTime" && !UserFields.IsEmpty(kv.Value) && UserFields.IsEmpty(dumpedAfter.GetValueOrDefault(kv.Key) ?? ""))
            .Select(kv => kv.Key)
            .Order(StringComparer.Ordinal)
            .ToList();
        if (faceBefore && !faceAfter)
        {
            lost.Add("face");
        }

        var saved = ReaderName(after.Value) == name;
        var changed = UserFields.Diff(dumpedBefore, dumpedAfter, ["stuUpdateTime"]);
        Note("P3", $"trial {t}: name saved {saved}; face before {faceBefore}, after {faceAfter}; emptied [{string.Join(", ", lost)}]");
        Note("P3", $"trial {t}: every field that changed: {(changed.Count == 0 ? "none" : string.Join("; ", changed))}");
        return (string.Join(",", lost), saved);
    }

    private sealed record Mutation(string Step, string UserId, bool FaceTime, Action Prepare, Func<string> Act);

    private void Mutations()
    {
        _r.Sub("P4 / P5 update time and alarms for each kind of change (two trials each)");
        var steps = new List<Mutation>
        {
            new("name", _a, false, RestoreA, () => Rewrite(_a, u => WithName(u, $"{Prefix} renamed {Guid.NewGuid().ToString("N")[..4]}"))),
            new("validity", _a, false, RestoreA, () => Rewrite(_a, u =>
            {
                u.stuValidEndTime = NET_TIME.FromDateTime(EndOfDay(Today().AddDays(45)));
                return u;
            })),
            new("freeze", _a, false, RestoreA, () => Rewrite(_a, u => WithStatus(u, 1))),
            new("authority", _a, false, RestoreA, () => Rewrite(_a, u =>
            {
                u.emAuthority = EM_ATTENDANCE_AUTHORITY.Administrators;
                return u;
            }))
        };
        if (_photo != null && _photo2 != null)
        {
            steps.Add(new("face replace", _a, true, () =>
            {
                RestoreA();
                PutFace(_a, _photo);
            }, () => _s.WriteFace(EM_NET_ACCESS_CTL_FACE_SERVICE.UPDATE, _a, _photo2)));
        }

        _cleanup.Add(_c);
        steps.Add(new("delete", _c, false, () => _s.InsertUser(Baseline(_c, "C")), () => _s.RemoveUser(_c)));

        var p4 = new List<(string Name, Verdict Verdict)>();
        foreach (var step in steps)
        {
            var trials = Enumerable.Range(1, Trials).Select(t => Measure(step, t)).ToList();
            if (step.Step == "delete")
            {
                Note("P4", "delete: no update time can be read after the record is gone; not counted");
            }
            else
            {
                var v = GateVerdicts.FromTrials(trials.Select(x => x.TimeChanged).ToList());
                Note("P4", $"{step.Step}: {GateVerdicts.Label(v)}");
                p4.Add((step.Step, v));
            }

            var codes = GateVerdicts.Repeated(trials.Select(x => x.WriteOk ? x.Codes : null).ToList());
            _p5.Add((step.Step, AlarmVerdict(step.Step, codes)));
        }

        if (_photo2 == null)
        {
            Note("P4", "face replace: not run (needs a face on A and a different --photo2)");
            Note("P5", "face replace: not run (needs a face on A and a different --photo2)");
            p4.Add(("face replace", Verdict.Unknown));
            _p5.Add(("face replace", Verdict.Unknown));
        }

        RestoreA();
        if (_photo != null)
        {
            PutFace(_a, _photo);
        }

        Note("P4", "even an update time that moves on every change is not shown by this run to be safe for ordering changes");
        var all = GateVerdicts.AllOf(p4.Select(x => x.Verdict));
        _gates["P4"].Set(all, all switch
        {
            Verdict.Observed => "the update time changed in both trials of every change tried",
            Verdict.NotObserved => $"the update time stayed the same in both trials of: {Names(p4, Verdict.NotObserved)}",
            _ => $"no clear result for: {Names(p4, Verdict.Unknown)}"
        });
        SetP5();
    }

    private MutationTrial Measure(Mutation m, int trial)
    {
        m.Prepare();
        Thread.Sleep(1100);
        var (userBefore, faceBefore) = UpdateTimes(m.UserId);
        _s.DrainAlarms();
        var write = m.Act();
        Thread.Sleep(_o.StepDelaySeconds * 1000);
        var alarms = _s.DrainAlarms();
        var (userAfter, faceAfter) = UpdateTimes(m.UserId);
        var (before, after) = m.FaceTime ? (faceBefore, faceAfter) : (userBefore, userAfter);
        var ok = write == "ok";
        bool? changed = !ok || before is null or "(zero)" || after is null or "(zero)" ? null : before != after;
        var note = changed == true && string.CompareOrdinal(after, before) < 0 ? " (went BACKWARDS)" : "";
        var judged = changed switch
        {
            null => NoResult,
            true => "changed",
            false => "SAME"
        };
        Note("P4", $"{m.Step} trial {trial}: write {write}; user time {userBefore ?? Unreadable} -> {userAfter ?? Unreadable}; "
                   + $"photo time {faceBefore ?? None} -> {faceAfter ?? None}; judged on the {(m.FaceTime ? "photo" : "user")} time"
                   + $" -> {judged}{note}");
        if (m.Step == "delete")
        {
            _deleteNote = $"after deleting a test user, GET {(_s.GetUser(m.UserId) == null ? "found no record" : "still returned it")}";
        }

        LogAlarms("P5", $"{m.Step} trial {trial}", alarms);
        return new MutationTrial(ok, changed, Codes(alarms));
    }

    private (string? User, string? Face) UpdateTimes(string id)
    {
        var user = _s.GetUser(id);
        var face = _s.GetFace(id);
        return (user == null ? null : ReaderSession.Raw(user.Value.stuUpdateTime),
            face.Ok && face.Photo != null ? face.UpdateRaw : null);
    }

    private Verdict AlarmVerdict(string name, string? repeatedCodes)
    {
        Verdict v;
        if (repeatedCodes == null)
        {
            v = Verdict.Unknown;
        }
        else if (repeatedCodes.Length == 0)
        {
            v = Verdict.NotObserved;
        }
        else
        {
            v = Verdict.Observed;
        }
        Note("P5", $"{name}: {GateVerdicts.Label(v)}{(repeatedCodes is { Length: > 0 } ? " with " + repeatedCodes : "")}");
        return v;
    }

    private void SetP5()
    {
        var all = GateVerdicts.AllOf(_p5.Select(x => x.Verdict));
        _gates["P5"].Set(all, all switch
        {
            Verdict.Observed => "every change tried raised the same alarm code(s) in both trials (seen in this run, not a guarantee)",
            Verdict.NotObserved => $"no alarm in either trial for: {Names(_p5, Verdict.NotObserved)}",
            _ => $"different alarms between trials, a skipped trial, or a failed write for: {Names(_p5, Verdict.Unknown)}"
        });
    }

    private void P2()
    {
        var g = _gates["P2"];
        _r.Sub("P2 user id chosen by the reader screen");
        var screenCreate = new List<string?>();
        var rules = new List<string?>();
        for (var t = 1; t <= Trials; t++)
        {
            var (codes, rule) = P2Trial(t);
            screenCreate.Add(codes);
            rules.Add(rule);
        }

        var repeated = GateVerdicts.Repeated(rules);
        if (repeated is "highest + 1" or "smallest free")
        {
            g.Set(Verdict.Observed, $"in both trials the screen gave the {repeated} numeric id (two samples, not proof for every case)");
        }
        else
        {
            g.Set(Verdict.Unknown, repeated == "both"
                ? "both new ids were 'highest + 1' and also 'smallest free' (no gap), so the rule cannot be told apart"
                : "no single rule fits both screen-created ids, or a trial was skipped, typed by hand, or unclear; the ids are in the evidence");
        }

        ScreenEdits(screenCreate);
    }

    private (string? Codes, string? Rule) P2Trial(int t)
    {
        var before = _s.ListUsers(Page);
        if (!Trusted(before))
        {
            Note("P2", $"trial {t}: user list unreadable before the screen create -> no result");
            return (null, null);
        }

        _s.DrainAlarms();
        var done = Ask($"  [P2 screen create {t}] On the reader screen add a new person named \"{Prefix} SCREEN {t}\". Keep the user ID the screen suggests, if it suggests one. d = done, k = skip: ", "dk");
        if (done != 'd')
        {
            Note("P2", $"trial {t}: skipped");
            return (null, null);
        }

        var who = Ask("  Who chose the ID? s = the screen suggested it, t = I typed it, k = not sure: ", "stk");
        Thread.Sleep(_o.StepDelaySeconds * 1000);
        var alarms = _s.DrainAlarms();
        LogAlarms("P5", $"screen create {t}", alarms);
        var after = _s.ListUsers(Page);
        if (!Trusted(after))
        {
            Note("P2", $"trial {t}: user list unreadable after the screen create -> no result");
            return (null, null);
        }

        var beforeIds = before.Users.Select(u => u.Id).ToHashSet(StringComparer.Ordinal);
        var added = after.Users.Where(u => !beforeIds.Contains(u.Id)).ToList();
        foreach (var u in added.Where(u => (u.Name ?? "").StartsWith(Prefix, StringComparison.Ordinal) && !_cleanup.Contains(u.Id)))
        {
            _cleanup.Add(u.Id);
        }

        if (added.Count != 1)
        {
            Note("P2", $"trial {t}: {added.Count} new users appeared [{string.Join(", ", added.Select(u => u.Id))}], expected exactly one -> no result");
            return (null, null);
        }

        var id = added[0].Id;
        var rule = who == 's' ? IdRule(id, beforeIds) : null;
        var chooser = who switch { 's' => "suggested by the screen", 't' => "typed by the operator", _ => "operator not sure who chose it" };
        Note("P2", $"trial {t}: new id {id} (name \"{added[0].Name}\"), {chooser}; fits: {rule ?? "not judged"}");
        return (Codes(alarms), rule);
    }

    private static string IdRule(string id, HashSet<string> existing)
    {
        if (!long.TryParse(id, out var n))
        {
            return "not numeric";
        }

        var numbers = existing.Select(x => long.TryParse(x, out var v) ? v : 0).Where(v => v > 0).ToHashSet();
        var highest = numbers.Count == 0 ? 0 : numbers.Max();
        var free = 1L;
        while (numbers.Contains(free))
        {
            free++;
        }

        return (n == highest + 1, n == free) switch
        {
            (true, true) => "both",
            (true, false) => "highest + 1",
            (false, true) => "smallest free",
            _ => $"neither (highest was {highest}, smallest free {free})"
        };
    }

    private void ScreenEdits(List<string?> screenCreate)
    {
        var edits = ScreenNameEdits();
        var faces = ScreenFaceEdits();

        if (_s.GetFace(_a).Photo is { } enrolled)
        {
            _faceOnA = true;
            _photo ??= enrolled;
        }

        RestoreA();
        _p5.Add(("screen create", AlarmVerdict("screen create", GateVerdicts.Repeated(screenCreate))));
        _p5.Add(("screen edit", AlarmVerdict("screen edit", GateVerdicts.Repeated(edits))));
        _p5.Add(("screen face", AlarmVerdict("screen face", GateVerdicts.Repeated(faces))));
        SetP5();
    }

    private List<string?> ScreenNameEdits()
    {
        var edits = new List<string?>();
        for (var t = 1; t <= Trials; t++)
        {
            var nameBefore = ReaderNameOf(_a);
            _s.DrainAlarms();
            var done = Ask($"  [P5 screen edit {t}] On the reader screen open user {_a} and change its name. d = done, k = skip: ", "dk");
            if (done != 'd')
            {
                Note("P5", $"screen edit {t}: skipped");
                edits.Add(null);
                continue;
            }

            Thread.Sleep(_o.StepDelaySeconds * 1000);
            var alarms = _s.DrainAlarms();
            var nameAfter = ReaderNameOf(_a);
            LogAlarms("P5", $"screen edit {t}", alarms);
            Note("P5", $"screen edit {t}: name read back {nameBefore} -> {nameAfter}{(nameBefore == nameAfter ? " (no change seen, trial not counted)" : "")}");
            edits.Add(nameBefore != nameAfter ? Codes(alarms) : null);
        }

        return edits;
    }

    private List<string?> ScreenFaceEdits()
    {
        var faces = new List<string?>();
        for (var t = 1; t <= Trials; t++)
        {
            var faceBefore = _s.GetFace(_a).Photo is { } b ? Probe.Md5(b) : None;
            _s.DrainAlarms();
            var done = Ask($"  [P5 screen face {t}] On the reader screen open user {_a} and enrol the face again (same person). d = done, k = skip: ", "dk");
            if (done != 'd')
            {
                Note("P5", $"screen face {t}: skipped");
                faces.Add(null);
                continue;
            }

            Thread.Sleep(_o.StepDelaySeconds * 1000);
            var alarms = _s.DrainAlarms();
            var faceAfter = _s.GetFace(_a).Photo is { } a ? Probe.Md5(a) : None;
            LogAlarms("P5", $"screen face {t}", alarms);
            Note("P5", $"screen face {t}: photo MD5 {faceBefore} -> {faceAfter}{(faceBefore == faceAfter ? " (no change seen, trial not counted)" : "")}");
            faces.Add(faceBefore != faceAfter ? Codes(alarms) : null);
        }

        return faces;
    }

    private void P14()
    {
        var g = _gates["P14"];
        _r.Sub("P14 photo read-back, and face INSERT / UPDATE answers");
        if (_photo == null)
        {
            g.Set(Verdict.Unknown, "no photo available (no --photo and no face enrolled on A)");
            return;
        }

        var sent = Probe.Md5(_photo);
        var fidelity = new List<string?>();
        var insertOver = new List<string?>();
        var updateEmpty = new List<string?>();
        for (var t = 1; t <= Trials; t++)
        {
            var trial = P14Trial(t, sent);
            fidelity.Add(trial.Read);
            insertOver.Add(trial.InsertOver);
            updateEmpty.Add(trial.UpdateEmpty);
        }

        _r.Line($"  face put back on A: {PutFace(_a, _photo)}");
        Outcomes(g, [
            ("read-back", GateVerdicts.Repeated(fidelity)),
            ("INSERT over a photo", GateVerdicts.Repeated(insertOver)),
            ("UPDATE without a photo", GateVerdicts.Repeated(updateEmpty))
        ]);
    }

    private (string? Read, string? InsertOver, string? UpdateEmpty) P14Trial(int t, string sent)
    {
        _s.RemoveFace(_a);
        var write = _s.WriteFace(EM_NET_ACCESS_CTL_FACE_SERVICE.INSERT, _a, _photo!);
        var first = _s.GetFace(_a);
        var second = _s.GetFace(_a);
        string? read = null;
        if (write == "ok" && first.Photo != null && second.Photo != null)
        {
            read = ReadFidelity(Probe.Md5(first.Photo), Probe.Md5(second.Photo), sent);
        }

        Note("P14", $"trial {t}: INSERT {write}; sent MD5 {sent}; read 1 {Describe(first)}; read 2 {Describe(second)} -> {read ?? NoResult}");

        var over = _s.WriteFace(EM_NET_ACCESS_CTL_FACE_SERVICE.INSERT, _a, _photo!);
        Note("P14", $"trial {t}: INSERT while a photo exists: {over}");

        _s.RemoveFace(_a);
        var update = _s.WriteFace(EM_NET_ACCESS_CTL_FACE_SERVICE.UPDATE, _a, _photo!);
        var after = _s.GetFace(_a);
        Note("P14", $"trial {t}: UPDATE without a photo: {update}; photo afterwards {Describe(after)}");
        string? updateAnswer = null;
        if (after.Ok && Answer(update) is { } answered)
        {
            var photo = after.Photo != null ? "yes" : "no";
            updateAnswer = $"{answered}, photo afterwards: {photo}";
        }

        return (read, Answer(over), updateAnswer);
    }

    private static string ReadFidelity(string first, string second, string sent)
    {
        if (first != second)
        {
            return "different between two reads";
        }

        return first == sent
            ? "identical to what was sent"
            : "changed by the reader, the same on both reads";
    }

    private void P15()
    {
        var g = _gates["P15"];
        _r.Sub("P15 answers for a missing user or photo");
        if (!_cleanup.Contains(_c))
        {
            _cleanup.Add(_c);
        }

        var getFace = new List<string?>();
        var removeFace = new List<string?>();
        var removeAgain = new List<string?>();
        var getMissing = new List<string?>();
        for (var t = 1; t <= Trials; t++)
        {
            var trial = P15Trial(t);
            getFace.Add(trial.GetFace);
            removeFace.Add(trial.RemoveFace);
            removeAgain.Add(trial.RemoveAgain);
            getMissing.Add(trial.GetMissing);
        }

        Outcomes(g, [
            ("GET photo of a user without one", GateVerdicts.Repeated(getFace)),
            ("REMOVE missing photo", GateVerdicts.Repeated(removeFace)),
            ("REMOVE missing user", GateVerdicts.Repeated(removeAgain)),
            ("GET missing user", GateVerdicts.Repeated(getMissing))
        ]);
    }

    private (string? GetFace, string? RemoveFace, string? RemoveAgain, string? GetMissing) P15Trial(int t)
    {
        var created = _s.InsertUser(Baseline(_c, "C"));
        if (created != "ok" || _s.GetUser(_c) == null)
        {
            Note("P15", $"trial {t}: test user C could not be created ({created}) -> no result");
            return (null, null, null, null);
        }

        var face = _s.GetFace(_c);
        string? faceAnswer = null;
        if (!(face.Error?.Contains(':') == true && face.FailCode == null))
        {
            var photo = face.Photo != null ? "yes" : "no";
            faceAnswer = $"ok={face.Ok} failCode={face.FailCode ?? None} photo={photo} error={face.Error ?? None}";
        }

        var noFace = _s.RemoveFace(_c);
        var removed = _s.RemoveUser(_c);
        var again = removed == "ok" ? _s.RemoveUser(_c) : null;
        var missing = _s.GetUser(_c) == null ? WithoutTiming(_s.DescribeGet(_c)) : null;
        Note("P15", $"trial {t}: GET photo of a user without one: {faceAnswer ?? "(read threw)"}");
        Note("P15", $"trial {t}: REMOVE photo that does not exist: {noFace}");
        Note("P15", $"trial {t}: REMOVE user: {removed}; REMOVE the same user again: {again ?? "(not tried)"}");
        Note("P15", $"trial {t}: GET the removed user: {missing ?? "(still returned)"}");
        return (faceAnswer, Answer(noFace), again == null ? null : Answer(again), missing);
    }

    private void P16()
    {
        var g = _gates["P16"];
        _r.Sub("P16 user list completeness (no writes between the reads)");
        var lists = Enumerable.Range(1, Trials).Select(_ => _s.ListUsers(Page)).ToList();
        var complete = new List<bool?>();
        for (var t = 0; t < lists.Count; t++)
        {
            var l = lists[t];
            Note("P16", $"read {t + 1}: {l.Users.Count} users read, {l.Total} announced, page capacity {l.CapNum}, {l.Calls} calls{(l.Error != null ? ", error " + l.Error : "")}");
            complete.Add(l.Error != null ? null : l.Users.Count == l.Total);
        }

        Verdict same;
        if (lists.Any(l => l.Error != null))
        {
            same = Verdict.Unknown;
        }
        else
        {
            var a = lists[0].Users.Select(u => u.Id).ToHashSet(StringComparer.Ordinal);
            var b = lists[1].Users.Select(u => u.Id).ToHashSet(StringComparer.Ordinal);
            var dupes = lists.Sum(l => l.Users.Count - l.Users.Select(u => u.Id).Distinct().Count());
            Note("P16", $"ids only in read 1: {a.Except(b).Count()}, only in read 2: {b.Except(a).Count()}, duplicate ids within a read: {dupes}");
            same = a.SetEquals(b) && dupes == 0 ? Verdict.Observed : Verdict.NotObserved;
        }

        var all = GateVerdicts.AllOf([GateVerdicts.FromTrials(complete), same]);
        g.Set(all, all switch
        {
            Verdict.Observed => "both reads returned the announced total and the same ids (staff did not edit users meanwhile)",
            Verdict.NotObserved => "a read returned a different count than announced, or the two reads differ; see evidence",
            _ => "a read failed"
        });
    }

    private void P17()
    {
        var g = _gates["P17"];
        _r.Sub("P17 name fields");
        var longName = (Prefix + " " + new string('L', 140))[..127];
        var shortName = (Prefix + " " + new string('S', 40))[..31];
        var extended = new List<string?>();
        var plain = new List<string?>();
        for (var t = 1; t <= Trials; t++)
        {
            RestoreA();
            var write = Rewrite(_a, u =>
            {
                u.szName = shortName;
                u.szNameEx = longName;
                u.bUseNameEx = true;
                return u;
            });
            var back = _s.GetUser(_a);
            string? ext = write != "ok" || back == null ? null
                : $"szNameEx {Stored(back.Value.szNameEx, longName)}, szName {Stored(back.Value.szName, shortName)}, bUseNameEx={back.Value.bUseNameEx}";
            Note("P17", $"trial {t}: write 127-char szNameEx + 31-char szName ({write}) -> {ext ?? NoResult}");
            extended.Add(ext);

            var newShort = $"{Prefix} PLAIN {t}";
            var write2 = Rewrite(_a, u =>
            {
                u.szName = newShort;
                u.bUseNameEx = false;
                return u;
            });
            var back2 = _s.GetUser(_a);
            string? pl = null;
            if (write2 == "ok" && back2 != null)
            {
                var kept = back2.Value.szNameEx?.Trim() == longName ? "yes" : "no";
                pl = $"szName {Stored(back2.Value.szName, newShort)}, szNameEx kept {kept}, bUseNameEx={back2.Value.bUseNameEx}";
            }
            Note("P17", $"trial {t}: write szName only with bUseNameEx=false ({write2}) -> {pl ?? NoResult}");
            plain.Add(pl);
        }

        var screen = Ask($"  [P17 screen] Open user {_a} on the reader screen. Which name is shown? p = \"{Prefix} PLAIN {Trials}\", l = the long LLL name, k = cannot tell: ", "plk");
        Note("P17", $"operator saw on screen after the plain write: {screen switch { 'p' => "the szName value", 'l' => "the old szNameEx value", _ => "not checked" }}");
        RestoreA();
        Outcomes(g, [
            ("szNameEx + szName", GateVerdicts.Repeated(extended)),
            ("szName only", GateVerdicts.Repeated(plain))
        ]);
    }

    private void P19()
    {
        var g = _gates["P19"];
        _r.Sub("P19 ADMIN authority on the reader");
        if (!_faceOnA || _o.NoWalks)
        {
            g.Set(Verdict.Unknown, "needs a face on A and an operator at the reader");
            return;
        }

        Note("P19", $"set Administrators: {Rewrite(_a, u => { u.emAuthority = EM_ATTENDANCE_AUTHORITY.Administrators; return u; })}; "
                    + $"read back {_s.GetUser(_a)?.emAuthority.ToString() ?? Unreadable}");
        var admin = new List<Door?> { MenuTry("P19 admin 1", "Administrators"), MenuTry("P19 admin 2", "Administrators") };
        Note("P19", $"set Customer: {Rewrite(_a, u => { u.emAuthority = EM_ATTENDANCE_AUTHORITY.Customer; return u; })}; "
                    + $"read back {_s.GetUser(_a)?.emAuthority.ToString() ?? Unreadable}");
        var user = new List<Door?> { MenuTry("P19 user 1", "Customer"), MenuTry("P19 user 2", "Customer") };
        RestoreA();

        var a = GateVerdicts.Repeated(admin);
        var u = GateVerdicts.Repeated(user);
        if (a == Door.Opened && u == Door.Shut)
        {
            g.Set(Verdict.Observed, "the admin menu opened for Administrators on both tries and was refused for Customer on both tries");
        }
        else if (a == Door.Shut && u == Door.Shut)
        {
            g.Set(Verdict.NotObserved, "the admin menu was refused for Administrators on both tries");
        }
        else
        {
            g.Set(Verdict.Unknown, "tries were skipped, unclear or disagreed, or Customer also opened the menu");
        }
    }

    private Door? MenuTry(string label, string authority)
    {
        var key = Ask($"  [{label}] As test user {_a} ({authority}), try to open the reader's admin menu with your face. o = menu opened, s = refused, u = not sure, k = skip: ", "osuk");
        var answer = key switch { 'o' => WalkAnswer.Opened, 's' => WalkAnswer.Shut, 'u' => WalkAnswer.Unclear, _ => WalkAnswer.Skipped };
        var result = GateVerdicts.Walk(answer, null);
        Note("P19", $"{label}: operator {answer} -> {Show(result)}");
        return result;
    }

    private void DoorEvidence()
    {
        _r.Sub("P13 / P18 live door events and punch times (from every walk in this run)");
        var arrived = new List<bool?>();
        var recMatch = new List<bool?>();
        var inClock = new List<bool?>();
        foreach (var w in _walks.Where(w => w.Mine.Count > 0))
        {
            var events = DoorEvents(w);
            var recs = events.Select(e => EventRec(e.Detail)).Where(r => r != null).ToList();
            var stored = w.Mine.Select(p => p.RecNo).ToHashSet();
            if (!w.Label.StartsWith("P6 after reboot", StringComparison.Ordinal))
            {
                arrived.Add(events.Count > 0);
                recMatch.Add(recs.Count == 0 ? null : recs.All(r => stored.Contains(r!.Value)));
            }

            Note("P13", $"{w.Label}: stored records [{string.Join(",", stored)}]; live events {events.Count}{(recs.Count > 0 ? $" with rec [{string.Join(",", recs)}]" : "")}");

            var times = w.Mine.Where(p => p.Time != null).ToList();
            bool? inside = times.Count == 0 ? null : times.All(p => p.Time >= w.Before.AddMinutes(-1) && p.Time <= w.After.AddMinutes(1));
            Note("P18", $"{w.Label}: reader clock {w.Before:HH:mm:ss}..{w.After:HH:mm:ss}; punch times {string.Join(",", times.Select(p => p.TimeRaw))} -> {Show(inside)}");
            inClock.Add(inside);
        }

        if (arrived.Count == 0)
        {
            Note("P13", "no walk left a stored punch for a test user");
        }

        var p13 = GateVerdicts.AllOf([GateVerdicts.FromTrials(arrived), GateVerdicts.FromTrials(recMatch)]);
        _gates["P13"].Set(p13, p13 switch
        {
            Verdict.Observed => $"a live event with the stored record number arrived for every one of {arrived.Count} walks",
            Verdict.NotObserved => "live events were missing on every walk, or carried a different record number",
            _ => "fewer than two walks with a punch, or walks disagreed (events arrived on some walks only)"
        });
        var p18 = GateVerdicts.FromTrials(inClock);
        _gates["P18"].Set(p18, p18 switch
        {
            Verdict.Observed => "every walk's punch time fell within a minute of the reader clock at the walk",
            Verdict.NotObserved => "punch times were offset from the reader clock on every walk; see evidence for the offset",
            _ => "fewer than two walks with a punch, or walks disagreed"
        });
    }

    private static List<AlarmSeen> DoorEvents(WalkRecord w) =>
        w.Alarms.Where(a => a.Type == "ALARM_ACCESS_CTL_EVENT" && a.Detail.Contains($"user={w.UserId} ", StringComparison.Ordinal)).ToList();

    /// <summary>
    /// Waits on the existing login for the SDK's own disconnect/reconnect callbacks.
    /// true = the old login answers again; false = it never did; null = no sign the reader restarted at all.
    /// </summary>
    private bool? AwaitSdkReconnect(int t)
    {
        _r.Line("  waiting for the SDK to reconnect the existing login by itself (up to 6 minutes)...");
        var seen = new List<AlarmSeen>();
        var deadline = DateTime.UtcNow.AddMinutes(6);
        var answers = false;
        while (DateTime.UtcNow < deadline)
        {
            Thread.Sleep(5000);
            seen.AddRange(_s.DrainAlarms());
            var down = seen.FirstOrDefault(a => a.Type == "SDK_DISCONNECTED");
            var up = seen.Any(a => a.Type == "SDK_RECONNECTED");
            if ((up || (down != null && DateTime.UtcNow - down.AtUtc > TimeSpan.FromSeconds(30))) && _s.GetUser(_a) != null)
            {
                answers = true;
                break;
            }
        }

        var wentDown = seen.Any(a => a.Type == "SDK_DISCONNECTED");
        var cameUp = seen.Any(a => a.Type == "SDK_RECONNECTED");
        Note("P20", $"reboot {t}: disconnect callback {(wentDown ? "seen" : "not seen")}, reconnect callback {(cameUp ? "seen" : "not seen")}, "
                    + $"old login answers a user read: {(answers ? "yes" : "no")}");
        if (answers)
        {
            return true;
        }

        if (!wentDown && !cameUp && _s.GetUser(_a) != null)
        {
            return null;
        }

        return false;
    }

    private void P20()
    {
        var g = _gates["P20"];
        _r.Sub("P20 SDK auto-reconnect after a reader power cycle (from the P6 reboots)");
        var back = GateVerdicts.FromTrials(_p20.Select(p => p.Back).ToList());
        var anyEventBefore = _walks.Any(w => !w.Label.StartsWith("P6 after reboot", StringComparison.Ordinal) && DoorEvents(w).Count > 0);
        var events = anyEventBefore ? GateVerdicts.FromTrials(_p20.Select(p => p.Events).ToList()) : Verdict.Unknown;
        if (!anyEventBefore)
        {
            Note("P20", "no live door event arrived on any walk before the reboots, so a missing event after them says nothing about reconnecting");
        }

        var all = back == Verdict.NotObserved ? Verdict.NotObserved : GateVerdicts.AllOf([back, events]);
        var how = _p20Unplugged ? " (at least one trial was a network unplug, not a power cycle)" : "";
        g.Set(all, P20Detail(all, back) + how);
    }

    private static string P20Detail(Verdict all, Verdict back)
    {
        if (all == Verdict.Observed)
        {
            return "on both trials the old login answered again and the walk's door event arrived on it";
        }

        if (all != Verdict.NotObserved)
        {
            return "trials were skipped, the drop-off could not be confirmed, or the door-event part could not be judged";
        }

        return back == Verdict.NotObserved
            ? "on both trials the old login never answered again; a new login was needed"
            : "the old login answered, but door events stopped arriving on it";
    }

    private void P21()
    {
        var g = _gates["P21"];
        _r.Sub("P21 photo size limit (the walker's photo padded with JPEG comment blocks)");
        if (_photo == null)
        {
            g.Set(Verdict.Unknown, "no photo available (no --photo and no face enrolled on A)");
            return;
        }

        var parts = new List<(string Name, string? Value)>();
        foreach (var kb in new[] { 100, 130, 200 })
        {
            var padded = PadJpeg(_photo, kb * 1024);
            if (padded == null)
            {
                Note("P21", $"{kb} KB: the photo is not a JPEG or is already larger; size not tried");
                parts.Add(($"{kb} KB", null));
                continue;
            }

            var answers = new List<string?>();
            for (var t = 1; t <= Trials; t++)
            {
                _s.RemoveFace(_a);
                var write = _s.WriteFace(EM_NET_ACCESS_CTL_FACE_SERVICE.INSERT, _a, padded);
                var back = _s.GetFace(_a);
                string stored;
                if (back.Photo == null)
                {
                    stored = "no";
                }
                else if (Probe.Md5(back.Photo) == Probe.Md5(padded))
                {
                    stored = "yes, byte for byte";
                }
                else
                {
                    stored = $"yes, as {back.Photo.Length / 1024} KB";
                }
                var answer = back.Ok && Answer(write) is { } a ? $"{a}, photo stored: {stored}" : null;
                Note("P21", $"{kb} KB trial {t}: INSERT {write}; read back {Describe(back)} -> {answer ?? NoResult}");
                answers.Add(answer);
            }

            parts.Add(($"{kb} KB", GateVerdicts.Repeated(answers)));
        }

        _r.Line($"  face put back on A: {PutFace(_a, _photo)}");
        Outcomes(g, parts);
    }

    /// <summary>Grows a JPEG to exactly <paramref name="size"/> bytes with COM segments after SOI; the image itself is unchanged.</summary>
    internal static byte[]? PadJpeg(byte[] jpeg, int size)
    {
        if (jpeg.Length < 4 || jpeg[0] != 0xFF || jpeg[1] != 0xD8 || size < jpeg.Length + 4)
        {
            return null;
        }

        using var ms = new MemoryStream(size);
        ms.Write(jpeg, 0, 2);
        var left = size - jpeg.Length;
        while (left > 0)
        {
            var seg = Math.Min(left, 65537);
            if (left - seg is > 0 and < 4)
            {
                seg -= 4;
            }

            var len = seg - 2;
            ms.Write([0xFF, 0xFE, (byte)(len >> 8), (byte)(len & 0xFF)]);
            ms.Write(new byte[seg - 4]);
            left -= seg;
        }

        ms.Write(jpeg, 2, jpeg.Length - 2);
        return ms.ToArray();
    }

    private static int? EventRec(string detail)
    {
        var m = Regex.Match(detail, @"rec=(-?\d+)", RegexOptions.None, MatchTimeout);
        return m.Success && int.TryParse(m.Groups[1].Value, out var n) ? n : null;
    }

    private static void Outcomes(GateResult g, List<(string Name, string? Value)> parts)
    {
        foreach (var (name, value) in parts)
        {
            g.Evidence.Add($"{name}: {value ?? "UNKNOWN (trials differed, failed or were unclear)"}");
        }

        var missing = parts.Where(p => p.Value == null).Select(p => p.Name).ToList();
        g.Set(missing.Count == 0 ? Verdict.Observed : Verdict.Unknown, missing.Count == 0
            ? string.Join("; ", parts.Select(p => $"{p.Name}: {p.Value}"))
            : $"no consistent answer for: {string.Join(", ", missing)}");
    }

    private static string? Answer(string result)
    {
        if (result == "ok")
        {
            return "ok";
        }

        var m = Regex.Match(result, @"(?:failCode=|codes=\[)([A-Za-z0-9_,]+)", RegexOptions.None, MatchTimeout);
        return m.Success && m.Groups[1].Value is not ("NOERROR" or "") ? $"refused ({m.Groups[1].Value})" : null;
    }

    private static string WithoutTiming(string describe) =>
        Regex.Replace(describe, @"\s+in \d+ ms$", "", RegexOptions.None, MatchTimeout);

    private static string Stored(string? value, string sent)
    {
        var v = value?.Trim() ?? "";
        if (v == sent)
        {
            return $"saved in full ({v.Length} chars)";
        }

        var how = sent.StartsWith(v, StringComparison.Ordinal) ? " (cut)" : " (different)";
        return $"stored as {v.Length} chars{how}";
    }

    private void P9()
    {
        var g = _gates["P9"];
        _r.Sub("P9 the same face on two user ids");
        if (_photo == null)
        {
            g.Set(Verdict.Unknown, "no photo available (no --photo and no face enrolled on A)");
            return;
        }

        PutFace(_a, _photo);
        _cleanup.Add(_b);
        var created = _s.InsertUser(Baseline(_b, "B"));
        Note("P9", $"create B: {created}");
        if (created != "ok")
        {
            g.Set(Verdict.Unknown, "user B could not be created");
            return;
        }

        var outcomes = new List<string?>();
        for (var t = 1; t <= Trials; t++)
        {
            if (t > 1)
            {
                _s.RemoveFace(_b);
            }

            var write = _s.WriteFace(EM_NET_ACCESS_CTL_FACE_SERVICE.INSERT, _b, _photo);
            var a = _s.GetFace(_a);
            var b = _s.GetFace(_b);
            var outcome = P9Outcome(write, a, b);
            Note("P9", $"trial {t}: face INSERT on B: {write}; A photo {Describe(a)}; B photo {Describe(b)} -> {outcome ?? "no clear result"}");
            outcomes.Add(outcome);
        }

        Note("P9", $"remove B: face {_s.RemoveFace(_b)}, user {_s.RemoveUser(_b)}");
        var repeated = GateVerdicts.Repeated(outcomes);
        g.Set(repeated == null ? Verdict.Unknown : Verdict.Observed,
            repeated ?? "the two trials differed, a read failed, or the SDK error did not say why");
    }

    private static string? P9Outcome(string write, FaceRead a, FaceRead b)
    {
        if (!a.Ok || !b.Ok)
        {
            return null;
        }

        if (write == "ok")
        {
            return (a.Photo != null, b.Photo != null) switch
            {
                (true, true) => "accepted: both ids hold the photo",
                (true, false) => "SDK said ok, but B has no photo",
                (false, true) => "accepted on B, and A lost its photo",
                _ => "SDK said ok, but neither id has a photo"
            };
        }

        var fail = FailCode(write);
        if (fail is null or "NOERROR")
        {
            return null;
        }

        var kept = a.Photo != null ? "kept" : "lost";
        return $"refused ({fail}); A {kept} its photo";
    }

    private void P10()
    {
        var g = _gates["P10"];
        _r.Sub("P10 read cost (one sample)");
        foreach (var line in g.Evidence)
        {
            _r.Line("  " + line);
        }

        var again = _s.ListUsers(Page);
        Note("P10", $"second full user list: {again.Users.Count} users in {again.Ms} ms over {again.Calls} calls{(again.Error != null ? ", error " + again.Error : "")}");
        Note("P10", $"GetUser on A x5: {Repeat(5, () => _s.GetUser(_a) != null)}");
        if (_faceOnA)
        {
            Note("P10", $"GetFace on A x5: {Repeat(5, () => _s.GetFace(_a).Photo != null)}");
        }

        var user = Task.Run(() => Timed(() => _s.GetUser(_a) != null));
        var face = Task.Run(() => Timed(() => _s.GetFace(_a).Ok));
        Task.WaitAll(user, face);
        Note("P10", $"overlapping GetUser + GetFace on A: GetUser {user.Result}, GetFace {face.Result}");
        g.Set(Verdict.Unknown, "one sample on one reader; a safe roster size and a safe number of parallel calls are not proven by these timings");
    }

    private void P7()
    {
        var g = _gates["P7"];
        _r.Sub("P7 attendance query bounds");
        Note("P7", "the record find condition (NET_FIND_RECORD_ACCESSCTLCARDREC_CONDITION_EX) offers a card number, a time window, a RealUTC window and a sort order; it has no record-number bound (read from the SDK struct, not from the reader)");
        var now = Clock();
        var from = now.AddDays(-1);
        var to = now.AddMinutes(5);
        var window = _s.QueryPunches(from, to);
        Remember(window);
        Note("P7", $"time-window query (last 24 h by reader clock): {window.Rows.Count} punches in {window.Ms} ms{Error(window)}");
        var asc = _s.QueryPunches(from, to, ascending: true);
        var desc = _s.QueryPunches(from, to, ascending: false);
        Remember(asc);
        Remember(desc);
        var ascOk = Sorted(asc, true);
        var descOk = Sorted(desc, false);
        Note("P7", $"asked for record-number order: ascending -> {Show(ascOk)}, descending -> {Show(descOk)}");
        Note("P7", ascOk == true && descOk == true
            ? "the reader returned rows in the requested record-number order in both directions"
            : "record-number ordering is not shown by this run");
        g.Set(Verdict.NotObserved, "the query cannot ask for records after a record number; only a time window (or card number) bounds it");
    }

    private void P8()
    {
        var g = _gates["P8"];
        _r.Sub("P8 oldest attendance on the reader");
        var to = Clock().AddDays(1);
        var count = _s.CountPunches(LogStart, to);
        var all = _s.QueryPunches(LogStart, to, cap: _o.LogCap);
        Remember(all);
        Note("P8", $"record count reported by the reader: {count?.ToString() ?? "(not supported)"}; read {all.Rows.Count} rows in {all.Ms} ms over {all.Calls} calls"
                   + $"{(all.Capped ? $", stopped at --log-cap {_o.LogCap}" : "")}{Error(all)}");
        var timed = all.Rows.Where(p => p.Time != null).ToList();
        if (timed.Count == 0)
        {
            g.Set(Verdict.Unknown, "no punches could be read");
            return;
        }

        var oldest = timed.MinBy(p => p.Time)!;
        var newest = timed.MaxBy(p => p.Time)!;
        Note("P8", $"oldest punch read {oldest.TimeRaw} (record {oldest.RecNo}); newest {newest.TimeRaw} (record {newest.RecNo}); lowest record number read {all.Rows.Min(p => p.RecNo)}");
        g.Set(Verdict.Unknown, $"punches back to {oldest.TimeRaw} are on the reader{(all.Capped ? " (the read stopped at the cap, so older ones may exist)" : "")}; "
                               + "that does not show how long the reader keeps them or what it drops when full");
    }

    private void P6()
    {
        var g = _gates["P6"];
        _r.Sub("P6 attendance record numbers");
        var parts = new List<(string Name, Verdict Verdict)>();

        var now = Clock();
        var run = _s.QueryPunches(_runStart.AddMinutes(-1), now.AddMinutes(1));
        Remember(run);
        var ordered = run.Rows.Where(p => p.Time != null).OrderBy(p => p.Time).ThenBy(p => p.RecNo).ToList();
        Verdict session;
        if (run.Error != null || ordered.Count < 2)
        {
            session = Verdict.Unknown;
            Note("P6", $"punches during this run: {ordered.Count}{Error(run)}; at least two are needed");
        }
        else
        {
            var falls = ordered.Zip(ordered.Skip(1)).Where(x => x.Second.RecNo <= x.First.RecNo).ToList();
            session = falls.Count == 0 ? Verdict.Observed : Verdict.NotObserved;
            Note("P6", "punches during this run in time order: " + string.Join(", ", ordered.Select(p => $"{p.RecNo}@{p.TimeRaw[11..]}{(IsTestUser(p.UserId) ? "*" : "")}")) + " (* = test user)");
            if (falls.Count > 0)
            {
                Note("P6", "record number did not rise: " + string.Join(", ", falls.Select(x => $"{x.First.RecNo} then {x.Second.RecNo}")));
            }
        }

        parts.Add(("record numbers rise in time order during this run", session));
        var windows = new List<bool?>
        {
            InsideWindow(now.AddHours(-1), now.AddMinutes(1)),
            InsideWindow(now.AddDays(-2), now.AddDays(-1))
        };
        parts.Add(("only rows inside the requested time window come back", GateVerdicts.FromTrials(windows)));
        parts.Add(("record numbers continue after a reboot", Reboots()));
        parts.Add(("record numbers continue when the log is full", StorageFull()));

        foreach (var (name, verdict) in parts)
        {
            Note("P6", $"{name}: {GateVerdicts.Label(verdict)}");
        }

        var all = GateVerdicts.AllOf(parts.Select(x => x.Verdict));
        g.Set(all, all switch
        {
            Verdict.Observed => "every part was observed",
            Verdict.NotObserved => $"seen to fail: {Names(parts, Verdict.NotObserved)}",
            _ => $"not shown: {Names(parts, Verdict.Unknown)}"
        });
    }

    private bool? InsideWindow(DateTime from, DateTime to)
    {
        var q = _s.QueryPunches(from, to);
        Remember(q);
        if (q.Error != null || q.Rows.Count == 0)
        {
            Note("P6", $"window {from:yyyy-MM-dd HH:mm} .. {to:yyyy-MM-dd HH:mm}: {q.Rows.Count} rows{Error(q)} -> no result");
            return null;
        }

        var outside = q.Rows.Count(p => p.Time == null || p.Time < from || p.Time > to);
        Note("P6", $"window {from:yyyy-MM-dd HH:mm} .. {to:yyyy-MM-dd HH:mm}: {q.Rows.Count} rows, {outside} outside the window or without a time");
        return outside == 0;
    }

    private Verdict Reboots()
    {
        if (_o.NoWalks || !_faceOnA)
        {
            Note("P6", "reboot: not run (needs door walks and a face on A)");
            return Verdict.Unknown;
        }

        var trials = new List<bool?>();
        for (var t = 1; t <= Trials; t++)
        {
            var step = RebootTrial(t);
            trials.Add(step.Result);
            if (step.Stop)
            {
                break;
            }
        }

        return GateVerdicts.FromTrials(trials);
    }

    private readonly record struct RebootStep(bool? Result, bool Stop);

    private RebootStep RebootTrial(int t)
    {
        var key = Ask($"  [P6 reboot {t}] Type r, then power the reader off and on. If it cannot be rebooted, type n and unplug the reader's "
                      + "network cable for about a minute instead (tests reconnecting only). k = skip: ", "rnk");
        if (key == 'k')
        {
            Note("P6", $"reboot {t}: skipped");
            _p20.Add((null, null));
            return new RebootStep(null, false);
        }

        var unplug = key == 'n';
        if (unplug)
        {
            _p20Unplugged = true;
            _r.Line("  unplug the reader's network cable now, wait about a minute, then plug it back in.");
        }

        var before = _seenPunches.Keys.ToList();
        int? highest = before.Count == 0 ? null : before.Max();
        var same = AwaitSdkReconnect(t);
        if (same != true && !Reconnect())
        {
            _p20.Add((null, null));
            return new RebootStep(null, true);
        }

        var mine = new List<Punch>();
        Walk($"P6 after reboot {t}", _a, "enabled", mine);
        NoteReconnect(t, same);
        if (unplug)
        {
            Note("P6", $"reboot {t}: network unplug instead of a reboot; record numbers across a power loss were not tested");
            Remember(_s.QueryPunches(_runStart.AddMinutes(-1), Clock().AddMinutes(1)));
            return new RebootStep(null, false);
        }

        return new RebootStep(RecordContinuation(t, before, highest, mine), false);
    }

    private void NoteReconnect(int t, bool? same)
    {
        if (same == true)
        {
            var label = $"P6 after reboot {t}";
            var w = _walks.LastOrDefault(x => x.Label == label);
            bool? events = w == null || w.Mine.Count == 0 ? null : DoorEvents(w).Count > 0;
            string eventText;
            if (events == null)
            {
                eventText = "unknown (no stored punch)";
            }
            else if (events.Value)
            {
                eventText = "arrived";
            }
            else
            {
                eventText = "did not arrive";
            }

            Note("P20", $"reboot {t}: walk on the same login: live door event {eventText}");
            _p20.Add((true, events));
            return;
        }

        var why = same == false
            ? "the old login did not come back; logged in again"
            : "could not tell whether the reader actually restarted; logged in again";
        Note("P20", $"reboot {t}: {why}");
        _p20.Add((same, null));
    }

    private bool? RecordContinuation(int t, List<int> before, int? highest, List<Punch> mine)
    {
        bool? continues = mine.Count == 0 || highest == null ? null : mine.Max(p => p.RecNo) > highest;
        var punches = mine.Count == 0 ? None : string.Join(",", mine.Select(p => p.RecNo));
        Note("P6", $"reboot {t}: highest record number seen before {highest?.ToString() ?? None}; test user's punch after the reboot "
                   + $"{punches} -> {Show(continues)}");
        var still = _s.QueryPunches(_runStart.AddMinutes(-1), Clock().AddMinutes(1));
        var kept = before.Count(r => still.Rows.Any(p => p.RecNo == r));
        var inRun = before.Count(r => _seenPunches[r].Time >= _runStart.AddMinutes(-1));
        Note("P6", $"reboot {t}: punches from this run still listed with the same record number: {kept} of {inRun}{Error(still)}");
        Remember(still);
        return continues;
    }

    private Verdict StorageFull()
    {
        if (!_o.SpareReader)
        {
            Note("P6", "full log: not run; this probe never fills a gym reader's log (needs --spare-reader)");
            return Verdict.Unknown;
        }

        if (_o.NoWalks || !_faceOnA)
        {
            Note("P6", "full log: not run (needs door walks and a face on A)");
            return Verdict.Unknown;
        }

        var key = Ask("  [P6 full log] Does this spare reader report its attendance store as full right now? y = yes, k = no or not sure: ", "yk");
        if (key != 'y')
        {
            Note("P6", "full log: operator did not confirm a full log");
            return Verdict.Unknown;
        }

        var trials = new List<bool?>();
        for (var t = 1; t <= Trials; t++)
        {
            var countBefore = _s.CountPunches(LogStart, Clock().AddDays(1));
            int? highest = _seenPunches.Count == 0 ? null : _seenPunches.Keys.Max();
            var mine = new List<Punch>();
            Walk($"P6 full log {t}", _a, "enabled", mine);
            var countAfter = _s.CountPunches(LogStart, Clock().AddDays(1));
            bool? continues = mine.Count == 0 || highest == null ? null : mine.Max(p => p.RecNo) > highest;
            Note("P6", $"full log {t}: record count {countBefore?.ToString() ?? "?"} -> {countAfter?.ToString() ?? "?"}; highest record number seen before {highest?.ToString() ?? None}; "
                       + $"new punch {(mine.Count == 0 ? "(none stored)" : string.Join(",", mine.Select(p => p.RecNo)))} -> {Show(continues)}");
            trials.Add(continues);
        }

        return GateVerdicts.FromTrials(trials);
    }

    private void P12()
    {
        var g = _gates["P12"];
        _r.Sub("P12 factory reset and empty reader");
        if (_deleteNote != null)
        {
            Note("P12", "side note, not the answer: " + _deleteNote);
        }

        if (!_o.SpareReader)
        {
            g.Set(Verdict.Unknown, "factory reset and reader replacement were not run; this probe never resets a gym reader (needs --spare-reader)");
            return;
        }

        Console.Write("  Type RESET to confirm this is a spare reader whose users and logs may be erased (anything else skips): ");
        if ((Console.ReadLine() ?? "").Trim() != "RESET")
        {
            g.Set(Verdict.Unknown, "the operator did not confirm a spare reader");
            return;
        }

        var signals = new List<string?>();
        for (var t = 1; t <= Trials; t++)
        {
            var key = Ask($"  [P12 reset {t}] Factory-reset the reader from its own menu. r = the reset has started, k = skip: ", "rk");
            if (key != 'r')
            {
                Note("P12", $"reset {t}: skipped");
                signals.Add(null);
                continue;
            }

            Thread.Sleep(5000);
            var alarms = _s.DrainAlarms();
            if (!Reconnect())
            {
                Note("P12", "could not log in after the reset; a reset may restore default credentials or change the IP");
                signals.Add(null);
                break;
            }

            alarms.AddRange(_s.DrainAlarms());
            LogAlarms("P12", $"reset {t}", alarms);
            var users = _s.ListUsers(Page);
            var punches = _s.CountPunches(LogStart, Clock().AddDays(1));
            Note("P12", $"reset {t}: user list {(users.Error ?? $"{users.Users.Count} users (announced {users.Total})")}; attendance records {punches?.ToString() ?? "(count not supported)"}");
            signals.Add(Codes(alarms));
        }

        var repeated = GateVerdicts.Repeated(signals);
        if (repeated == null)
        {
            g.Set(Verdict.Unknown, "the reset trials were skipped, could not reconnect, or raised different alarms");
        }
        else
        {
            g.Set(repeated.Length == 0 ? Verdict.NotObserved : Verdict.Observed, repeated.Length == 0
                ? "no alarm other than disconnect / reconnect was raised by either reset; the empty-state readings are in the evidence"
                : $"both resets raised {repeated}; the empty-state readings are in the evidence");
        }
    }

    private void Cleanup()
    {
        _r.Sub("Cleanup");
        if (_lost)
        {
            _r.Line($"  the reader is not reachable; remove these test users by hand: {string.Join(", ", _cleanup.Distinct())}");
            return;
        }

        foreach (var id in _cleanup.Distinct())
        {
            if (_s.GetUser(id) == null)
            {
                _r.Line($"  {id}: not on the reader");
                continue;
            }

            _r.Line($"  remove {id}: face {_s.RemoveFace(id)}, user {_s.RemoveUser(id)}");
        }

        var end = _s.ListUsers(Page);
        if (!Trusted(end))
        {
            _r.Line("  final user list unreadable; check by hand that no SYNCPOC users remain");
            return;
        }

        var endIds = end.Users.Select(u => u.Id).ToHashSet(StringComparer.Ordinal);
        var added = endIds.Except(_startIds).ToList();
        var removed = _startIds.Except(endIds).ToList();
        _r.Line(added.Count == 0 && removed.Count == 0
            ? $"  user list matches the start of the run ({endIds.Count} users)"
            : $"  user list DIFFERS from the start: added [{string.Join(", ", added)}], missing [{string.Join(", ", removed)}]. Users created on the screen without the {Prefix} name were left in place.");
    }

    private void Summarize()
    {
        _r.Section("Sync gate verdicts");
        foreach (var line in Rules)
        {
            _r.Line(line);
        }

        _r.Line();
        foreach (var g in _order)
        {
            _r.Line($"{g.Id,-4} {GateVerdicts.Label(g.Verdict),-13} {g.Question}");
            _r.Line($"     {g.Summary}");
        }

        _r.Csv("gates.csv", ["id", "verdict", "question", "summary", "evidence"],
            _order.Select(g => new[] { g.Id, GateVerdicts.Label(g.Verdict), g.Question, g.Summary, string.Join(" | ", g.Evidence) }));
    }

    private Door? Walk(string label, string userId, string state, List<Punch>? mine = null)
    {
        if (_o.NoWalks)
        {
            _r.Line($"  walk {label}: skipped (--no-walks)");
            return null;
        }

        var before = Clock();
        _s.DrainAlarms();
        var key = Ask($"  [{label}] Walk to the reader as test user {userId} ({state}). o = door opened, s = stayed shut, u = walked but not sure, k = did not walk: ", "osuk");
        var answer = key switch { 'o' => WalkAnswer.Opened, 's' => WalkAnswer.Shut, 'u' => WalkAnswer.Unclear, _ => WalkAnswer.Skipped };
        Thread.Sleep(2000);
        var after = Clock();
        var alarms = _s.DrainAlarms();
        // A wide window so a punch stored in another time zone is still found (P18).
        var q = _s.QueryPunches(before.AddHours(-14), after.AddHours(14));
        if (q.Capped)
        {
            _r.Line($"  walk {label}: the ±14 h punch read hit its cap; reading ±1 min instead (a time-zone offset would be missed)");
            q = _s.QueryPunches(before.AddMinutes(-1), after.AddMinutes(1));
        }

        Remember(q);
        var punches = q.Rows.Where(p => p.UserId == userId && !_attributed.Contains(p.RecNo)).ToList();
        foreach (var p in punches)
        {
            _attributed.Add(p.RecNo);
        }

        mine?.AddRange(punches);
        if (answer != WalkAnswer.Skipped)
        {
            _walks.Add(new WalkRecord(label, userId, before, after, punches, alarms));
        }
        bool? granted;
        if (punches.Count == 0)
        {
            granted = null;
        }
        else if (punches.All(p => p.Granted))
        {
            granted = true;
        }
        else if (punches.All(p => !p.Granted))
        {
            granted = false;
        }
        else
        {
            granted = null;
        }
        var result = GateVerdicts.Walk(answer, granted);
        var seen = q.Error ?? (punches.Count == 0 ? "none" : string.Join(", ", punches.Select(Describe)));
        _r.Line($"  walk {label}: operator {answer}; punches for {userId}: {seen} -> {Show(result)}");
        return result;
    }

    private List<Door?> Pair(string label, string state) =>
        _faceOnA ? [Walk(label + " 1", _a, state), Walk(label + " 2", _a, state)] : [null, null];

    private bool Reconnect()
    {
        _r.Line("  waiting for the reader to come back (up to 6 minutes)...");
        var target = _s.Target;
        _s.Dispose();
        var deadline = DateTime.UtcNow.AddMinutes(6);
        Thread.Sleep(15000);
        while (DateTime.UtcNow < deadline)
        {
            var session = ReaderSession.Open(target, out var error);
            if (session != null)
            {
                _s = session;
                _r.Line($"  logged in again; reader clock {_s.DeviceTime()?.ToString("yyyy-MM-dd HH:mm:ss") ?? Unreadable}");
                return true;
            }

            _r.Line($"  not back yet: {error}");
            Thread.Sleep(10000);
        }

        _lost = true;
        _r.Line($"  the reader did not come back; the remaining checks cannot run. Remove these test users by hand: {string.Join(", ", _cleanup.Distinct())}");
        return false;
    }

    private List<string> FreeIds(int count)
    {
        var first = long.TryParse(_o.TestUser, out var n) ? n : 990001;
        var ids = new List<string>();
        for (var id = first; ids.Count < count && id < first + 1000; id++)
        {
            var text = id.ToString();
            if (!_startIds.Contains(text) && _s.GetUser(text) == null)
            {
                ids.Add(text);
            }
        }

        return ids;
    }

    private byte[]? LoadPhoto(string? path, string option)
    {
        if (path == null)
        {
            return null;
        }

        var bytes = File.ReadAllBytes(path);
        if (bytes.Length <= MaxPhotoBytes)
        {
            return bytes;
        }

        _r.Line($"  {option} is {bytes.Length / 1024} KB; the reader limit is 120 KB, so it is not used.");
        return null;
    }

    private NET_ACCESS_USER_INFO Baseline(string id, string tag)
    {
        var today = Today();
        var user = new NET_ACCESS_USER_INFO
        {
            szUserID = id,
            emUserType = EM_USER_TYPE.NORMAL,
            emAuthority = EM_ATTENDANCE_AUTHORITY.Customer,
            nUserStatus = 0,
            nDoorNum = 1,
            nDoors = new int[32],
            nTimeSectionNum = 1,
            nTimeSectionNo = new int[32],
            nSpecialDaysSchedule = new int[128],
            nFirstEnterDoors = new int[32],
            stuValidBeginTime = NET_TIME.FromDateTime(today.AddDays(-1)),
            stuValidEndTime = NET_TIME.FromDateTime(EndOfDay(today.AddDays(30)))
        };
        return WithName(user, $"{Prefix} {tag}");
    }

    private void RestoreA()
    {
        var write = _s.InsertUser(Baseline(_a, "A"));
        if (write != "ok")
        {
            _r.Line($"  restoring A failed: {write}");
        }
    }

    private string PutFace(string id, byte[] photo) =>
        _s.WriteFace(_s.GetFace(id).Photo == null ? EM_NET_ACCESS_CTL_FACE_SERVICE.INSERT : EM_NET_ACCESS_CTL_FACE_SERVICE.UPDATE, id, photo);

    private string Rewrite(string id, Func<NET_ACCESS_USER_INFO, NET_ACCESS_USER_INFO> change)
    {
        var current = _s.GetUser(id);
        return current == null ? "FAILED: user not found" : _s.InsertUser(change(current.Value));
    }

    private static NET_ACCESS_USER_INFO WithName(NET_ACCESS_USER_INFO user, string name)
    {
        user.szName = name;
        user.szNameEx = name;
        user.bUseNameEx = true;
        return user;
    }

    private static NET_ACCESS_USER_INFO WithStatus(NET_ACCESS_USER_INFO user, uint status)
    {
        user.nUserStatus = status;
        return user;
    }

    private static string? ReaderName(NET_ACCESS_USER_INFO user) =>
        user.bUseNameEx && !string.IsNullOrWhiteSpace(user.szNameEx) ? user.szNameEx.Trim() : user.szName?.Trim();

    private string ReaderNameOf(string id) => _s.GetUser(id) is { } user ? ReaderName(user) ?? "(blank)" : Unreadable;

    private DateTime Today() => (_s.DeviceTime() ?? DateTime.UtcNow).Date;

    private static DateTime EndOfDay(DateTime date) => date.Date.AddDays(1).AddSeconds(-1);

    private DateTime Clock()
    {
        var time = _s.DeviceTime();
        if (time != null)
        {
            return time.Value;
        }

        _r.Line("  reader clock unreadable; using PC UTC for this time window");
        return DateTime.UtcNow;
    }

    private void Remember(PunchQuery q)
    {
        foreach (var p in q.Rows)
        {
            _seenPunches.TryAdd(p.RecNo, p);
        }
    }

    private bool IsTestUser(string? id) => id != null && (id == _a || id == _b || id == _c || _cleanup.Contains(id));

    private void Note(string gate, string line)
    {
        _gates[gate].Evidence.Add(line);
        _r.Line("  " + line);
    }

    private void LogAlarms(string gate, string label, List<AlarmSeen> alarms)
    {
        if (alarms.Count == 0)
        {
            Note(gate, $"{label}: no alarms");
            return;
        }

        foreach (var a in alarms)
        {
            var detail = a.Type == "ALARM_ACCESS_CTL_EVENT" ? "(door event, not a user change)" : a.Detail;
            Note(gate, $"{label}: {a.AtUtc:HH:mm:ss} {a.Type} {detail}");
        }
    }

    private static string Codes(List<AlarmSeen> alarms) =>
        string.Join(",", alarms.Select(a => a.Type).Where(t => !NotUserChanges.Contains(t)).Distinct().Order(StringComparer.Ordinal));

    private static string Names(List<(string Name, Verdict Verdict)> parts, Verdict verdict) =>
        string.Join(", ", parts.Where(x => x.Verdict == verdict).Select(x => x.Name));

    private static bool Trusted(UserListResult list) => list.Error == null && (list.Users.Count > 0 || list.Total == 0);

    private static bool? Sorted(PunchQuery q, bool ascending)
    {
        if (q.Error != null || q.Rows.Count < 2)
        {
            return null;
        }

        return q.Rows.Zip(q.Rows.Skip(1)).All(x => ascending ? x.Second.RecNo > x.First.RecNo : x.Second.RecNo < x.First.RecNo);
    }

    private static string? FailCode(string write)
    {
        const string marker = "failCode=";
        var at = write.IndexOf(marker, StringComparison.Ordinal);
        return at < 0 ? null : write[(at + marker.Length)..].Trim();
    }

    private static string Describe(Punch p) =>
        $"record {p.RecNo} at {p.TimeRaw} {(p.Granted ? "granted" : $"denied (error 0x{p.ErrorCode:X})")} by {p.Method}";

    private static string Describe(FaceRead f) =>
        f.Photo != null ? $"{f.Photo.Length / 1024} KB, MD5 {Probe.Md5(f.Photo)}" : $"none ({f.FailCode ?? f.Error ?? "no photo"})";

    private static string Show(Door? door) => door?.ToString() ?? "no clear result";

    private static string Show(bool? value) => value switch { true => "yes", false => "NO", _ => "no clear result" };

    private static string Error(PunchQuery q) => q.Error == null ? "" : $", error {q.Error}";

    private static string Repeat(int times, Func<bool> call)
    {
        var ms = new List<long>();
        var failed = 0;
        for (var i = 0; i < times; i++)
        {
            var watch = Stopwatch.StartNew();
            if (!call())
            {
                failed++;
            }

            ms.Add(watch.ElapsedMilliseconds);
        }

        ms.Sort();
        return $"min {ms[0]} / median {ms[ms.Count / 2]} / max {ms[^1]} ms, {failed} failed";
    }

    private static string Timed(Func<bool> call)
    {
        var watch = Stopwatch.StartNew();
        try
        {
            return $"{(call() ? "ok" : "failed")} in {watch.ElapsedMilliseconds} ms";
        }
        catch (Exception ex)
        {
            return $"threw {ex.GetType().Name} after {watch.ElapsedMilliseconds} ms";
        }
    }

    private static char Ask(string prompt, string allowed)
    {
        while (true)
        {
            Console.Write(prompt);
            var line = Console.ReadLine();
            if (line == null)
            {
                return allowed[^1];
            }

            var key = line.Trim().ToLowerInvariant().FirstOrDefault();
            if (allowed.Contains(key))
            {
                return key;
            }
        }
    }
}
