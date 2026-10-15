using System.Reflection;
using NetSDKCS;

namespace Gym.Gateway.SdkProbe;

/// <summary>Every public field of NET_ACCESS_USER_INFO as text, so two reads can be compared field by field.</summary>
internal static class UserFields
{
    private static readonly FieldInfo[] Fields = typeof(NET_ACCESS_USER_INFO)
        .GetFields(BindingFlags.Public | BindingFlags.Instance)
        .Where(f => f.FieldType != typeof(IntPtr) && f.Name != "byReserved")
        .ToArray();

    private static readonly HashSet<string> EmptyValues = ["", "0", "False", "(zero)", "[]", "UNKNOWN"];

    public static Dictionary<string, string> Dump(NET_ACCESS_USER_INFO user)
    {
        object boxed = user;
        return Fields.ToDictionary(f => f.Name, f => Format(f.GetValue(boxed)), StringComparer.Ordinal);
    }

    public static IEnumerable<string> NonEmpty(Dictionary<string, string> dump) =>
        dump.Where(kv => !IsEmpty(kv.Value)).Select(kv => $"{kv.Key}={kv.Value}");

    public static bool IsEmpty(string value) =>
        EmptyValues.Contains(value) || value.EndsWith("_UNKNOWN", StringComparison.Ordinal);

    public static List<string> Diff(Dictionary<string, string> before, Dictionary<string, string> after, ICollection<string>? ignore = null) =>
        after.Where(kv => ignore?.Contains(kv.Key) != true && (!before.TryGetValue(kv.Key, out var old) || old != kv.Value))
            .Select(kv => $"{kv.Key}: {Quote(before.GetValueOrDefault(kv.Key))} -> {Quote(kv.Value)}")
            .ToList();

    private static string Quote(string? value) => value == null ? "(missing)" : value.Length == 0 ? "\"\"" : value;

    private static string Format(object? value) =>
        value switch
        {
            null => "",
            string s => s.TrimEnd('\0', ' '),
            NET_TIME t => ReaderSession.Raw(t),
            int[] a => Array(a.Select(x => x.ToString()).ToArray(), a.Select(x => x != 0).ToArray()),
            uint[] a => Array(a.Select(x => x.ToString()).ToArray(), a.Select(x => x != 0).ToArray()),
            byte[] a => Array(a.Select(x => x.ToString()).ToArray(), a.Select(x => x != 0).ToArray()),
            Enum e => e.ToString(),
            bool or int or uint or long or short or byte or double or float => Convert.ToString(value, System.Globalization.CultureInfo.InvariantCulture) ?? "",
            ValueType => Nested(value),
            _ => value.ToString() ?? ""
        };

    private static string Array(string[] items, bool[] nonZero)
    {
        var last = System.Array.FindLastIndex(nonZero, x => x);
        return last < 0 ? "[]" : "[" + string.Join(",", items.Take(last + 1)) + "]";
    }

    private static string Nested(object value)
    {
        var parts = value.GetType().GetFields(BindingFlags.Public | BindingFlags.Instance)
            .Where(f => f.FieldType != typeof(IntPtr))
            .Select(f => (f.Name, Value: Format(f.GetValue(value))))
            .Where(p => !EmptyValues.Contains(p.Value))
            .Select(p => $"{p.Name}={p.Value}")
            .ToList();
        return parts.Count == 0 ? "(zero)" : "{" + string.Join(" ", parts) + "}";
    }
}
