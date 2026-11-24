using System.ComponentModel;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using Microsoft.Win32;
using RemoteKeyboard.Core.Network;
using RemoteKeyboard.Core.Pairing;
using RemoteKeyboard.Core.Sessions;

namespace RemoteKeyboard.Windows.Network;

/// <summary>Owns listeners and authenticated sessions for one explicitly selected Private Wi-Fi profile.</summary>
public sealed class LanHost(
    DesktopTrust trust, PairingWindow pairing, X509Certificate2 certificate, ISessionInput input,
    bool syntheticFocus = false)
{
    private readonly object state = new();
    private readonly SemaphoreSlim inputSession = new(1, 1);
    private CancellationTokenSource? activeLink;
    private bool suspended;
    private volatile WifiLink? listening;
    private volatile string status = "Waiting for trusted Wi-Fi";

    public string Status => status;
    public WifiLink? Listening => listening;
    public string? ArmPairing() => listening is null ? null : pairing.Arm();

    public async Task RunAsync(WifiSelection selection, CancellationToken token)
    {
        NetworkChange.NetworkAddressChanged += OnAddressChanged;
        NetworkChange.NetworkAvailabilityChanged += OnAvailabilityChanged;
        SystemEvents.PowerModeChanged += OnPowerChanged;
        try
        {
            while (!token.IsCancellationRequested)
            {
                using var generation = CancellationTokenSource.CreateLinkedTokenSource(token);
                lock (state)
                {
                    if (!suspended)
                        activeLink = generation;
                }

                try
                {
                    if (activeLink == generation && WindowsWifi.Find(selection) is { } link)
                        await ListenAsync(link, generation.Token).ConfigureAwait(false);
                    else
                        status = "Waiting for trusted Private Wi-Fi";
                }
                catch (OperationCanceledException) when (generation.IsCancellationRequested)
                {
                    // A network, power or application event invalidated the current listeners.
                }
                catch (Exception error) when (IsNetworkFailure(error))
                {
                    status = "Local access blocked or Wi-Fi unavailable";
                }
                finally
                {
                    lock (state)
                        activeLink = null;
                    listening = null;
                    pairing.Cancel();
                }

                await Task.Delay(TimeSpan.FromSeconds(2), token).ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException) when (token.IsCancellationRequested)
        {
            // Application is stopping.
        }
        finally
        {
            SystemEvents.PowerModeChanged -= OnPowerChanged;
            NetworkChange.NetworkAvailabilityChanged -= OnAvailabilityChanged;
            NetworkChange.NetworkAddressChanged -= OnAddressChanged;
            listening = null;
        }
    }

    private async Task ListenAsync(WifiLink link, CancellationToken token)
    {
        using var reconnect = new TcpListener(link.Address, LanPorts.Session);
        using var pair = new TcpListener(link.Address, LanPorts.Pairing);
        reconnect.Server.ExclusiveAddressUse = true;
        pair.Server.ExclusiveAddressUse = true;
        reconnect.Start(2);
        pair.Start(2);
        listening = link;
        status = "Listening on trusted Wi-Fi";

        using var stay = CancellationTokenSource.CreateLinkedTokenSource(token);
        bool discoveryRoute = link.UsesCurrentSystemRoute(link.Gateway);
        var monitor = WatchLinkAsync(link, discoveryRoute, stay.Token);
        var reconnecting = AcceptAsync(reconnect, link, false, stay.Token);
        var pairingRequests = AcceptAsync(pair, link, true, stay.Token);
        var advertising = discoveryRoute ? AdvertiseAsync(link, stay.Token) : Task.CompletedTask;
        try
        {
            await Task.WhenAny(monitor, reconnecting, pairingRequests).ConfigureAwait(false);
        }
        finally
        {
            await stay.CancelAsync().ConfigureAwait(false);
            listening = null;
            status = "Waiting for trusted Wi-Fi";
            try
            {
                await Task.WhenAll(monitor, reconnecting, pairingRequests, advertising).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (stay.IsCancellationRequested)
            {
                // Link shutdown canceled the workers.
            }
        }
    }

    private async Task AdvertiseAsync(WifiLink link, CancellationToken token)
    {
        try
        {
            await using var advertisement = await MdnsAdvertisement.RegisterAsync(link, token).ConfigureAwait(false);
            await Task.Delay(Timeout.Infinite, token).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (token.IsCancellationRequested)
        {
            // The service is withdrawn when the link ends.
        }
        catch (Exception error) when (error is Win32Exception or EntryPointNotFoundException)
        {
            if (!token.IsCancellationRequested)
                status = "Discovery unavailable. Use the desktop IP.";
        }
    }

    private static async Task WatchLinkAsync(WifiLink link, bool discoveryRoute, CancellationToken token)
    {
        using var ticks = new PeriodicTimer(TimeSpan.FromSeconds(2));
        while (await ticks.WaitForNextTickAsync(token).ConfigureAwait(false))
            if (WindowsWifi.Find(link.Selection) != link || link.UsesCurrentSystemRoute(link.Gateway) != discoveryRoute)
                return;
    }

    private async Task AcceptAsync(TcpListener listener, WifiLink link, bool isPairing, CancellationToken token)
    {
        while (!token.IsCancellationRequested)
        {
            using var client = await listener.AcceptTcpClientAsync(token).ConfigureAwait(false);
            if (client.Client.RemoteEndPoint is not IPEndPoint endpoint ||
                client.Client.LocalEndPoint is not IPEndPoint local || !local.Address.Equals(link.Address) ||
                !CurrentLinkAllows(link, endpoint.Address))
                continue;

            client.NoDelay = true;
            try
            {
                await using var connection = isPairing
                    ? await trust.PairAsync(client.GetStream(), certificate, token).ConfigureAwait(false)
                    : await trust.ReconnectAsync(client.GetStream(), certificate, token).ConfigureAwait(false);

                using var dispatch = CancellationTokenSource.CreateLinkedTokenSource(token, connection.Cancellation);
                await inputSession.WaitAsync(dispatch.Token).ConfigureAwait(false);
                try
                {
                    await LiveDesktopSession.RunAsync(trust, connection, input,
                        () => CurrentLinkAllows(link, endpoint.Address), syntheticFocus, dispatch.Token).ConfigureAwait(false);
                }
                catch (ObjectDisposedException) when (dispatch.IsCancellationRequested)
                {
                    // Revocation or replacement may close TLS while a frame is in flight.
                }
                finally
                {
                    inputSession.Release();
                }
            }
            catch (OperationCanceledException) when (!token.IsCancellationRequested)
            {
                // Peer, pairing or handshake deadline ended this connection.
            }
            catch (Exception error) when (IsNetworkFailure(error))
            {
                // Drop this endpoint. Other authenticated reconnects may continue.
            }
        }
    }

    private static bool CurrentLinkAllows(WifiLink link, IPAddress peer) =>
        WindowsWifi.Find(link.Selection) == link && link.Allows(peer);

    private static bool IsNetworkFailure(Exception failure) =>
        failure is IOException or SocketException or AuthenticationException or InvalidDataException
            or CryptographicException or Win32Exception or TimeoutException or System.Runtime.InteropServices.COMException;

    private void OnAddressChanged(object? sender, EventArgs args) => Invalidate();
    private void OnAvailabilityChanged(object? sender, NetworkAvailabilityEventArgs args) => Invalidate();

    private void OnPowerChanged(object sender, PowerModeChangedEventArgs args)
    {
        lock (state)
        {
            if (args.Mode == PowerModes.Suspend)
                suspended = true;
            else if (args.Mode == PowerModes.Resume)
                suspended = false;
            else
                return;
            activeLink?.Cancel();
        }
    }

    private void Invalidate()
    {
        lock (state)
            activeLink?.Cancel();
    }
}
