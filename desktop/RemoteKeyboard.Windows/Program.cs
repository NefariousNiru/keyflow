using System.Drawing;

namespace RemoteKeyboard.Windows;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        ApplicationConfiguration.Initialize();
        using var menu = new ContextMenuStrip();
        menu.Items.Add("Scaffold mode").Enabled = false;
        menu.Items.Add("Exit", null, (_, _) => Application.ExitThread());
        using var tray = new NotifyIcon
        {
            Icon = SystemIcons.Application,
            Text = "Keyflow",
            ContextMenuStrip = menu,
            Visible = true
        };
        Application.Run();
    }
}
