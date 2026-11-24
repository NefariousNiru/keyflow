# Windows packaging

Use the self-contained win-x64 publish command in the [root README](../../README.md) for M0. Setup executable and installer validation are scheduled for M6.

## Private Wi-Fi firewall setup

Run the Windows app as a standard interactive user. Installing the rule is an administrator setup action. Select the specific physical Wi-Fi adapter and a Windows network profile already set to Private.

```powershell
& .\packaging\windows\Set-KeyflowFirewall.ps1 -Program .\artifacts\windows\RemoteKeyboard.Windows.exe -InterfaceAlias 'Wi-Fi'
```

The inbound rule allows TCP 48732 and 48733 for that executable on the named Wireless interface, Private profile and LocalSubnet only. Edge traversal is blocked. The app also checks its selected profile and on-link endpoints. Pass `-Remove` with the same executable path to remove the rule. Use the published M2 harness path in place of the app path for the physical LAN test.