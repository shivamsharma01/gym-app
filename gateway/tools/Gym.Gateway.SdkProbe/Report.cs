using System.Text;

namespace Gym.Gateway.SdkProbe;

/// <summary>Writes every line to the console and to report.txt; CSVs go next to it.</summary>
internal sealed class Report : IDisposable
{
    private readonly StreamWriter _file;

    public Report(string directory)
    {
        Directory.CreateDirectory(directory);
        Folder = directory;
        _file = new StreamWriter(Path.Combine(directory, "report.txt"), false, new UTF8Encoding(false)) { AutoFlush = true };
    }

    public string Folder { get; }

    public void Line(string text = "")
    {
        Console.WriteLine(text);
        _file.WriteLine(text);
    }

    public void Section(string title)
    {
        Line();
        Line(new string('=', 78));
        Line(title);
        Line(new string('=', 78));
    }

    public void Sub(string title)
    {
        Line();
        Line("--- " + title);
    }

    public void Csv(string fileName, IEnumerable<string> header, IEnumerable<IEnumerable<string?>> rows)
    {
        var path = Path.Combine(Folder, fileName);
        using var w = new StreamWriter(path, false, new UTF8Encoding(false));
        w.WriteLine(string.Join(",", header.Select(Escape)));
        foreach (var row in rows)
        {
            w.WriteLine(string.Join(",", row.Select(Escape)));
        }

        Line($"  (wrote {fileName})");
    }

    private static string Escape(string? value)
    {
        value ??= "";
        return value.IndexOfAny([',', '"', '\n', '\r']) >= 0 ? "\"" + value.Replace("\"", "\"\"") + "\"" : value;
    }

    public void Dispose() => _file.Dispose();
}
