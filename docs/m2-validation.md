# M2 validation

The Windows 10 build 19045 host and LAVA LXX503 running Android 14 were connected to the same IPv4 /28 Wi-Fi subnet. ADB installed the apps and controlled the tests. M2 test traffic did not use USB forwarding.

| Case | Evidence |
| --- | --- |
| Windows Public profile | Windows still reported the active Wi-Fi profile as Public. The published harness returned PRIVATE_WIFI_REQUIRED and neither M2 TCP port was listening. |
| LAVA trusted route and manual address policy | Physical instrumentation read the Wi-Fi identity with foreground permission, accepted the desktop as an on-link candidate, and rejected public, loopback and off-subnet destinations. Attempting the off-subnet socket failed before connection. A simulated different BSSID with the same SSID produced UNTRUSTED_WIFI. |
| Blocked path over actual Wi-Fi | A source-bound Android TCP attempt to the desktop on the Public profile failed. No pairing, TLS session or input was accepted. |
| Physical Wi-Fi interruption | Disabling and restoring Wi-Fi on the LAVA produced loss and restoration callbacks. The original Wi-Fi identity returned. Both Wi-Fi and temporary app permissions were restored. This exercised link state, with no authenticated M2 session present. |
| Desktop route and session policy | .NET tests rejected incorrect IPv4 subnets, reassigned subnets, self, broadcast, loopback, IPv6 and public endpoints. A Windows loopback TLS test simulated a route denial and verified that it blocked new input and released the existing held key. A silent-peer test received heartbeat traffic, then passed the watchdog and key-release assertions. |
| Builds | Windows Release and both self-contained x64 publishes passed. Android debug, unsigned release and instrumentation APK builds and debug lint passed. The Android JVM unit task reported NO-SOURCE. |
| M1 regression | Desktop protocol, pairing-window, TLS pin rejection and session-authorization checks passed. Android protocol and session-authorization instrumentation passed on the LAVA. |
| Windows firewall | The rule script passed syntax and parameter checks. Installing it requires an elevated setup session and a user-approved Private profile. No firewall policy was changed during this run. |
| Direct Wi-Fi pairing, discovery and reconnect | Pending. The public profile prevents the scoped positive test. The paired-input, manual IP, mDNS and Wi-Fi recovery cases are in the LAN runner below. |
| Release validation | Actual DHCP IP lease changes, roaming and a separate untrusted AP, VPN LAN-allowed and LAN-blocked profiles, AP isolation, sleep/resume, Windows 11, Samsung and Android 17 local-network permissions remain pending. No VPN or router test setup was available during this run. |

The M2 development gate remains open. The private-profile and firewall setup must be made by the administrator of the trusted network. Keep Public Wi-Fi blocked.

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

On a trusted Windows Private Wi-Fi profile, install the scoped test rule from elevated PowerShell. Run the LAN test from ordinary PowerShell after the rule is active.

```powershell
& .\packaging\windows\Set-KeyflowFirewall.ps1 -Program .\artifacts\m2-windows\RemoteKeyboard.Windows.Spike.exe -InterfaceAlias 'Wi-Fi'
```

```powershell
& .\desktop\tests\RemoteKeyboard.Windows.Spike\Test-LanInterop.ps1 -Serial 'YOUR-ADB-DEVICE-SERIAL'
```

The runner uses isolated synthetic credentials, briefly toggles phone Wi-Fi, and restores the original Wi-Fi and app permission state. For the native app, select Wi-Fi in the Windows tray and select the corresponding Wi-Fi on Android. Use the desktop IP as a fallback when discovery is unavailable. [Firewall setup and removal](../packaging/windows/README.md)