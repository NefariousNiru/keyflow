using System.Buffers.Binary;
using System.Security.Authentication;
using System.Text;
using Org.BouncyCastle.Crypto;
using Org.BouncyCastle.Crypto.Agreement.JPake;
using Org.BouncyCastle.Math;

namespace RemoteKeyboard.Core.Pairing;

/// <summary>Single, bounded J-PAKE exchange. The returned identity may only be used to check TLS possession.</summary>
public static class PairingExchange
{
    private const int MaximumBody = 8192;

    public static async Task<PairingIdentity> ConfirmAsync(
        Stream stream, PairingIdentity own, bool desktop, ReadOnlyMemory<char> otp,
        CancellationToken cancellationToken)
    {
        if (otp.Length != 6 || otp.Span.IndexOfAnyExceptInRange('0', '9') >= 0)
            throw new ArgumentException("Expected a six-digit pairing code.", nameof(otp));

        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(TimeSpan.FromSeconds(20));
        var token = deadline.Token;
        char[] password = otp.ToArray();
        try
        {
            JPakeParticipant participant = new(own.Id, password, JPakePrimeOrderGroups.NIST_3072);
            var one = participant.CreateRound1PayloadToSend();
            await SendAsync(stream, token, one.ParticipantId, one.Gx1, one.Gx2,
                one.KnowledgeProofForX1[0], one.KnowledgeProofForX1[1],
                one.KnowledgeProofForX2[0], one.KnowledgeProofForX2[1]).ConfigureAwait(false);
            var input = await ReceiveAsync(stream, 7, token).ConfigureAwait(false);
            var peer = PairingIdentity.Parse(input[0], !desktop);
            participant.ValidateRound1PayloadReceived(new JPakeRound1Payload(
                peer.Id, Number(input[1]), Number(input[2]), Proof(input, 3), Proof(input, 5)));

            var two = participant.CreateRound2PayloadToSend();
            await SendAsync(stream, token, two.ParticipantId, two.A,
                two.KnowledgeProofForX2s[0], two.KnowledgeProofForX2s[1]).ConfigureAwait(false);
            input = await ReceiveAsync(stream, 4, token).ConfigureAwait(false);
            RequireIdentity(input, peer);
            participant.ValidateRound2PayloadReceived(new JPakeRound2Payload(peer.Id, Number(input[1]), Proof(input, 2)));

            var material = participant.CalculateKeyingMaterial();
            var three = participant.CreateRound3PayloadToSend(material);
            await SendAsync(stream, token, three.ParticipantId, three.MacTag).ConfigureAwait(false);
            input = await ReceiveAsync(stream, 2, token).ConfigureAwait(false);
            RequireIdentity(input, peer);
            participant.ValidateRound3PayloadReceived(new JPakeRound3Payload(peer.Id, Number(input[1], signed: true)), material);
            return peer;
        }
        catch (Exception error) when (error is CryptoException or ArithmeticException or FormatException)
        {
            throw new AuthenticationException("Pairing key confirmation failed.");
        }
        finally
        {
            Array.Clear(password);
        }
    }

    private static void RequireIdentity(string[] values, PairingIdentity peer)
    {
        if (values[0] != peer.Id)
            throw new InvalidDataException("Pairing identity changed.");
    }

    private static BigInteger Number(string value, bool signed = false)
    {
        var digits = value.AsSpan();
        if (signed && digits.StartsWith("-"))
            digits = digits[1..];
        if (digits.Length is < 1 or > 925 || digits.IndexOfAnyExceptInRange('0', '9') >= 0 ||
            (digits.Length > 1 && digits[0] == '0') || value == "-0")
            throw new InvalidDataException("Invalid pairing integer.");
        var number = new BigInteger(value);
        if (!signed && number.CompareTo(JPakePrimeOrderGroups.NIST_3072.P) >= 0)
            throw new InvalidDataException("Pairing integer exceeds the group size.");
        return number;
    }

    private static BigInteger[] Proof(string[] values, int offset) =>
        [Number(values[offset]), Number(values[offset + 1])];

    private static async Task SendAsync(Stream stream, CancellationToken token, params object[] values)
    {
        byte[] body = Encoding.ASCII.GetBytes(string.Join(',', values));
        var header = new byte[4];
        BinaryPrimitives.WriteInt32BigEndian(header, body.Length);
        await stream.WriteAsync(header, token).ConfigureAwait(false);
        await stream.WriteAsync(body, token).ConfigureAwait(false);
        await stream.FlushAsync(token).ConfigureAwait(false);
    }

    private static async Task<string[]> ReceiveAsync(Stream stream, int fields, CancellationToken token)
    {
        var header = new byte[4];
        await stream.ReadExactlyAsync(header, token).ConfigureAwait(false);
        int length = BinaryPrimitives.ReadInt32BigEndian(header);
        if (length is < 1 or > MaximumBody)
            throw new InvalidDataException("Invalid pairing frame size.");
        var body = new byte[length];
        await stream.ReadExactlyAsync(body, token).ConfigureAwait(false);
        foreach (var b in body)
            if (b is < 32 or > 126)
                throw new InvalidDataException("Invalid pairing frame encoding.");
        var values = Encoding.ASCII.GetString(body).Split(',');
        if (values.Length != fields)
            throw new InvalidDataException("Invalid pairing frame fields.");
        return values;
    }
}
