using System.Drawing;
using System.IO;
using System.Runtime.InteropServices;
using System.Text.Json;
using RemoteKeyboard.Core.Pairing;
using RemoteKeyboard.Core.Protocol;
using RemoteKeyboard.Core.Sessions;
using RemoteKeyboard.Windows.Network;

namespace RemoteKeyboard.Windows;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        ApplicationConfiguration.Initialize();
        using var menu = new ContextMenuStrip();
        using var tray = new NotifyIcon
        {
            Icon = SystemIcons.Application,
            Text = "Keyflow",
            ContextMenuStrip = menu,
            Visible = true
        };

        var root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Keyflow");
        var preference = Path.Combine(root, "wifi.json");
        var store = new WindowsTrustStore(root);
        using var identity = store.LoadOrCreateIdentity();
        var pairing = new PairingWindow(TimeProvider.System);
        var trust = new DesktopTrust(store, pairing, TimeProvider.System);
        var host = new LanHost(trust, pairing, identity, new InactiveInput());
        CancellationTokenSource? running = null;
        Task? listening = null;
        WifiSelection? selection = LoadSelection(preference);

        void Stop()
        {
            if (running is null)
                return;
            running.Cancel();
            try
            {
                listening?.GetAwaiter().GetResult();
            }
            finally
            {
                running.Dispose();
                running = null;
            }
        }

        void Start()
        {
            if (selection is not { } selected)
                return;
            running = new CancellationTokenSource();
            var token = running.Token;
            listening = Task.Run(() => host.RunAsync(selected, token), token);
        }

        void Select(WifiSelection? chosen)
        {
            Stop();
            if (chosen is null)
                File.Delete(preference);
            else
            {
                Directory.CreateDirectory(root);
                File.WriteAllText(preference, JsonSerializer.Serialize(chosen));
            }
            selection = chosen;
            Start();
        }

        menu.Opening += (_, _) =>
        {
            menu.Items.Clear();
            menu.Items.Add(selection is null ? "Select a trusted Wi-Fi profile" : host.Status).Enabled = false;
            var networks = new ToolStripMenuItem("Trusted Wi-Fi");
            try
            {
                foreach (var link in WindowsWifi.ListPrivate())
                {
                    var choice = new ToolStripMenuItem(link.Label) { Checked = link.Selection == selection };
                    choice.Click += (_, _) => Select(link.Selection);
                    networks.DropDownItems.Add(choice);
                }
            }
            catch (COMException)
            {
                networks.DropDownItems.Add("Windows network status unavailable").Enabled = false;
            }
            if (networks.DropDownItems.Count == 0)
                networks.DropDownItems.Add("Connect to a Private Wi-Fi profile").Enabled = false;
            if (selection is not null)
                networks.DropDownItems.Add("Forget selection", null, (_, _) => Select(null));
            menu.Items.Add(networks);

            var pair = menu.Items.Add("Pair phone", null, (_, _) =>
            {
                var address = host.Listening?.Address;
                if (address is not null && host.ArmPairing() is { } code)
                    MessageBox.Show($"Desktop IP {address}\nPairing code {code}\nExpires after two minutes.", "Keyflow");
            });
            pair.Enabled = host.Listening is not null;

            menu.Items.Add("Revoke phone", null, (_, _) => trust.Revoke());
            menu.Items.Add("Exit", null, (_, _) => Application.ExitThread());
        };

        Start();
        try
        {
            Application.Run();
        }
        finally
        {
            Stop();
        }
    }

    private static WifiSelection? LoadSelection(string path)
    {
        if (!File.Exists(path))
            return null;
        try
        {
            return JsonSerializer.Deserialize<WifiSelection>(File.ReadAllText(path));
        }
        catch (JsonException)
        {
            return null; // A corrupt preference requires a fresh explicit selection.
        }
    }

    private sealed class InactiveInput : ISessionInput
    {
        public bool TryKey(long generation, CanonicalKey key, bool down) => false;
        public bool TryText(long generation, string text) => false;
        public void ReleaseKeys(IReadOnlyList<CanonicalKey> keys) { }
    }
}
