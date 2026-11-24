# M0 validation

| Environment or scenario | Evidence |
| --- | --- |
| Local Windows host | Windows 10 Pro build 19045 reported by Win32_OperatingSystem. |
| Windows 10 22H2 x64, .NET SDK 10.0.404 | Release solution build succeeded with zero warnings and errors. All six synthetic focus policy cases passed. Self-contained win-x64 publish succeeded. The NuGet audit reported no vulnerable packages from the configured source. |
| Windows native behavior | Published GUI runtime, ordinary-app UIA and SendInput, elevated/secure-desktop boundary and negotiated TLS tests pending on Windows 10 22H2 and Windows 11. |
| Android build | Gradle 9.3.1 wrapper JAR and distribution verified. With JDK 17 and the configured local SDK, `gradlew.bat assembleDebug` passed using Android Platform 37 and Build Tools 36.0.0. Android lint and unit tests remain pending. |
| GitHub Actions | Baseline workflow added. Hosted runs pending. |
| Physical Samsung | Foreground, background, screen off, secure keyguard, Doze, companion eligibility, notification tap and OEM power management pending. |
| Pairing | Library interoperability and authenticated transcript design pending. |

For Android, record device model, OS build, prior activity state, permissions and battery mode. Use synthetic local triggers. Record whether the surface opens automatically, opens on notification tap, or remains blocked. For Windows, record OS build, runtime, app and privilege level. Use synthetic text and report metadata and outcomes only. Record actual TLS versions and cipher suites for each tested connection. GitHub-hosted Windows runners provide build evidence only.
