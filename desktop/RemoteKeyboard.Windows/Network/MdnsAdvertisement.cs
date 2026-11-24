using System.ComponentModel;
using System.Runtime.InteropServices;
using RemoteKeyboard.Core.Network;

namespace RemoteKeyboard.Windows.Network;

/// <summary>Publishes only an ephemeral instance name, local address and session port on the selected interface.</summary>
internal sealed class MdnsAdvertisement : IAsyncDisposable
{
    private const uint DnsRequestPending = 9506;
    private static readonly Completion Callback = Complete;
    private readonly IntPtr instance;
    private readonly uint interfaceIndex;

    private MdnsAdvertisement(IntPtr instance, uint interfaceIndex)
    {
        this.instance = instance;
        this.interfaceIndex = interfaceIndex;
    }

    public static async Task<MdnsAdvertisement> RegisterAsync(WifiLink link, CancellationToken token)
    {
        var random = Guid.NewGuid().ToString("N")[..12];
        uint ipv4 = BitConverter.ToUInt32(link.Address.GetAddressBytes());
        var instance = DnsServiceConstructInstance(
            $"Keyflow-{random}._keyflow._tcp.local", $"keyflow-{random}.local", ref ipv4, IntPtr.Zero,
            LanPorts.Session, 0, 0, 0, IntPtr.Zero, IntPtr.Zero);
        if (instance == IntPtr.Zero)
            throw new Win32Exception("Could not construct the local DNS-SD service.");

        var service = new MdnsAdvertisement(instance, (uint)link.Index);
        try
        {
            await service.ExecuteAsync(true, token).ConfigureAwait(false);
            return service;
        }
        catch
        {
            DnsServiceFreeInstance(instance);
            throw;
        }
    }

    public async ValueTask DisposeAsync()
    {
        try
        {
            await ExecuteAsync(false, CancellationToken.None).ConfigureAwait(false);
        }
        finally
        {
            DnsServiceFreeInstance(instance);
        }
    }

    private async Task ExecuteAsync(bool register, CancellationToken token)
    {
        var result = new TaskCompletionSource<uint>(TaskCreationOptions.RunContinuationsAsynchronously);
        var context = GCHandle.Alloc(result);
        // DNSAPI holds the callback context until completion. Keep it rooted until the callback.
        var request = new Request(1, interfaceIndex, instance, Callback, GCHandle.ToIntPtr(context), IntPtr.Zero, 0);
        IntPtr cancel = register ? Marshal.AllocHGlobal(IntPtr.Size) : IntPtr.Zero;
        if (register)
            Marshal.WriteIntPtr(cancel, IntPtr.Zero);
        try
        {
            uint status = register ? DnsServiceRegister(ref request, cancel) : DnsServiceDeRegister(ref request, IntPtr.Zero);
            if (status != DnsRequestPending)
                throw new Win32Exception((int)status);

            using var cancellation = register
                ? token.Register(static pointer => DnsServiceRegisterCancel((IntPtr)pointer!), cancel)
                : default;
            status = await result.Task.ConfigureAwait(false);
            if (status != 0)
                throw new Win32Exception((int)status);
        }
        finally
        {
            if (cancel != IntPtr.Zero)
                Marshal.FreeHGlobal(cancel);
            context.Free();
        }
    }

    private static void Complete(uint status, IntPtr context, IntPtr returned)
    {
        if (returned != IntPtr.Zero)
            DnsServiceFreeInstance(returned);
        ((TaskCompletionSource<uint>)GCHandle.FromIntPtr(context).Target!).TrySetResult(status);
    }

    [UnmanagedFunctionPointer(CallingConvention.Winapi)]
    private delegate void Completion(uint status, IntPtr context, IntPtr instance);

    [StructLayout(LayoutKind.Sequential)]
    private readonly record struct Request(
        uint Version, uint InterfaceIndex, IntPtr Instance, Completion Callback, IntPtr Context,
        IntPtr Credentials, int UnicastEnabled);

    [DllImport("dnsapi.dll", CharSet = CharSet.Unicode, ExactSpelling = true)]
    private static extern IntPtr DnsServiceConstructInstance(
        string service, string host, ref uint ipv4, IntPtr ipv6, ushort port, ushort priority, ushort weight,
        uint propertyCount, IntPtr keys, IntPtr values);

    [DllImport("dnsapi.dll", ExactSpelling = true)]
    private static extern void DnsServiceFreeInstance(IntPtr instance);

    [DllImport("dnsapi.dll", ExactSpelling = true)]
    private static extern uint DnsServiceRegister(ref Request request, IntPtr cancel);

    [DllImport("dnsapi.dll", ExactSpelling = true)]
    private static extern uint DnsServiceRegisterCancel(IntPtr cancel);

    [DllImport("dnsapi.dll", ExactSpelling = true)]
    private static extern uint DnsServiceDeRegister(ref Request request, IntPtr cancel);
}
