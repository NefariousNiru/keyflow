# Keyflow wire protocol

Version 1 uses a four-byte unsigned big-endian size followed by one UTF-8 JSON object. Allowed sizes are 1 through 16,384 bytes. Peers reject truncated frames, malformed Unicode, duplicate properties, unknown properties and unsupported versions. Close the connection after a framing error. Each direction uses a single serialized reader or writer. Application frames require an authenticated TLS session.

Every object has `v` equal to 1 and a `type` string. After the hello, every frame includes a nonzero UUID `session` in hyphenated form and the next integer `seq`, starting at 1 independently for each direction. The desktop chooses a fresh identifier for each authorized connection and communicates it in the first focus frame. Each endpoint sends a hello before session traffic. The desktop accepts input, heartbeat and release frames from the phone. Focus and pause come from the desktop.

| Type | Additional properties |
| --- | --- |
| `hello` | `capabilities` bitmask, 1 for native key changes and 2 for Unicode text commits |
| `focus` | Positive `generation`, boolean `active` |
| `key` | Positive `generation`, integer `key`, boolean `down` |
| `text` | Positive `generation`, `text` with 1–1,024 UTF-8 bytes and no control characters |
| `release` | None |
| `heartbeat` | None |
| `pause` | Boolean `paused` |
| `error` | Integer `code`, 1 for unexpected message, 2 for stale focus and 3 for input unavailable |

The [canonical key enumeration](../desktop/RemoteKeyboard.Core/Protocol/ProtocolMessage.cs) defines the numeric assignments 1 through 77, independent of Android key codes and Windows virtual keys. Sequence numbers and focus generations must fit a signed 64-bit integer. Input must match the currently active focus generation.

Example body for a heartbeat. The transport writes its actual UTF-8 byte count in the preceding header.

```json
{"v":1,"type":"heartbeat","session":"12345678-1234-1234-1234-123456789abc","seq":1}
```

## Pairing and reconnect prefaces

The pairing path is available only during an explicitly armed desktop window. Its first messages are the three Bouncy Castle J-PAKE rounds using the NIST 3072-bit group and mutual key confirmation. Each uses the four-byte big-endian length followed by 1–8,192 bytes of printable ASCII. Round 1 has seven comma-separated fields (participant identifier, two group elements and two two-component proofs). Round 2 has four fields (identifier, group element and two-component proof). Round 3 has two fields (identifier and signed confirmation tag). Integers use canonical decimal. Both peers validate the expected role and context before accepting a proof.

The identifier is `keyflow-pair-v1.<role>.<nonce>.<commitment>`, with the role `desktop` or `android`. Nonce and commitment are each 32 bytes encoded as lowercase hex. The commitment is SHA-256 of the nonce followed by SHA-256 of the endpoint certificate's DER SubjectPublicKeyInfo. After both PAKE confirmations, the same stream immediately starts mutual TLS. Each endpoint validates that the peer certificate opens the confirmed commitment. TLS proves possession of both private keys.

Inside TLS, the desktop offers an eight-byte big-endian signed Unix timestamp in milliseconds. The phone stores its peer pin and acknowledges with byte 1. The desktop stores its peer pin after that acknowledgment. App frames begin after the trust exchange. Reconnect starts directly with pinned mutual TLS and the unchanged timestamp, then app frames. A reconnect failure closes the connection and cannot initiate pairing. Authorization has a fixed 30-day lifetime with independent rollback and active-session checks.