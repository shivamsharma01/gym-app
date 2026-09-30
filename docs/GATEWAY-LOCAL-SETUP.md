
 # Gym Gateway - Local Development Setup

 This document explains how to configure and run the **Gym Gateway locally** while connecting it to the **cloud-hosted Backend** and a **locally running Simulator**.

 The Gateway acts as the bridge between the local Simulator/device and the cloud Backend.

---

 ## Architecture

 The local development environment consists of:

```
                           CLOUD
        ┌─────────────────────────────────────┐
        │                                     │
        │       ┌───────────────┐             │
        │       │   Frontend    │             │
        │       └───────┬───────┘             │
        │               │                     │
        │               ▼                     │
        │       ┌───────────────┐             │
        │       │    Backend    │             │
        │       └───────▲───────┘             │
        │               │                     │
        └───────────────┼─────────────────────┘
                        │
                  HTTPS / WebSocket
                        │
                        ▼
        ┌─────────────────────────────────────┐
        │           LOCAL MACHINE             │
        │                                     │
        │       ┌───────────────────┐         │
        │       │      Gateway      │         │
        │       │                   │         │
        │       │ Gym.Gateway       │         │
        │       └─────────┬─────────┘         │
        │                 │                   │
        │                 │ Local connection  │
        │                 ▼                   │
        │       ┌───────────────────┐         │
        │       │     Simulator     │         │
        │       │ 127.0.0.1:9001    │         │
        │       └───────────────────┘         │
        │                                     │
        │       data/dvt.db                   │
        │                                     │
        └─────────────────────────────────────┘
```

 ### Components

 | Component | Location | Purpose |
| --- | --- | --- |
| Frontend | Cloud | User interface |
| Backend | Cloud | API and application services |
| Gateway | Local machine | Connects local devices/Simulator to the cloud Backend |
| Simulator | Local machine | Simulates the device locally |
| SQLite Database | Local machine | Stores local Gateway/device data |

---

 # Prerequisites

 Make sure the following are installed and available on the local development machine:

 - .NET SDK/runtime required by the project
- Gateway source code
- Simulator
- Internet/network access
- VS Code (recommended)
- SQLite VS Code extension (optional)

 Verify the .NET installation:

```
dotnet --version
```

---

 # Project Structure

 The Gateway project is located at:

```
src/Gym.Gateway
```

 The SQLite database is located at:

```
data/dvt.db
```

 A typical repository structure looks like:

```
project/
│
├── src/
│   └── Gym.Gateway/
│       └── ...
│
├── data/
│   └── dvt.db
│
├── ...
│
└── README.md
```

---

 # Gateway Configuration

 The Gateway configuration contains:

 - Gateway identity
- Authentication credential/token
- Cloud Backend URL
- Communication settings
- Reconnection settings
- Device/Simulator configuration

 Example:

```
{
  "Gateway": {
    "Id": "",
    "Token": "",
    "BackendUrl": "http://localhost:8080",
    "Adapter": "Remote",
    "UseWebSocket": true,
    "HeartbeatSeconds": 15,
    "ReconnectBaseSeconds": 2,
    "ReconnectMaxSeconds": 60,
    "NativeDirectory": "",
    "Devices": [
      {
        "DeviceId": "",
        "Ip": "127.0.0.1",
        "Port": 9001,
        "Username": "admin",
        "Password": ""
      },
      {
        "DeviceId": "",
        "Ip": "127.0.0.1",
        "Port": 9002,
        "Username": "admin",
        "Password": ""
      }
    ]
  }
}
```

---

 # Gateway Configuration Properties

 | Property | Description | Example |
| --- | --- | --- |
| `Id` | Unique Gateway identifier | `gateway-local-01` |
| `Token` | Credential returned during Gateway enrollment | `YOUR_GATEWAY_CREDENTIAL` |
| `BackendUrl` | URL of the Backend | `https://backend.example.com` |
| `Adapter` | Adapter used by the Gateway | `Remote` |
| `UseWebSocket` | Enables WebSocket communication | `true` |
| `HeartbeatSeconds` | Heartbeat interval in seconds | `15` |
| `ReconnectBaseSeconds` | Initial reconnect delay | `2` |
| `ReconnectMaxSeconds` | Maximum reconnect delay | `60` |
| `NativeDirectory` | Path to native libraries if required | `/path/to/native` |
| `Devices` | List of configured devices/Simulators | See below |

---

 # Device Configuration

 Each item in the `Devices` array represents a device or local Simulator that the Gateway connects to.

 Example:

```
{
  "DeviceId": "simulator-01",
  "Ip": "127.0.0.1",
  "Port": 9001,
  "Username": "admin",
  "Password": ""
}
```

 | Property | Description | Example |
| --- | --- | --- |
| `DeviceId` | Unique device identifier | `simulator-01` |
| `Ip` | IP address or hostname of the device | `127.0.0.1` |
| `Port` | Device/Simulator port | `9001` |
| `Username` | Device username | `admin` |
| `Password` | Device password | `YOUR_PASSWORD` |

---

 # Gateway Enrollment

 Before running the Gateway, it must be enrolled with the Backend.

 The enrollment endpoint is:

```
POST /internal/gateway/enroll
```

 For local Backend development, the endpoint is:

```
http://localhost:8080/internal/gateway/enroll
```

 If the Backend is deployed in the cloud, use the appropriate cloud Backend URL.

---

 ## Enrollment Request

 Use the following request:

```
curl --location --request POST 'http://localhost:8080/internal/gateway/enroll' \
--header 'Content-Type: application/json' \
--data-raw '{
    "gatewayId": "",
    "enrollmentToken": ""
}'
```

 ### Request Fields

 | Field | Description |
| --- | --- |
| `gatewayId` | Unique ID of the Gateway being enrolled |
| `enrollmentToken` | Enrollment token provided for the Gateway |

Example:

```
{
  "gatewayId": "gateway-local-01",
  "enrollmentToken": "YOUR_ENROLLMENT_TOKEN"
}
```

---

 # Enrollment Response

 A successful enrollment returns a response similar to:

```
{
  "gatewayId": "gateway-local-01",
  "credential": "GENERATED_GATEWAY_CREDENTIAL",
  "expiresAt": "2026-09-30T12:00:00Z"
}
```

 The important value is:

```
credential
```

 The `credential` returned by the enrollment API must be used as the **`Token`** in the Gateway `appsettings.json`.

---

 # Configure the Gateway Token

 For example, if enrollment returns:

```
{
  "gatewayId": "gateway-local-01",
  "credential": "abc123-generated-credential",
  "expiresAt": "2026-09-30T12:00:00Z"
}
```

 Set the Gateway configuration to:

```
{
  "Gateway": {
    "Id": "gateway-local-01",
    "Token": "abc123-generated-credential"
  }
}
```

 In other words:

```
Enrollment API
      │
      │
      ▼
credential
      │
      │
      ▼
Gateway appsettings.json
      │
      │
      └── Gateway.Token
```

 Do not use the enrollment token as the Gateway `Token`.

 The Gateway `Token` should be the **`credential` returned by the enrollment endpoint**.

---

 # Important: Credential Expiration

 The enrollment response contains:

```
{
  "expiresAt": "..."
}
```

 This indicates when the returned credential expires.

 If the credential expires, the Gateway may no longer be able to authenticate with the Backend.

 In that case, follow the enrollment process again and update the Gateway `Token` with the newly returned `credential`.

 Do not assume that an expired credential can continue to be used.

---

 # Cloud Backend Configuration

 Because the Backend is deployed in the cloud, the local Gateway must point to the **cloud Backend URL**.

 For example:

```
{
  "Gateway": {
    "BackendUrl": "https://your-cloud-backend.com"
  }
}
```

 Do not use:

```
http://localhost:8080
```

 when the Backend is deployed in the cloud.

 `localhost` always refers to the machine where the Gateway process is running.

 Therefore:

```
Local Gateway
     |
     └── http://localhost:8080
```

 means the Gateway is trying to connect to a Backend running on the local machine.

 For the current setup:

```
Local Gateway
     |
     | HTTPS / WebSocket
     ▼
Cloud Backend
```

---

 # Cloud Enrollment

 If the Backend is deployed in the cloud, replace the local Backend URL in the enrollment request with the cloud Backend URL.

 For example:

```
curl --location --request POST 'https://your-cloud-backend.com/internal/gateway/enroll' \
--header 'Content-Type: application/json' \
--data-raw '{
    "gatewayId": "gateway-local-01",
    "enrollmentToken": "YOUR_ENROLLMENT_TOKEN"
}'
```

 The actual cloud Backend URL and enrollment token should be provided by your environment/application configuration.

 After successful enrollment, copy the returned:

```
credential
```

 into:

```
"Gateway": {
  "Token": "YOUR_RETURNED_CREDENTIAL"
}
```

---

 # Complete Local Gateway Configuration

 After enrollment, the configuration should look similar to:

```
{
  "Gateway": {
    "Id": "gateway-local-01",
    "Token": "YOUR_RETURNED_CREDENTIAL",
    "BackendUrl": "https://your-cloud-backend.com",
    "Adapter": "Remote",
    "UseWebSocket": true,
    "HeartbeatSeconds": 15,
    "ReconnectBaseSeconds": 2,
    "ReconnectMaxSeconds": 60,
    "NativeDirectory": "",
    "Devices": [
      {
        "DeviceId": "simulator-01",
        "Ip": "127.0.0.1",
        "Port": 9001,
        "Username": "admin",
        "Password": "YOUR_DEVICE_PASSWORD"
      }
    ]
  }
}
```

 The important values are:

```
Gateway.Id
    ↓
The Gateway ID used during enrollment

Gateway.Token
    ↓
The credential returned from the enrollment API

Gateway.BackendUrl
    ↓
The cloud Backend URL

Devices[].Ip
    ↓
Local Simulator IP

Devices[].Port
    ↓
Local Simulator port
```

---

 # Environment Variables

 Sensitive and environment-specific values should preferably be provided through environment variables.

 For .NET configuration, nested properties use `__` (double underscore).

 Example:

```
Gateway__Id=gateway-local-01
Gateway__Token=YOUR_RETURNED_CREDENTIAL
Gateway__BackendUrl=https://your-cloud-backend.com
Gateway__Adapter=Remote
Gateway__UseWebSocket=true
Gateway__HeartbeatSeconds=15
Gateway__ReconnectBaseSeconds=2
Gateway__ReconnectMaxSeconds=60
```

 For the first device:

```
Gateway__Devices__0__DeviceId=simulator-01
Gateway__Devices__0__Ip=127.0.0.1
Gateway__Devices__0__Port=9001
Gateway__Devices__0__Username=admin
Gateway__Devices__0__Password=YOUR_DEVICE_PASSWORD
```

 For a second device:

```
Gateway__Devices__1__DeviceId=simulator-02
Gateway__Devices__1__Ip=127.0.0.1
Gateway__Devices__1__Port=9002
Gateway__Devices__1__Username=admin
Gateway__Devices__1__Password=YOUR_DEVICE_PASSWORD
```

---

 # SQLite Database

 The Gateway uses:

```
data/dvt.db
```

 The database is local to the Gateway environment.

 ## Open Database in VS Code

 1. Open the project in VS Code.
2. Locate:

```
data/dvt.db
```

 3. Install a SQLite extension if necessary.
4. Press:

```
Ctrl + Shift + P
```

 5. Select:

```
SQLite: Open Database
```

 6. Select:

```
data/dvt.db
```

 You can then inspect the database tables and data.

---

 # Running the Simulator

 Start the Simulator before starting the Gateway.

 The Simulator should listen on the port configured in the Gateway.

 For example:

```
127.0.0.1:9001
```

 Expected:

```
Simulator
    |
    └── Listening on 127.0.0.1:9001
```

 Make sure the Simulator is fully started before starting the Gateway.

---

 # Running the Gateway

 From the **repository root**, run:

```
dotnet run --project src/Gym.Gateway
```

 This is the standard command for running the Gateway locally.

 The expected startup sequence is:

```
1. Start Simulator
       |
       ▼
2. Simulator listens on 127.0.0.1:9001
       |
       ▼
3. Enroll Gateway with Backend
       |
       ▼
4. Copy returned credential to Gateway.Token
       |
       ▼
5. Configure cloud Backend URL
       |
       ▼
6. Start Gateway
       |
       ▼
7. Gateway connects to Simulator
       |
       ▼
8. Gateway connects to Cloud Backend
       |
       ▼
9. Gateway authenticates
       |
       ▼
10. Gateway starts communication
```

---

 # Complete Setup Flow

 For a fresh local setup, follow these steps in order.

 ## Step 1 - Start Simulator

 Start the local Simulator.

 Verify that it is listening on:

```
127.0.0.1:9001
```

---

 ## Step 2 - Get Enrollment Token

 Obtain the Gateway enrollment token required by the Backend.

 The enrollment token is used only for the enrollment process.

---

 ## Step 3 - Enroll Gateway

 Call:

```
curl --location --request POST 'http://localhost:8080/internal/gateway/enroll' \
--header 'Content-Type: application/json' \
--data-raw '{
    "gatewayId": "gateway-local-01",
    "enrollmentToken": "YOUR_ENROLLMENT_TOKEN"
}'
```

 If using the cloud Backend:

```
curl --location --request POST 'https://your-cloud-backend.com/internal/gateway/enroll' \
--header 'Content-Type: application/json' \
--data-raw '{
    "gatewayId": "gateway-local-01",
    "enrollmentToken": "YOUR_ENROLLMENT_TOKEN"
}'
```

---

 ## Step 4 - Copy the Credential

 The Backend returns:

```
{
  "gatewayId": "gateway-local-01",
  "credential": "GENERATED_GATEWAY_CREDENTIAL",
  "expiresAt": "2026-09-30T12:00:00Z"
}
```

 Copy:

```
GENERATED_GATEWAY_CREDENTIAL
```

---

 ## Step 5 - Configure `Token`

 Set the returned credential as:

```
"Token": "GENERATED_GATEWAY_CREDENTIAL"
```

 For example:

```
{
  "Gateway": {
    "Id": "gateway-local-01",
    "Token": "GENERATED_GATEWAY_CREDENTIAL",
    "BackendUrl": "https://your-cloud-backend.com"
  }
}
```

---

 ## Step 6 - Configure Simulator

 Configure the local Simulator:

```
{
  "DeviceId": "simulator-01",
  "Ip": "127.0.0.1",
  "Port": 9001,
  "Username": "admin",
  "Password": "YOUR_DEVICE_PASSWORD"
}
```

---

 ## Step 7 - Start Gateway

 From the repository root:

```
dotnet run --project src/Gym.Gateway
```

---

 ## Step 8 - Verify Gateway

 Check the Gateway logs and verify:

 - Configuration loaded successfully.
- Gateway ID is correct.
- Gateway connects to the local Simulator.
- Gateway connects to the cloud Backend.
- Gateway authentication succeeds.
- Device communication starts.
- No connection/reconnection errors occur.

---

 ## Step 9 - Verify Frontend

 Open the deployed cloud Frontend and verify that the Gateway/device information is available.

 The complete communication path should be:

```
Local Simulator
       │
       ▼
Local Gateway
       │
       │ HTTPS / WebSocket
       ▼
Cloud Backend
       │
       ▼
Cloud Frontend
```

---

 # Multiple Simulators / Devices

 Multiple devices can be configured under `Devices`.

 Example:

```
{
  "Devices": [
    {
      "DeviceId": "simulator-01",
      "Ip": "127.0.0.1",
      "Port": 9001,
      "Username": "admin",
      "Password": "YOUR_PASSWORD"
    },
    {
      "DeviceId": "simulator-02",
      "Ip": "127.0.0.1",
      "Port": 9002,
      "Username": "admin",
      "Password": "YOUR_PASSWORD"
    }
  ]
}
```

 Each device should have a unique `DeviceId`.

 If multiple Simulators run on the same machine, each Simulator must use a different port.

 Example:

```
Simulator 1 → 127.0.0.1:9001
Simulator 2 → 127.0.0.1:9002
Simulator 3 → 127.0.0.1:9003
```

---

 # WebSocket Configuration

 If the Gateway communicates with the Backend using WebSocket:

```
{
  "UseWebSocket": true
}
```

 The cloud infrastructure must support WebSocket connections.

 If the Backend is behind a reverse proxy or load balancer, WebSocket upgrade/forwarding must also be supported.

---

 # Heartbeat and Reconnection

 The Gateway configuration contains:

```
{
  "HeartbeatSeconds": 15,
  "ReconnectBaseSeconds": 2,
  "ReconnectMaxSeconds": 60
}
```

 ## Heartbeat

```
HeartbeatSeconds = 15
```

 Controls the configured heartbeat interval.

 ## Reconnection

```
ReconnectBaseSeconds = 2
ReconnectMaxSeconds = 60
```

 The Gateway starts with the configured reconnect delay and can increase the delay after repeated connection failures until it reaches the configured maximum.

---

 # Troubleshooting

 ## Gateway cannot connect to Backend

 Check:

 1. `BackendUrl` is correct.
2. Cloud Backend is running.
3. Local machine has Internet access.
4. Gateway `Token` contains the `credential` returned from enrollment.
5. The credential has not expired.
6. Firewall/proxy rules are not blocking the connection.
7. Backend allows the Gateway connection.
8. WebSocket support is enabled if WebSocket is being used.

---

 ## Gateway cannot connect to Simulator

 Check:

 1. Simulator is running.
2. Simulator is listening on the expected port.
3. Gateway IP is correct.
4. Gateway port is correct.
5. Device credentials are correct if authentication is required.

 Example:

```
{
  "Ip": "127.0.0.1",
  "Port": 9001
}
```

---

 ## Simulator is running but Gateway cannot connect

 Make sure the ports match.

 Simulator:

```
127.0.0.1:9001
```

 Gateway:

```
{
  "Ip": "127.0.0.1",
  "Port": 9001
}
```

 If the Simulator is running on `9002`, the Gateway must use:

```
{
  "Ip": "127.0.0.1",
  "Port": 9002
}
```

---

 ## Enrollment Fails

 Check:

 1. The Backend URL is correct.
2. The enrollment endpoint is available.
3. `gatewayId` is valid.
4. The enrollment token is valid.
5. The Gateway has not already been enrolled if duplicate enrollment is not supported.
6. The local machine can reach the Backend.

 Endpoint:

```
POST /internal/gateway/enroll
```

 Example:

```
curl --location --request POST 'https://your-cloud-backend.com/internal/gateway/enroll' \
--header 'Content-Type: application/json' \
--data-raw '{
    "gatewayId": "gateway-local-01",
    "enrollmentToken": "YOUR_ENROLLMENT_TOKEN"
}'
```

---

 ## Gateway Authentication Fails

 Verify that:

```
"Token": "..."
```

 contains the **`credential` returned by the enrollment API**.

 Do not use:

```
enrollmentToken
```

 as the Gateway `Token`.

 The flow is:

```
Enrollment Token
       │
       ▼
/internal/gateway/enroll
       │
       ▼
credential
       │
       ▼
Gateway.Token
```

 Also check the `expiresAt` value returned during enrollment.

---

 ## Gateway Credential Has Expired

 If the returned credential has expired:

 1. Enroll the Gateway again using a valid enrollment token.
2. Copy the new `credential`.
3. Update `Gateway.Token`.
4. Restart the Gateway.

 Example:

```
{
  "Gateway": {
    "Id": "gateway-local-01",
    "Token": "NEW_GENERATED_CREDENTIAL"
  }
}
```

---

 ## Gateway Repeatedly Reconnects

 Check:

```
{
  "HeartbeatSeconds": 15,
  "ReconnectBaseSeconds": 2,
  "ReconnectMaxSeconds": 60
}
```

 Also verify:

 - Cloud Backend availability.
- Gateway authentication.
- Network connectivity.
- WebSocket configuration.
- Gateway credential expiration.

---

 ## Gateway Starts but Device Is Not Visible in Frontend

 Check the complete communication chain:

```
Simulator
    ↓
Gateway
    ↓
Cloud Backend
    ↓
Frontend
```

 Verify each connection independently:

 1. Simulator is running.
2. Gateway connects to Simulator.
3. Gateway connects to Backend.
4. Gateway authentication succeeds.
5. Backend recognizes the Gateway/device.
6. Frontend is connected to the correct Backend environment.

---

 # Security

 Do not commit real credentials, passwords, enrollment tokens, or Gateway credentials to source control.

 Avoid committing:

```
{
  "Token": "REAL_GATEWAY_CREDENTIAL"
}
```

 or:

```
{
  "Password": "REAL_DEVICE_PASSWORD"
}
```

 or:

```
YOUR_ENROLLMENT_TOKEN
```

 Prefer environment variables or a secure secret-management mechanism for sensitive values.

 For local-only configuration files, consider adding them to `.gitignore`.

 Example:

```
.env
config.local.json
appsettings.Local.json
```

 Never share real Gateway credentials or device passwords in public repositories, screenshots, logs, or documentation.

---

 # Quick Start

 For a fresh local setup:

 ### 1\. Start the Simulator

```
Simulator → 127.0.0.1:9001
```

 ### 2\. Enroll the Gateway

```
curl --location --request POST 'https://your-cloud-backend.com/internal/gateway/enroll' \
--header 'Content-Type: application/json' \
--data-raw '{
    "gatewayId": "gateway-local-01",
    "enrollmentToken": "YOUR_ENROLLMENT_TOKEN"
}'
```

 ### 3\. Copy the Returned Credential

 From:

```
{
  "gatewayId": "gateway-local-01",
  "credential": "GENERATED_GATEWAY_CREDENTIAL",
  "expiresAt": "..."
}
```

 copy:

```
GENERATED_GATEWAY_CREDENTIAL
```

 ### 4\. Set the Gateway Token

```
"Token": "GENERATED_GATEWAY_CREDENTIAL"
```

 ### 5\. Set the Cloud Backend URL

```
"BackendUrl": "https://your-cloud-backend.com"
```

 ### 6\. Configure the Local Simulator

```
{
  "DeviceId": "simulator-01",
  "Ip": "127.0.0.1",
  "Port": 9001,
  "Username": "admin",
  "Password": "YOUR_DEVICE_PASSWORD"
}
```

 ### 7\. Run the Gateway

 From the repository root:

```
dotnet run --project src/Gym.Gateway
```

 ### 8\. Verify

 Expected communication:

```
Local Simulator
      │
      ▼
Local Gateway
      │
      │ HTTPS / WebSocket
      ▼
Cloud Backend
      │
      ▼
Cloud Frontend
```

---

 # Final Configuration Reference

 The key configuration for this development setup is:

```
                         CLOUD
             ┌────────────────────────┐
             │                        │
             │      Frontend          │
             │          │             │
             │          ▼             │
             │       Backend          │
             │          ▲             │
             └──────────┼─────────────┘
                        │
                  HTTPS/WebSocket
                        │
                        ▼
             ┌────────────────────────┐
             │    LOCAL MACHINE       │
             │                        │
             │       Gateway          │
             │          │             │
             │          │             │
             │          ▼             │
             │      Simulator         │
             │    127.0.0.1:9001     │
             │                        │
             │     data/dvt.db        │
             └────────────────────────┘
```

 ## Important Values

```
Gateway.Id
    ↓
Unique Gateway ID used during enrollment

Gateway.Token
    ↓
credential returned from /internal/gateway/enroll

Gateway.BackendUrl
    ↓
Cloud Backend URL

Devices[].DeviceId
    ↓
Local device/Simulator identifier

Devices[].Ip
    ↓
Local Simulator IP

Devices[].Port
    ↓
Local Simulator port
```

 ## Standard Local Start Command

```
dotnet run --project src/Gym.Gateway
```

 ## Complete Flow

```
1. Start Simulator
        ↓
2. Get Enrollment Token
        ↓
3. Call /internal/gateway/enroll
        ↓
4. Receive credential
        ↓
5. Set credential as Gateway.Token
        ↓
6. Set cloud Backend URL
        ↓
7. Start Gateway
        ↓
8. Gateway connects to local Simulator
        ↓
9. Gateway connects to cloud Backend
        ↓
10. Gateway authenticates
        ↓
11. Gateway communicates with Backend
        ↓
12. Frontend displays Gateway/device information
```

 The final setup allows the **Gateway and Simulator to run locally**, while the **Backend and Frontend remain deployed in the cloud**.