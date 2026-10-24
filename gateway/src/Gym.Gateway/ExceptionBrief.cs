using System.Text;

namespace Gym.Gateway;

/// <summary>
/// A connection failure's type, message, and the first frames of each cause. The rest of a socket
/// stack is framework frames and is counted instead of printed.
/// </summary>
public static class ExceptionBrief
{
    public static string Of(Exception exception, int maxFrames = 8, int maxCauses = 4)
    {
        var text = new StringBuilder();
        var current = exception;
        var causes = 0;
        var limit = Math.Max(1, maxFrames);
        while (current != null && causes < Math.Max(1, maxCauses))
        {
            if (causes > 0)
            {
                text.Append(" ---> ");
            }

            text.Append(current.GetType().FullName).Append(": ").Append(current.Message);
            var frames = new System.Diagnostics.StackTrace(current, false).GetFrames() ?? [];
            var shown = Math.Min(limit, frames.Length);
            for (var i = 0; i < shown; i++)
            {
                var method = frames[i].GetMethod();
                text.AppendLine();
                text.Append("   at ").Append(method?.DeclaringType?.FullName).Append('.').Append(method?.Name);
            }

            if (frames.Length > shown)
            {
                text.AppendLine();
                text.Append("   ... ").Append(frames.Length - shown).Append(" more frames");
            }

            current = current.InnerException;
            causes++;
        }

        return text.ToString();
    }
}
