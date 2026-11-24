# M0 architecture decisions

## Transport

Use one direct TCP connection with TLS and length-prefixed UTF-8 JSON. A four-byte unsigned big-endian length precedes each frame. Reject zero and lengths above 16 KiB before allocating frame storage. Reject malformed UTF-8, duplicate object keys and unknown protocol versions. The [wire protocol](../../protocol/README.md) defines the initial message schemas. Verify TLS version, cipher suite and peer identity before sending application messages. Prefer TLS 1.3 on Windows 11 and allow TLS 1.2 ECDHE with AEAD on Windows 10, subject to native interoperability tests.

## Pairing gate

Bouncy Castle publishes J-PAKE participants for [.NET](https://github.com/bcgit/bc-csharp/blob/master/crypto/src/crypto/agreement/jpake/JPakeParticipant.cs) and [Java](https://github.com/bcgit/bc-java/blob/main/core/src/main/java/org/bouncycastle/crypto/agreement/jpake/JPAKEParticipant.java). The M0 USB probe used .NET 2.7.0 and Java 1.87 with the NIST 3072-bit group. Both runtimes passed mutual key confirmation with matching synthetic codes and rejected mismatched codes. This supports selecting J-PAKE as the candidate. M1 implements and tests production identity binding separately from the primitive feasibility probe. The probe uses synthetic role labels and establishes no persistent trust. The initial authenticated channel must protect against a LAN impersonator before disclosing any OTP or exchanging persistent trust. Authorization requires one desktop-armed two-minute window, at most five attempts, global cooldown and one-time consumption. If a vetted interoperable PAKE cannot be identified, the alternative is an audited out-of-band design with equivalent protections. M0 feasibility is complete. M1 owns production identity binding and persistent trust. [RFC 8236](https://www.rfc-editor.org/rfc/rfc8236)

### M1 pairing architecture

Use separate pairing and reconnect entry points. The armed pairing path accepts bounded J-PAKE traffic. Its participant identifiers bind the protocol version, endpoint roles and salted commitments to both long-term certificate public keys. The parser validates the expected roles and version. Bouncy Castle binds the full identifiers into the PAKE proofs and confirmation. The TLS upgrade checks the commitments and proves possession using both certificates. No OTP is transmitted. Reconnect uses the stored SPKI pins and starts with mutual TLS.

Use Windows DPAPI CurrentUser for the desktop identity and trust record. Keep the Android private key and the trust record's AES-GCM key in Android Keystore. After TLS, the desktop offers the pairing time and the phone acknowledges its local protected write. The desktop commits trust after that acknowledgement. A failed desktop commit can leave the phone with an orphaned pin, which cannot authenticate to the desktop and requires explicit pairing. The desktop enforces 30 days from the issued grant. Android applies its own 30-day monotonic and wall-clock checks anchored to the locally observed pairing. Both persist clock high-water marks, and neither renews trust on reconnect. [J-PAKE specification](https://www.rfc-editor.org/rfc/rfc8236) · [TLS 1.3 certificate proof](https://www.rfc-editor.org/rfc/rfc8446#section-4.4.3)

## OS boundaries

Keep protocol, policy and session state in the .NET core. Supply focus, injection, network policy, secure storage, session lock and lifecycle adapters at the Windows boundary. Use metadata-only UI Automation and ordinary-user Win32 input checks. DPAPI CurrentUser and Android Keystore protect local identity material. Android owns activity and notification lifecycle and binds sockets through the selected Android network when OS and VPN policy permit.

## Trusted LAN and privacy

Assume LAN spoofing, malicious discovery responses, stale sessions and stolen device access. Discovery supplies address hints only. Require authenticated peers and an explicit Wi-Fi choice, then validate the interface, endpoint and available network metadata. Stop on changes or block when VPN policy prevents local access. Use mutually authenticated sessions after pairing with a fixed 30-day lifetime, rollback detection and immediate revocation. Scope input to live focus generations and release remotely held keys on teardown. Persist only identity and settings needed to operate. OS paging, OS-managed dumps and a compromised paired device remain residual risks.
