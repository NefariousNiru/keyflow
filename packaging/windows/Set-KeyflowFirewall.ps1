[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory)] [string]$Program,
    [Parameter(Mandatory)] [string]$InterfaceAlias,
    [switch]$Remove
)

$ErrorActionPreference = 'Stop'
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
if (-not [Security.Principal.WindowsPrincipal]::new($identity).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Windows requires an administrator to install or remove a firewall rule. Run this setup command in elevated PowerShell.'
}

$exe = [IO.Path]::GetFullPath($Program)
if ([IO.Path]::GetFileName($exe) -notin 'RemoteKeyboard.Windows.exe','RemoteKeyboard.Windows.Spike.exe') {
    throw 'Use the Keyflow executable or the published M2 validation harness.'
}
$identityBytes = [Text.Encoding]::UTF8.GetBytes($exe.ToUpperInvariant())
$hash = [Security.Cryptography.SHA256]::Create()
try { $suffix = [BitConverter]::ToString($hash.ComputeHash($identityBytes)).Replace('-', '').Substring(0, 16) }
finally { $hash.Dispose() }
$name = "Keyflow-Private-WiFi-$suffix"

if ($Remove) {
    if ($PSCmdlet.ShouldProcess($name, 'Remove firewall rule')) {
        Get-NetFirewallRule -Name $name -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    }
    return
}
if (-not (Test-Path -LiteralPath $exe -PathType Leaf)) { throw 'Keyflow executable was not found.' }
$adapter = Get-NetAdapter -Name $InterfaceAlias -Physical
$profiles = @(Get-NetConnectionProfile -InterfaceIndex $adapter.ifIndex)
if ($adapter.Status -ne 'Up' -or $adapter.MediaType -ne 'Native 802.11' -or
    $profiles.Count -ne 1 -or $profiles[0].NetworkCategory -ne 'Private') {
    throw 'Select a connected physical Wi-Fi adapter on a trusted Private profile.'
}
if ($PSCmdlet.ShouldProcess($exe, 'Install Private Wi-Fi TCP rule')) {
    $scope = @{
        Profile = 'Private'
        Direction = 'Inbound'
        Action = 'Allow'
        Enabled = 'True'
        Program = $exe
        InterfaceAlias = $adapter.Name
        InterfaceType = 'Wireless'
        Protocol = 'TCP'
        LocalPort = @('48732','48733')
        RemoteAddress = 'LocalSubnet'
        EdgeTraversalPolicy = 'Block'
    }
    if (Get-NetFirewallRule -Name $name -ErrorAction SilentlyContinue) {
        Set-NetFirewallRule -Name $name @scope
    } else {
        New-NetFirewallRule -Name $name -DisplayName 'Keyflow on trusted Private Wi-Fi' @scope | Out-Null
    }
}
