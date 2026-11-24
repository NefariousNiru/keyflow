using Org.BouncyCastle.Crypto;
using Org.BouncyCastle.Crypto.Agreement.JPake;
using Org.BouncyCastle.Math;

var participant = new JPakeParticipant("windows", "384017".ToCharArray(), JPakePrimeOrderGroups.NIST_3072);
var first = participant.CreateRound1PayloadToSend();
Send(first.ParticipantId, first.Gx1, first.Gx2,
    first.KnowledgeProofForX1[0], first.KnowledgeProofForX1[1],
    first.KnowledgeProofForX2[0], first.KnowledgeProofForX2[1]);
var peerFirst = Receive(7);
participant.ValidateRound1PayloadReceived(new JPakeRound1Payload(
    peerFirst[0], Number(peerFirst[1]), Number(peerFirst[2]), Proof(peerFirst, 3), Proof(peerFirst, 5)));

var second = participant.CreateRound2PayloadToSend();
Send(second.ParticipantId, second.A, second.KnowledgeProofForX2s[0], second.KnowledgeProofForX2s[1]);
var peerSecond = Receive(4);
participant.ValidateRound2PayloadReceived(new JPakeRound2Payload(
    peerSecond[0], Number(peerSecond[1]), Proof(peerSecond, 2)));

var material = participant.CalculateKeyingMaterial();
var third = participant.CreateRound3PayloadToSend(material);
Send(third.ParticipantId, third.MacTag);
var peerThird = Receive(2);
try
{
    participant.ValidateRound3PayloadReceived(new JPakeRound3Payload(peerThird[0], Number(peerThird[1])), material);
    Console.WriteLine("CONFIRMED");
}
catch (CryptoException)
{
    Console.WriteLine("REJECTED");
}

static void Send(params object[] values) => Console.WriteLine(string.Join(',', values));

static string[] Receive(int count)
{
    var line = Console.ReadLine() ?? throw new InvalidOperationException("Test exchange ended early.");
    var fields = line.Split(',');
    if (line.Length > 8192 || fields.Length != count || fields[0] != "android")
        throw new InvalidOperationException("Invalid test payload.");
    return fields;
}

static BigInteger Number(string value) => new(value);
static BigInteger[] Proof(string[] values, int offset) => [Number(values[offset]), Number(values[offset + 1])];
