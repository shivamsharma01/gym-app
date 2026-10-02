<#
.SYNOPSIS
  Adds people a TrueFace restore skipped, from a backup CSV, using the POC's adapter calls.

.EXAMPLE
  # 1. Dry run: lists who would be created, writes nothing.
  .\Import-MissingDeviceUsers.ps1 -Csv C:\gym\missing_device_users_73.csv -Ip 192.168.31.91

  # 2. Write them.
  .\Import-MissingDeviceUsers.ps1 -Csv C:\gym\missing_device_users_73.csv -Ip 192.168.31.91 -Apply
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $Csv,
    [Parameter(Mandatory)] [string] $Ip,
    [int] $Port = 37777,
    [string] $Username = 'admin',
    [switch] $Apply,
    [switch] $SkipFaces
)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot

if (-not (Test-Path $Csv)) { throw "CSV not found: $Csv" }
if (-not (Get-Command dotnet -ErrorAction SilentlyContinue)) { throw 'dotnet (.NET 10 SDK) is not on PATH' }

$gateway = Get-Service -Name 'Gym Gateway' -ErrorAction SilentlyContinue
if ($gateway -and $gateway.Status -ne 'Stopped') {
    throw "The 'Gym Gateway' service is $($gateway.Status). Stop it first (Stop-Service 'Gym Gateway' from an admin PowerShell) so it does not write to the device during the import."
}

$ias = Get-Process | Where-Object { $_.ProcessName -match '^(IAS|SmartPSS|SmartPss)' }
if ($ias) {
    throw "Close $(($ias | Select-Object -ExpandProperty ProcessName -Unique) -join ', ') before running the import."
}

$secure = Read-Host -AsSecureString "Device password for $Username@$Ip"
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
    $env:TRUEFACE_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    $env:TRUEFACE_IP = $Ip
    $env:TRUEFACE_PORT = "$Port"
    $env:TRUEFACE_USERNAME = $Username

    $toolArgs = @('--import-csv', (Resolve-Path $Csv).Path)
    if ($Apply) { $toolArgs += '--apply' }
    if ($SkipFaces) { $toolArgs += '--skip-face-upload' }

    Push-Location $project
    try {
        & dotnet run -c Release -- @toolArgs
        $code = $LASTEXITCODE
    }
    finally {
        Pop-Location
    }
}
finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    Remove-Item Env:TRUEFACE_PASSWORD -ErrorAction SilentlyContinue
}

Write-Host ''
Write-Host "Log written to $project\poc-run-*.log (latest). Exit code $code."
if ($code -eq 0 -and -not $Apply) { Write-Host 'Dry run OK. Rerun with -Apply to create the users.' }
if ($code -eq 0 -and $Apply) { Write-Host 'Import done. Start the gateway again: Start-Service ''Gym Gateway''' }
exit $code
