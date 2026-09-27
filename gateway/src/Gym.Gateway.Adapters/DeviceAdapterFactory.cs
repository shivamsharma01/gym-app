namespace Gym.Gateway.Adapters;

public static class DeviceAdapterFactory
{
    public static IDeviceAdapter Create(string adapterName)
    {
        if (adapterName.Equals("TrueFace", StringComparison.OrdinalIgnoreCase))
        {
            return new TrueFaceDeviceAdapter();
        }

        if (adapterName.Equals("Remote", StringComparison.OrdinalIgnoreCase) ||
            adapterName.Equals("Simulator", StringComparison.OrdinalIgnoreCase) ||
            adapterName.Equals("Http", StringComparison.OrdinalIgnoreCase))
        {
            return new RemoteDeviceAdapter();
        }

        return new MockDeviceAdapter();
    }
}
