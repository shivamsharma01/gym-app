using Gym.Gateway;
using Xunit;

namespace Gym.Gateway.Tests;

public class ExceptionBriefTests
{
    [Fact]
    public void A_connection_failure_keeps_the_type_message_and_inner_cause_and_drops_the_long_tail()
    {
        Exception? caught = null;
        try
        {
            Deep(20);
        }
        catch (Exception ex)
        {
            caught = ex;
        }

        var text = ExceptionBrief.Of(caught!);

        Assert.Contains("InvalidOperationException: socket closed", text);
        Assert.Contains("IOException: reset", text);
        Assert.Contains("more frames", text);
        Assert.DoesNotContain("   at System.Runtime", text);
    }

    private static void Deep(int left)
    {
        if (left == 0)
        {
            throw new InvalidOperationException("socket closed", new IOException("reset"));
        }

        Deep(left - 1);
    }
}
