using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Windows;
using Gym.Gateway.Adapters;
using Gym.Gateway.Config;
using Gym.Gateway.Security;
using Microsoft.Extensions.Logging.Abstractions;

namespace Gym.Gateway.Configurator;

public partial class MainWindow : Window
{
    private readonly GatewayEnrollmentClient _client = new();
    private readonly ObservableCollection<DeviceRow> _devices = [];
    private string? _operationalCredential;
    private DateTimeOffset? _credentialExpiresAt;

    public MainWindow()
    {
        InitializeComponent();
        DevicesGrid.ItemsSource = _devices;
        BackendUrlBox.Text = "https://";
        RefreshServiceStatus();
    }

    private async void Enroll_Click(object sender, RoutedEventArgs e)
    {
        StatusText.Text = "Enrolling…";
        try
        {
            var backend = BackendUrlBox.Text.Trim().TrimEnd('/');
            var gatewayId = GatewayIdBox.Text.Trim();
            var enrollment = EnrollmentTokenBox.Password.Trim();
            if (string.IsNullOrWhiteSpace(backend) || string.IsNullOrWhiteSpace(gatewayId) ||
                string.IsNullOrWhiteSpace(enrollment))
            {
                StatusText.Text = "Backend URL, Gateway ID, and enrollment token are required.";
                return;
            }

            var result = await _client.EnrollAsync(backend, gatewayId, enrollment).ConfigureAwait(true);
            _operationalCredential = result.Credential;
            _credentialExpiresAt = result.ExpiresAt;
            GatewayIdBox.Text = result.GatewayId;

            var remote = await _client.ListDevicesAsync(backend, result.Credential).ConfigureAwait(true);
            _devices.Clear();
            foreach (var d in remote)
            {
                _devices.Add(new DeviceRow
                {
                    DeviceId = d.Id,
                    Name = d.Name,
                    Role = d.Role,
                    Ip = d.Host ?? "",
                    Port = (ushort)(d.Port is > 0 and <= ushort.MaxValue ? d.Port.Value : 37777),
                    Username = "admin",
                    Password = ""
                });
            }

            if (_devices.Count == 0)
            {
                StatusText.Text =
                    $"Enrolled. Credential expires {_credentialExpiresAt:u}. No devices yet — add device rows in staff UI, then re-enroll list via Refresh devices.";
            }
            else
            {
                StatusText.Text =
                    $"Enrolled. Credential expires {_credentialExpiresAt:u}. Configure { _devices.Count } device(s), then Save & start.";
            }
        }
        catch (Exception ex)
        {
            StatusText.Text = "Enroll failed: " + ex.Message;
        }
    }

    private async void RefreshDevices_Click(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrWhiteSpace(_operationalCredential))
        {
            StatusText.Text = "Enroll first.";
            return;
        }

        try
        {
            var backend = BackendUrlBox.Text.Trim().TrimEnd('/');
            var remote = await _client.ListDevicesAsync(backend, _operationalCredential).ConfigureAwait(true);
            var byId = _devices.ToDictionary(d => d.DeviceId, StringComparer.Ordinal);
            _devices.Clear();
            foreach (var d in remote)
            {
                if (byId.TryGetValue(d.Id, out var existing))
                {
                    existing.Name = d.Name;
                    existing.Role = d.Role;
                    if (string.IsNullOrWhiteSpace(existing.Ip) && !string.IsNullOrWhiteSpace(d.Host))
                    {
                        existing.Ip = d.Host;
                    }

                    if (d.Port is > 0)
                    {
                        existing.Port = (ushort)d.Port.Value;
                    }

                    _devices.Add(existing);
                }
                else
                {
                    _devices.Add(new DeviceRow
                    {
                        DeviceId = d.Id,
                        Name = d.Name,
                        Role = d.Role,
                        Ip = d.Host ?? "",
                        Port = (ushort)(d.Port is > 0 and <= ushort.MaxValue ? d.Port.Value : 37777),
                        Username = "admin"
                    });
                }
            }

            StatusText.Text = $"Loaded {_devices.Count} device(s).";
        }
        catch (Exception ex)
        {
            StatusText.Text = "Refresh failed: " + ex.Message;
        }
    }

    private void TestConnect_Click(object sender, RoutedEventArgs e)
    {
        if (DevicesGrid.SelectedItem is not DeviceRow row)
        {
            StatusText.Text = "Select a device row first.";
            return;
        }

        try
        {
            using var adapter = DeviceAdapterFactory.Create("TrueFace");
            var status = adapter.Connect(new DeviceConnectionConfig(
                row.DeviceId,
                row.Ip,
                row.Port,
                row.Username,
                row.Password,
                null));
            StatusText.Text = status.Ok
                ? $"Connect OK for {row.DeviceId} ({status.ConnectionState})"
                : $"Connect failed for {row.DeviceId}: {status.Error}";
            adapter.Disconnect();
        }
        catch (Exception ex)
        {
            StatusText.Text = "Connect test error: " + ex.Message;
        }
    }

    private void SaveAndStart_Click(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrWhiteSpace(_operationalCredential))
        {
            StatusText.Text = "Enroll first to obtain an operational credential.";
            return;
        }

        try
        {
            ISecretProtector protector = new DpapiSecretProtector();
            var store = new GatewayConfigStore(protector, NullLogger<GatewayConfigStore>.Instance);
            var config = new PersistedGatewayConfig
            {
                BackendUrl = BackendUrlBox.Text.Trim().TrimEnd('/'),
                GatewayId = GatewayIdBox.Text.Trim(),
                CredentialProtected = store.Protect(_operationalCredential),
                CredentialExpiresAt = _credentialExpiresAt,
                RenewBefore = TimeSpan.FromDays(7),
                Adapter = "TrueFace",
                UseWebSocket = true,
                Devices = _devices.Select(d => new PersistedDeviceConfig
                {
                    DeviceId = d.DeviceId,
                    Ip = d.Ip.Trim(),
                    Port = d.Port,
                    Username = d.Username.Trim(),
                    PasswordProtected = store.Protect(d.Password)
                }).ToList()
            };
            store.Save(config);

            var serviceResult = GatewayServiceControl.EnsureInstalledAndRunning();
            RefreshServiceStatus();
            StatusText.Text = "Configuration saved. " + serviceResult;
        }
        catch (Exception ex)
        {
            StatusText.Text = "Save/start failed: " + ex.Message;
        }
    }

    private void RefreshStatus_Click(object sender, RoutedEventArgs e) => RefreshServiceStatus();

    private void RefreshServiceStatus()
    {
        ServiceStatusText.Text = GatewayServiceControl.DescribeStatus();
    }
}

public sealed class DeviceRow : INotifyPropertyChanged
{
    private string _deviceId = "";
    private string _name = "";
    private string _role = "";
    private string _ip = "";
    private ushort _port = 37777;
    private string _username = "admin";
    private string _password = "";

    public string DeviceId
    {
        get => _deviceId;
        set => Set(ref _deviceId, value);
    }

    public string Name
    {
        get => _name;
        set => Set(ref _name, value);
    }

    public string Role
    {
        get => _role;
        set => Set(ref _role, value);
    }

    public string Ip
    {
        get => _ip;
        set => Set(ref _ip, value);
    }

    public ushort Port
    {
        get => _port;
        set => Set(ref _port, value);
    }

    public string Username
    {
        get => _username;
        set => Set(ref _username, value);
    }

    public string Password
    {
        get => _password;
        set => Set(ref _password, value);
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    private void Set<T>(ref T field, T value, [CallerMemberName] string? name = null)
    {
        if (Equals(field, value))
        {
            return;
        }

        field = value;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
    }
}
