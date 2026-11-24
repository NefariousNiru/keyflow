# MILESTONE.md — Implementation roadmap

Follow AGENTS.md. Development gates govern when implementation can advance. Production release gates are mandatory and require recorded evidence. Unavailable hardware or OS environments leave their validation pending and do not by themselves block subsequent development. Check boxes only after recording actual evidence. Windows 10 22H2 x64 and Windows 11 x64 are mandatory production targets. Develop on Windows; no Linux components. Do not build the full product in one change.

## M0 — Scaffold and risk discovery

- [x] Scaffold the monorepo, desktop and Android projects, test projects, protocol and docs, and baseline Windows/Android CI.
- [x] Record the transport, framing, OS adapter boundaries, trusted-LAN and privacy design, and J-PAKE feasibility evidence.
- [x] Demonstrate initial feasibility on Windows 10 22H2 and LAVA Android 14 using synthetic inputs.

**Development gate closed.** Windows 10 passed the native UIA, SendInput and TLS 1.2 probes. The LAVA demonstrated foreground launch, notification fallback after a background launch denial, and fallback posting while asleep and during forced Doze. Both platforms confirmed matching J-PAKE codes and rejected mismatched codes. See the [validation matrix](docs/m0-validation.md).

Production identity binding moves to M1, companion association and remaining activation checks to M3, Windows security-boundary checks to M4, and the outstanding cross-platform compatibility matrix to M5. These items remain release requirements.

## M1 — Protocol and secure transport

- [x] Define versioned messages for capabilities, focus generations, key and Unicode input, release, heartbeat, errors and pause, with strict limits and canonical keys.
- [x] Implement the desktop core and Android trust paths, OTP pairing, mutual TLS, protected storage, revocation and fixed 30-day authorization.
- [x] Run the Windows in-memory focus/input harness against the Android test client with no production OS injection.
- [x] Test interoperability, wrong OTP, throttling, unknown and substituted identities, replay, malformed frames, expiry during an active session, clock rollback, revocation, disconnect and stale focus.
- [x] Bind the protocol context and both long-term device identities to the OTP-authenticated exchange and verify TLS proof of possession before trust is persisted.

**Development gate closed.** The Windows 10 and LAVA end-to-end workflow paired, exchanged authenticated application frames and reconnected from persisted credentials. Negative security cases passed on the connected device and in component tests. The [M1 validation notes](docs/m1-validation.md) record commands, results and scope. Native LAN transport policy, production UI, Windows 11 and physical Samsung release validation remain tracked in subsequent milestones.

## M2 — LAN discovery, VPN and recovery
- [x] Implement trusted Wi-Fi selection, interface/network callbacks, mDNS/NSD and manual IP entry, with scoped firewall setup for Private and explicitly selected Public Wi-Fi.
- [x] Implement per-network binding where permitted, reconnect/backoff, heartbeat/watchdog, sleep/resume and route-change handling.
- [ ] Test changing IP, roaming, network loss, same SSID/different network, untrusted network, VPN with LAN allowed and VPN with LAN blocked, AP client isolation.
- [x] Verify refusal of Public Wi-Fi without explicit approval, off-subnet and mismatched Wi-Fi rejection, live phone network callbacks, session watchdog and held-key cleanup on route denial.
- Development gate: reconnect without re-pairing inside authorization window; blocked LAN fails safely with meaningful status; no off-LAN input.

**Development gate closed.** Windows 10 and LAVA paired over explicitly selected Public Wi-Fi, exchanged authenticated synthetic input and automatically reconnected after real Wi-Fi loss with the original authorization. The [M2 validation notes](docs/m2-validation.md) track pending Private mDNS/NSD, firewall enforcement, VPN, DHCP change, sleep/resume and cross-platform release checks.

## M3 — Android keyboard and lifecycle
- [ ] Implement keyboard renderer, key mapping, text commit, shortcuts/modifiers/repeat, layout rotation, haptics; setup and minimal permission/status UI.
- [ ] Foreground service/notification and automatic activation controller; use supported lock-screen APIs only when eligible; fallback notification; dismiss suppression.
- [ ] Test keyboard state, ordering, release-all, rotation, sleep, background, force-stop, reboot and physical Samsung lock-screen conditions. Pair and test with the Windows test harness.
- [ ] Validate companion association eligibility and actual association, background launch exemptions, screen-off and secure-keyguard behavior, natural Doze, notification-tap fallback, and Samsung power management. Keep unsupported automatic activation cases on the notification fallback path.
- Development gate: usable phone keyboard on the available Windows/Android workflow with an evidence-based activation matrix. Full target-device activation validation is required before release.

## M4 — Windows native integration
- [ ] Interactive user agent, UIA editable focus detection and bounded click fallback, debounce, focus generation and session-lock monitor.
- [ ] Win32 injection, Unicode and scan-code mapping, modifier ownership/release; manual mode, sensitive-field policy and secure desktop protection.
- [ ] Tray, startup, DPAPI storage, pairing/revocation window, pause/exit; ensure single instance and clean shutdown.
- [ ] Test Notepad, Chrome/Edge input and contenteditable, VS Code, Office, terminals, readonly/password fields, same-field clicks, focus changes, Windows lock, elevated apps and disconnect mid-shortcut.
- [ ] Verify ordinary-user injection stays within UIPI and active, unlocked desktop limits, including elevated apps, Windows lock and secure desktop. Verify held-key release and fail-closed focus revalidation.
- Development gate: local end-to-end typing and safety behavior on available targets with gaps documented. The full desktop compatibility matrix is tracked in M5.

## M5 — Hardening and performance
- [ ] Threat model review, dependency/license audit, protocol fuzz/property tests, privacy inspection of app-owned storage and network captures with synthetic keys.
- [ ] Add cool icongraphy to android app (icon + splash) as well as windows app. use same icons. Style it like this for the name eveywhere: "KeyFlow"
- [ ] Stress network interruptions, concurrent focus changes, stale messages, disconnect while modifier held, expiry while connected, malformed input, sleep and battery saver.
- [ ] Measure latency, desktop idle CPU/memory and Android idle battery overhead with baseline and device details. Tune without weakening controls.
- [ ] Verify no logging of user content and no external network calls; document residual risks (OS memory/dumps, trusted peer compromise, OEM limits).
- [ ] Complete cross-platform runtime, UIA, input, lifecycle, LAN/VPN and TLS validation on actual Windows 10 22H2 x64 and supported Windows 11 x64 installations or interactive VMs, plus physical Android coverage including Samsung. Verify end-to-end activation and typing and document unsupported applications. Windows Server CI is build evidence only.
- [ ] Verify TLS version, peer authentication and cipher restrictions in cross-platform integration tests. Require TLS 1.2 ECDHE/AEAD on Windows 10 and prefer TLS 1.3 on Windows 11.
- Development gate: automated hardening, privacy and local performance checks pass on available targets. Record remaining device-dependent work before packaging.
- Production release gate: resolve release-blocking security failures and complete the performance, privacy and compatibility evidence. Pending physical validation permits development to proceed to packaging.

## M6 — Packaging, release and documentation
- [ ] Self-contained Windows x64 publish and standalone setup EXE; clean install, upgrade, startup opt-in and uninstall on clean Windows 10 22H2 and Windows 11 VMs. Verify runtime-free installation, firewall scope and upgrade on both.
- [ ] Stable signed Android release APK; test install and update with same signing identity. Android SDK/ABI support documented.
- [ ] GitHub Actions PR checks; protected tag release workflow publishes setup EXE, APK, SHA-256 checksums, release notes; signing secrets protected. Verify artifacts; clearly label Windows unsigned if applicable.
- [ ] Final README with installation, pairing, feature overview, permissions, VPN/lock-screen caveats, screenshots, architecture, build commands, troubleshooting and security reporting.
- Development gate: build and smoke-test the installer and APK on available targets.
- Production release gate: all mandatory AGENTS.md security, privacy, compatibility and packaging checks pass, including carried-forward M1/M3/M4/M5 items. Tagged artifacts are independently installable, tests are green, and compatibility claims match recorded evidence. Release only with explicit approval.
