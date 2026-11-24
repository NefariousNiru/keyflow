# MILESTONE.md — Implementation roadmap

Follow AGENTS.md. Each gate is mandatory unless explicitly scoped experimental. Check boxes only after recording actual evidence. Windows 10 22H2 x64 and Windows 11 x64 are mandatory production targets. Develop on Windows; no Linux components. Do not build the full product in one change.

## M0 — Scaffold and risk discovery
- [ ] Scaffold monorepo, .NET solution/core/Windows agent/tests, Android Gradle project, protocol and docs; add baseline Windows/Android CI.
- [ ] ADRs: transport/framing, authenticated OTP pairing library and interoperability, OS adapters, trusted network and privacy threat model.
- [ ] Physical Android spike: test foreground/background, screen off, secure lock screen, Doze, companion eligibility, notification fallback and Samsung power management using local synthetic triggers. No production unauthenticated network endpoint.
- [ ] Tiny Windows spike: UIA focus metadata and SendInput with ordinary apps; record elevated and secure desktop boundaries. Run on both Windows 10 22H2 and Windows 11; verify runtime, UIA, injection and TLS compatibility (TLS 1.2 ECDHE/AEAD on Windows 10; prefer TLS 1.3 on Windows 11). Record untested cases as pending.
- Gate: documented activation matrix and viable secure pairing design; if auto-wake impossible on target device, keep notification fallback and explicitly record degraded UX.

## M1 — Protocol and secure transport
- [ ] Define versioned messages: handshake/capabilities, focus state/generation, key down/up, text commit, release-all, heartbeat, errors, pause. Strict limits and canonical key mapping.
- [ ] Implement desktop core session lifecycle, OTP authenticated pairing, peer key pinning, TLS, 30-day expiry, revocation, secure-store abstraction.
- [ ] Windows test harness simulates focus and receives synthetic input in memory only; Android client connects with a minimal test surface. No production OS injection yet.
- [ ] Test interop, wrong OTP, brute-force throttling, MITM/unknown peer, replay, malformed messages, expiry during active session, clock rollback, revocation, disconnect and stale events.
- Gate: authenticated Windows-to-Android end-to-end session; no plaintext or insecure fallback.

## M2 — LAN discovery, VPN and recovery
- [ ] Trusted Wi-Fi selection, interface/network callbacks, mDNS/NSD and manual IP entry; private firewall scope on Windows.
- [ ] Per-network socket binding where permitted, reconnect/backoff, heartbeat/watchdog, sleep/resume and route-change handling.
- [ ] Test changing IP, roaming, network loss, same SSID/different network, untrusted network, VPN with LAN allowed and VPN with LAN blocked, AP client isolation.
- Gate: reconnect without re-pairing inside authorization window; blocked LAN fails safely with meaningful status; no off-LAN input.

## M3 — Android keyboard and lifecycle
- [ ] Implement keyboard renderer, key mapping, text commit, shortcuts/modifiers/repeat, layout rotation, haptics; setup and minimal permission/status UI.
- [ ] Foreground service/notification and automatic activation controller; use supported lock-screen APIs only when eligible; fallback notification; dismiss suppression.
- [ ] Test keyboard state, ordering, release-all, rotation, sleep, background, force-stop, reboot and physical Samsung lock-screen conditions. Pair and test with the Windows test harness.
- Gate: usable phone keyboard on Windows development workflow; actual activation matrix documented.

## M4 — Windows native integration
- [ ] Interactive user agent, UIA editable focus detection and bounded click fallback, debounce, focus generation and session-lock monitor.
- [ ] Win32 injection, Unicode and scan-code mapping, modifier ownership/release; manual mode, sensitive-field policy and secure desktop protection.
- [ ] Tray, startup, DPAPI storage, pairing/revocation window, pause/exit; ensure single instance and clean shutdown.
- [ ] Test Notepad, Chrome/Edge input and contenteditable, VS Code, Office, terminals, readonly/password fields, same-field clicks, focus changes, Windows lock, elevated apps and disconnect mid-shortcut.
- Gate: Windows 10 and Windows 11 + physical Android end-to-end automatic typing, with documented unsupported applications. Windows Server CI alone does not satisfy this gate; use actual desktop OS installations or VMs with an interactive session.

## M5 — Hardening and performance
- [ ] Threat model review, dependency/license audit, protocol fuzz/property tests, privacy inspection of app-owned storage and network captures with synthetic keys.
- [ ] Stress network interruptions, concurrent focus changes, stale messages, disconnect while modifier held, expiry while connected, malformed input, sleep and battery saver.
- [ ] Measure latency, desktop idle CPU/memory and Android idle battery overhead with baseline and device details. Tune without weakening controls.
- [ ] Verify no logging of user content and no external network calls; document residual risks (OS memory/dumps, trusted peer compromise, OEM limits).
- Gate: release-blocking security failures fixed; benchmark results and manual compatibility matrix recorded for both desktop OS versions; verify TLS version and cipher restrictions with integration tests.

## M6 — Packaging, release and documentation
- [ ] Self-contained Windows x64 publish and standalone setup EXE; clean install, upgrade, startup opt-in and uninstall on clean Windows 10 22H2 and Windows 11 VMs. Verify runtime-free installation, firewall scope and upgrade on both.
- [ ] Stable signed Android release APK; test install and update with same signing identity. Android SDK/ABI support documented.
- [ ] GitHub Actions PR checks; protected tag release workflow publishes setup EXE, APK, SHA-256 checksums, release notes; signing secrets protected. Verify artifacts; clearly label Windows unsigned if applicable.
- [ ] Final README with installation, pairing, feature overview, permissions, VPN/lock-screen caveats, screenshots, architecture, build commands, troubleshooting and security reporting.
- Gate: tagged release artifacts independently installable, tests green, required Windows 10/11 and physical Android validation recorded, no unsupported promises. Release only with explicit approval.
