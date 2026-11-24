# M0 architecture decisions

## Transport

Use one direct TCP connection with TLS and length-prefixed UTF-8 JSON. A four-byte unsigned big-endian length precedes each frame. Reject zero and lengths above 16 KiB before allocating frame storage. Reject malformed UTF-8, duplicate object keys and unknown protocol versions. M1 will define exact message schemas and tests. Verify TLS version, cipher suite and peer identity before sending application messages. Prefer TLS 1.3 on Windows 11 and allow TLS 1.2 ECDHE with AEAD on Windows 10, subject to native interoperability tests.

## Pairing gate

Bouncy Castle publishes J-PAKE participants for [.NET](https://github.com/bcgit/bc-csharp/blob/master/crypto/src/crypto/agreement/jpake/JPakeParticipant.cs) and [Java](https://github.com/bcgit/bc-java/blob/main/core/src/main/java/org/bouncycastle/crypto/agreement/jpake/JPAKEParticipant.java). The M0 USB probe used .NET 2.7.0 and Java 1.87 with the NIST 3072-bit group. Both runtimes passed mutual key confirmation with matching synthetic codes and rejected mismatched codes. This supports selecting J-PAKE as the candidate. Production adoption still requires a reviewed method for binding the protocol version, peer roles and device-key commitments into the authenticated exchange, and proof of possession before committing pins. The probe uses synthetic role labels and establishes no persistent trust. The initial authenticated channel must protect against a LAN impersonator before disclosing any OTP or exchanging persistent trust. Authorization requires one desktop-armed two-minute window, at most five attempts, global cooldown and one-time consumption. If a vetted interoperable PAKE cannot be identified, the alternative is an audited out-of-band design with equivalent protections. Pairing remains blocked pending evidence. [RFC 8236](https://www.rfc-editor.org/rfc/rfc8236)

## OS boundaries

Keep protocol, policy and session state in the .NET core. Supply focus, injection, network policy, secure storage, session lock and lifecycle adapters at the Windows boundary. Use metadata-only UI Automation and ordinary-user Win32 input checks. DPAPI CurrentUser and Android Keystore protect local identity material. Android owns activity and notification lifecycle and binds sockets through the selected Android network when OS and VPN policy permit.

## Trusted LAN and privacy

Assume LAN spoofing, malicious discovery responses, stale sessions and stolen device access. Discovery supplies address hints only. Require authenticated peers and an explicit Wi-Fi choice, then validate the interface, endpoint and available network metadata. Stop on changes or block when VPN policy prevents local access. Use mutually authenticated sessions after pairing with a fixed 30-day lifetime, rollback detection and immediate revocation. Scope input to live focus generations and release remotely held keys on teardown. Persist only identity and settings needed to operate. OS paging, OS-managed dumps and a compromised paired device remain residual risks.
