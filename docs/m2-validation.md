# M2 validation

The Windows 10 build 19045 host and LAVA LXX503 running Android 14 were connected to the same IPv4 /28 Wi-Fi subnet. ADB installed the apps and controlled the tests. M2 test traffic did not use USB forwarding.

| Case | Evidence |
| --- | --- |
| Public Wi-Fi opt-in | Windows 10 kept B10 classified Public. The harness refused to select it without a phone address, then bound to the selected interface after explicit opt-in. The source filter accepted the on-link phone and rejected other peers. |
| LAVA trusted route and manual address policy | Physical instrumentation read the Wi-Fi identity with foreground permission, accepted the desktop as an on-link candidate, and rejected public, loopback and off-subnet destinations. Attempting the off-subnet socket failed before connection. A simulated different BSSID with the same SSID produced UNTRUSTED_WIFI. |
| Blocked path over actual Wi-Fi | Before Public opt-in, the Android source-bound TCP attempt to the desktop failed and the Windows harness did not listen. Off-subnet attempts are rejected before connecting. |
| Physical Wi-Fi interruption | Disabling and restoring Wi-Fi on the LAVA produced loss and restoration callbacks. The original Wi-Fi identity returned. Both Wi-Fi and temporary app permissions were restored. This exercised link state, with no authenticated M2 session present. |
| Desktop route and session policy | .NET tests rejected incorrect IPv4 subnets, reassigned subnets, self, broadcast, loopback, IPv6 and public endpoints. A Windows loopback TLS test simulated a route denial and verified that it blocked new input and released the existing held key. A silent-peer test received heartbeat traffic, then passed the watchdog and key-release assertions. |
| Builds | Windows Release and both self-contained x64 publishes passed. Android debug, unsigned release and instrumentation APK builds and debug lint passed. The Android JVM unit task reported NO-SOURCE. |
| M1 regression | Desktop protocol, pairing-window, TLS pin rejection and session-authorization checks passed. Android protocol and session-authorization instrumentation passed on the LAVA. |
| Windows firewall | The script declares a program-, port-, interface- and profile-scoped rule. Public uses one exact phone IPv4 address. The scripts passed syntax checks. B10 already permitted the test connection. This run did not install a rule or test its enforcement. |
| Direct Public Wi-Fi pairing and reconnect | LAVA paired with the Windows harness over B10 using manual desktop IPv4 and sent authenticated synthetic input. After the runner disabled and restored the phone Wi-Fi, the client automatically authenticated again and sent fresh synthetic input. The original pairing timestamp was unchanged. ADB controlled the phone, with no forwarding of the test connection. |

The M2 development gate passed on the available Windows 10 and LAVA setup. Public Wi-Fi requires explicit selection and one approved peer address. Unsupported or blocked paths fail closed. Remaining release validation is tracked in [M6](../MILESTONE.md#m6--packaging-release-and-documentation).

## Reproduce

Use Windows-native tooling and an authorized USB test phone. The LAN harness uses an ordinary user process and the same pairing, trust and network code as the desktop app. It accepts synthetic input only in memory.

```powershell
dotnet build desktop/RemoteKeyboard.slnx -c Release
dotnet run --project desktop/tests/RemoteKeyboard.Core.Tests -c Release --no-build
dotnet run --project desktop/tests/RemoteKeyboard.Windows.Spike -c Release --no-build
dotnet publish desktop/RemoteKeyboard.Windows -c Release -r win-x64 --self-contained -o artifacts/windows
dotnet publish desktop/tests/RemoteKeyboard.Windows.Spike -c Release -r win-x64 --self-contained -o artifacts/m2-windows
Push-Location android
.\gradlew.bat --max-workers=2 assembleDebug assembleDebugAndroidTest lintDebug testDebugUnitTest assembleRelease
Pop-Location
```

Install the scoped test rule from elevated PowerShell when required by the Windows firewall. For Public Wi-Fi, pass the phone address as shown. For Private Wi-Fi, omit it. Run the LAN test from ordinary PowerShell.

```powershell
& .\packaging\windows\Set-KeyflowFirewall.ps1 -Program .\artifacts\m2-windows\RemoteKeyboard.Windows.Spike.exe -InterfaceAlias 'Wi-Fi' -PublicPeer 'PHONE-IPV4'
```

```powershell
& .\desktop\tests\RemoteKeyboard.Windows.Spike\Test-LanInterop.ps1 -Serial 'YOUR-ADB-DEVICE-SERIAL' -PublicPhoneAddress 'PHONE-IPV4'
```

The runner uses isolated synthetic credentials, briefly toggles phone Wi-Fi, and restores the original Wi-Fi and app permission state. For the native app, select Wi-Fi in the Windows tray and select the corresponding Wi-Fi on Android. Public Wi-Fi uses manual desktop IPv4 and sends no discovery announcements. On Private Wi-Fi, the desktop IP is the fallback when discovery is unavailable. [Firewall setup and removal](../packaging/windows/README.md)