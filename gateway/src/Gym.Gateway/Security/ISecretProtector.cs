namespace Gym.Gateway.Security;

/// <summary>Protects secrets at rest. Production Windows uses DPAPI LocalMachine.</summary>
public interface ISecretProtector
{
    string Protect(string plaintext);

    string Unprotect(string protectedPayload);
}
