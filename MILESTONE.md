# MILESTONE.md — Implementation roadmap

Follow AGENTS.md. Development gates govern when implementation can advance. Production release gates are mandatory and require recorded evidence. Unavailable hardware or OS environments leave their validation pending and do not by themselves block subsequent development. Check boxes only after recording actual evidence. Windows 10 22H2 x64 and Windows 11 x64 are mandatory production targets. Develop on Windows; no Linux components. Do not build the full product in one change.

## M0 — Scaffold and risk discovery

- [x] Scaffold the monorepo, desktop and Android projects, test projects, protocol and docs, and baseline Windows/Android CI.
- [x] Record the transport, framing, OS adapter boundaries, trusted-LAN and privacy design, and J-PAKE feasibility evidence.
- [x] Demonstrate initial feasibility on Windows 10 22H2 and LAVA Android 14 using synthetic inputs.

**Development gate closed.** Windows 10 passed the native UIA, SendInput and TLS 1.2 probes. The LAVA demonstrated foreground launch, notification fallback after a background launch denial, and fallback posting while asleep and during forced Doze. Both platforms confirmed matching J-PAKE codes and rejected mismatched codes. See the [validation matrix](docs/m0-validation.md).

M1 implemented production identity binding. Remaining release validation is tracked in [M6](#m6--packaging-release-and-documentation).

## M1 — Protocol and secure transport

- [x] Define versioned messages for capabilities, focus generations, key and Unicode input, release, heartbeat, errors and pause, with strict limits and canonical keys.
- [x] Implement the desktop core and Android trust paths, OTP pairing, mutual TLS, protected storage, revocation and fixed 30-day authorization.
- [x] Run the Windows in-memory focus/input harness against the Android test client with no production OS injection.
- [x] Test interoperability, wrong OTP, throttling, unknown and substituted identities, replay, malformed frames, expiry during an active session, clock rollback, revocation, disconnect and stale focus.
- [x] Bind the protocol context and both long-term device identities to the OTP-authenticated exchange and verify TLS proof of possession before trust is persisted.

**Development gate closed.** The Windows 10 and LAVA end-to-end workflow paired, exchanged authenticated application frames and reconnected from persisted credentials. Negative security cases passed on the connected device and in component tests. See the [M1 validation notes](docs/m1-validation.md) for evidence and [M6](#m6--packaging-release-and-documentation) for remaining release validation.

## M2 — LAN discovery, VPN and recovery
- [x] Implement trusted Wi-Fi selection, interface/network callbacks, mDNS/NSD and manual IP entry, with scoped firewall setup for Private and explicitly selected Public Wi-Fi.
- [x] Implement per-network binding where permitted, reconnect/backoff, heartbeat/watchdog, sleep/resume and route-change handling.
- [x] Verify refusal of Public Wi-Fi without explicit approval, off-subnet and mismatched Wi-Fi rejection, live phone network callbacks, session watchdog and held-key cleanup on route denial.
- Development gate: reconnect without re-pairing inside authorization window; blocked LAN fails safely with meaningful status; no off-LAN input.

**Development gate closed.** Windows 10 and LAVA paired over explicitly selected Public Wi-Fi, exchanged authenticated synthetic input and automatically reconnected after real Wi-Fi loss with the original authorization. See the [M2 validation notes](docs/m2-validation.md) for evidence and [M6](#m6--packaging-release-and-documentation) for remaining release validation.

## M3 — Android keyboard and lifecycle
- [x] Implement keyboard renderer, canonical key mapping, Unicode commits, shortcuts, sticky and held modifiers, repeat, rotation, haptics and setup controls.
- [x] Implement foreground service, focus-scoped automatic activation and dismissal, Android companion chooser, opt-in wake/keyguard presentation and notification fallback.
- [x] Run the Windows test harness and LAVA over Wi-Fi. Verify keyboard ordering, repeat, Unicode, rotation, dismissal, trusted reconnect, awake/background activation paths, screen-off focus, secure keyguard, forced Doze and no automatic service start after reboot.

**Development gate closed.** LAVA and Windows 10 demonstrated a usable keyboard over authenticated Wi-Fi, safe dismissal and reconnect, and the phone's foreground and notification activation paths. See the [M3 validation notes](docs/m3-validation.md) for evidence and [M6](#m6--packaging-release-and-documentation) for remaining release validation.

## M3.5 — KeyFlow visual identity
- [x] Use shared KeyFlow icon artwork for the Android launcher and splash and the Windows executable and tray.
- [x] Apply the KeyFlow name and soft glass styling to setup and keyboard, with a matching Windows palette and native high-contrast support.
- [Visual identity and validation](docs/m3.5-validation.md)

## M4 — Windows native integration
- [ ] Interactive user agent, UIA editable focus detection and bounded click fallback, debounce, focus generation and session-lock monitor.
- [ ] Win32 injection, Unicode and scan-code mapping, modifier ownership/release; manual mode, sensitive-field policy and secure desktop protection.
- [ ] Tray, startup, DPAPI storage, pairing/revocation window, pause/exit; ensure single instance and clean shutdown.
- [ ] Test Notepad, Chrome/Edge input and contenteditable, VS Code, Office, terminals, readonly/password fields, same-field clicks, focus changes, Windows lock, elevated apps and disconnect mid-shortcut.
- [ ] Verify ordinary-user injection stays within UIPI and active, unlocked desktop limits, including elevated apps, Windows lock and secure desktop. Verify held-key release and fail-closed focus revalidation.
- Development gate: local end-to-end typing and safety behavior on available targets with gaps documented. The full desktop compatibility matrix is tracked in M5.

## M5 — Hardening and performance
- [ ] Threat model review, dependency/license audit, protocol fuzz/property tests, privacy inspection of app-owned storage and network captures with synthetic keys.
- [ ] Stress network interruptions, concurrent focus changes, stale messages, disconnect while modifier held, expiry while connected, malformed input, sleep and battery saver.
- [ ] Measure latency, desktop idle CPU/memory and Android idle battery overhead with baseline and device details. Tune without weakening controls.
- [ ] Verify no logging of user content and no external network calls; document residual risks (OS memory/dumps, trusted peer compromise, OEM limits).
- [ ] Complete cross-platform runtime, UIA, input, lifecycle, LAN/VPN and TLS validation on actual Windows 10 22H2 x64 and supported Windows 11 x64 installations or interactive VMs, plus physical Android coverage including Samsung. Verify end-to-end activation and typing and document unsupported applications. Windows Server CI is build evidence only.
- [ ] Verify TLS version, peer authentication and cipher restrictions in cross-platform integration tests. Require TLS 1.2 ECDHE/AEAD on Windows 10 and prefer TLS 1.3 on Windows 11.
- Development gate: automated hardening, privacy and local performance checks pass on available targets. Record remaining device-dependent work before packaging.
- Track remaining release evidence in [M6](#m6--packaging-release-and-documentation). Pending physical validation permits development to proceed to packaging.

## M6 — Packaging, release and documentation
- [ ] Self-contained Windows x64 publish and standalone setup EXE; clean install, upgrade, startup opt-in and uninstall on clean Windows 10 22H2 and Windows 11 VMs. Verify runtime-free installation, firewall scope and upgrade on both.
- [ ] Stable signed Android release APK; test install and update with same signing identity. Android SDK/ABI support documented.
- [ ] GitHub Actions PR checks; protected tag release workflow publishes setup EXE, APK, SHA-256 checksums, release notes; signing secrets protected. Verify artifacts; clearly label Windows unsigned if applicable.
- [ ] Final README with installation, pairing, feature overview, permissions, VPN/lock-screen caveats, screenshots, architecture, build commands, troubleshooting and security reporting.
- Development gate: build and smoke-test the installer and APK on available targets.

### Release validation carried forward from M0–M3

- [ ] **M0/M1 — Production pairing and Windows safety.** Exercise production pairing UI, identity binding and proof of possession, wrong OTP and substituted identities, revocation and fixed expiry on the release platforms. Verify ordinary-user UIA and input injection in external apps, including elevated-app/UIPI boundaries, Windows lock and secure desktop.
- [ ] **M0/M1 — Target devices and TLS.** Complete Windows 10 22H2 and Windows 11 x64 coverage and physical Samsung and supported Android coverage. Record actual Android-to-Windows TLS versions, cipher suites and mutual identity checks. Verify hosted PR and release checks.
- [ ] **M2 — Discovery and firewall.** Verify Private-network mDNS/NSD and manual IP fallback, and enforce installed Private and explicitly opted-in Public firewall rules, including rejection of a Public source other than the approved on-link phone IPv4 address.
- [ ] **M2 — Network changes and VPN.** Verify recovery without re-pairing after actual IP/DHCP changes, roaming and Windows/Android sleep or resume. Test same-SSID/different-BSSID and a separate untrusted AP, VPN LAN-allowed and LAN-blocked profiles, AP isolation and Android 17 local-network permission variants. Verify blocked access fails securely.
- [ ] **M0/M3 — Android activation.** Verify real companion consent and removal with a discoverable Windows desktop, background launch exemptions, and notification fallback when Bluetooth is unavailable, the association is removed, or Android denies a launch. Test notification permission allowed and denied, notification tap from secure keyguard, and opt-in wake and lock-screen display with typing disabled while locked.
- [ ] **M0/M3 — Android lifecycle.** Verify force-stop recovery and explicit post-boot restart with persisted trust on release builds. Measure natural Doze and battery drain against idle baseline, test battery saver, and verify foreground, background, screen-off, secure keyguard, companion and notification behavior under Samsung OEM power management.
- [ ] **M3 — Released keyboard.** Verify hardware touch-held modifiers, haptic feedback and latency with the signed APK, plus end-to-end focus and native SendInput with the production Windows agent.
- [ ] **M3.5 — Release branding.** Verify the Android launcher icon and cold-start splash on supported Android versions, including API 29–30 and themed icon launchers. Verify Windows executable, tray and installer icon presentation on Windows 10 and Windows 11, including high contrast and display scaling.

**Production release gate.** Resolve release-blocking security failures and complete all mandatory AGENTS.md security, privacy, performance, compatibility and packaging checks. Tagged artifacts must install independently, tests must pass, and compatibility claims must match recorded evidence. Release only with explicit approval.
