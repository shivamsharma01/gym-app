# TrueFace Linux SDK Compatibility POC

Isolated proof-of-concept: can the **existing C# NetSDK wrapper** talk to a TrueFace3000 from **Linux x64 + .NET 10 + `libdhnetsdk.so`**?

This is **not** the production Device Gateway. It only exercises:

```
initialize → login (CLIENT_LoginEx2 / TCP) → print NET_DEVICEINFO_Ex → logout → cleanup
```

No users are changed, no faces enrolled or deleted, no doors opened, no device configuration written.

A successful **login against the physical TrueFace3000** is the only result that establishes practical compatibility. Native symbols existing, or `CLIENT_InitEx` succeeding locally, is necessary but not sufficient.

## Prerequisites

- Linux x64 (tested: Ubuntu 24.04.5 LTS). The gym Windows PC cannot run this POC.
- .NET 10 SDK
- TrueFace3000 reachable on TCP **37777**
- Valid device credentials (never committed)
- The `Native/*.so` files already in this folder (tracked in git)

## Setup

On the gym Linux machine: clone/copy this repo (or just `TrueFaceLinuxPOC/`) and install .NET 10. You do **not** need `dahua-sdk-master` and you do **not** need `./copy-native.sh`.

### 1. .NET 10 SDK

If `dotnet --version` is not 10.x:

```bash
curl -fsSL https://dot.net/v1/dotnet-install.sh | bash -s -- --channel 10.0
export DOTNET_ROOT="$HOME/.dotnet"
export PATH="$HOME/.dotnet:$PATH"
```

Ubuntu 24.04 can also use `sudo apt-get install -y dotnet-sdk-10.0`.

### 2. Native libraries

The required Linux x64 `.so` files ship in `Native/`:

| Library | Why |
| --- | --- |
| `libdhnetsdk.so` | Primary NetSDK. P/Invoke target of `OriginalSDK`. ELF 64-bit x86-64. Exports `CLIENT_InitEx`, `CLIENT_LoginEx2`, `CLIENT_Logout`, `CLIENT_Cleanup`. |
| `libdhconfigsdk.so` | Runtime `dlopen` from `libdhnetsdk.so` (not a DT_NEEDED). Login/init historically load it. |
| `libavnetsdk.so` | Same: name appears in `libdhnetsdk.so` strings. |
| `libInfra.so`, `libNetFramework.so`, `libStream.so`, `libStreamSvr.so` | DT_NEEDED of `libavnetsdk.so`. |

Not included: `libjawt.so` (Java AWT), `libdhplay.so` (play SDK; P/Invoke is lazy and unused here), `libStreamConvertor.so` (named in netsdk strings but absent from the vendor lin64 pack; not required for Init).

Libraries stay **application-local**. They are copied to the build output. `LD_LIBRARY_PATH` is prepended at process start so native `dlopen` of companions works. Nothing is installed into `/usr/lib`.

`./copy-native.sh` is only for refreshing `Native/` from a local SDK pack (`$HOME/Downloads/dahua-sdk-master/libs/lin64` or `DAHUA_LIN64_DIR`). That pack exists on the development workstation, not on the gym machine.

### 3. Build

```bash
cd TrueFaceLinuxPOC
dotnet restore
dotnet build -c Release
```

Vendor C# is compiled with `LINUX` and `LINUX_X64` defined in `NetSDKCS/NetSDKCS.csproj`. The original `TrueFace_SDK/` tree is not modified.

### 4. Tests (no hardware)

```bash
dotnet test -c Release
```

These cover configuration parsing, invalid ports, password redaction, and native-path handling. They **do not** claim that device login works.

## Running

Password is prompted without echo if omitted. Prefer env vars over putting a password on the command line (shell history).

```bash
dotnet run --project TrueFaceLinuxPOC.csproj -c Release -- \
    --ip <DEVICE_IP> \
    --port 37777 \
    --username <USERNAME>
```

Environment equivalents: `TRUEFACE_IP`, `TRUEFACE_PORT`, `TRUEFACE_USERNAME`, `TRUEFACE_PASSWORD`, `TRUEFACE_NATIVE_DIR`.

Self-check (load native library + `CLIENT_InitEx` + cleanup, **no device**):

```bash
dotnet run --project TrueFaceLinuxPOC.csproj -c Release -- --self-check
```

## Expected successful output (example)

```
[INFO] Starting TrueFace Linux SDK POC
[INFO] Runtime: .NET 10.0.x
[INFO] OS: Ubuntu 24.04.x LTS (X64)
[INFO] Native SDK: libdhnetsdk.so
[INFO] Device: 192.0.2.10:37777
[INFO] Initializing SDK...
[OK] SDK initialized
[INFO] Logging in via NETClient.Login → CLIENT_LoginEx2 (TCP)...
[OK] Login successful
[INFO] Serial Number: TW3000...
[INFO] Device Type: ...
[INFO] Channel Count: ...
[INFO] Logging out...
[OK] Logout successful
[OK] SDK cleanup completed
```

Passwords, tokens, and biometric data are never printed.

## Troubleshooting

Classify failures; do not lump everything into “Linux SDK unsupported.”

### Native library not found

`[ERROR] Native loading: libdhnetsdk.so not found`

`Native/` is missing the tracked `.so` files. Re-copy the full `TrueFaceLinuxPOC` folder, or pass `--native-dir` at a directory that contains them.

### Dependency missing

`DllNotFoundException` / `libxxx.so: cannot open shared object file`

A companion `.so` was `dlopen`ed. Confirm every file listed above is in `Native/` and that `LD_LIBRARY_PATH` includes `Native/` (the app sets this itself). Check with `ldd Native/libavnetsdk.so`.

### Architecture mismatch

`libdhnetsdk.so` must be `ELF 64-bit … x86-64`. A 32-bit or ARM library will fail to load. `file Native/libdhnetsdk.so`.

### SDK initialization failed

`CLIENT_InitEx` returned false. Print the SDK error code/description from the app output. Could be missing companions, ABI, or a corrupted library.

### Connection refused / timeout

Device unreachable, wrong IP, firewall, or port 37777 closed. This is a **network** problem, not an ABI problem. `nc -vz <IP> 37777`.

### Login failed

SDK initialized and TCP may work, but `CLIENT_LoginEx2` returned a zero handle. Wrong credentials, user locked, or device/protocol mismatch (`NET_DEV_VER_NOMATCH`, `NET_NOT_AUTHORIZED`, etc.). The app prints the **vendor** error code and mapped description from `NETClient.GetLastError()`.

### Native crash (SIGSEGV)

Likely **ABI/marshalling**: structure packing, `LINUX_X64` not defined, callback signature, or string marshalling. Capture a stack with `coredumpctl` / `gdb`. Do not proceed to a production gateway until this is understood.

## Vendor source

Copied (byte-identical) from `TrueFace_SDK/NetSDKCS/`:

- `NetSDK.cs`
- `NetSDKStruct.cs`
- `OriginalSDK.cs`

**No edits** were made to those copies. Linux P/Invoke names and `LINUX_X64` struct layouts are selected only via `DefineConstants`.

## Compatibility conclusion

| Check | Status on this workstation |
| --- | --- |
| `dotnet build` | PASS |
| Load `libdhnetsdk.so` | PASS (`--self-check`) |
| `CLIENT_InitEx` / cleanup | PASS (`--self-check`) |
| Physical `CLIENT_LoginEx2` | **Not run** — no device IP/credentials supplied |

Only a successful login to the real TrueFace3000 establishes practical compatibility.

## What this is not

Do not grow this project into the production gateway. After a real-device PASS, the next work is a separate .NET 10 Device Gateway with `DeviceAdapter`, WSS, outbox, etc.
