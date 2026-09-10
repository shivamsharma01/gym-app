using TrueFaceLinuxPOC;
using Xunit;

namespace TrueFaceLinuxPOC.Tests;

public class NativeBootstrapTests
{
    [Fact]
    public void PrimaryLibraryNameIsVendorLinuxSoname()
    {
        Assert.Equal("libdhnetsdk.so", NativeBootstrap.PrimaryLibrary);
    }

    [Fact]
    public void ConfiguredNativeDirIsUsedAsIs()
    {
        var dir = Path.Combine(Path.GetTempPath(), "tf-native-test");
        var resolved = NativeBootstrap.ResolveNativeDirectory(dir);
        Assert.Equal(Path.GetFullPath(dir), resolved);
    }

    [Fact]
    public void MissingPrimaryLibraryIsReportedNotThrown()
    {
        var empty = Directory.CreateTempSubdirectory("tf-empty-native");
        var handle = NativeBootstrap.TryLoadPrimary(empty.FullName, out var error);
        Assert.Equal(IntPtr.Zero, handle);
        Assert.Contains("not found", error, StringComparison.OrdinalIgnoreCase);
    }
}
