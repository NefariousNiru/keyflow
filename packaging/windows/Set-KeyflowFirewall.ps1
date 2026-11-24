[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory)] [string]$Program,
    [Parameter(Mandatory)] [string]$InterfaceAlias,
    [string]$PublicPeer,
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
    throw 'Use the KeyFlow executable or the published M2 validation harness.'
}
$identityBytes = [Text.Encoding]::UTF8.GetBytes($exe.ToUpperInvariant())
$hash = [Security.Cryptography.SHA256]::Create()
try { $suffix = [BitConverter]::ToString($hash.ComputeHash($identityBytes)).Replace('-', '').Substring(0, 16) }
finally { $hash.Dispose() }
$category = if ($PublicPeer) { 'Public' } else { 'Private' }
$name = "Keyflow-$category-WiFi-$suffix"

if ($Remove) {
    if ($PSCmdlet.ShouldProcess($name, 'Remove firewall rule')) {
        Get-NetFirewallRule -Name $name -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    }
    return
}
if (-not (Test-Path -LiteralPath $exe -PathType Leaf)) { throw 'KeyFlow executable was not found.' }
$adapter = Get-NetAdapter -Name $InterfaceAlias -Physical
$profiles = @(Get-NetConnectionProfile -InterfaceIndex $adapter.ifIndex)
if ($adapter.Status -ne 'Up' -or $adapter.MediaType -ne 'Native 802.11' -or
    $profiles.Count -ne 1 -or $profiles[0].NetworkCategory -ne $category) {
    throw "Select a connected physical Wi-Fi adapter on the requested $category profile."
}
$remoteAddress = 'LocalSubnet'
if ($PublicPeer) {
    $peer = $null
    if (-not [Net.IPAddress]::TryParse($PublicPeer, [ref]$peer) -or
        $peer.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork -or
        $peer.ToString() -cne $PublicPeer) {
        throw 'Public Wi-Fi requires one numeric phone IPv4 address.'
    }
    $local = @(Get-NetIPAddress -InterfaceIndex $adapter.ifIndex -AddressFamily IPv4 -AddressState Preferred)
    $parts = $peer.GetAddressBytes()
    $private = $parts[0] -eq 10 -or ($parts[0] -eq 172 -and $parts[1] -in (16..31)) -or
        ($parts[0] -eq 192 -and $parts[1] -eq 168)
    if ($local.Count -ne 1 -or $local[0].PrefixLength -notin (8..30) -or -not $private) {
        throw 'The phone must be on the selected private-addressed Wi-Fi subnet.'
    }
    $self = [Net.IPAddress]::Parse($local[0].IPAddress).GetAddressBytes()
    [array]::Reverse($self)
    [array]::Reverse($parts)
    $localNumber = [uint64][BitConverter]::ToUInt32($self, 0)
    $peerNumber = [uint64][BitConverter]::ToUInt32($parts, 0)
    $hostMask = ([uint64]1 -shl (32 - $local[0].PrefixLength)) - 1
    $hostNumber = $peerNumber -band $hostMask
    if (($localNumber -shr (32 - $local[0].PrefixLength)) -ne ($peerNumber -shr (32 - $local[0].PrefixLength)) -or
        $peerNumber -eq $localNumber -or $hostNumber -in @(0, $hostMask)) {
        throw 'The phone must be a different usable address on the selected Wi-Fi subnet.'
    }
    $remoteAddress = $PublicPeer
}

if ($PSCmdlet.ShouldProcess($exe, "Install $category Wi-Fi TCP rule")) {
    $scope = @{
        Profile = $category
        Direction = 'Inbound'
        Action = 'Allow'
        Enabled = 'True'
        Program = $exe
        InterfaceAlias = $adapter.Name
        InterfaceType = 'Wireless'
        Protocol = 'TCP'
        LocalPort = @('48732','48733')
        RemoteAddress = $remoteAddress
        EdgeTraversalPolicy = 'Block'
    }
    if (Get-NetFirewallRule -Name $name -ErrorAction SilentlyContinue) {
        Set-NetFirewallRule -Name $name @scope
    } else {
        New-NetFirewallRule -Name $name -DisplayName "KeyFlow on selected $category Wi-Fi" @scope | Out-Null
    }
}
