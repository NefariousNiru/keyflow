using System.IO;
using RemoteKeyboard.Core.Pairing;
using RemoteKeyboard.Core.Protocol;
using RemoteKeyboard.Core.Sessions;
using RemoteKeyboard.Windows;
using RemoteKeyboard.Windows.Network;

internal static class LanEndpoint
{
    public static async Task RunAsync(string directory, string mode, string? publicPeer = null)
    {
        if (!System.Text.RegularExpressions.Regex.IsMatch(Path.GetFileName(directory), "^keyflow-test-[0-9a-f]{16}$"))
            throw new ArgumentException("A dedicated LAN-test directory is required.");

        var link = WindowsWifi.ListEligible().SingleOrDefault(item => item.IsPublic == (publicPeer is not null));
        var selected = link?.IsPublic == true ? link.SelectPublicPeer(publicPeer) : link?.Selection;
        if (selected is null)
        {
            Console.WriteLine("WIFI_SELECTION_REQUIRED");
            return;
        }

        if (mode == "public-policy")
        {
            if (link?.IsPublic != true || publicPeer is null || WindowsWifi.Find(link.Selection) is not null ||
                link.SelectPublicPeer("8.8.8.8") is not null ||
                WindowsWifi.Find(selected) is not { } scoped ||
                !scoped.Allows(System.Net.IPAddress.Parse(publicPeer)) || scoped.Allows(link.Gateway))
                throw new InvalidOperationException("Public Wi-Fi peer scope check failed.");

            Console.WriteLine("PUBLIC_PEER_SCOPE_OK");
            return;
        }

        var store = new WindowsTrustStore(directory);
        using var identity = store.LoadOrCreateIdentity();
        var pairing = new PairingWindow(TimeProvider.System);
        var trust = new DesktopTrust(store, pairing, TimeProvider.System);
        var host = new LanHost(trust, pairing, identity, new SyntheticInput(), syntheticFocus: true);
        using var stop = new CancellationTokenSource(TimeSpan.FromSeconds(100));
        var started = host.RunAsync(selected, stop.Token);
        try
        {
            for (int index = 0; host.Listening is null && index < 25; index++)
                await Task.Delay(200, stop.Token);
            if (host.Listening is not { } listening)
                throw new InvalidOperationException(host.Status);
            Console.WriteLine($"ADDRESS {listening.Address}");
            if (mode == "pair")
                Console.WriteLine($"OTP {host.ArmPairing() ?? throw new InvalidOperationException("Could not arm pairing.")}");
            if (mode == "status")
                Console.WriteLine(host.Status);
            else
                await Console.In.ReadLineAsync(stop.Token);
        }
        finally
        {
            await stop.CancelAsync();
            await started;
        }
    }

    private sealed class SyntheticInput : ISessionInput
    {
        public bool TryKey(long generation, CanonicalKey key, bool down) => false;
        public bool TryText(long generation, string text)
        {
            if (generation != 1 || text != "m2-synthetic")
                return false;
            Console.WriteLine("AUTHENTICATED");
            return true;
        }
        public void ReleaseKeys(IReadOnlyList<CanonicalKey> keys) { }
    }
}
