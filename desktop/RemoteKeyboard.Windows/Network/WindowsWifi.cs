using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using RemoteKeyboard.Core.Network;

namespace RemoteKeyboard.Windows.Network;

public sealed record WifiSelection(Guid Profile, Guid Adapter);

public sealed record WifiLink(
    WifiSelection Selection, string Label, string InterfaceName, int Index, IPAddress Address, int PrefixLength, IPAddress Gateway)
{
    public bool Allows(IPAddress peer) =>
        new LanScope(Address, PrefixLength).AllowsPeer(peer) && UsesCurrentSystemRoute(peer);

    public bool UsesCurrentSystemRoute(IPAddress destination) =>
        destination.AddressFamily == AddressFamily.InterNetwork &&
        GetBestInterface(BitConverter.ToUInt32(destination.GetAddressBytes()), out uint index) == 0 &&
        index == Index;

    [DllImport("iphlpapi.dll")]
    private static extern uint GetBestInterface(uint destination, out uint bestInterface);
}

/// <summary>Uses the Windows profile identity and interface GUID, with a fresh Private-profile check.</summary>
public static class WindowsWifi
{
    private static readonly Guid ManagerClass = new("dcb00c01-570f-4a9b-8d69-199fdba5723b");

    public static IReadOnlyList<WifiLink> ListPrivate()
    {
        var adapters = NetworkInterface.GetAllNetworkInterfaces()
            .Where(adapter => adapter.NetworkInterfaceType == NetworkInterfaceType.Wireless80211 &&
                adapter.OperationalStatus == OperationalStatus.Up)
            .ToArray();
        if (adapters.Length == 0)
            return [];

        var result = new List<WifiLink>();
        dynamic manager = Activator.CreateInstance(Type.GetTypeFromCLSID(ManagerClass, throwOnError: true)!)!;
        try
        {
            dynamic networks = manager.GetNetworks(1); // NLM_ENUM_NETWORK_CONNECTED
            try
            {
                foreach (dynamic network in networks)
                {
                    try
                    {
                        if ((int)network.GetCategory() != 1) // NLM_NETWORK_CATEGORY_PRIVATE
                            continue;

                        var profile = ((INetworkIdentity)network).GetNetworkId();
                        string label = network.GetName();
                        dynamic connections = network.GetNetworkConnections();
                        try
                        {
                            foreach (dynamic connection in connections)
                            {
                                try
                                {
                                    var id = ((IConnectionIdentity)connection).GetAdapterId();
                                    var adapter = adapters.FirstOrDefault(item => Guid.TryParse(item.Id, out var parsed) && parsed == id);
                                    if (adapter is not null && Build(adapter, profile, id, label) is { } link)
                                        result.Add(link);
                                }
                                finally
                                {
                                    Marshal.ReleaseComObject(connection);
                                }
                            }
                        }
                        finally
                        {
                            Marshal.ReleaseComObject(connections);
                        }
                    }
                    finally
                    {
                        Marshal.ReleaseComObject(network);
                    }
                }
            }
            finally
            {
                Marshal.ReleaseComObject(networks);
            }
        }
        finally
        {
            Marshal.ReleaseComObject(manager);
        }
        return result;
    }

    public static WifiLink? Find(WifiSelection selection) =>
        ListPrivate().FirstOrDefault(link => link.Selection == selection);

    private static WifiLink? Build(NetworkInterface adapter, Guid profile, Guid id, string label)
    {
        var properties = adapter.GetIPProperties();
        var ipv4 = properties.GetIPv4Properties();
        var gateways = properties.GatewayAddresses.Where(gateway => LanScope.IsPrivate(gateway.Address)).ToArray();
        if (ipv4 is null || gateways.Length != 1)
            return null;

        var addresses = properties.UnicastAddresses.Where(address =>
            address.Address.AddressFamily == AddressFamily.InterNetwork &&
            address.DuplicateAddressDetectionState == DuplicateAddressDetectionState.Preferred &&
            LanScope.IsPrivate(address.Address) && address.PrefixLength is >= 8 and <= 30).ToArray();
        // Avoid guessing the Wi-Fi source where more than one usable address is configured.
        if (addresses.Length != 1)
            return null;
        var selected = addresses[0];
        return new WifiLink(new WifiSelection(profile, id), label, adapter.Name, ipv4.Index,
            selected.Address, selected.PrefixLength, gateways[0].Address);
    }
    // GUID-valued methods cannot be read through NLM's late-bound automation interface.
    [ComImport, Guid("dcb00002-570f-4a9b-8d69-199fdba5723b"), InterfaceType(ComInterfaceType.InterfaceIsDual)]
    private interface INetworkIdentity
    {
        [return: MarshalAs(UnmanagedType.BStr)] string GetName();
        void SetName([MarshalAs(UnmanagedType.BStr)] string name);
        [return: MarshalAs(UnmanagedType.BStr)] string GetDescription();
        void SetDescription([MarshalAs(UnmanagedType.BStr)] string description);
        Guid GetNetworkId();
    }

    [ComImport, Guid("dcb00005-570f-4a9b-8d69-199fdba5723b"), InterfaceType(ComInterfaceType.InterfaceIsDual)]
    private interface IConnectionIdentity
    {
        [return: MarshalAs(UnmanagedType.Interface)] INetworkIdentity GetNetwork();
        [return: MarshalAs(UnmanagedType.VariantBool)] bool IsConnectedToInternet();
        [return: MarshalAs(UnmanagedType.VariantBool)] bool IsConnected();
        int GetConnectivity();
        Guid GetConnectionId();
        Guid GetAdapterId();
    }
}
