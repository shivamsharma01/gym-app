using System.Diagnostics;
using System.Security.Cryptography;
using Gym.Gateway.Adapters;
using Gym.Gateway.SdkProbe;

ProbeOptions options;
try
{
    options = ProbeOptions.Parse(args);
}
catch (Exception ex)
{
    Console.WriteLine(ex.Message);
    Console.WriteLine(ProbeOptions.Usage);
    return 2;
}

if (options.Help)
{
    Console.WriteLine(ProbeOptions.Usage);
    return 0;
}

if (args.Contains("--self-test"))
{
    var blank = new NetSDKCS.NET_ACCESS_USER_INFO { szUserID = "1", szName = "A" };
    var changed = blank;
    changed.szName = "B";
    changed.nTimeSectionNo = new int[32];
    changed.nTimeSectionNo[0] = 255;
    changed.stuValidEndTime = NetSDKCS.NET_TIME.FromDateTime(new DateTime(2027, 6, 7, 21, 22, 23));
    Console.WriteLine(string.Join(" | ", UserFields.NonEmpty(UserFields.Dump(changed))));
    Console.WriteLine(string.Join(" | ", UserFields.Diff(UserFields.Dump(blank), UserFields.Dump(changed))));
    return 0;
}

var outDir = options.OutDir ?? Path.Combine(Environment.CurrentDirectory, "sdk-probe-" + DateTime.Now.ToString("yyyyMMdd-HHmmss"));
using var report = new Report(outDir);
var exitCode = 0;
try
{
    exitCode = new Probe(options, report).Run();
}
catch (Exception ex)
{
    report.Line("PROBE FAILED: " + ex);
    exitCode = 1;
}

report.Line();
report.Line($"Results are in {outDir}. Please send the whole folder.");
if (options.PauseAtEnd)
{
    Console.WriteLine("Press Enter to close.");
    Console.ReadLine();
}

return exitCode;

namespace Gym.Gateway.SdkProbe
{
    internal sealed record SampleResult(string UserId, FaceRead Read, string? ReadMd5, string[] ListMd5, bool? Match, string? UserUpdateRaw);

    internal sealed class DeviceFindings(ReaderSession session)
    {
        public ReaderSession Session { get; } = session;
        public UserListResult? Users { get; set; }
        public Dictionary<string, UserRow> UserById { get; } = new(StringComparer.Ordinal);
        public FaceList? Faces { get; set; }
        public Dictionary<string, FaceRow> FaceById { get; } = new(StringComparer.Ordinal);
        public List<SampleResult> Samples { get; } = [];
        public double? ClockSkewSeconds { get; set; }
    }

    internal sealed record Snapshot(
        bool UserExists, string? Name, uint Status, string ValidTo, string UserUpdate,
        bool FacePresent, string FaceListMd5, string FaceReadMd5, int FaceKb, string FaceUpdate,
        string DeviceClock, List<AlarmSeen> Alarms);

    internal sealed record StepResult(string Code, string Title, string Outcome, Snapshot Before, Snapshot After);

    internal sealed class Probe(ProbeOptions o, Report r)
    {
        private readonly List<string> _answers = [];

        public int Run()
        {
            r.Section("TrueFace SDK probe");
            r.Line($"Started  {DateTime.Now:yyyy-MM-dd HH:mm:ss} local, {DateTime.UtcNow:yyyy-MM-dd HH:mm:ss} UTC, zone {TimeZoneInfo.Local.Id}");
            r.Line($"Machine  {Environment.MachineName}, write test {(o.Write ? "ON" : "off")}, field test {(o.Fields ? "ON" : "off")}, "
                   + $"interactive {(o.Interactive ? "ON" : "off")}, read-only part {(o.SkipRead ? "skipped" : "on")}, samples {o.Samples}");

            var gatewayRunning = Process.GetProcessesByName("Gym.Gateway").Length > 0;
            if (gatewayRunning)
            {
                r.Line("WARNING: the Gym Gateway service is running. Its reader traffic will distort timings,");
                r.Line("and a test user written by --write would be reported to the server as a real member.");
                if (o.WritesToReader || !o.AllowGatewayRunning)
                {
                    r.Line("Stop it first:  sc stop \"Gym Gateway\"   (or add --allow-gateway-running for the read-only part).");
                    return 3;
                }
            }

            var targets = ProbeConfig.Load(o, r);
            if (targets.Count == 0)
            {
                r.Line("No readers to probe.");
                return 2;
            }

            var nativeDir = WindowsNativeBootstrap.ResolveNativeDirectory(o.NativeDir);
            if (!WindowsNativeBootstrap.TryConfigure(nativeDir, out var nativeError))
            {
                r.Line("Native SDK not found: " + nativeError);
                return 2;
            }

            if (!ReaderSession.Init(out var initError))
            {
                r.Line(initError);
                return 2;
            }

            var findings = new List<DeviceFindings>();
            try
            {
                foreach (var target in targets)
                {
                    r.Section($"Reader {target.DeviceId} ({target.Ip}:{target.Port})");
                    var session = ReaderSession.Open(target, out var loginError);
                    if (session == null)
                    {
                        r.Line("  " + loginError);
                        continue;
                    }

                    var f = new DeviceFindings(session);
                    findings.Add(f);
                    r.Line("  logged in");
                    if (!o.SkipRead)
                    {
                        ReadOnlySuite(f);
                    }
                }

                if (!o.SkipRead)
                {
                    CrossReader(findings);
                }

                if (o.WritesToReader)
                {
                    var writeTarget = o.WriteDevice == null
                        ? findings.FirstOrDefault()
                        : findings.FirstOrDefault(f => string.Equals(f.Session.Name, o.WriteDevice, StringComparison.OrdinalIgnoreCase)
                                                       || string.Equals(f.Session.Target.Ip, o.WriteDevice, StringComparison.OrdinalIgnoreCase));
                    if (writeTarget == null)
                    {
                        r.Line("Write tests skipped: reader not found or not logged in.");
                    }
                    else
                    {
                        if (o.Write)
                        {
                            new WriteSuite(o, r, writeTarget, findings, _answers).Run();
                        }

                        if (o.Fields)
                        {
                            new FieldSuite(o, r, writeTarget, _answers).Run();
                        }
                    }
                }

                r.Section("Answers");
                foreach (var a in _answers)
                {
                    r.Line("* " + a);
                }
            }
            finally
            {
                foreach (var f in findings)
                {
                    f.Session.Dispose();
                }
            }

            return 0;
        }

        private void ReadOnlySuite(DeviceFindings f)
        {
            var s = f.Session;
            r.Line($"  serial {s.Info.sSerialNumber}, type {s.Info.nDVRType}, listening for events: {s.Listening}");

            r.Sub("Clock");
            var utcBefore = DateTime.UtcNow;
            var clock = s.DeviceTime();
            if (clock == null)
            {
                r.Line("  QueryDeviceTime failed");
            }
            else
            {
                f.ClockSkewSeconds = (clock.Value - utcBefore).TotalSeconds;
                var vsLocal = (clock.Value - DateTime.Now).TotalSeconds;
                r.Line($"  reader clock {clock:yyyy-MM-dd HH:mm:ss}; PC UTC {utcBefore:HH:mm:ss}, PC local {DateTime.Now:HH:mm:ss}");
                r.Line($"  reader minus PC UTC = {f.ClockSkewSeconds:0}s, reader minus PC local = {vsLocal:0}s");
                r.Line(Math.Abs(f.ClockSkewSeconds.Value) < 120 ? "  -> reader runs on UTC (as the gateway sets it)" : "  -> reader does NOT run on UTC");
            }

            UsersPart(f);
            FacesPart(f);
            SamplesPart(f);
        }

        private void UsersPart(DeviceFindings f)
        {
            var s = f.Session;
            r.Sub("User list (StartFindUserInfo/DoFindUserInfo, page 50)");
            var list = s.ListUsers(50);
            f.Users = list;
            r.Line($"  announced total {list.Total}, reader page capacity (nCapNum) {list.CapNum}, read {list.Users.Count} in {list.Ms} ms over {list.Calls} calls");
            if (list.Error != null)
            {
                r.Line("  ERROR " + list.Error);
            }

            foreach (var u in list.Users)
            {
                f.UserById.TryAdd(u.Id, u);
            }

            r.Csv($"users-{Safe(s.Name)}.csv", ["userId", "name", "status", "validFrom", "validTo", "updateTime"],
                list.Users.Select(u => new[] { u.Id, u.Name, u.Status.ToString(), u.ValidFrom, u.ValidTo, u.UpdateRaw }));

            var withTime = list.Users.Where(u => u.UpdatedAt != null).ToList();
            r.Line($"  user stuUpdateTime filled for {withTime.Count} of {list.Users.Count} users");
            if (withTime.Count > 0)
            {
                var distinct = withTime.Select(u => u.UpdatedAt).Distinct().Count();
                var now = DateTime.UtcNow.AddSeconds(f.ClockSkewSeconds ?? 0);
                r.Line($"  oldest {withTime.Min(u => u.UpdatedAt):yyyy-MM-dd HH:mm:ss}, newest {withTime.Max(u => u.UpdatedAt):yyyy-MM-dd HH:mm:ss}, {distinct} distinct values");
                r.Line($"  in the future (vs reader clock): {withTime.Count(u => u.UpdatedAt > now.AddMinutes(1))}; changed in last 24 h: {withTime.Count(u => u.UpdatedAt > now.AddDays(-1))}; last 7 days: {withTime.Count(u => u.UpdatedAt > now.AddDays(-7))}");
                r.Line("  busiest days:");
                foreach (var g in withTime.GroupBy(u => u.UpdatedAt!.Value.Date).OrderByDescending(g => g.Count()).Take(8))
                {
                    r.Line($"    {g.Key:yyyy-MM-dd}: {g.Count()}");
                }

                r.Line("  most recently changed:");
                foreach (var u in withTime.OrderByDescending(u => u.UpdatedAt).Take(5))
                {
                    r.Line($"    {u.Id} \"{u.Name}\" updated {u.UpdateRaw}");
                }
            }

            _answers.Add($"[{s.Name}] User last-modified time (stuUpdateTime) in the user list: filled for {withTime.Count}/{list.Users.Count} users.");

            r.Sub("Single-user reads: GetOperateAccessUserService vs the list, and StartFindUserInfo with a user ID");
            foreach (var u in Spread(list.Users, 3))
            {
                var watch = Stopwatch.StartNew();
                var got = s.GetUser(u.Id);
                var getMs = watch.ElapsedMilliseconds;
                var got2 = got == null ? null : ReaderSession.ToRow(got.Value);
                var filtered = s.ListUsers(10, u.Id);
                r.Line($"  {u.Id}: list update {u.UpdateRaw} | Get update {got2?.UpdateRaw ?? "(not found)"} ({getMs} ms) | "
                       + $"filtered search returned {filtered.Users.Count} user(s) [{string.Join(",", filtered.Users.Take(3).Select(x => x.Id))}] of total {filtered.Total} in {filtered.Ms} ms");
                if (got != null)
                {
                    r.Line("     fields in use: " + string.Join(" | ", UserFields.NonEmpty(UserFields.Dump(got.Value))));
                }
            }

            r.Sub("Reading a user ID that does not exist (how the reader says \"not found\")");
            var known = list.Users.Select(u => u.Id).ToHashSet(StringComparer.Ordinal);
            var absent = Enumerable.Range(0, 1000).Select(i => (999900 + i).ToString()).First(id => !known.Contains(id));
            var probe = s.DescribeGet(absent);
            r.Line($"  user {absent}: {probe}");
            _answers.Add($"[{s.Name}] Reading a missing user ({absent}): {probe}.");
        }

        private void FacesPart(DeviceFindings f)
        {
            var s = f.Session;
            r.Sub("Photo checksum list (StartFindFaceInfo with empty user ID, page 50)");
            var faces = s.ListFaces(null, 50);
            f.Faces = faces;
            if (!faces.Supported)
            {
                r.Line("  NOT SUPPORTED: " + faces.Error);
                _answers.Add($"[{s.Name}] Listing all photo MD5s: not supported ({faces.Error}).");
            }
            else
            {
                foreach (var row in faces.Rows)
                {
                    f.FaceById.TryAdd(row.UserId, row);
                }

                var withMd5 = faces.Rows.Count(x => x.Md5s.Length > 0);
                var duplicates = faces.Rows.Count - f.FaceById.Count;
                var usersWithoutEntry = f.UserById.Keys.Count(id => !f.FaceById.ContainsKey(id));
                var entriesWithoutUser = f.FaceById.Keys.Count(id => !f.UserById.ContainsKey(id));
                r.Line($"  announced total {faces.Total}; got {faces.Rows.Count} entries ({withMd5} with an MD5) in {faces.TotalMs} ms "
                       + $"(start {faces.StartMs} ms, {faces.Calls} page calls)");
                r.Line($"  max MD5s per entry {faces.Rows.Select(x => x.Md5s.Length).DefaultIfEmpty(0).Max()}, duplicate user IDs {duplicates}, paging stuck: {faces.PagingStuck}");
                r.Line($"  users without a photo entry {usersWithoutEntry}; photo entries for unknown users {entriesWithoutUser}");
                if (faces.Error != null)
                {
                    r.Line("  ERROR " + faces.Error);
                }

                foreach (var row in faces.Rows.Take(3))
                {
                    r.Line($"    e.g. {row.UserId}: [{string.Join(", ", row.Md5s)}]");
                }

                r.Csv($"faces-{Safe(s.Name)}.csv", ["userId", "md5Count", "md5s"],
                    faces.Rows.Select(x => new[] { x.UserId, x.Md5s.Length.ToString(), string.Join(" ", x.Md5s) }));
                _answers.Add($"[{s.Name}] Listing all photo MD5s: {faces.Rows.Count} entries ({withMd5} with MD5) of {faces.Total} announced, "
                             + $"{faces.TotalMs} ms total, paging {(faces.PagingStuck ? "BROKEN" : "ok")}; reader has {f.UserById.Count} users.");

                r.Sub("Second pass with page 100 (speed and stability)");
                var again = s.ListFaces(null, 100);
                var same = again.Rows.Count(x => f.FaceById.TryGetValue(x.UserId, out var first) && first.Md5s.SequenceEqual(x.Md5s));
                r.Line($"  got {again.Rows.Count} entries in {again.TotalMs} ms over {again.Calls} calls; identical to first pass: {same}; error: {again.Error ?? "none"}");
            }

            r.Sub("Per-user StartFindFaceInfo (cost if it has to be one call per user)");
            foreach (var id in Spread(f.UserById.Keys.ToList(), 3))
            {
                var one = s.ListFaces(id, 5);
                var mine = one.Rows.Where(x => x.UserId == id).SelectMany(x => x.Md5s);
                r.Line($"  {id}: supported {one.Supported}, total {one.Total}, {one.Rows.Count} row(s) "
                       + $"[{string.Join(",", one.Rows.Take(3).Select(x => x.UserId))}], MD5 [{string.Join(", ", mine)}], {one.TotalMs} ms{(one.Error != null ? ", " + one.Error : "")}");
            }
        }

        private void SamplesPart(DeviceFindings f)
        {
            var s = f.Session;
            r.Sub($"Photo downloads (GetFace) for {o.Samples} users: checksum match and photo update time");
            var withEntry = f.UserById.Keys.Where(f.FaceById.ContainsKey).ToList();
            var pool = withEntry.Count > 0 ? withEntry : f.UserById.Keys.ToList();
            var picks = Spread(pool, o.Samples).ToList();
            picks.AddRange(Spread(f.UserById.Keys.Where(id => !f.FaceById.ContainsKey(id)).ToList(), 2).Where(id => !picks.Contains(id)));
            foreach (var id in picks)
            {
                var read = s.GetFace(id);
                var md5 = read.Photo == null ? null : Md5(read.Photo);
                var listMd5 = f.FaceById.TryGetValue(id, out var row) ? row.Md5s : [];
                bool? match = md5 == null || listMd5.Length == 0 ? null : listMd5.Contains(md5);
                f.UserById.TryGetValue(id, out var user);
                f.Samples.Add(new SampleResult(id, read, md5, listMd5, match, user?.UpdateRaw));
                var photo = read.Photo == null ? $"no photo (ok={read.Ok} fail={read.FailCode} {read.Error})" : $"{read.Photo.Length / 1024} KB MD5 {md5}";
                var alt = match == false && read.Photo != null ? $" | MD5(base64)={Md5(System.Text.Encoding.ASCII.GetBytes(Convert.ToBase64String(read.Photo)))}" : "";
                r.Line($"  {id}: {read.Ms} ms, {photo}, list MD5 [{string.Join(",", listMd5)}] match={Show(match)}, photo update {read.UpdateRaw}, user update {user?.UpdateRaw}{alt}");
            }

            r.Csv($"samples-{Safe(s.Name)}.csv", ["userId", "ms", "bytes", "getFaceMd5", "listMd5", "match", "photoUpdate", "userUpdate", "failCode"],
                f.Samples.Select(x => new[]
                {
                    x.UserId, x.Read.Ms.ToString(), (x.Read.Photo?.Length ?? 0).ToString(), x.ReadMd5, string.Join(" ", x.ListMd5),
                    Show(x.Match), x.Read.UpdateRaw, x.UserUpdateRaw, x.Read.FailCode
                }));

            var compared = f.Samples.Where(x => x.Match != null).ToList();
            var photos = f.Samples.Where(x => x.Read.Photo != null).ToList();
            var avgMs = photos.Count == 0 ? 0 : photos.Average(x => x.Read.Ms);
            _answers.Add($"[{s.Name}] Reader MD5 equals MD5 of the downloaded photo: {compared.Count(x => x.Match == true)}/{compared.Count} compared.");
            _answers.Add($"[{s.Name}] Photo update time (GetFace stuUpdateTime) filled: {photos.Count(x => x.Read.UpdatedAt != null)}/{photos.Count} photos; "
                         + $"same as the user update time: {photos.Count(x => x.Read.UpdateRaw == x.UserUpdateRaw)}/{photos.Count}.");
            _answers.Add($"[{s.Name}] Average GetFace time {avgMs:0} ms; downloading all {f.FaceById.Count} photos would take about {avgMs * f.FaceById.Count / 1000:0} s.");
            var flagged = f.Samples.Where(x => x.Read.Photo == null && x.ListMd5.Length > 0).ToList();
            if (flagged.Count > 0)
            {
                _answers.Add($"[{s.Name}] {flagged.Count} users have an MD5 in the list but GetFace returned no photo: {string.Join(", ", flagged.Take(5).Select(x => x.UserId))}.");
            }
        }

        private void CrossReader(List<DeviceFindings> findings)
        {
            if (findings.Count < 2)
            {
                return;
            }

            for (var i = 0; i < findings.Count; i++)
            {
                for (var j = i + 1; j < findings.Count; j++)
                {
                    var a = findings[i];
                    var b = findings[j];
                    r.Section($"Readers compared: {a.Session.Name} vs {b.Session.Name}");
                    var common = a.UserById.Keys.Where(b.UserById.ContainsKey).ToList();
                    var sameName = common.Count(id => a.UserById[id].Name == b.UserById[id].Name);
                    var sameTime = common.Count(id => a.UserById[id].UpdateRaw == b.UserById[id].UpdateRaw);
                    var bothMd5 = common.Where(id => a.FaceById.TryGetValue(id, out var fa) && fa.Md5s.Length > 0
                                                     && b.FaceById.TryGetValue(id, out var fb) && fb.Md5s.Length > 0).ToList();
                    var sameMd5 = bothMd5.Count(id => a.FaceById[id].Md5s.Intersect(b.FaceById[id].Md5s).Any());
                    r.Line($"  users only on {a.Session.Name}: {a.UserById.Count - common.Count}, only on {b.Session.Name}: {b.UserById.Count - common.Count}, on both: {common.Count}");
                    r.Line($"  on both: same name {sameName}, same update time {sameTime}, both have a photo MD5 {bothMd5.Count}, same MD5 {sameMd5}");
                    foreach (var id in bothMd5.Where(id => !a.FaceById[id].Md5s.Intersect(b.FaceById[id].Md5s).Any()).Take(5))
                    {
                        r.Line($"    MD5 differs for {id}: {string.Join(",", a.FaceById[id].Md5s)} vs {string.Join(",", b.FaceById[id].Md5s)}");
                    }

                    _answers.Add($"Photo MD5 identical on {a.Session.Name} and {b.Session.Name} for {sameMd5}/{bothMd5.Count} shared users "
                                 + $"(user update time identical for {sameTime}/{common.Count}).");
                }
            }
        }

        internal static IEnumerable<string> Spread(IReadOnlyList<string> ids, int count)
        {
            if (ids.Count <= count)
            {
                return ids;
            }

            return Enumerable.Range(0, count).Select(i => ids[(int)((long)i * (ids.Count - 1) / Math.Max(1, count - 1))]).Distinct();
        }

        private static IEnumerable<UserRow> Spread(IReadOnlyList<UserRow> users, int count) =>
            Spread(users.Select(u => u.Id).ToList(), count).Select(id => users.First(u => u.Id == id));

        internal static string Md5(byte[] bytes) => Convert.ToHexString(MD5.HashData(bytes)); // NOSONAR S4790: must match the reader's MD5

        internal static string Show(bool? value) => value == null ? "n/a" : value.Value ? "yes" : "NO";

        internal static string Safe(string name) => string.Concat(name.Select(c => char.IsLetterOrDigit(c) || c is '-' or '_' ? c : '_'));
    }
}
