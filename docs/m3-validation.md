# M3 validation

The Windows 10 build 19045 x64 host and LAVA LXX503 running Android 14 used the B10 Public Wi-Fi network. The Windows harness selected the on-link phone address explicitly. ADB installed the debug APKs and drove device UI and power state. Keyboard traffic flowed over Wi-Fi with the existing pairing and mutually authenticated TLS transport.

| Case | Evidence |
| --- | --- |
| Setup in foreground | The debug fixture started the same foreground-service implementation from a visible activity. The Windows focus event opened the keyboard automatically after fresh OTP pairing. |
| Keyboard and rotation | The Windows harness received Q down/up, Ctrl+C with balanced control and key events, F12, balanced repeated Backspace, a Unicode sample and a supplementary Unicode scalar entered with the in-app keypad. The keyboard survived portrait-to-landscape rotation and could be dismissed there. |
| Modifiers, buffers and stale focus | Android model checks covered one-shot, latched and held modifiers, chord order, Unicode scalar validation, bounded queue overflow, priority release, and dismissal suppression. The Windows session and input tests covered stale focus, authorization and owned key release. |
| Dismissal and background launch | Dismissal held for the same focus. A fresh focus from the launcher produced a notification. Tapping it opened the keyboard for the current focus. |
| Companion eligibility | LAVA reports both the Android companion setup feature and Bluetooth support. The Windows host enumerates an Intel Bluetooth radio. |
| Wi-Fi interruption and recovery | Disabling Wi-Fi while the keyboard was visible cleared focus and hid the keyboard. Restoring Wi-Fi reauthenticated without an OTP. The previous focus token could no longer send input. A separate M2 regression also verified the original pairing timestamp through recovery. |
| Screen off and secure keyguard | A fresh authenticated focus reached the phone with its screen off. The phone stayed asleep and rejected input. After wake the secure keyguard remained locked and the service rejected keyboard input. |
| Forced deep Doze | Android reported deep idle after the test used `cmd deviceidle force-idle deep`. The phone stayed asleep and keyboard input was rejected. The runner removed the forced idle state afterward. KeyFlow was absent from the system's battery-optimization exemption list. |
| Reboot | ADB rebooted the LAVA and confirmed Android completed startup, the secure keyguard was present and the KeyFlow service had no service record. A fresh foreground start after unlock remains to be verified. |
| Android and Windows builds | Android debug, instrumentation and unsigned release builds and debug lint passed. The Android JVM unit task had no source. Windows Release build, core checks and self-contained x64 harness publish passed. Native Windows loopback verified TLS 1.2 pinning, link-policy loss and held-key release, heartbeat and watchdog. |

## Pending release validation

- Complete Android companion chooser consent with a discoverable Windows Bluetooth desktop. Verify the association can be removed, the associated app can launch from background where Android allows it, and the opt-in wake flags present the keyboard above secure keyguard while keeping input disabled. Confirm the notification path with Bluetooth unavailable or the association removed.
- Measure natural Doze and battery drain against idle baseline. Validate battery saver, force-stop recovery and Samsung-specific background and power management. Verify notification-denied UX, other supported Android versions and Windows 11 in the release matrix.
- Verify persistent trust by explicitly restarting the connection after a reboot and unlock.
- Validate keyboard behavior on released APK builds, including touch-held modifiers on hardware, haptic feedback, and latency. The M4 Windows integration still needs native focus and SendInput validation.
- All open M1 and M2 release requirements remain in force.

## Reproduce

Build and publish with the commands in [M2 validation](m2-validation.md). With the LAVA awake and unlocked on the selected Wi-Fi, run the physical keyboard test in ordinary PowerShell. Omit `-PublicPhoneAddress` for an eligible Private profile. The test toggles phone Wi-Fi, rotates the screen, performs a shell-forced Doze cycle, and ends at the phone's existing keyguard.

```powershell
.\desktop\tests\RemoteKeyboard.Windows.Spike\Test-KeyboardInterop.ps1 `
  -Serial '<adb serial>' -Adb '<path to adb.exe>' -PublicPhoneAddress '<phone Wi-Fi IPv4>'
```

Add `-ForceStopOnly` to exercise force-stop and explicit foreground restart without locking the phone for the screen-off test.
