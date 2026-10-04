using NetSDKCS;

namespace Gym.Gateway.SdkProbe;

/// <summary>
/// Creates one test user on one reader, changes it step by step and records what each step does to the
/// user update time, the photo update time, the photo MD5 and the events the reader raises. Deletes it at the end.
/// </summary>
internal sealed class WriteSuite(ProbeOptions o, Report r, DeviceFindings target, List<DeviceFindings> all, List<string> answers)
{
    private const string NamePrefix = "SDKPROBE";
    private const int MaxPhotoBytes = 120 * 1024;
    private readonly ReaderSession _s = target.Session;
    private readonly List<StepResult> _steps = [];
    private Snapshot? _last;
    private string Id => o.TestUser;

    public void Run()
    {
        r.Section($"Write test on {_s.Name} with test user {Id}");
        var existing = _s.GetUser(Id);
        if (existing != null)
        {
            var name = existing.Value.szName?.Trim() ?? "";
            if (!name.StartsWith(NamePrefix, StringComparison.Ordinal))
            {
                r.Line($"  ABORTED: user {Id} already exists as \"{name}\". Pick a free ID with --test-user.");
                return;
            }

            r.Line($"  leftover test user from an earlier run found; removing it: {_s.RemoveFace(Id)} / {_s.RemoveUser(Id)}");
        }

        var photo = LoadPhoto();
        var uploadedMd5 = photo == null ? null : Probe.Md5(photo);
        if (photo != null)
        {
            r.Line($"  test photo {photo.Length / 1024} KB, MD5 {uploadedMd5}");
        }

        try
        {
            _last = Take([]);
            Print("start", "before anything", "", _last, _last);

            var validTo = DateTime.UtcNow.Date.AddDays(30);
            Step("W1", "create user", () => _s.InsertUser(NewUser(NamePrefix + " A", validTo)));
            Step("W2", "write the same user again, nothing changed", () => Rewrite(u => u));
            Step("W3", "rename", () => Rewrite(u => { u.szName = NamePrefix + " B"; return u; }));
            Step("W4", "freeze", () => Rewrite(u => { u.nUserStatus = 1; return u; }));
            Step("W5", "unfreeze", () => Rewrite(u => { u.nUserStatus = 0; return u; }));
            Step("W6", "move expiry one day later", () => Rewrite(u => { u.stuValidEndTime = NET_TIME.FromDateTime(EndOfDay(validTo.AddDays(1))); return u; }));
            if (photo != null)
            {
                Step("W7", "add photo", () => AddPhoto(photo));
                Step("W8", "upload the identical photo again", () => _s.WriteFace(EM_NET_ACCESS_CTL_FACE_SERVICE.UPDATE, Id, photo));
                Step("W9", "rename while the photo stays", () => Rewrite(u => { u.szName = NamePrefix + " C"; return u; }));
                Step("W10", "remove photo only", () => _s.RemoveFace(Id));
                Step("W11", "add the same photo again", () => AddPhoto(photo));
            }
            else
            {
                r.Line("  photo steps skipped: no test photo (use --photo C:\\face.jpg)");
            }

            if (o.Interactive)
            {
                Interactive();
            }

            Step("W12", "delete user", () => _s.RemoveUser(Id));
        }
        finally
        {
            if (_s.GetUser(Id) != null)
            {
                r.Line($"  cleanup: removing test user {Id}: {_s.RemoveFace(Id)} / {_s.RemoveUser(Id)}");
            }
        }

        Summarize(uploadedMd5);
    }

    private void Interactive()
    {
        r.Section("Edits on the reader screen");
        r.Line($"  Walk to reader {_s.Name} ({_s.Target.Ip}). For each step do the edit on the reader, then press Enter here.");
        r.Line("  Type s and Enter to skip a step.");
        Ask("I1", $"On the reader, open user {Id} and change the NAME to anything.");
        Ask("I2", $"On the reader, change the EXPIRY / validity date of user {Id} (skip if the menu has none).");
        Ask("I3", $"On the reader, RE-ENROL the face of user {Id} (anyone can stand in front of the camera).");
        Ask("I4", $"On the reader, delete only the FACE of user {Id}, keep the user (skip if not possible).");
        Ask("I5", $"On the reader, enrol a face for user {Id} again.");
    }

    private void Ask(string code, string instruction)
    {
        r.Line();
        r.Line($"[{code}] {instruction}");
        Console.Write("  Press Enter when done (s = skip): ");
        var answer = Console.ReadLine()?.Trim();
        if (string.Equals(answer, "s", StringComparison.OrdinalIgnoreCase))
        {
            r.Line("  skipped");
            return;
        }

        Step(code, instruction, () => "done on the reader", drainFirst: false);
    }

    private void Step(string code, string title, Func<string> action, bool drainFirst = true)
    {
        if (drainFirst)
        {
            _s.DrainAlarms();
        }

        var outcome = action();
        Thread.Sleep(TimeSpan.FromSeconds(o.StepDelaySeconds));
        var after = Take(_s.DrainAlarms());
        var before = _last!;
        _steps.Add(new StepResult(code, title, outcome, before, after));
        Print(code, title, outcome, before, after);
        _last = after;
    }

    private Snapshot Take(List<AlarmSeen> alarms)
    {
        var user = _s.GetUser(Id);
        var row = user == null ? null : ReaderSession.ToRow(user.Value);
        var list = _s.ListFaces(Id, 5);
        var joined = string.Join(",", list.Rows.Where(x => x.UserId == Id).SelectMany(x => x.Md5s));
        var listMd5 = !list.Supported ? "(unsupported)" : joined.Length > 0 ? joined : "(none)";
        var face = _s.GetFace(Id);
        var clock = _s.DeviceTime();
        return new Snapshot(
            row != null, row?.Name, row?.Status ?? 0, row?.ValidTo ?? "", row?.UpdateRaw ?? "-",
            face.Photo != null, listMd5, face.Photo == null ? "(none)" : Probe.Md5(face.Photo), (face.Photo?.Length ?? 0) / 1024,
            face.Photo == null ? "-" : face.UpdateRaw, clock?.ToString("yyyy-MM-dd HH:mm:ss") ?? "?", alarms);
    }

    private void Print(string code, string title, string outcome, Snapshot before, Snapshot after)
    {
        r.Line();
        r.Line($"[{code}] {title}{(outcome.Length > 0 ? ": " + outcome : "")}");
        r.Line($"   user : {(after.UserExists ? $"\"{after.Name}\" status={after.Status} validTo={after.ValidTo}" : "absent")}, "
               + $"update {Change(before.UserUpdate, after.UserUpdate)}");
        r.Line($"   photo: list MD5 {Change(before.FaceListMd5, after.FaceListMd5)}");
        r.Line($"          GetFace {(after.FacePresent ? after.FaceKb + " KB " : "")}MD5 {Change(before.FaceReadMd5, after.FaceReadMd5)}, update {Change(before.FaceUpdate, after.FaceUpdate)}");
        r.Line($"   reader clock {after.DeviceClock}");
        r.Line(after.Alarms.Count == 0
            ? "   events: none"
            : "   events: " + string.Join(" | ", after.Alarms.Select(a => $"{a.AtUtc:HH:mm:ss} {a.Type} {a.Detail}")));
    }

    private static string Change(string before, string after) => before == after ? after + " (same)" : $"{before} -> {after} (CHANGED)";

    private void Summarize(string? uploadedMd5)
    {
        r.Section("Write test summary");
        r.Line($"  {"step",-5} {"user time",-10} {"photo time",-10} {"list MD5",-9} {"photo MD5",-9} events");
        foreach (var st in _steps)
        {
            r.Line($"  {st.Code,-5} {Mark(st.Before.UserUpdate, st.After.UserUpdate),-10} {Mark(st.Before.FaceUpdate, st.After.FaceUpdate),-10} "
                   + $"{Mark(st.Before.FaceListMd5, st.After.FaceListMd5),-9} {Mark(st.Before.FaceReadMd5, st.After.FaceReadMd5),-9} "
                   + $"{string.Join(",", st.After.Alarms.Select(a => a.Type).Distinct())}  ({st.Title})");
        }

        var w1 = Find("W1");
        if (w1 != null && DateTime.TryParse(w1.After.UserUpdate, out var created) && DateTime.TryParse(w1.After.DeviceClock, out var clock))
        {
            answers.Add($"User update time right after creating (W1) is {(clock - created).TotalSeconds:0}s behind the reader clock (reader clock is UTC if the clock check said so).");
        }

        answers.Add($"User update time changes on: {Changed(st => (st.Before.UserUpdate, st.After.UserUpdate))}.");
        answers.Add($"Photo update time changes on: {Changed(st => (st.Before.FaceUpdate, st.After.FaceUpdate))}.");
        answers.Add($"Listed photo MD5 changes on: {Changed(st => (st.Before.FaceListMd5, st.After.FaceListMd5))}.");
        var w7 = Find("W7");
        if (w7 != null && uploadedMd5 != null)
        {
            answers.Add($"After upload (W7): listed MD5 {(w7.After.FaceListMd5.Contains(uploadedMd5) ? "EQUALS" : "differs from")} the uploaded file's MD5; "
                        + $"downloaded photo {(w7.After.FaceReadMd5 == uploadedMd5 ? "is byte-identical to" : "differs from")} the upload.");
            var w11 = Find("W11");
            if (w11 != null)
            {
                answers.Add($"Re-uploading the same photo (W11) gives {(w11.After.FaceListMd5 == w7.After.FaceListMd5 ? "the SAME" : "a DIFFERENT")} listed MD5 as W7.");
            }
        }

        var own = _steps.Where(st => st.Code.StartsWith('W')).SelectMany(st => st.After.Alarms.Select(a => a.Type)).Distinct().ToList();
        answers.Add($"Events raised by our own SDK writes: {(own.Count == 0 ? "none" : string.Join(", ", own))}.");
        var screen = _steps.Where(st => st.Code.StartsWith('I')).ToList();
        foreach (var st in screen)
        {
            answers.Add($"Reader-screen edit {st.Code} ({st.Title}): user time {Mark(st.Before.UserUpdate, st.After.UserUpdate)}, "
                        + $"photo time {Mark(st.Before.FaceUpdate, st.After.FaceUpdate)}, list MD5 {Mark(st.Before.FaceListMd5, st.After.FaceListMd5)}, "
                        + $"events {string.Join(",", st.After.Alarms.Select(a => $"{a.Type} {a.Detail}").Distinct())}.");
        }
    }

    private StepResult? Find(string code) => _steps.FirstOrDefault(st => st.Code == code);

    private string Changed(Func<StepResult, (string Before, string After)> pick)
    {
        var codes = _steps.Where(st => { var (b, a) = pick(st); return b != a; }).Select(st => st.Code).ToList();
        return codes.Count == 0 ? "no step" : string.Join(", ", codes);
    }

    private static string Mark(string before, string after) => before == after ? "same" : "CHANGED";

    private string AddPhoto(byte[] photo)
    {
        var insert = _s.WriteFace(EM_NET_ACCESS_CTL_FACE_SERVICE.INSERT, Id, photo);
        return insert == "ok" ? "INSERT ok" : $"INSERT {insert}; UPDATE {_s.WriteFace(EM_NET_ACCESS_CTL_FACE_SERVICE.UPDATE, Id, photo)}";
    }

    private string Rewrite(Func<NET_ACCESS_USER_INFO, NET_ACCESS_USER_INFO> change)
    {
        var current = _s.GetUser(Id);
        return current == null ? "FAILED: test user not found" : _s.InsertUser(change(current.Value));
    }

    private NET_ACCESS_USER_INFO NewUser(string name, DateTime validTo)
    {
        var user = new NET_ACCESS_USER_INFO
        {
            szUserID = Id,
            szName = name,
            emUserType = EM_USER_TYPE.NORMAL,
            emAuthority = EM_ATTENDANCE_AUTHORITY.Customer,
            nUserStatus = 0,
            nDoorNum = 1,
            nDoors = new int[32],
            nTimeSectionNum = 1,
            nTimeSectionNo = new int[32],
            nSpecialDaysSchedule = new int[128],
            nFirstEnterDoors = new int[32],
            stuValidBeginTime = NET_TIME.FromDateTime(DateTime.UtcNow.Date),
            stuValidEndTime = NET_TIME.FromDateTime(EndOfDay(validTo))
        };
        return user;
    }

    private static DateTime EndOfDay(DateTime date) => date.Date.AddDays(1).AddSeconds(-1);

    private byte[]? LoadPhoto()
    {
        if (o.PhotoPath != null)
        {
            var bytes = File.ReadAllBytes(o.PhotoPath);
            if (bytes.Length > MaxPhotoBytes)
            {
                r.Line($"  --photo is {bytes.Length / 1024} KB; the reader limit is 120 KB. Photo steps skipped.");
                return null;
            }

            return bytes;
        }

        var borrowed = all.SelectMany(f => f.Samples)
            .Where(x => x.Read.Photo is { Length: > 0 and <= MaxPhotoBytes })
            .OrderBy(x => x.Read.Photo!.Length)
            .FirstOrDefault();
        if (borrowed != null)
        {
            r.Line($"  no --photo given: borrowing the photo of user {borrowed.UserId} for the test user (removed again at the end).");
        }

        return borrowed?.Read.Photo;
    }
}
