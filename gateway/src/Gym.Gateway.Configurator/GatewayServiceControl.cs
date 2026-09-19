using System.Diagnostics;
using System.IO;
using System.ServiceProcess;

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
            return "Gateway executable not found next to the configurator. Install via MSI or place Gym.Gateway.exe alongside this app.";
        }

        try
        {
            using var existing = new ServiceController(ServiceName);
            _ = existing.Status;
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

    private static string? FindGatewayExe()
    {
        var dir = AppContext.BaseDirectory;
        var candidates = new[]
        {
            Path.Combine(dir, "Gym.Gateway.exe"),
            Path.Combine(dir, "..", "Gym.Gateway", "Gym.Gateway.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Gym Gateway", "Gym.Gateway.exe")
        };
        return candidates.Select(Path.GetFullPath).FirstOrDefault(File.Exists);
    }

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
        if (process == null)
        {
            return -1;
        }

        process.WaitForExit(15_000);
        return process.ExitCode;
    }
}
