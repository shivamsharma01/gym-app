# Complete Local Testing & Device Simulator Guide (v2 Architecture)

This document is the operational guide for testing your gym system using the **True Multi-Device Hardware Emulator Architecture**.

---

## 1. Architecture Overview (4-Tier Local Topology)

In this setup, your **real .NET Gateway (`Gym.Gateway.exe`) runs unmodified**, connecting upstream to Spring Boot over WSS/REST, and downstream to virtual TrueFace biometric terminals over local HTTP/TCP network ports.

```mermaid
flowchart TB
    subgraph Tier1 ["1. Web UI (Staff & Admin)"]
        UI["React Frontend\nhttp://localhost:5173"]
    end

    subgraph Tier2 ["2. Cloud / Local Backend Server"]
        BE["Spring Boot API (:8080)\n(SPRING_PROFILES_ACTIVE=dev)"]
        DB[("MySQL / H2 DB (:3306)")]
        BE <--> DB
    end

    subgraph Tier3 ["3. Gym Local PC Gateway"]
        GW["Gym.Gateway (.NET 8 Runtime)\n(Adapter: 'Remote')"]
        CACHE[("Local Disk Cache & Outbox Store")]
        GW <--> CACHE
    end

    subgraph Tier4 ["4. Multi-Device Hardware Emulator Server (:9000)"]
        DASHBOARD["Admin Web Dashboard (:9000)\nhttp://127.0.0.1:9000"]
        V_ENTRY["Virtual Device #1 (ENTRY)\nhttp://127.0.0.1:9001"]
        V_EXIT["Virtual Device #2 (EXIT)\nhttp://127.0.0.1:9002"]
        DASHBOARD --- V_ENTRY
        DASHBOARD --- V_EXIT
    end

    UI <-->|Vite Proxy /api & /live| BE
    BE <==>|WSS /ws/gateway\nor REST /internal/gateway/*| GW
    GW <==>|HTTP :9001 (RemoteDeviceAdapter)| V_ENTRY
    GW <==>|HTTP :9002 (RemoteDeviceAdapter)| V_EXIT
```

---

## 2. Configuration Setup

### 2.1 Gateway Configuration (`Gym.Gateway/appsettings.json`)
Set `"Adapter": "Remote"` and specify the virtual device ports `9001` (Entry) and `9002` (Exit):

```json
{
  "Gateway": {
    "Id": "<YOUR_GATEWAY_PUBLIC_ID>",
    "Token": "<YOUR_GATEWAY_TOKEN>",
    "BackendUrl": "http://127.0.0.1:8080",
    "Adapter": "Remote",
    "UseWebSocket": true,
    "Devices": [
      {
        "DeviceId": "dev-entry-01",
        "Ip": "127.0.0.1",
        "Port": 9001,
        "Username": "admin",
        "Password": "password"
      },
      {
        "DeviceId": "dev-exit-02",
        "Ip": "127.0.0.1",
        "Port": 9002,
        "Username": "admin",
        "Password": "password"
      }
    ]
  }
}
```

---

## 3. Step-by-Step Execution Runbook

Open 4 separate terminal windows:

### Terminal 1: Multi-Device Hardware Simulator Server
```powershell
cd "gym-app\simulator"
python device_server.py
```
- Virtual Device #1 (ENTRY) starts on `http://127.0.0.1:9001`
- Virtual Device #2 (EXIT) starts on `http://127.0.0.1:9002`
- Admin Visual Dashboard starts on `http://127.0.0.1:9000`

### Terminal 2: Spring Boot Backend
```powershell
cd "gym-app\backend"
$env:SPRING_PROFILES_ACTIVE="dev"
$env:DB_URL="jdbc:mysql://localhost:3306/gym?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
$env:DB_USERNAME="gym"
$env:DB_PASSWORD="gym"
.\mvnw.cmd spring-boot:run
```

### Terminal 3: React Frontend
```powershell
cd "gym-app\frontend"
npm run dev
```
- Open `http://localhost:5173` in browser.

### Terminal 4: Real .NET Gateway Application
```powershell
cd "gym-app\gateway\src\Gym.Gateway"
dotnet run
```

---

## 4. Live Testing Scenarios with the Hardware Dashboard

Open the Simulator Dashboard at **`http://127.0.0.1:9000`**:

1. **Member Enrolment & Photo Sync**:
   - In React UI, create member `Alice` with a photo.
   - Watch the Gateway push the command to Port 9001 and 9002.
   - On `http://127.0.0.1:9000`, Alice appears in both terminal tables with her decoded photo thumbnail!
2. **Live Attendance Trigger**:
   - On `http://127.0.0.1:9000`, click **🟢 Punch ENTRY (Grant)** under Main Entrance Gate.
   - The simulator emits an event $\rightarrow$ Gateway picks it up $\rightarrow$ pushes to Backend $\rightarrow$ React UI triggers a live check-in notification.
3. **Simulate Hardware Failure / Offline**:
   - On `http://127.0.0.1:9000`, click **Simulate Offline** on the Exit Gate.
   - Create a member in React UI $\rightarrow$ Gateway successfully updates Entry Gate, buffers Exit Gate in local disk outbox.
   - Click **Bring Online** on Exit Gate $\rightarrow$ Gateway auto-reconnects and drains the outbox!
4. **Direct Terminal Walk-in Enrolment**:
   - On `http://127.0.0.1:9000`, click **➕ Direct Terminal Enroll** $\rightarrow$ Enter ID `3001` and Name `Bob Walk-in`.
   - The simulator emits `USER_CHANGED` $\rightarrow$ Gateway picks it up $\rightarrow$ syncs to Exit Gate and uploads to Backend $\rightarrow$ `Bob Walk-in` appears in React UI!

---

## 5. Summary of Built Components

| Component | Path | Description |
| :--- | :--- | :--- |
| **Remote Device Adapter** | [`Gym.Gateway.Adapters/RemoteDeviceAdapter.cs`](gateway/src/Gym.Gateway.Adapters/RemoteDeviceAdapter.cs) | Implements `IDeviceAdapter` over HTTP/REST to virtual hardware with long-poll event listener. |
| **Adapter Factory** | [`Gym.Gateway.Adapters/DeviceAdapterFactory.cs`](gateway/src/Gym.Gateway.Adapters/DeviceAdapterFactory.cs) | Added support for `Adapter: "Remote"`. |
| **Device Server & Dashboard** | [`simulator/device_server.py`](simulator/device_server.py) | Multi-device host (Ports 9001/9002) + Admin REST API + Web Dashboard (:9000). |
| **Adapter Tests** | [`Gym.Gateway.Tests/RemoteDeviceAdapterTests.cs`](gateway/tests/Gym.Gateway.Tests/RemoteDeviceAdapterTests.cs) | Unit tests verifying CRUD, face sync, and health checks over HTTP. |
