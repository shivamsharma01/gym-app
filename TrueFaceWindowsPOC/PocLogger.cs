namespace TrueFaceWindowsPOC;

/// <summary>Console + file logger. Never write passwords or face image bytes.</summary>
public sealed class PocLogger : IDisposable
{
    private readonly object _gate = new();
    private readonly StreamWriter _file;
    public string LogPath { get; }

    public PocLogger(string? directory = null)
    {
        var dir = directory ?? Directory.GetCurrentDirectory();
        Directory.CreateDirectory(dir);
        var stamp = DateTime.UtcNow.ToString("yyyyMMdd-HHmmss");
        LogPath = Path.Combine(dir, $"poc-run-{stamp}.log");
        _file = new StreamWriter(new FileStream(LogPath, FileMode.Create, FileAccess.Write, FileShare.Read))
        {
            AutoFlush = true
        };
        Info($"Log file: {LogPath}");
    }

    public void Info(string message) => Write("INFO", message);

    public void Ok(string message) => Write("OK", message);

    public void Warn(string message) => Write("WARN", message);

    public void Error(string message) => Write("ERROR", message);

    public void Step(string title)
    {
        Write("STEP", new string('=', 8) + " " + title + " " + new string('=', 8));
    }

    public void Dispose()
    {
        lock (_gate)
        {
            _file.Dispose();
        }
    }

    private void Write(string level, string message)
    {
        var line = $"[{DateTimeOffset.Now:O}] [{level}] {message}";
        lock (_gate)
        {
            Console.WriteLine(line);
            _file.WriteLine(line);
        }
    }
}
