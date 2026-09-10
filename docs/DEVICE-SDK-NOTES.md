# TrueFace3000 / Dahua NetSDK — Integration Notes & Capability Matrix

**Source of truth for the native integration boundary.** Derived from the actual artifacts in
`TrueFace_SDK/` (P/Invoke layer `OriginalSDK.cs`, wrapper `NetSDK.cs`, structs `NetSDKStruct.cs`,
demo `AccessDemo2s`) and the real device session log `bin/x64Debug/sdk_log/1_sdk_log.log`.

> **Golden rule:** we never invent SDK behaviour. Each operation below carries a verification
> status. Anything not `VERIFIED` is either behind the mock/simulator or a guided fallback until
> confirmed on hardware.

## Identity of the SDK

- **It is the Dahua NetSDK** (native `dhnetsdk` / `dhconfigsdk` / `avnetsdk` / `dhplay`), exposed
  through a managed C# assembly `NetSDKCS` (entry class `NETClient`).
- Device family: `AccessControl2S` — 2nd-generation standalone access-control terminal.
- Device confirmed: TrueFace3000, S/N `TW30000005250265`, SW `V1.000.004H003.0.R.20250424`.
- Control port: **TCP 37777** (Dahua proprietary binary protocol). A separate HTTP/CGI server on
  **port 80** exists for diagnostics but is **not** the integration path for production features.

## Verification status legend

| Status | Meaning |
| --- | --- |
| `VERIFIED` | Succeeded against the real device (log `ret:1`/`error:0`) or proven artifact. |
| `VERIFIED*` | Reported successful in prior investigation; to be re-confirmed in Phase 4. |
| `DOC` | Declared/bound and implemented in the demo, not exercised in the available log. |
| `FAILED_IN_LOG` | Attempted in the recorded session and returned an SDK error code. |
| `N/A?` | Likely not applicable to this face-only hardware. |

## Capability matrix (managed `NETClient` wrapper → native entry point)

### Session / lifecycle
| Operation | Wrapper | Native | Status |
| --- | --- | --- | --- |
| Init | `NETClient.Init` | `CLIENT_InitEx` | VERIFIED |
| Cleanup | `NETClient.Cleanup` | `CLIENT_Cleanup` | DOC |
| Login (high security) | `LoginWithHighLevelSecurity` | `CLIENT_LoginWithHighLevelSecurity` | VERIFIED |
| Login (standard) | `Login` | `CLIENT_LoginEx2` | DOC |
| Logout | `Logout` | `CLIENT_Logout` | DOC |
| Auto-reconnect | `SetAutoReconnect` | `CLIENT_SetAutoReconnect` | VERIFIED |
| Connect timing | — | `CLIENT_SetConnectTime` | DOC |
| Connection status | `GetConnectionStatus` | `CLIENT_GetConnectionStatus` | DOC |
| Disconnect callback | `fDisConnectCallBack` | (delegate) | VERIFIED |

### Device info / config
| Operation | Wrapper | Native | Status |
| --- | --- | --- | --- |
| Access-control caps | `GetDevCaps(ACCESSCONTROL_CAPS)` | `CLIENT_GetDevCaps` | VERIFIED |
| Get/Set new dev config | `GetNewDevConfig`/`SetNewDevConfig` | `CLIENT_GetNewDevConfig`/`Set...` | VERIFIED (get) |
| Device time get/set | `QueryDeviceTime`/`SetupDeviceTime` | `CLIENT_QueryDeviceTime`/`Setup...` | DOC |
| Software version / serial | — | `CLIENT_GetSoftwareVersion`/`GetDeviceSerialNo` | DOC |
| Reboot | `ControlDevice(REBOOT)` | `CLIENT_ControlDevice` | DOC |
| Reset / restore | `ResetSystem` / `ControlDevice(RESTOREDEFAULT)` | `CLIENT_ResetSystem` | DOC |
| Firmware upgrade | — | `CLIENT_StartUpgradeEx` | DOC (guarded) |

### Users / cards / credentials
| Operation | Wrapper | Native | Status |
| --- | --- | --- | --- |
| User service (add/mod/del/query) | `OperateAccessUserService` | `CLIENT_OperateAccessUserService` | VERIFIED* |
| User enumeration (paged) | `StartFindUserInfo`/`DoFindUserInfo` | `CLIENT_StartFindUserInfo`/`Do...` | VERIFIED |
| Attendance user CRUD | `Attendance_AddUser/DelUser/ModifyUser/GetUser/FindUser` | `CLIENT_Attendance_*` | DOC |
| Card service | `OperateAccessCardService` | `CLIENT_OperateAccessCardService` | DOC |
| Card enumeration | `StartFindCardInfo`/`DoFindCardInfo` | `CLIENT_StartFindCardInfo`/`Do...` | VERIFIED |

### Biometrics
| Operation | Wrapper | Native | Status |
| --- | --- | --- | --- |
| **Face access service** | `OperateAccessFaceService` | `CLIENT_OperateAccessFaceService` | **FAILED_IN_LOG `0x10030110`** |
| Face info op | `FaceInfoOpreate` | `CLIENT_FaceInfoOpreate` | DOC |
| Face DB op | — | `CLIENT_OperateFaceRecognitionDB` | DOC |
| Remote capture cmd | `AccessControlCaptureCmd` | `CLIENT_AccessControlCaptureCmd` | VERIFIED* |
| **Fingerprint service** | `OperateAccessFingerprintService` | `CLIENT_OperateAccessFingerprintService` | **FAILED_IN_LOG `0x1003000d` / N/A?** |
| Fingerprint by user id | `Attendance_*FingerByUserID` | `CLIENT_Attendance_*FingerByUserID` | N/A? |

### Access control operations & events
| Operation | Wrapper | Native | Status |
| --- | --- | --- | --- |
| Open door | `ControlDevice(ACCESS_OPEN)` | `CLIENT_ControlDevice` | DOC |
| Close door | `ControlDevice(ACCESS_CLOSE)` | `CLIENT_ControlDevice` | DOC |
| Open/Close always | `SetNewDevConfig(emState)` | `CLIENT_SetNewDevConfig` | DOC |
| Door status | `QueryDevState(DOOR_STATE)` | `CLIENT_QueryDevState` | DOC |
| Face open door | — | `CLIENT_FaceOpenDoor` | DOC |
| Real-time events (start/stop) | `StartListen`/`StopListen` + `SetDVRMessCallBack` | `CLIENT_StartListenEx`/`StopListen` | VERIFIED |
| Auto-register server | `ListenServer`/`StopListenServer` | `CLIENT_ListenServer`/`Stop...` | DOC |

### Records / logs (attendance reconciliation)
| Operation | Wrapper | Native | Status |
| --- | --- | --- | --- |
| Start record find | `FindRecord(ACCESSCTLCARDREC_EX)` | `CLIENT_FindRecord` | DOC |
| Record count | `QueryRecordCount` | `CLIENT_QueryRecordCount` | DOC |
| Next record (paged) | `FindNextRecord` | `CLIENT_FindNextRecord` | DOC |
| Alarm record find | `FindRecord(ACCESS_ALARMRECORD)` | `CLIENT_FindRecord` | DOC |
| Device log query | `StartQueryLog`/`QueryNextLog`/`QueryDevLogCount` | `CLIENT_StartQueryLog`/... | DOC |

### Discovery
| Operation | Wrapper | Native | Status |
| --- | --- | --- | --- |
| LAN search | `StartSearchDevicesEx` | `CLIENT_StartSearchDevices` | DOC |
| Search by IPs | — | `CLIENT_SearchDevicesByIPs` | DOC |
| Modify device net | — | `CLIENT_ModifyDevice` | DOC |
| Init account | `InitDevAccount` | `CLIENT_InitDevAccount` | DOC |

## Real-time event payloads (from `AccessForm.AlarmCallBack`)

`EM_ALARM_TYPE` → struct handled by the demo (all normalized by the gateway before enqueue):

- `ALARM_ACCESS_CTL_EVENT` → `NET_ALARM_ACCESS_CTL_EVENT_INFO` (`szUserID`, `szCardNo`, `nDoor`,
  `emOpenMethod`, `bStatus`, `stuTime`) — **primary attendance signal**.
- `ALARM_ACCESS_CTL_NOT_CLOSE`, `..._BREAK_IN`, `..._REPEAT_ENTER`, `..._DURESS`, `..._MALICIOUS`,
  `ALARM_CHASSISINTRUDED`, `ALARM_ALARM_EX2` — security alarms → `SecurityEvent`.

## Attendance record fields (from `RecordQueryForm`)

`NET_RECORDSET_ACCESS_CTL_CARDREC`: `nRecNo` (**stable per-device record number → dedup key**),
`stuTime` (UTC → local), `szCardNo`, `bStatus`, `nDoor`, `emMethod`.

## Native-safety rules (enforced in `TrueFaceDeviceAdapter`, master prompt §39)

1. SDK callbacks run on native threads — **normalize immediately, enqueue, return fast**. No DB
   writes, no long business logic, no exceptions escaping the callback (the demo already marshals
   then `BeginInvoke`s; the gateway will marshal then push to an internal channel).
2. Never leak native handles / unmanaged memory; every `AllocHGlobal` is paired with `FreeHGlobal`.
3. Graceful shutdown: stop listeners → logout/close sessions → free native resources → drain queues.
4. SDK structs, enums, handles, error codes **never** cross out of the adapter into domain code.

## Error codes observed

| Code | Where | Interpretation (to confirm w/ vendor manual) |
| --- | --- | --- |
| `error:0` | login | success |
| `ret:1` | most ops | success |
| `0x10030110` | `OperateAccessFaceService` | face service call rejected/unsupported in that state |
| `0x1003000d` | `OperateAccessFingerprintService` | fingerprint service rejected / not applicable |
| `0x1007ffff` | JsonParser (GetDevCaps/GetConfig) | partial parse warning; call still returned `ret:1` |

## Outstanding vendor asks (blockers for full Phase 4 on Linux)

1. **`libdhnetsdk.so` + companion `.so`** files for the target Linux/arch.
2. Confirmation of the correct **face enrollment sequence** for this firmware (given the
   `0x10030110` result), or the intended **remote capture** workflow.
3. Meaning of the observed **error codes** from the official manual.
