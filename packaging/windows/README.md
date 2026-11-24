# Windows packaging

Use the self-contained win-x64 publish command in the [root README](../../README.md) for M0. Setup executable and installer validation are scheduled for M6.

## Wi-Fi firewall setup

Run the Windows app as a standard interactive user. Installing the rule is an administrator setup action. Select the specific physical Wi-Fi adapter. Private profiles allow the local subnet. Public profiles require the exact on-link IPv4 address of the phone.

```powershell
& .\packaging\windows\Set-KeyflowFirewall.ps1 -Program .\artifacts\windows\RemoteKeyboard.Windows.exe -InterfaceAlias 'Wi-Fi'
```

The inbound rule allows TCP 48732 and 48733 for that executable on the named Wireless interface, Private profile and LocalSubnet only. Edge traversal is blocked. The app also checks its selected profile and on-link endpoints. Pass `-Remove` with the same executable path to remove the rule. Use the published M2 harness path in place of the app path for the physical LAN test.
For a Public profile, include the phone address and use the same value in **KeyFlow → Trusted Wi-Fi**. Enter the desktop Wi-Fi IPv4 address in the Android app.

```powershell
& .\packaging\windows\Set-KeyflowFirewall.ps1 -Program .\artifacts\windows\RemoteKeyboard.Windows.exe -InterfaceAlias 'Wi-Fi' -PublicPeer 'PHONE-IPV4'
```

The Public rule is scoped to the executable, the named Wireless interface, TCP 48732 and 48733, and that one peer address. If the phone address changes, update the Windows selection and rule. If the desktop address changes, update the Android manual address. Removing the Public rule uses the same command with `-Remove`. Wi-Fi client isolation or managed firewall and VPN policies can still block connectivity.