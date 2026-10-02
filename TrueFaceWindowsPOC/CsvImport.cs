using System.Globalization;
using System.Text;
using Gym.Gateway.Adapters;

namespace TrueFaceWindowsPOC;

/// <summary>One person from a device backup CSV. Dates are the device's wall-clock values.</summary>
public sealed record ImportRow(int Line, string DeviceUserId, string Name, DateTime ValidFrom, DateTime ValidTo, byte[]? Photo);

/// <summary>
/// Re-creates people that a device restore skipped, using the same adapter calls the POC used for its
/// throwaway user. Ids already on the device are never written, so a rerun only adds what is still missing.
/// </summary>
public static class CsvImport
{
    private const int MaxConsecutiveFailures = 3;
    private static readonly string[] DateFormats = ["yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd"];

    public static IReadOnlyList<ImportRow> Load(string path)
    {
        if (!File.Exists(path))
        {
            throw new PocConfigException($"CSV not found: {path}");
        }

        var records = ParseCsv(File.ReadAllText(path, Encoding.UTF8));
        if (records.Count < 2)
        {
            throw new PocConfigException("CSV has no data rows");
        }

        var header = records[0].Select(h => h.Trim().TrimStart('\uFEFF')).ToList();
        int Column(string name)
        {
            var index = header.FindIndex(h => string.Equals(h, name, StringComparison.OrdinalIgnoreCase));
            return index >= 0 ? index : throw new PocConfigException($"CSV is missing column '{name}'");
        }

        var idCol = Column("deviceUserId");
        var nameCol = Column("name");
        var fromCol = Column("valid_from");
        var toCol = Column("valid_to");
        var photoCol = header.FindIndex(h => string.Equals(h, "photo_base64", StringComparison.OrdinalIgnoreCase));

        var rows = new List<ImportRow>();
        var seen = new HashSet<string>(StringComparer.Ordinal);
        for (var i = 1; i < records.Count; i++)
        {
            var record = records[i];
            var line = i + 1;
            if (record.All(string.IsNullOrWhiteSpace))
            {
                continue;
            }

            string Field(int col) => col < record.Count ? record[col].Trim() : "";

            var id = Field(idCol);
            if (id.Length == 0)
            {
                throw new PocConfigException($"Row {line}: deviceUserId is empty");
            }

            if (!seen.Add(id))
            {
                throw new PocConfigException($"Row {line}: deviceUserId {id} appears more than once");
            }

            var name = Field(nameCol);
            if (name.Length == 0)
            {
                throw new PocConfigException($"Row {line}: name is empty for id {id}");
            }

            var from = ParseDate(Field(fromCol), line, "valid_from");
            var to = ParseDate(Field(toCol), line, "valid_to");
            if (to < from)
            {
                throw new PocConfigException($"Row {line}: valid_to is before valid_from for id {id}");
            }

            byte[]? photo = null;
            var photoText = photoCol >= 0 ? Field(photoCol) : "";
            if (photoText.Length > 0)
            {
                try
                {
                    photo = Convert.FromBase64String(photoText);
                }
                catch (FormatException)
                {
                    throw new PocConfigException($"Row {line}: photo_base64 is not valid base64 for id {id}");
                }
            }

            rows.Add(new ImportRow(line, id, name, from, to, photo));
        }

        return rows;
    }

    public static int Run(PocLogger log, IDeviceAdapter adapter, IReadOnlyList<ImportRow> rows, bool apply, bool skipFaces)
    {
        log.Step(apply ? "IMPORT (APPLY)" : "IMPORT (DRY RUN — nothing is written)");

        var onDevice = adapter.ListUsers().ToDictionary(u => u.DeviceUserId, StringComparer.Ordinal);
        log.Info($"Users on device before import={onDevice.Count}");
        if (onDevice.Count == 0)
        {
            log.Error("The device returned no users. Refusing to write, because an unreadable list cannot be told apart from an empty one.");
            return 30;
        }

        int created = 0, wouldCreate = 0, skipped = 0, failed = 0, faces = 0, facesFailed = 0, consecutive = 0;
        var failedIds = new List<string>();
        var expired = rows.Count(r => r.ValidTo.Date < DateTime.Today);
        if (expired > 0)
        {
            log.Warn($"{expired} of {rows.Count} rows have a valid_to before today; they are added as in the backup and the door will deny them until renewed.");
        }

        foreach (var row in rows)
        {
            var label = $"id={row.DeviceUserId} name=\"{row.Name}\" valid={row.ValidFrom:yyyy-MM-dd}..{row.ValidTo:yyyy-MM-dd} face={(row.Photo == null ? "no" : "yes")}";
            if (onDevice.TryGetValue(row.DeviceUserId, out var existing))
            {
                skipped++;
                var note = string.Equals(existing.Name, row.Name, StringComparison.Ordinal)
                    ? ""
                    : $" (device name is \"{existing.Name}\", left unchanged)";
                log.Info($"SKIP already on device {label}{note}");
                continue;
            }

            if (!apply)
            {
                wouldCreate++;
                log.Info($"WOULD CREATE {label}");
                continue;
            }

            var result = adapter.CreateUser(new DeviceUserMutation(
                row.DeviceUserId,
                Name: row.Name,
                Enabled: true,
                ValidFrom: AsWallClock(row.ValidFrom),
                ValidTo: AsWallClock(row.ValidTo)));
            var check = result.Ok ? adapter.GetUser(row.DeviceUserId) : null;
            if (!result.Ok || check == null)
            {
                failed++;
                consecutive++;
                failedIds.Add(row.DeviceUserId);
                log.Error($"FAILED {label}: {(result.Ok ? "created but not readable afterwards" : result.Error)}");
                if (consecutive >= MaxConsecutiveFailures)
                {
                    log.Error($"Stopping after {MaxConsecutiveFailures} failures in a row. Check the device connection, then rerun; added users are skipped.");
                    break;
                }

                continue;
            }

            consecutive = 0;
            created++;
            onDevice[row.DeviceUserId] = check;
            log.Ok($"CREATED {label} (device shows name=\"{check.Name}\" valid={check.ValidFrom:yyyy-MM-dd}..{check.ValidTo:yyyy-MM-dd})");

            if (row.Photo != null && !skipFaces)
            {
                var face = adapter.UpsertFace(row.DeviceUserId, row.Photo);
                if (face.Ok)
                {
                    faces++;
                    log.Ok($"FACE uploaded id={row.DeviceUserId} bytes={row.Photo.Length}");
                }
                else
                {
                    facesFailed++;
                    log.Warn($"FACE failed id={row.DeviceUserId}: {face.Error} (user was created; enroll the face on the tablet)");
                }
            }
        }

        log.Step("IMPORT SUMMARY");
        log.Info($"rows={rows.Count} alreadyOnDevice={skipped} " +
                 (apply ? $"created={created} failed={failed} faces={faces} facesFailed={facesFailed}" : $"wouldCreate={wouldCreate}"));
        if (failedIds.Count > 0)
        {
            log.Error($"Failed ids: {string.Join(", ", failedIds)}");
        }

        if (apply)
        {
            var after = adapter.ListUsers().Select(u => u.DeviceUserId).ToHashSet(StringComparer.Ordinal);
            var missing = rows.Where(r => !after.Contains(r.DeviceUserId)).Select(r => r.DeviceUserId).ToList();
            log.Info($"Users on device after import={after.Count}");
            if (missing.Count > 0)
            {
                log.Error($"Still missing on device: {string.Join(", ", missing)}");
                return 31;
            }

            log.Ok("Every CSV id is now on the device");
        }
        else
        {
            log.Info("Dry run only. Rerun with --apply to write.");
        }

        return failed > 0 ? 31 : 0;
    }

    /// <summary>The adapter writes UtcDateTime to the device, so a zero offset keeps the backup's wall-clock date.</summary>
    private static DateTimeOffset AsWallClock(DateTime value) =>
        new(DateTime.SpecifyKind(value, DateTimeKind.Unspecified), TimeSpan.Zero);

    private static DateTime ParseDate(string text, int line, string column)
    {
        if (DateTime.TryParseExact(text, DateFormats, CultureInfo.InvariantCulture, DateTimeStyles.None, out var value))
        {
            return value;
        }

        throw new PocConfigException($"Row {line}: {column} '{text}' is not yyyy-MM-dd[ HH:mm:ss]");
    }

    internal static List<List<string>> ParseCsv(string text)
    {
        var records = new List<List<string>>();
        var record = new List<string>();
        var field = new StringBuilder();
        var quoted = false;
        for (var i = 0; i < text.Length; i++)
        {
            var c = text[i];
            if (quoted)
            {
                if (c == '"' && i + 1 < text.Length && text[i + 1] == '"')
                {
                    field.Append('"');
                    i++;
                }
                else if (c == '"')
                {
                    quoted = false;
                }
                else
                {
                    field.Append(c);
                }

                continue;
            }

            switch (c)
            {
                case '"':
                    quoted = true;
                    break;
                case ',':
                    record.Add(field.ToString());
                    field.Clear();
                    break;
                case '\r':
                    break;
                case '\n':
                    record.Add(field.ToString());
                    field.Clear();
                    records.Add(record);
                    record = [];
                    break;
                default:
                    field.Append(c);
                    break;
            }
        }

        if (field.Length > 0 || record.Count > 0)
        {
            record.Add(field.ToString());
            records.Add(record);
        }

        return records;
    }
}
