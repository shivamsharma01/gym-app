namespace Gym.Gateway.Adapters;

public static class DeviceAdapterFactory
{
    public static IDeviceAdapter Create(string adapterName)
    {
        if (adapterName.Equals("TrueFace", StringComparison.OrdinalIgnoreCase))
        {
            return new TrueFaceDeviceAdapter();
        }

        return new MockDeviceAdapter();
    }
}
