using TrueFaceLinuxPOC;
using Xunit;

namespace TrueFaceLinuxPOC.Tests;

public class PocOptionsTests
{
    [Fact]
    public void ParsesCommandLineOverEnvironment()
    {
        var env = new Dictionary<string, string?>
        {
            ["TRUEFACE_IP"] = "10.0.0.1",
            ["TRUEFACE_PORT"] = "1",
            ["TRUEFACE_USERNAME"] = "env-user"
        };

        var opts = PocOptions.Parse(["--ip", "192.0.2.10", "--port", "37777", "--username", "admin"], env);

        Assert.Equal("192.0.2.10", opts.Ip);
        Assert.Equal((ushort)37777, opts.Port);
        Assert.Equal("admin", opts.Username);
        Assert.True(opts.HasConnectionTarget);
    }

    [Fact]
    public void ReadsEnvironmentWhenArgsOmitted()
    {
        var env = new Dictionary<string, string?>
        {
            ["TRUEFACE_IP"] = "192.0.2.20",
            ["TRUEFACE_PORT"] = "37777",
            ["TRUEFACE_USERNAME"] = "operator",
            ["TRUEFACE_PASSWORD"] = "secret-value"
        };

        var opts = PocOptions.Parse([], env);

        Assert.Equal("192.0.2.20", opts.Ip);
        Assert.Equal("operator", opts.Username);
        Assert.Equal("secret-value", opts.Password);
        Assert.DoesNotContain("secret-value", opts.ToString());
        Assert.Contains("password=***", opts.ToString());
    }

    [Fact]
    public void DefaultsPortTo37777()
    {
        var opts = PocOptions.Parse(["--ip", "192.0.2.1", "--username", "admin"], new Dictionary<string, string?>());
        Assert.Equal((ushort)37777, opts.Port);
    }

    [Fact]
    public void RejectsInvalidPort()
    {
        var ex = Assert.Throws<PocConfigException>(
            () => PocOptions.Parse(["--ip", "192.0.2.1", "--port", "not-a-port", "--username", "a"],
                new Dictionary<string, string?>()));
        Assert.Contains("Invalid port", ex.Message);
    }

    [Fact]
    public void RejectsZeroPort()
    {
        Assert.Throws<PocConfigException>(
            () => PocOptions.Parse(["--port", "0"], new Dictionary<string, string?>()));
    }

    [Fact]
    public void MissingIpAndUsernameIsNotAConnectionTarget()
    {
        var opts = PocOptions.Parse([], new Dictionary<string, string?>());
        Assert.False(opts.HasConnectionTarget);
    }

    [Fact]
    public void PasswordOptionRequiresValue()
    {
        Assert.Throws<PocConfigException>(
            () => PocOptions.Parse(["--password"], new Dictionary<string, string?>()));
    }

    [Fact]
    public void SelfCheckFlagDoesNotRequireDevice()
    {
        var opts = PocOptions.Parse(["--self-check"], new Dictionary<string, string?>());
        Assert.True(opts.SelfCheckOnly);
        Assert.False(opts.HasConnectionTarget);
    }
}
