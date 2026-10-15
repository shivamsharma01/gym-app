using Gym.Gateway.Adapters;

namespace Gym.Gateway;

/// <summary>
/// Opens the reader used by <see cref="Execution.ReaderWorker"/>. A null result means this device
/// has no reader, so a desired revision must not be acknowledged.
/// </summary>
public interface IReaderAdapterFactory
{
    IReaderAdapter? Open(string deviceId, IDeviceAdapter? connected);
}

/// <summary>Production reader: the device adapter already connected for that reader.</summary>
public sealed class ConnectedReaderAdapterFactory : IReaderAdapterFactory
{
    public IReaderAdapter? Open(string deviceId, IDeviceAdapter? connected) =>
        connected == null ? null : new DeviceReaderAdapter(connected);
}
