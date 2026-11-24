using System.Security.Cryptography;

namespace RemoteKeyboard.Core.Pairing;

/// <summary>A role and protocol version bound to a one-attempt commitment to a TLS public key.</summary>
public sealed class PairingIdentity
{
    private readonly byte[] nonce;
    private readonly byte[] commitment;

    private PairingIdentity(string id, byte[] nonce, byte[] commitment)
    {
        Id = id;
        this.nonce = nonce;
        this.commitment = commitment;
    }

    public string Id { get; }

    public static PairingIdentity Create(bool desktop, ReadOnlySpan<byte> spkiPin)
    {
        byte[] nonce = RandomNumberGenerator.GetBytes(32);
        byte[] commitment = Commit(nonce, spkiPin);
        return new PairingIdentity(
            Prefix(desktop) + Convert.ToHexStringLower(nonce) + "." + Convert.ToHexStringLower(commitment),
            nonce, commitment);
    }

    public static PairingIdentity Parse(string id, bool desktop)
    {
        string prefix = Prefix(desktop);
        if (id.Length != prefix.Length + 129 || !id.StartsWith(prefix, StringComparison.Ordinal) ||
            id[prefix.Length + 64] != '.')
            throw new InvalidDataException("Invalid pairing identity.");

        var nonce = ReadHex(id.AsSpan(prefix.Length, 64));
        var commitment = ReadHex(id.AsSpan(prefix.Length + 65, 64));
        return new PairingIdentity(id, nonce, commitment);
    }

    public bool Matches(ReadOnlySpan<byte> spkiPin) =>
        spkiPin.Length == 32 && CryptographicOperations.FixedTimeEquals(commitment, Commit(nonce, spkiPin));

    private static string Prefix(bool desktop) => desktop ? "keyflow-pair-v1.desktop." : "keyflow-pair-v1.android.";

    private static byte[] Commit(ReadOnlySpan<byte> nonce, ReadOnlySpan<byte> spkiPin)
    {
        if (spkiPin.Length != 32)
            throw new ArgumentException("Expected a SHA-256 SPKI pin.", nameof(spkiPin));

        Span<byte> value = stackalloc byte[64];
        nonce.CopyTo(value);
        spkiPin.CopyTo(value[32..]);
        return SHA256.HashData(value);
    }

    private static byte[] ReadHex(ReadOnlySpan<char> value)
    {
        foreach (char digit in value)
            if (digit is not (>= '0' and <= '9' or >= 'a' and <= 'f'))
                throw new InvalidDataException("Invalid pairing identity.");
        return Convert.FromHexString(value);
    }
}
