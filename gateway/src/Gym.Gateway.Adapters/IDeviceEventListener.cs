namespace Gym.Gateway.Adapters;

public interface IDeviceEventListener
{
    void OnNormalizedEvent(NormalizedDeviceEvent evt);
}
