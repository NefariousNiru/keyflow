using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;

namespace RemoteKeyboard.Core.Network;

/// <summary>IPv4 LAN scope selected on a trusted interface. Endpoint identity still requires TLS.</summary>
public sealed class LanScope
{
    private readonly uint local;
    private readonly uint mask;

    public LanScope(IPAddress address, int prefixLength)
    {
        if (!IsPrivate(address) || prefixLength is < 8 or > 30)
            throw new ArgumentException("A private IPv4 subnet is required.");
        local = AsNumber(address);
        mask = uint.MaxValue << (32 - prefixLength);
        if (local == (local & mask) || local == (local | ~mask))
            throw new ArgumentException("A usable local address is required.");
    }

    public bool AllowsPeer(IPAddress address)
    {
        if (!IsPrivate(address))
            return false;

        uint peer = AsNumber(address);
        uint network = local & mask;
        return (peer & mask) == network && peer != local && peer != network && peer != (network | ~mask);
    }

    public static bool IsPrivate(IPAddress address)
    {
        if (address.AddressFamily != AddressFamily.InterNetwork)
            return false;
        uint value = AsNumber(address);
        return (value >> 24) == 10 || (value >> 20) == 0xac1 || (value >> 16) == 0xc0a8;
    }

    private static uint AsNumber(IPAddress address) => BinaryPrimitives.ReadUInt32BigEndian(address.GetAddressBytes());
}

public static class LanPorts
{
    public const int Session = 48732;
    public const int Pairing = 48733;
}
