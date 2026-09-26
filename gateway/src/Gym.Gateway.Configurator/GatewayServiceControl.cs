using System.Diagnostics;
using System.IO;
using System.ServiceProcess;
using Microsoft.Win32;

namespace Gym.Gateway.Configurator;

/// <summary>Install / start the Gym Gateway Windows Service (requires elevation).</summary>
public static class GatewayServiceControl
{
    public const string ServiceName = "Gym Gateway";

    public static string DescribeStatus()
    {
        try
        {
            using var sc = new ServiceController(ServiceName);
            return $"Service '{ServiceName}': {sc.Status}";
        }
        catch (InvalidOperationException)
        {
            return $"Service '{ServiceName}' is not installed.";
        }
        catch (Exception ex)
        {
            return $"Service status unavailable: {ex.Message}";
        }
    }

    public static string EnsureInstalledAndRunning()
    {
        var exe = FindGatewayExe();
        if (exe == null)
        {
            var tried = string.Join(" | ", CandidateExePaths());
            return "Gym.Gateway.exe not found. Reinstall the MSI, then run Configurator from " +
                   @"C:\Program Files\Gym Gateway\. Looked in: " + tried;
        }

        try
        {
            using var existing = new ServiceController(ServiceName);
            _ = existing.Status;
            // Point service at the exe we found (repairs orphaned / wrong binPath installs).
            RunSc($"config \"{ServiceName}\" binPath= \"{exe}\"");
            return StartExisting(existing);
        }
        catch (InvalidOperationException)
        {
            // not installed
        }

        var create = RunSc($"create \"{ServiceName}\" binPath= \"{exe}\" start= auto DisplayName= \"Gym Gateway\"");
        if (create != 0)
        {
            return $"sc create failed (exit {create}). Ensure you are elevated.";
        }

        RunSc($"failure \"{ServiceName}\" reset= 86400 actions= restart/5000/restart/10000/restart/30000");
        RunSc($"description \"{ServiceName}\" \"Gym device gateway (TrueFace) connecting to the gym backend.\"");

        try
        {
            using var sc = new ServiceController(ServiceName);
            return StartExisting(sc);
        }
        catch (Exception ex)
        {
            return $"Service created but could not start: {ex.Message}";
        }
    }

    private static string StartExisting(ServiceController sc)
    {
        sc.Refresh();
        if (sc.Status is ServiceControllerStatus.Running or ServiceControllerStatus.StartPending)
        {
            return $"Service already {sc.Status}.";
        }

        sc.Start();
        sc.WaitForStatus(ServiceControllerStatus.Running, TimeSpan.FromSeconds(30));
        return "Service started.";
    }

    private static IEnumerable<string> CandidateExePaths()
    {
        var dir = AppContext.BaseDirectory;
        var list = new List<string>
        {
            Path.Combine(dir, "Gym.Gateway.exe"),
            Path.Combine(dir, "..", "Gym.Gateway", "Gym.Gateway.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Gym Gateway", "Gym.Gateway.exe"),
        };

        var pf64 = Environment.GetEnvironmentVariable("ProgramW6432");
        if (!string.IsNullOrWhiteSpace(pf64))
        {
            list.Add(Path.Combine(pf64, "Gym Gateway", "Gym.Gateway.exe"));
        }

        try
        {
            using var key = Registry.LocalMachine.OpenSubKey(@"Software\Gym\Gateway");
            var installDir = key?.GetValue("InstallDir") as string;
            if (!string.IsNullOrWhiteSpace(installDir))
            {
                list.Add(Path.Combine(installDir, "Gym.Gateway.exe"));
            }
        }
        catch
        {
            // ignore registry miss
        }

        return list;
    }

    private static string? FindGatewayExe() =>
        CandidateExePaths().Select(Path.GetFullPath).FirstOrDefault(File.Exists);

    private static int RunSc(string args)
    {
        using var process = Process.Start(new ProcessStartInfo
        {
            FileName = "sc.exe",
            Arguments = args,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true
        });
        process?.WaitForExit(15_000);
        return process?.ExitCode ?? -1;
    }
}
