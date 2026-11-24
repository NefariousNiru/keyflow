using System.Runtime.InteropServices;
using System.Windows.Automation;

internal static class NativeInputCheck
{
    private const char SyntheticCharacter = '\uE000';
    private const uint KeyboardInputType = 1;
    private const uint UnicodeFlag = 0x0004;
    private const uint KeyUpFlag = 0x0002;

    public static void Run()
    {
        // The WinForms message loop stays responsive while UIA inspects it from an MTA worker.
        var completion = new TaskCompletionSource();
        var ui = new Thread(() => RunForm(completion));
        ui.SetApartmentState(ApartmentState.STA);
        ui.Start();
        ui.Join();
        if (!completion.Task.IsCompleted)
            throw new InvalidOperationException("The test window closed before verification.");
        completion.Task.GetAwaiter().GetResult();
        Console.WriteLine("Verified UI Automation edit metadata and received synthetic Unicode through SendInput.");
    }

    private static void RunForm(TaskCompletionSource completion)
    {
        using var form = new Form { Text = "Keyflow input smoke test" };
        using var input = new TextBox { Dock = DockStyle.Top };
        form.Controls.Add(input);
        form.Shown += async (_, _) =>
        {
            try
            {
                form.Activate();
                input.Focus();
                var window = form.Handle;
                var control = input.Handle;
                await Task.Run(() => InspectAndInject(window, control));
                var deadline = Environment.TickCount64 + 2000;
                while (input.TextLength == 0 && Environment.TickCount64 < deadline)
                    await Task.Delay(25);
                if (input.Text != SyntheticCharacter.ToString())
                    throw new InvalidOperationException("The test field did not receive the expected character.");
                completion.SetResult();
            }
            catch (Exception error)
            {
                completion.SetException(error);
            }
            finally
            {
                form.Close();
            }
        };
        System.Windows.Forms.Application.Run(form);
    }

    private static void InspectAndInject(IntPtr window, IntPtr control)
    {
        var focus = AutomationElement.FocusedElement;
        if (focus is null ||
            focus.Current.NativeWindowHandle != control.ToInt32() ||
            focus.Current.ControlType != ControlType.Edit ||
            !focus.Current.IsEnabled || focus.Current.IsPassword ||
            !focus.TryGetCurrentPattern(ValuePattern.Pattern, out var value) ||
            value is not ValuePattern pattern || pattern.Current.IsReadOnly ||
            GetForegroundWindow() != window)
        {
            throw new InvalidOperationException("The dedicated editable test field was not verified as the foreground target.");
        }

        var events = new Input[]
        {
            new() { Type = KeyboardInputType, Key = new() { Scan = SyntheticCharacter, Flags = UnicodeFlag } },
            new() { Type = KeyboardInputType, Key = new() { Scan = SyntheticCharacter, Flags = UnicodeFlag | KeyUpFlag } }
        };
        if (SendInput((uint)events.Length, events, Marshal.SizeOf<Input>()) != events.Length)
            throw new InvalidOperationException("SendInput did not enqueue both events.");
    }

    // INPUT contains a union whose largest member is MOUSEINPUT on x64.
    [StructLayout(LayoutKind.Explicit, Size = 40)]
    private struct Input
    {
        [FieldOffset(0)] public uint Type;
        [FieldOffset(8)] public KeyboardInput Key;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct KeyboardInput
    {
        public ushort VirtualKey;
        public ushort Scan;
        public uint Flags;
        public uint Time;
        public UIntPtr ExtraInfo;
    }

    [DllImport("user32.dll")]
    private static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint count, Input[] inputs, int inputSize);
}
