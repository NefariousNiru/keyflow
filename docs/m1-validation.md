# M1 validation

Tested on Windows 10 Pro 22H2 build 19045 x64 with .NET SDK 10.0.404 and LAVA LXX503 Android 14 (API 34). The physical-device suite used a local ADB forward with an in-memory TCP relay, using Platform-Tools 34.0.5 after the installed 37.0.3 ADB server crashed while testing reverse forwarding. Production protocol, TLS and storage code ran on both endpoints. The desktop harness accepted a synthetic text commit into memory.

| Area | Evidence |
| --- | --- |
| Full pairing | Both sides completed all three J-PAKE rounds, verified their committed public-key identities via TLS and wrote protected trust. Negotiated TLS 1.2 with TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384. The desktop accepted a synthetic input frame after authentication. |
| Durable reconnect | Reconnected using the same stored pins and issuance time across a new Windows harness process and a new Android instrumentation process. No OTP was used and the lifetime did not slide. |
| Pairing rejection | Wrong OTP, peer-key substitution between PAKE and TLS, a forged desktop role in the Android round, oversized pairing frame and missing protected-write acknowledgement all rejected without changing desktop trust. |
| Reconnect rejection | A different Android key and a different desktop key were both refused. A repeated application sequence on an authenticated connection closed the session. |
| Storage integrity | Modifying the Windows DPAPI ciphertext or Android Keystore AES-GCM ciphertext was rejected. Windows stored its identity and pin with DPAPI CurrentUser. The Android private identity and the trust-encryption key were Keystore-backed. |
| Clock and expiry | Injected clock offsets caused each endpoint independently to reject rollback and expired credentials. The desktop active session closed on its authorization check after advancing 30 days. Component tests also exercised the exact expiration boundary and monotonic expiry. |
| Revocation | The desktop canceled the active connection immediately, deleted its stored trust and refused reconnect. The Android cleanup called its local revoke path, deleted the trusted record and test-only aliases. |
| Component tests | Six desktop focus policy cases passed, plus framing, malformed UTF-8 and sizes, authorization, sequence gaps, stale focus, owned-key release, and OTP arming, throttling, single use, cooldown and expiry checks. Android framing and authorization instrumentation passed. Windows loopback TLS rejected wrong pins in both roles. |
| Builds and dependencies | .NET Release solution built with zero warnings and errors, self-contained Windows x64 publish passed, and NuGet reported no vulnerable packages from the configured source. Android debug, unsigned release and instrumentation builds and lint passed. The Android JVM unit-test task returned NO-SOURCE. |

Direct LAN connectivity and recovery are M2 work. The pairing and input harness does not install a LAN listener, inject real keyboard input, or test the production pairing UI. Remaining release validation is tracked in [M6](../MILESTONE.md#m6--packaging-release-and-documentation).

## Reproduce

Build the solution and APKs on Windows, then pass the authorized USB device serial to the physical-device test runner. The runner uses its own temporary desktop credentials and Android Keystore namespace.

```powershell
dotnet build desktop/RemoteKeyboard.slnx -c Release
dotnet run --project desktop/tests/RemoteKeyboard.Core.Tests -c Release --no-build
dotnet run --project desktop/tests/RemoteKeyboard.Windows.Spike -c Release --no-build
dotnet publish desktop/RemoteKeyboard.Windows -c Release -r win-x64 --self-contained -o artifacts/windows
dotnet list desktop/RemoteKeyboard.slnx package --vulnerable --include-transitive --no-restore
Push-Location android
.\gradlew.bat --max-workers=2 assembleDebug assembleDebugAndroidTest lintDebug testDebugUnitTest assembleRelease
Pop-Location
.\desktop\tests\RemoteKeyboard.Windows.Spike\Test-TrustInterop.ps1 -Serial 'YOUR-ADB-DEVICE-SERIAL'
adb shell am instrument -w -r io.keyflow.remotekeyboard.test/io.keyflow.remotekeyboard.protocol.ProtocolInstrumentation
```