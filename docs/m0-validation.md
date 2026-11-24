# M0 validation

| Environment or scenario | Evidence |
| --- | --- |
| Local Windows host | Windows 10 Pro build 19045 reported by Win32_OperatingSystem. |
| Windows 10 22H2 x64, .NET SDK 10.0.404 | Release solution build succeeded with zero warnings and errors. All six synthetic focus policy cases passed. Self-contained win-x64 publish succeeded. The NuGet audit reported no vulnerable packages from the configured source. |
| Windows native behavior | On Windows 10 Pro build 19045, the ordinary-user WinForms probe verified focused editable-control metadata via UI Automation and received one synthetic Unicode character through SendInput. The local Schannel loopback probe negotiated TLS 1.2 with TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384. Validation was scoped to this Windows 10 host. Windows 11, external app coverage, elevated and secure-desktop boundaries, and Android TLS interoperability have no test evidence. |
| Android build | Gradle 9.3.1 with JDK 17, Android Platform 37 and Build Tools 36.0.0 passed `lintDebug` (zero errors, six warnings), `assembleDebug` and `assembleRelease`. `testDebugUnitTest` completed as NO-SOURCE. APK manifests confirm both probes and the notification permission are debug-only. |
| GitHub Actions | Baseline workflow added. Hosted runs pending. |
| LAVA LXX503, Android 14 (API 34) | Debug APK installed over authorized USB and cold-launched. Build LAVA_LXX503_SW_U_V09_HW_V1.0_20250725, awake and unlocked, Battery Saver off and device idle state ACTIVE. |
| LAVA foreground activation | The delayed synthetic trigger opened Keyflow while the probe activity was visible. |
| LAVA background activation | After Home, Android logged a background activity start denial, the launcher stayed foreground, and the probe posted its notification. |
| LAVA notification fallback | With notification permission granted, tapping the displayed test notification opened Keyflow. Temporary permission changes were reverted. |
| LAVA screen off and secure keyguard | In the repeat run, the phone was asleep at the start and end of the probe interval and the notification was active. After an explicit ADB wake, Android reported the secure keyguard showing. No unlock was attempted. The first screen-off run ended awake and did not establish an uninterrupted screen-off interval. |
| LAVA forced deep Doze | Android reported deep IDLE and an asleep display while the probe notification was active. Forced idle was reverted. Natural Doze timing and a tap from the locked state remain untested. |
| LAVA companion feature | Android advertises android.software.companion_device_setup. Association and any background-start exemption remain untested. |
| Physical Samsung | Foreground, background, screen off, secure keyguard, Doze, companion eligibility, notification tap and OEM power management pending. |
| Pairing primitive, Windows 10 and LAVA | Bouncy Castle .NET 2.7.0 and Java 1.87 completed all three J-PAKE rounds over USB. Both endpoints confirmed matching synthetic codes and rejected mismatched codes. The probe creates no device pins or authenticated TLS session. Production transcript and device-key binding review remains open. See the [pairing ADR](adr/0001-m0-architecture.md). |

For Android, record device model, OS build, prior activity state, permissions and battery mode. Use synthetic local triggers. Record whether the surface opens automatically, opens on notification tap, or remains blocked. For Windows, record OS build, runtime, app and privilege level. Use synthetic text and report metadata and outcomes only. Record actual TLS versions and cipher suites for each tested connection. GitHub-hosted Windows runners provide build evidence only.

## Reproduce the Windows probes

Run these from the repository root using .NET 10. The default command uses loopback TLS with a short-lived locally trusted test certificate scoped to the client chain policy. The interactive command opens a dedicated test field in the ordinary user desktop and checks that the field is foreground before sending a synthetic Unicode character.

```powershell
dotnet run --project desktop/tests/RemoteKeyboard.Windows.Spike -c Release
dotnet run --project desktop/tests/RemoteKeyboard.Windows.Spike -c Release -- --interactive
```
## Reproduce the Android probe

Install the debug APK on an authorized USB device and grant notifications for Keyflow. Start the probe while the device is awake and unlocked. It waits 20 seconds, requests a local activity start and posts a test notification. For the background case, force-stop Keyflow before starting a fresh probe, then press Home during the wait. If the launcher remains foreground, tap **Open Keyflow test** in the notification shade to check the fallback.

```powershell
adb shell am start -W -n io.keyflow.remotekeyboard/.ActivationProbeActivity
```

Android restricts background activity starts. Companion eligibility and exceptions require separate checks on the target devices. [Android background activity start rules](https://developer.android.com/guide/components/activities/secure-bal)
## Reproduce the USB pairing probe

Build the debug APK, install it on an authorized Android device, then run these commands from the repository root. The runner relays only synthetic J-PAKE messages through ADB, checks mutual confirmation in both cases and cleans up app-private test files.

```powershell
dotnet build desktop/tests/RemoteKeyboard.Pairing.Spike -c Release
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
.\desktop\tests\RemoteKeyboard.Pairing.Spike\Test-AndroidInterop.ps1 -Serial 'YOUR-ADB-DEVICE-SERIAL'
```