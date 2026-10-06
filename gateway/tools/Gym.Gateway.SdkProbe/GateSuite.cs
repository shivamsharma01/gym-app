using System.Diagnostics;
using NetSDKCS;

namespace Gym.Gateway.SdkProbe;

internal sealed record MutationTrial(bool WriteOk, bool? TimeChanged, string Codes);

/// <summary>
/// Third hardware POC: the P1–P12 checks from docs/architecture/gym-device-sync-before-poc-architecture.md,
/// run on one reader with throwaway SYNCPOC users. Each check ends OBSERVED, NOT OBSERVED or UNKNOWN by the
/// rules in <see cref="GateVerdicts"/>; see GATES.md for the operator steps.
/// </summary>
internal sealed class GateSuite
{
    private const string Prefix = "SYNCPOC";
    private const int MaxPhotoBytes = 120 * 1024;
    private const int Trials = 2;
    private const int Page = 50;
    private static readonly DateTime LogStart = new(2000, 1, 1);
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
    }

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
                Step("P2", P2);
                Step("P9", P9);
                Step("P10", P10);
                Step("P7", P7);
                Step("P8", P8);
                Step("P6", P6);
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
        Note("P1", $"freeze write {freeze}; nUserStatus read back {status?.ToString() ?? "(unreadable)"}");
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
        Note("P1", $"unfreeze write {unfreeze}; nUserStatus read back {_s.GetUser(_a)?.nUserStatus.ToString() ?? "(unreadable)"}");
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
            ? "(unreadable)"
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
                emptied.Add(null);
                nameSaved.Add(null);
                continue;
            }

            var b = UserFields.Dump(before.Value);
            var a = UserFields.Dump(after.Value);
            var lost = b.Where(kv => kv.Key != "stuUpdateTime" && !UserFields.IsEmpty(kv.Value) && UserFields.IsEmpty(a.GetValueOrDefault(kv.Key) ?? ""))
                .Select(kv => kv.Key)
                .Order(StringComparer.Ordinal)
                .ToList();
            if (faceBefore && !faceAfter)
            {
                lost.Add("face");
            }

            var saved = ReaderName(after.Value) == name;
            var changed = UserFields.Diff(b, a, ["stuUpdateTime"]);
            Note("P3", $"trial {t}: name saved {saved}; face before {faceBefore}, after {faceAfter}; emptied [{string.Join(", ", lost)}]");
            Note("P3", $"trial {t}: every field that changed: {(changed.Count == 0 ? "none" : string.Join("; ", changed))}");
            emptied.Add(string.Join(",", lost));
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
        Note("P4", $"{m.Step} trial {trial}: write {write}; user time {userBefore ?? "(unreadable)"} -> {userAfter ?? "(unreadable)"}; "
                   + $"photo time {faceBefore ?? "(none)"} -> {faceAfter ?? "(none)"}; judged on the {(m.FaceTime ? "photo" : "user")} time"
                   + $" -> {(changed == null ? "no result" : changed.Value ? "changed" : "SAME")}{note}");
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
        var v = repeatedCodes == null ? Verdict.Unknown : repeatedCodes.Length == 0 ? Verdict.NotObserved : Verdict.Observed;
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
            var before = _s.ListUsers(Page);
            if (!Trusted(before))
            {
                Note("P2", $"trial {t}: user list unreadable before the screen create -> no result");
                screenCreate.Add(null);
                rules.Add(null);
                continue;
            }

            _s.DrainAlarms();
            var done = Ask($"  [P2 screen create {t}] On the reader screen add a new person named \"{Prefix} SCREEN {t}\". Keep the user ID the screen suggests, if it suggests one. d = done, k = skip: ", "dk");
            if (done != 'd')
            {
                Note("P2", $"trial {t}: skipped");
                screenCreate.Add(null);
                rules.Add(null);
                continue;
            }

            var who = Ask("  Who chose the ID? s = the screen suggested it, t = I typed it, k = not sure: ", "stk");
            Thread.Sleep(_o.StepDelaySeconds * 1000);
            var alarms = _s.DrainAlarms();
            LogAlarms("P5", $"screen create {t}", alarms);
            var after = _s.ListUsers(Page);
            if (!Trusted(after))
            {
                Note("P2", $"trial {t}: user list unreadable after the screen create -> no result");
                screenCreate.Add(null);
                rules.Add(null);
                continue;
            }

            var beforeIds = before.Users.Select(u => u.Id).ToHashSet(StringComparer.Ordinal);
            var added = after.Users.Where(u => !beforeIds.Contains(u.Id)).ToList();
            foreach (var u in added.Where(u => (u.Name ?? "").StartsWith(Prefix, StringComparison.Ordinal) && !_cleanup.Contains(u.Id)))
            {
                _cleanup.Add(u.Id);
            }

            screenCreate.Add(added.Count == 1 ? Codes(alarms) : null);
            if (added.Count != 1)
            {
                Note("P2", $"trial {t}: {added.Count} new users appeared [{string.Join(", ", added.Select(u => u.Id))}], expected exactly one -> no result");
                rules.Add(null);
                continue;
            }

            var id = added[0].Id;
            var rule = who == 's' ? IdRule(id, beforeIds) : null;
            var chooser = who switch { 's' => "suggested by the screen", 't' => "typed by the operator", _ => "operator not sure who chose it" };
            Note("P2", $"trial {t}: new id {id} (name \"{added[0].Name}\"), {chooser}; fits: {rule ?? "not judged"}");
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

        RestoreA();
        _p5.Add(("screen create", AlarmVerdict("screen create", GateVerdicts.Repeated(screenCreate))));
        _p5.Add(("screen edit", AlarmVerdict("screen edit", GateVerdicts.Repeated(edits))));
        SetP5();
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
            string? outcome;
            if (!a.Ok || !b.Ok)
            {
                outcome = null;
            }
            else if (write == "ok")
            {
                outcome = (a.Photo != null, b.Photo != null) switch
                {
                    (true, true) => "accepted: both ids hold the photo",
                    (true, false) => "SDK said ok, but B has no photo",
                    (false, true) => "accepted on B, and A lost its photo",
                    _ => "SDK said ok, but neither id has a photo"
                };
            }
            else
            {
                var fail = FailCode(write);
                outcome = fail is null or "NOERROR" ? null : $"refused ({fail}); A {(a.Photo != null ? "kept" : "lost")} its photo";
            }

            Note("P9", $"trial {t}: face INSERT on B: {write}; A photo {Describe(a)}; B photo {Describe(b)} -> {outcome ?? "no clear result"}");
            outcomes.Add(outcome);
        }

        Note("P9", $"remove B: face {_s.RemoveFace(_b)}, user {_s.RemoveUser(_b)}");
        var repeated = GateVerdicts.Repeated(outcomes);
        g.Set(repeated == null ? Verdict.Unknown : Verdict.Observed,
            repeated ?? "the two trials differed, a read failed, or the SDK error did not say why");
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
            var key = Ask($"  [P6 reboot {t}] Power the reader off and on now. r = I am rebooting it now, k = skip: ", "rk");
            if (key != 'r')
            {
                Note("P6", $"reboot {t}: skipped");
                trials.Add(null);
                continue;
            }

            var before = _seenPunches.Keys.ToList();
            int? highest = before.Count == 0 ? null : before.Max();
            if (!Reconnect())
            {
                trials.Add(null);
                break;
            }

            var mine = new List<Punch>();
            Walk($"P6 after reboot {t}", _a, "enabled", mine);
            bool? continues = mine.Count == 0 || highest == null ? null : mine.Max(p => p.RecNo) > highest;
            Note("P6", $"reboot {t}: highest record number seen before {highest?.ToString() ?? "(none)"}; test user's punch after the reboot "
                       + $"{(mine.Count == 0 ? "(none)" : string.Join(",", mine.Select(p => p.RecNo)))} -> {Show(continues)}");
            var still = _s.QueryPunches(_runStart.AddMinutes(-1), Clock().AddMinutes(1));
            var kept = before.Count(r => still.Rows.Any(p => p.RecNo == r));
            var inRun = before.Count(r => _seenPunches[r].Time >= _runStart.AddMinutes(-1));
            Note("P6", $"reboot {t}: punches from this run still listed with the same record number: {kept} of {inRun}{Error(still)}");
            Remember(still);
            trials.Add(continues);
        }

        return GateVerdicts.FromTrials(trials);
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
            Note("P6", $"full log {t}: record count {countBefore?.ToString() ?? "?"} -> {countAfter?.ToString() ?? "?"}; highest record number seen before {highest?.ToString() ?? "(none)"}; "
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
        var key = Ask($"  [{label}] Walk to the reader as test user {userId} ({state}). o = door opened, s = stayed shut, u = walked but not sure, k = did not walk: ", "osuk");
        var answer = key switch { 'o' => WalkAnswer.Opened, 's' => WalkAnswer.Shut, 'u' => WalkAnswer.Unclear, _ => WalkAnswer.Skipped };
        Thread.Sleep(2000);
        var after = Clock();
        var q = _s.QueryPunches(before.AddMinutes(-1), after.AddMinutes(1));
        Remember(q);
        var punches = q.Rows.Where(p => p.UserId == userId).ToList();
        mine?.AddRange(punches);
        bool? granted = punches.Count == 0 ? null
            : punches.All(p => p.Granted) ? true
            : punches.All(p => !p.Granted) ? false
            : null;
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
                _r.Line($"  logged in again; reader clock {_s.DeviceTime()?.ToString("yyyy-MM-dd HH:mm:ss") ?? "(unreadable)"}");
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

    private string ReaderNameOf(string id) => _s.GetUser(id) is { } user ? ReaderName(user) ?? "(blank)" : "(unreadable)";

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
