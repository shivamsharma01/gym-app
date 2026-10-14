namespace Gym.Gateway.Execution;

/// <summary>What one reader's scans remember: photo hashes by reader checksum, and what the server already has.</summary>
public sealed record ReaderScanMemory(FaceChecksumCache Faces, ScanUploadState Uploads)
{
    /// <summary>An unchanged list is still sent this often, so the server's own retries keep running.</summary>
    public static readonly TimeSpan DefaultRefresh = TimeSpan.FromHours(1);

    public static ReaderScanMemory InMemory(TimeProvider? clock = null) =>
        new(new FaceChecksumCache(null), new ScanUploadState(DefaultRefresh, clock));
}
