# KeyFlow

KeyFlow is a local-only Android keyboard for Windows under development. The Android keyboard pairs with Windows and exchanges authenticated input over selected Wi-Fi. Native Windows focus and input integration is scheduled for M4.

## Development on Windows

Install the .NET 10 SDK, JDK 17 and the Android SDK with platform 37 and Build Tools 36.0.0. Set `JAVA_HOME` and `ANDROID_HOME` to those installations.

```powershell
dotnet build desktop/RemoteKeyboard.slnx -c Release
dotnet run --project desktop/tests/RemoteKeyboard.Core.Tests -c Release --no-build
dotnet publish desktop/RemoteKeyboard.Windows -c Release -r win-x64 --self-contained -o artifacts/windows
Push-Location android
.\gradlew.bat --no-daemon lintDebug testDebugUnitTest assembleDebug
Pop-Location
```

Production targets are Windows 10 22H2 x64, supported Windows 11 x64 releases, and Android API 29+. See the [M0 validation matrix](docs/m0-validation.md) for verified results. Windows 10 22H2 is past regular servicing. Use current security updates and applicable ESU coverage. [Microsoft servicing information](https://learn.microsoft.com/en-us/windows/release-health/supported-versions-windows-client)

See the [roadmap](MILESTONE.md) and [architecture decisions](docs/adr/0001-m0-architecture.md).
