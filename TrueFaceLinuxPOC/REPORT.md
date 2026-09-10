# TrueFace Linux/.NET 10 SDK Compatibility POC — Engineering Report

## Result

**INCONCLUSIVE — Physical device not tested**

Host-side interoperability that does **not** require a device is verified:

- Build: **PASS** (`dotnet build -c Release`, 0 warnings, 0 errors)
- Native loading: **PASS** (`libdhnetsdk.so` ELF 64-bit x86-64)
- SDK initialization: **PASS** (`NETClient.InitWithDefaultSetting` → `CLIENT_InitEx`)
- SDK cleanup: **PASS** (`CLIENT_Cleanup`)
- Login / `NET_DEVICEINFO_Ex` / logout: **not executed** (no `TRUEFACE_IP` / credentials in the environment)

Do **not** treat Init success as device compatibility. The decisive test remains `CLIENT_LoginEx2` on the physical TrueFace3000.

## Environment

| Item | Value |
| --- | --- |
| OS | Ubuntu 24.04.5 LTS (Noble) |
| Kernel | 7.0.0-31-generic x86_64 |
| .NET SDK | 10.0.401 (user-local `$HOME/.dotnet`, install script) |
| Runtime (run) | .NET 10.0.12 |
| C# wrapper source | `TrueFace_SDK/NetSDKCS/` copies (NetSDK.cs, NetSDKStruct.cs, OriginalSDK.cs), originally .NET Framework 4.0 |
| Native SDK | `~/Downloads/dahua-sdk-master/libs/lin64/libdhnetsdk.so` dated 2019-07-07, stripped |
| Native architecture | ELF 64-bit LSB shared object, x86-64, BuildID `99a89b4d2159aa631dca38215a32ac6388e1b880` |

## Tested APIs

Exercised on this host:

| Managed wrapper | Native |
| --- | --- |
| `NETClient.InitWithDefaultSetting` | `CLIENT_InitEx`, `CLIENT_SetNetworkParam`, `CLIENT_SetAutoReconnect`, `CLIENT_SetConnectTime` |
| `NETClient.Cleanup` | `CLIENT_Cleanup` |

Present in the library and wired through the wrapper, **not** called against hardware in this run:

| Managed wrapper | Native |
| --- | --- |
| `NETClient.Login` | `CLIENT_LoginEx2` (`EM_LOGIN_SPAC_CAP_TYPE.TCP`) |
| `NETClient.Logout` | `CLIENT_Logout` |
| `NETClient.GetLastError` | `CLIENT_GetLastError` |

Not used (by design): `CLIENT_LoginWithHighLevelSecurity`, face/user/door/attendance APIs.

## Physical Device Result

**The physical TrueFace3000 was not contacted.** No IP or credentials were provided via arguments or `TRUEFACE_*` environment variables. The application does not hardcode a device address.

To complete the decisive test:

```bash
cd TrueFaceLinuxPOC
dotnet run -c Release -- --ip <DEVICE_IP> --port 37777 --username <USER>
```

## Issues

1. **.NET 10 SDK was not preinstalled.** Installed to `$HOME/.dotnet` via `dotnet-install.sh` (no root). Ubuntu also packages `dotnet-sdk-10.0`.
2. **`libStreamConvertor.so`** is named inside `libdhnetsdk.so` strings but is **absent** from `libs/lin64`. Init still succeeded without it.
3. **`libdhplay.so`** is declared in `OriginalSDK` under `LINUX` but is not required for this flow (lazy P/Invoke). Not copied.
4. Vendor wrapper defaults `SetThrowErrorMessage(false)`; the POC keeps that and checks `IntPtr.Zero` plus `GetLastError()`, matching the wrapper’s documented login semantics (failed login returns 0).

## Changes to Vendor Code

**None.** The three copied files are byte-identical to `TrueFace_SDK/NetSDKCS/` (MD5 match). Linux behaviour is selected only by:

```xml
<DefineConstants>$(DefineConstants);LINUX;LINUX_X64</DefineConstants>
```

in `NetSDKCS/NetSDKCS.csproj`. Originals under `TrueFace_SDK/` were not edited. The gym backend/simulator were not edited.

POC-only additions (not vendor): `Program.cs`, `PocOptions.cs`, `NativeBootstrap.cs`, `DeviceSession.cs`, test project, `InternalsVisibleTo` on the wrapper **csproj**.

## Native dependencies (why each is present)

| File | Resolution |
| --- | --- |
| `libdhnetsdk.so` | Primary; `DllImport("libdhnetsdk.so")` |
| `libdhconfigsdk.so` | Runtime `dlopen` from netsdk (strings), not DT_NEEDED |
| `libavnetsdk.so` | Runtime `dlopen` from netsdk (strings) |
| `libInfra.so`, `libNetFramework.so`, `libStream.so`, `libStreamSvr.so` | DT_NEEDED of `libavnetsdk.so` (`ldd`) |

System libs (`libpthread`, `libdl`, `libstdc++`, `libm`, `libgcc_s`, `libc`) already resolve from the OS.

## Automated tests

`dotnet test -c Release`: **11 passed**, 0 failed. Configuration and native-path tests only. No fake login tests.

`--self-check` additionally loaded the `.so` and ran Init/Cleanup successfully.

## Recommendation

**Do not start the production Linux Device Gateway yet.** Host Init/Cleanup works, which is a strong ABI signal, but **login to the physical terminal is still unproven**.

Next recommended test (still this POC, no gateway work):

1. Place the machine on the device LAN.
2. Run `dotnet run -c Release -- --ip <ip> --port 37777 --username <user>`.
3. Confirm `[OK] Login successful`, serial/type/channel fields, `[OK] Logout successful`.
4. If that PASSes: proceed to a **separate** user/device-operations POC (`OperateAccessUserService` query/add — still not the production gateway).
5. If login FAILs: classify using the README (network vs authentication vs ABI crash) before any gateway implementation.
