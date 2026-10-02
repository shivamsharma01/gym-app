using TrueFaceWindowsPOC;
using Xunit;

namespace TrueFaceWindowsPOC.Tests;

public class CsvImportTests
{
    private const string Header = "deviceUserId,name,unnamed_2,field_1,field_2,record_id,version,valid_from,valid_to,photo_base64";

    [Fact]
    public void LoadsBackupRowsWithQuotedNamesAndPhotos()
    {
        var path = Write(
            Header,
            "16,Deepak Nandal,,0,200,18574705,2,2018-01-01 00:00:00,2026-08-05 00:00:00,",
            "1067,\"Dahiya, Ishant\",,0,200,0,2,2018-01-01 00:00:00,2026-10-20 00:00:00,/9j/AA==");

        var rows = CsvImport.Load(path);

        Assert.Equal(2, rows.Count);
        Assert.Equal("16", rows[0].DeviceUserId);
        Assert.Null(rows[0].Photo);
        Assert.Equal(new DateTime(2026, 8, 5), rows[0].ValidTo);
        Assert.Equal("Dahiya, Ishant", rows[1].Name);
        Assert.Equal(new byte[] { 0xFF, 0xD8, 0xFF, 0x00 }, rows[1].Photo);
    }

    [Fact]
    public void RejectsDuplicateIds()
    {
        var path = Write(
            Header,
            "16,A,,0,200,0,2,2018-01-01 00:00:00,2026-08-05 00:00:00,",
            "16,B,,0,200,0,2,2018-01-01 00:00:00,2026-08-05 00:00:00,");

        var ex = Assert.Throws<PocConfigException>(() => CsvImport.Load(path));
        Assert.Contains("more than once", ex.Message);
    }

    [Fact]
    public void RejectsUnparseableDates()
    {
        var path = Write(Header, "16,A,,0,200,0,2,01/01/2018,2026-08-05 00:00:00,");

        var ex = Assert.Throws<PocConfigException>(() => CsvImport.Load(path));
        Assert.Contains("valid_from", ex.Message);
    }

    [Fact]
    public void ParsesImportFlags()
    {
        var opts = PocOptions.Parse(["--import-csv", "C:\\gym\\missing.csv", "--apply"], new Dictionary<string, string?>());

        Assert.Equal("C:\\gym\\missing.csv", opts.ImportCsvPath);
        Assert.True(opts.Apply);
        Assert.False(PocOptions.Parse(["--import-csv", "x.csv"], new Dictionary<string, string?>()).Apply);
    }

    private static string Write(params string[] lines)
    {
        var path = Path.Combine(Path.GetTempPath(), $"import-{Guid.NewGuid():N}.csv");
        File.WriteAllText(path, string.Join("\r\n", lines) + "\r\n");
        return path;
    }
}
