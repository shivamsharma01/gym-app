namespace Gym.Gateway.SdkProbe;

internal enum Verdict
{
    Unknown,
    Observed,
    NotObserved
}

internal enum Door
{
    Opened,
    Shut
}

/// <summary>What the operator reported after one door walk.</summary>
internal enum WalkAnswer
{
    Opened,
    Shut,
    Unclear,
    Skipped
}

/// <summary>One P-check from the architecture document, with its verdict and the evidence behind it.</summary>
internal sealed class GateResult(string id, string question)
{
    public string Id { get; } = id;
    public string Question { get; } = question;
    public Verdict Verdict { get; private set; }
    public string Summary { get; private set; } = "not run";
    public List<string> Evidence { get; } = [];

    public void Set(Verdict verdict, string summary)
    {
        Verdict = verdict;
        Summary = summary;
    }
}

/// <summary>
/// Turns observations into verdicts without filling gaps with a likely answer. A skipped step, a failed read,
/// a single trial, or two signals that disagree give no value, and a check without a value is UNKNOWN.
/// An SDK call that returned true is never evidence on its own; only a read-back, a walk, or a punch is.
/// </summary>
internal static class GateVerdicts
{
    public static string Label(Verdict verdict) => verdict switch
    {
        Verdict.Observed => "OBSERVED",
        Verdict.NotObserved => "NOT OBSERVED",
        _ => "UNKNOWN"
    };

    /// <summary>
    /// One door walk. Either the operator's answer or the stored punch is enough, but they must not contradict
    /// each other. A walk the operator skipped is no evidence even if a punch turns up.
    /// </summary>
    public static Door? Walk(WalkAnswer answer, bool? punchGranted)
    {
        Door? punch = punchGranted switch
        {
            true => Door.Opened,
            false => Door.Shut,
            _ => null
        };
        switch (answer)
        {
            case WalkAnswer.Skipped:
                return null;
            case WalkAnswer.Unclear:
                return punch;
            default:
                var seen = answer == WalkAnswer.Opened ? Door.Opened : Door.Shut;
                return punch == null || punch == seen ? seen : null;
        }
    }

    /// <summary>The value every trial agreed on; fewer than two trials, a missing trial, or any difference gives null.</summary>
    public static T? Repeated<T>(IReadOnlyList<T?> trials) where T : struct
    {
        if (trials.Count < 2 || trials.Any(t => t == null))
        {
            return null;
        }

        var first = trials[0]!.Value;
        return trials.All(t => EqualityComparer<T>.Default.Equals(t!.Value, first)) ? first : null;
    }

    public static string? Repeated(IReadOnlyList<string?> trials)
    {
        if (trials.Count < 2 || trials.Any(t => t == null))
        {
            return null;
        }

        return trials.All(t => string.Equals(t, trials[0], StringComparison.Ordinal)) ? trials[0] : null;
    }

    /// <summary>Repeated true is OBSERVED, repeated false is NOT OBSERVED, anything else is UNKNOWN.</summary>
    public static Verdict FromTrials(IReadOnlyList<bool?> trials) => Repeated(trials) switch
    {
        true => Verdict.Observed,
        false => Verdict.NotObserved,
        _ => Verdict.Unknown
    };

    /// <summary>A statement made of parts holds only if every part was observed; one part seen to fail makes it NOT OBSERVED.</summary>
    public static Verdict AllOf(IEnumerable<Verdict> parts)
    {
        var list = parts.ToList();
        if (list.Count == 0)
        {
            return Verdict.Unknown;
        }

        if (list.Contains(Verdict.NotObserved))
        {
            return Verdict.NotObserved;
        }

        return list.All(v => v == Verdict.Observed) ? Verdict.Observed : Verdict.Unknown;
    }

    /// <summary>Checks the rules above without a reader; returns one line per failed rule.</summary>
    public static List<string> SelfCheck()
    {
        var failures = new List<string>();

        void Expect<T>(string rule, T actual, T expected)
        {
            if (!EqualityComparer<T>.Default.Equals(actual, expected))
            {
                failures.Add($"{rule}: got {actual?.ToString() ?? "null"}, expected {expected?.ToString() ?? "null"}");
            }
        }

        Expect("skipped walk with a denied punch", Walk(WalkAnswer.Skipped, false), (Door?)null);
        Expect("operator saw open, punch denied", Walk(WalkAnswer.Opened, false), (Door?)null);
        Expect("operator saw shut, no punch", Walk(WalkAnswer.Shut, null), Door.Shut);
        Expect("operator saw shut, punch denied", Walk(WalkAnswer.Shut, false), Door.Shut);
        Expect("operator unsure, punch granted", Walk(WalkAnswer.Unclear, true), Door.Opened);
        Expect("operator unsure, no punch", Walk(WalkAnswer.Unclear, null), (Door?)null);
        Expect("single trial", Repeated(new bool?[] { true }), (bool?)null);
        Expect("missing trial", Repeated(new bool?[] { true, null }), (bool?)null);
        Expect("trials disagree", Repeated(new bool?[] { true, false }), (bool?)null);
        Expect("trials agree", Repeated(new bool?[] { false, false }), false);
        Expect("text trials agree", Repeated(new string?[] { "a", "a" }), "a");
        Expect("text single trial", Repeated(new string?[] { "a" }), null);
        byte[] jpeg = [0xFF, 0xD8, 1, 2, 3, 0xFF, 0xD9];
        foreach (var size in new[] { 11, 1000, 65537 + 7, 65537 + 9, 200 * 1024 })
        {
            var padded = GateSuite.PadJpeg(jpeg, size);
            Expect($"padded photo size {size}", padded?.Length, (int?)size);
            Expect($"padded photo {size} keeps the image", padded == null ? null : Convert.ToHexString(padded[^5..]), Convert.ToHexString(jpeg[2..]));
        }

        Expect("photo already too large to pad", GateSuite.PadJpeg(jpeg, 8), null);
        Expect("two true trials", FromTrials([true, true]), Verdict.Observed);
        Expect("one true trial", FromTrials([true]), Verdict.Unknown);
        Expect("observed and unknown parts", AllOf([Verdict.Observed, Verdict.Unknown]), Verdict.Unknown);
        Expect("one part failed", AllOf([Verdict.Observed, Verdict.NotObserved, Verdict.Unknown]), Verdict.NotObserved);
        Expect("no parts", AllOf([]), Verdict.Unknown);
        return failures;
    }
}
