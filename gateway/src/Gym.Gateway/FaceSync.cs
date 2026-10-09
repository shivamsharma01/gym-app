using System.Collections.Concurrent;
using System.Diagnostics;
using System.Security.Cryptography;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace Gym.Gateway;

/// <summary>REST transfer of face images (never inside WebSocket frames).</summary>
public interface IFaceTransfer
{
    Task<FaceDownload> DownloadFaceAsync(string memberId, int version, CancellationToken cancellationToken);

    /// <summary>Uploads an image read from a device. Never throws for network failures.</summary>
    Task<FaceUpload> UploadFaceAsync(byte[] jpegBytes, CancellationToken cancellationToken);
}

public sealed record FaceDownload(bool Ok, byte[]? Bytes, string? Error);

/// <summary>
/// Upload outcome. <see cref="Rejected"/> means the server will never accept this image (e.g. not
/// a valid photo); otherwise a missing id is transient (server unreachable) and should be retried.
/// </summary>
public sealed record FaceUpload(string? UploadId, bool Rejected = false)
{
    public static readonly FaceUpload Transient = new(UploadId: null);
}

public static class FaceHash
{
    public static string Sha256Hex(byte[] bytes) => Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
}

/// <summary>
/// One lock per device so command dispatch does not interleave SDK calls. Each holder
/// names its task, so logs can say what a reader is busy with and who had to wait for it.
/// </summary>
public sealed class DeviceLocks
{
    private static readonly TimeSpan NoticeableWait = TimeSpan.FromSeconds(2);
    private static readonly TimeSpan LongTask = TimeSpan.FromSeconds(10);

    private readonly ConcurrentDictionary<string, SemaphoreSlim> _locks = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, (string Task, DateTimeOffset Since)> _busy = new(StringComparer.Ordinal);
    private readonly ILogger _log;

    public DeviceLocks(ILogger? log = null)
    {
        _log = log ?? NullLogger.Instance;
    }

    public SemaphoreSlim For(string deviceId) => _locks.GetOrAdd(deviceId, _ => new SemaphoreSlim(1, 1));

    /// <summary>Waits for the reader and marks it busy with <paramref name="task"/> until the lease is disposed.</summary>
    public async Task<IDisposable> AcquireAsync(string deviceId, string task, CancellationToken cancellationToken = default)
    {
        var gate = For(deviceId);
        if (!await gate.WaitAsync(0, cancellationToken).ConfigureAwait(false))
        {
            var holder = BusyWith(deviceId) ?? "another task";
            var waited = Stopwatch.StartNew();
            await gate.WaitAsync(cancellationToken).ConfigureAwait(false);
            if (waited.Elapsed >= NoticeableWait)
            {
                _log.LogInformation("Reader {DeviceId}: {Task} waited {Seconds} s because the reader was busy with {Holder}",
                    deviceId, task, (long)waited.Elapsed.TotalSeconds, holder);
            }
        }

        _busy[deviceId] = (task, DateTimeOffset.UtcNow);
        return new Lease(this, deviceId, gate);
    }

    /// <summary>What the reader is doing right now and for how long, or null when it is idle.</summary>
    public string? BusyWith(string deviceId) =>
        _busy.TryGetValue(deviceId, out var busy)
            ? $"{busy.Task} (for {(long)(DateTimeOffset.UtcNow - busy.Since).TotalSeconds} s)"
            : null;

    private void Release(string deviceId, SemaphoreSlim gate)
    {
        if (_busy.TryRemove(deviceId, out var busy))
        {
            var took = DateTimeOffset.UtcNow - busy.Since;
            if (took >= LongTask)
            {
                _log.LogInformation("Reader {DeviceId}: finished {Task} after {Seconds} s", deviceId, busy.Task,
                    (long)took.TotalSeconds);
            }
        }

        gate.Release();
    }

    private sealed class Lease(DeviceLocks owner, string deviceId, SemaphoreSlim gate) : IDisposable
    {
        private int _released;

        public void Dispose()
        {
            if (Interlocked.Exchange(ref _released, 1) == 0)
            {
                owner.Release(deviceId, gate);
            }
        }
    }
}
