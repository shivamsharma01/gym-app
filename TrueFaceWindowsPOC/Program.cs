using System.Runtime.InteropServices;
using Gym.Gateway.Adapters;

namespace TrueFaceWindowsPOC;

internal static class Program
{
    private static IDeviceAdapter? s_adapter;
    private static SessionUndo? s_undo;
    private static PocLogger? s_log;
    private static int s_exitCode = 2;

    public static async Task<int> Main(string[] args)
    {
        if (HasFlag(args, "--help") || HasFlag(args, "-h"))
        {
            Console.WriteLine(PocOptions.Usage());
            return 0;
        }

        using var log = new PocLogger();
        s_log = log;
        log.Info("Starting TrueFace Windows gym-visit POC (no Spring / MySQL)");
        log.Info($"Runtime: {RuntimeInformation.FrameworkDescription}");
        log.Info($"OS: {RuntimeInformation.OSDescription} ({RuntimeInformation.OSArchitecture})");

        PocOptions options;
        try
        {
            options = PocOptions.Parse(args, EnvMap());
        }
        catch (PocConfigException ex) when (ex.Message == "HELP")
        {
            Console.WriteLine(PocOptions.Usage());
            return 0;
        }
        catch (PocConfigException ex)
        {
            log.Error(ex.Message);
            Console.WriteLine();
            Console.WriteLine(PocOptions.Usage());
            return 2;
        }

        log.Info($"Options: {options}");

        Console.CancelKeyPress += OnCancel;

        var undo = new SessionUndo();
        s_undo = undo;
        HashSet<string>? beforeIds = null;
        string? pocUserId = null;
        IDeviceAdapter? adapter = null;

        try
        {
            if (options.SelfCheckOnly)
            {
                return RunSelfCheck(log, options);
            }

            if (!options.UseMockAdapter && !options.HasConnectionTarget)
            {
                log.Error("Missing --ip and/or --username (or TRUEFACE_* env). Use --mock for CI, --self-check for native path.");
                return 2;
            }

            var password = options.Password;
            if (!options.UseMockAdapter && string.IsNullOrEmpty(password))
            {
                password = ReadPassword();
            }

            adapter = options.UseMockAdapter
                ? new MockDeviceAdapter()
                : new TrueFaceDeviceAdapter();
            s_adapter = adapter;

            log.Step("CONNECT");
            var deviceId = "poc-local";
            var status = adapter.Connect(new DeviceConnectionConfig(
                deviceId,
                options.UseMockAdapter ? "127.0.0.1" : options.Ip,
                options.Port,
                options.UseMockAdapter ? "mock" : options.Username,
                options.UseMockAdapter ? "x" : password,
                options.NativeDir));
            if (!status.Ok)
            {
                log.Error($"Connect failed: {status.Error}");
                s_exitCode = 6;
                return s_exitCode;
            }

            log.Ok($"Connected state={status.ConnectionState}");
            var info = adapter.GetDeviceInfo();
            log.Info($"Serial={info.SerialNumber ?? "(none)"} type={info.DeviceType} channels={info.ChannelCount}");

            var waiter = new AccessEventWaiter();
            adapter.RegisterEventListener(waiter);

            log.Step("SNAPSHOT BEFORE");
            var beforeUsers = adapter.ListUsers();
            beforeIds = SessionUndo.SnapshotIds(beforeUsers.Select(u => u.DeviceUserId));
            log.Info($"User count={beforeUsers.Count}");
            foreach (var u in beforeUsers.OrderBy(x => x.DeviceUserId, StringComparer.Ordinal))
            {
                log.Info($"  id={u.DeviceUserId} name={u.Name ?? "(none)"} frozen={u.Frozen}");
            }

            pocUserId = SessionUndo.NewPocUserId(DateTimeOffset.UtcNow);
            log.Step("CREATE USER");
            log.Info($"Creating throwaway user id={pocUserId} (will be deleted on cleanup)");
            var create = adapter.CreateUser(new DeviceUserMutation(
                pocUserId,
                Name: "POC Test",
                Enabled: true,
                ValidFrom: DateTimeOffset.UtcNow.AddDays(-1),
                ValidTo: DateTimeOffset.UtcNow.AddDays(7)));
            if (!create.Ok)
            {
                log.Error($"CreateUser failed: {create.Error}");
                s_exitCode = 10;
                return s_exitCode;
            }

            undo.RememberCreate(pocUserId);
            log.Ok("CreateUser succeeded");

            var afterCreate = adapter.ListUsers();
            if (afterCreate.All(u => u.DeviceUserId != pocUserId))
            {
                log.Error("POC user missing from ListUsers after create");
                s_exitCode = 10;
                return s_exitCode;
            }

            log.Ok("POC user visible in enumeration");

            log.Step("MODIFY USER");
            var rename = adapter.UpdateUser(new DeviceUserMutation(
                pocUserId,
                Name: "POC Renamed",
                Enabled: true,
                ValidFrom: DateTimeOffset.UtcNow.AddDays(-1),
                ValidTo: DateTimeOffset.UtcNow.AddDays(14)));
            if (!rename.Ok)
            {
                log.Error($"UpdateUser failed: {rename.Error}");
                s_exitCode = 11;
                return s_exitCode;
            }

            var renamed = adapter.ListUsers().FirstOrDefault(u => u.DeviceUserId == pocUserId);
            log.Info($"After update: name={renamed?.Name ?? "(missing)"} frozen={renamed?.Frozen}");
            log.Ok("UpdateUser completed");

            if (!options.SkipFaceUpload)
            {
                log.Step("FACE UPLOAD PROBE");
                byte[]? jpeg = null;
                if (!string.IsNullOrWhiteSpace(options.FaceImagePath))
                {
                    if (!File.Exists(options.FaceImagePath))
                    {
                        log.Error($"Face image not found: {options.FaceImagePath}");
                        s_exitCode = 12;
                        return s_exitCode;
                    }

                    jpeg = await File.ReadAllBytesAsync(options.FaceImagePath).ConfigureAwait(false);
                    log.Info($"Loaded face image path={options.FaceImagePath} bytes={jpeg.Length} (bytes not logged)");
                }
                else if (options.UseMockAdapter)
                {
                    jpeg = [0xFF, 0xD8, 0xFF, 0x00];
                    log.Info("Mock adapter: using tiny synthetic jpeg bytes for probe");
                }
                else
                {
                    log.Warn("No --face-image; skipping remote face INSERT probe (use --skip-face-upload to silence)");
                }

                if (jpeg != null)
                {
                    var probe = adapter.ProbeRemoteFaceInsert(pocUserId, jpeg);
                    log.Info($"Face probe sdkReturnedTrue={probe.SdkCallReturnedTrue} code={probe.SdkErrorHex} failCode={probe.FailCode ?? "(none)"}");
                    log.Info($"Face probe detail: {probe.Detail}");
                    if (probe.MatchesKnownFirmwareReject)
                    {
                        log.Ok("Matches known firmware reject 0x10030110 — product should keep guided on-device enroll");
                    }
                    else if (probe.SdkCallReturnedTrue)
                    {
                        log.Warn("SDK returned true for face INSERT — still do NOT claim product remote enroll until verified on multiple units");
                    }
                    else
                    {
                        log.Warn("Face INSERT failed with an unexpected code — capture this log for vendor discussion");
                    }
                }
            }
            else
            {
                log.Info("Skipping face upload probe (--skip-face-upload)");
            }

            if (!options.SkipDoor)
            {
                await RunDoorEvidenceAsync(log, adapter, waiter, pocUserId, options).ConfigureAwait(false);
            }
            else
            {
                log.Warn("Skipping door allow/deny walks (--skip-door). Deny/allow is NOT claimed.");
                if (options.UseMockAdapter)
                {
                    // Still exercise disable/enable + events for CI when --skip-door is not set;
                    // with --skip-door we only touch CRUD.
                }
            }

            if (options.UseMockAdapter && options.SkipDoor)
            {
                // Exercise disable/enable without door waits for CI coverage of those adapter calls.
                log.Step("DISABLE / ENABLE (mock, no door)");
                AssertOk(log, adapter.DisableUser(pocUserId), "DisableUser");
                AssertOk(log, adapter.EnableUser(pocUserId), "EnableUser");
            }

            log.Step("DELETE USER");
            var delete = adapter.DeleteUser(pocUserId);
            if (!delete.Ok)
            {
                log.Error($"DeleteUser failed: {delete.Error}");
                log.Error($"MANUAL CLEANUP REQUIRED on tablet: delete user id {pocUserId}");
                s_exitCode = 20;
                return s_exitCode;
            }

            undo.Forget(pocUserId);
            log.Ok("DeleteUser succeeded");

            log.Step("SNAPSHOT AFTER");
            var afterUsers = adapter.ListUsers();
            var afterIds = SessionUndo.SnapshotIds(afterUsers.Select(u => u.DeviceUserId));
            log.Info($"User count={afterUsers.Count}");
            if (!SessionUndo.SnapshotsEqual(beforeIds, afterIds))
            {
                var diff = SessionUndo.SymmetricDifference(beforeIds, afterIds);
                log.Error("Snapshot mismatch after cleanup — device user list is NOT identical to start");
                log.Error($"Difference: {string.Join(", ", diff)}");
                s_exitCode = 21;
                return s_exitCode;
            }

            log.Ok("Before/after user-id snapshots match");
            log.Ok("POC PASS — connectivity + user CRUD (+ optional door/face evidence) completed without leftover POC user");
            s_exitCode = 0;
            return 0;
        }
        catch (Exception ex)
        {
            log.Error($"Unexpected exception: {ex.GetType().Name}: {ex.Message}");
            log.Error(ex.ToString());
            s_exitCode = 5;
            return s_exitCode;
        }
        finally
        {
            await BestEffortCleanupAsync(log, adapter, undo, beforeIds, pocUserId).ConfigureAwait(false);
            try
            {
                adapter?.Dispose();
            }
            catch
            {
                // best-effort
            }

            s_adapter = null;
            s_undo = null;
        }
    }

    private static async Task RunDoorEvidenceAsync(
        PocLogger log,
        IDeviceAdapter adapter,
        AccessEventWaiter waiter,
        string pocUserId,
        PocOptions options)
    {
        log.Step("ON-DEVICE ENROLL");
        if (adapter is MockDeviceAdapter)
        {
            log.Info("Mock adapter: simulating on-device enroll (no tablet UI)");
        }
        else
        {
            log.Info($"On the TrueFace tablet, enroll FACE for user id {pocUserId} (name POC Renamed).");
            log.Info("Do NOT enroll under an existing gym member id.");
            PromptEnter(log, "Press Enter when on-device face enroll for the POC user is done (or Ctrl+C to abort)...");
        }

        log.Step("GRANT WALK (expect ACCESS granted=true)");
        if (adapter is MockDeviceAdapter mockGrant)
        {
            mockGrant.EmitAccessEvent(pocUserId, granted: true, recNo: 1);
        }
        else
        {
            PromptEnter(log, $"Press Enter, then have the test person face the door as {pocUserId}...");
        }

        var grant = await waiter.WaitForAsync(
            pocUserId, granted: true, TimeSpan.FromSeconds(options.DoorWaitSeconds), CancellationToken.None)
            .ConfigureAwait(false);
        if (grant == null)
        {
            log.Error($"No ACCESS granted=true event for {pocUserId} within {options.DoorWaitSeconds}s");
            throw new InvalidOperationException("Grant walk evidence missing");
        }

        log.Ok($"Grant event: user={grant.DeviceUserId} method={grant.Method} at={grant.OccurredAt:O} recNo={grant.RecNo}");

        log.Step("DISABLE USER");
        AssertOk(log, adapter.DisableUser(pocUserId), "DisableUser");
        var disabled = adapter.ListUsers().FirstOrDefault(u => u.DeviceUserId == pocUserId);
        log.Info($"After disable: frozen={disabled?.Frozen}");

        log.Step("DENY WALK (expect ACCESS granted=false OR door stays locked)");
        if (adapter is MockDeviceAdapter mockDeny)
        {
            mockDeny.EmitAccessEvent(pocUserId, granted: false, recNo: 2);
        }
        else
        {
            PromptEnter(log, "Press Enter, then have the test person try the door again (should be denied)...");
        }

        var deny = await waiter.WaitForAsync(
            pocUserId, granted: false, TimeSpan.FromSeconds(options.DoorWaitSeconds), CancellationToken.None)
            .ConfigureAwait(false);
        if (deny != null)
        {
            log.Ok($"Deny event: user={deny.DeviceUserId} granted=false method={deny.Method} at={deny.OccurredAt:O}");
        }
        else
        {
            log.Warn("No ACCESS granted=false event received (some firmwares stay silent when locked)");
            if (adapter is not MockDeviceAdapter)
            {
                Console.Write("Did the door stay locked / access denied? type YES or NO: ");
                var answer = (Console.ReadLine() ?? "").Trim();
                log.Info($"Operator deny observation: {answer}");
                if (!string.Equals(answer, "YES", StringComparison.OrdinalIgnoreCase))
                {
                    log.Warn("Deny walk marked UNVERIFIED");
                }
                else
                {
                    log.Ok("Operator confirmed door stayed locked");
                }
            }
            else
            {
                throw new InvalidOperationException("Mock deny event missing");
            }
        }

        log.Step("ENABLE USER");
        AssertOk(log, adapter.EnableUser(pocUserId), "EnableUser");

        log.Step("SECOND GRANT WALK");
        if (adapter is MockDeviceAdapter mockGrant2)
        {
            mockGrant2.EmitAccessEvent(pocUserId, granted: true, recNo: 3);
        }
        else
        {
            PromptEnter(log, "Press Enter, then have the test person walk again (should be allowed)...");
        }

        var grant2 = await waiter.WaitForAsync(
            pocUserId, granted: true, TimeSpan.FromSeconds(options.DoorWaitSeconds), CancellationToken.None)
            .ConfigureAwait(false);
        if (grant2 == null)
        {
            log.Error("Second grant walk: no ACCESS granted=true event");
            throw new InvalidOperationException("Second grant evidence missing");
        }

        log.Ok($"Second grant event: user={grant2.DeviceUserId} at={grant2.OccurredAt:O}");
    }

    private static async Task BestEffortCleanupAsync(
        PocLogger log,
        IDeviceAdapter? adapter,
        SessionUndo undo,
        HashSet<string>? beforeIds,
        string? pocUserId)
    {
        if (adapter == null)
        {
            return;
        }

        log.Step("CLEANUP");
        foreach (var id in undo.CreatedUserIds.Reverse())
        {
            log.Info($"Best-effort DeleteUser id={id}");
            var result = adapter.DeleteUser(id);
            if (!result.Ok)
            {
                log.Error($"Cleanup DeleteUser failed for {id}: {result.Error}");
                log.Error($"MANUAL CLEANUP REQUIRED on tablet: delete user id {id}");
            }
            else
            {
                undo.Forget(id);
                log.Ok($"Deleted {id}");
            }
        }

        if (beforeIds != null)
        {
            try
            {
                var after = SessionUndo.SnapshotIds(adapter.ListUsers().Select(u => u.DeviceUserId));
                if (!SessionUndo.SnapshotsEqual(beforeIds, after))
                {
                    var diff = SessionUndo.SymmetricDifference(beforeIds, after);
                    log.Error($"Post-cleanup snapshot still differs: {string.Join(", ", diff)}");
                    if (pocUserId != null)
                    {
                        log.Error($"MANUAL CLEANUP REQUIRED on tablet: delete user id {pocUserId}");
                    }
                }
                else
                {
                    log.Ok("Cleanup snapshot matches start");
                }
            }
            catch (Exception ex)
            {
                log.Error($"Cleanup snapshot failed: {ex.Message}");
            }
        }

        try
        {
            adapter.Disconnect();
            log.Info("Disconnected");
        }
        catch (Exception ex)
        {
            log.Warn($"Disconnect: {ex.Message}");
        }

        await Task.CompletedTask.ConfigureAwait(false);
    }

    private static int RunSelfCheck(PocLogger log, PocOptions options)
    {
        log.Step("SELF-CHECK");
        if (options.UseMockAdapter || !OperatingSystem.IsWindows())
        {
            using var mock = new MockDeviceAdapter();
            var st = mock.Connect(new DeviceConnectionConfig("self", "127.0.0.1", 37777, "a", "b"));
            log.Info($"Mock connect ok={st.Ok}");
            log.Ok("Self-check complete (mock)");
            return st.Ok ? 0 : 4;
        }

        var nativeDir = WindowsNativeBootstrap.ResolveNativeDirectory(options.NativeDir);
        log.Info($"Native directory: {nativeDir}");
        if (!WindowsNativeBootstrap.TryConfigure(nativeDir, out var err))
        {
            log.Error(err);
            return 3;
        }

        log.Ok($"Native bootstrap ok ({WindowsNativeBootstrap.PrimaryLibrary} resolvable)");
        log.Ok("Self-check complete (no device login)");
        return 0;
    }

    private static void AssertOk(PocLogger log, DeviceCommandResult result, string op)
    {
        if (!result.Ok)
        {
            log.Error($"{op} failed: {result.Error}");
            throw new InvalidOperationException($"{op} failed: {result.Error}");
        }

        log.Ok($"{op} succeeded");
    }

    private static void PromptEnter(PocLogger log, string message)
    {
        log.Info(message);
        Console.Write("> ");
        Console.ReadLine();
    }

    private static void OnCancel(object? sender, ConsoleCancelEventArgs e)
    {
        e.Cancel = true;
        s_log?.Warn("Interrupted — running cleanup...");
        try
        {
            if (s_adapter != null && s_undo != null)
            {
                foreach (var id in s_undo.CreatedUserIds.Reverse())
                {
                    s_log?.Info($"Interrupt DeleteUser {id}");
                    var r = s_adapter.DeleteUser(id);
                    if (!r.Ok)
                    {
                        s_log?.Error($"MANUAL CLEANUP REQUIRED on tablet: delete user id {id}");
                    }
                }

                s_adapter.Disconnect();
            }
        }
        catch
        {
            // best-effort
        }

        Environment.Exit(130);
    }

    private static string ReadPassword()
    {
        if (Console.IsInputRedirected)
        {
            return (Console.In.ReadLine() ?? "").TrimEnd('\r', '\n');
        }

        Console.Write("Device password: ");
        var buffer = new System.Text.StringBuilder();
        while (true)
        {
            var key = Console.ReadKey(intercept: true);
            if (key.Key == ConsoleKey.Enter)
            {
                Console.WriteLine();
                break;
            }

            if (key.Key == ConsoleKey.Backspace)
            {
                if (buffer.Length > 0)
                {
                    buffer.Length--;
                }

                continue;
            }

            if (!char.IsControl(key.KeyChar))
            {
                buffer.Append(key.KeyChar);
            }
        }

        return buffer.ToString();
    }

    private static bool HasFlag(string[] args, string name) =>
        args.Any(a => string.Equals(a, name, StringComparison.OrdinalIgnoreCase));

    private static Dictionary<string, string?> EnvMap()
    {
        var map = new Dictionary<string, string?>(StringComparer.Ordinal);
        foreach (System.Collections.DictionaryEntry entry in Environment.GetEnvironmentVariables())
        {
            map[entry.Key.ToString() ?? ""] = entry.Value?.ToString();
        }

        return map;
    }
}
