namespace Gym.Gateway.Adapters;

public static class DeviceAdapterFactory
{
    public static IDeviceAdapter Create(string adapterName) =>
        adapterName.Equals("TrueFace", StringComparison.OrdinalIgnoreCase)
            ? new TrueFaceDeviceAdapter()
            : new MockDeviceAdapter();
}
