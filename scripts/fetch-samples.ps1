#requires -Version 5.1
<#
.SYNOPSIS
  Fetches the official sample PDF that is intentionally not committed.

.DESCRIPTION
  docs/samples/hypertension-2025.pdf is a publicly distributed guideline (~1.2 MB) that the
  repository does not redistribute. CI and a fresh clone download it here, and the SHA-256 from
  docs/samples/SOURCES.md is verified so the eval corpus cannot drift silently.

  The other two eval documents are project-generated (the 2024 test excerpt and the 2026
  typeset copy of a public norm) and are committed under docs/samples/ directly.

.EXAMPLE
  pwsh scripts/fetch-samples.ps1
#>
[CmdletBinding()]
param(
    [string]$OutputDir = 'docs/samples',
    [string]$FileName = 'hypertension-2025.pdf',
    [string]$Url = 'https://hbp-office.nccd.org.cn/files/%E5%9B%BD%E5%AE%B6%E5%9F%BA%E5%B1%82%E9%AB%98%E8%A1%80%E5%8E%8B%E9%98%B2%E6%B2%BB%E7%AE%A1%E7%90%86%E6%8C%87%E5%8D%972025%E7%89%88.pdf',
    [string]$Sha256 = 'd6da41b5d3565c4969150009a6a5d00bb45de19b361cfa3ec9a0f51d25ef9bbd'
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

$target = Join-Path $OutputDir $FileName
$directory = Split-Path -Parent $target
if ($directory -and -not (Test-Path -LiteralPath $directory)) {
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
}

function Get-Sha256([string]$Path) {
    $stream = [System.IO.File]::OpenRead($Path)
    try {
        $sha = [System.Security.Cryptography.SHA256]::Create()
        try {
            return ([System.BitConverter]::ToString($sha.ComputeHash($stream)) -replace '-', '').ToLowerInvariant()
        } finally {
            $sha.Dispose()
        }
    } finally {
        $stream.Dispose()
    }
}

if (Test-Path -LiteralPath $target) {
    $existing = Get-Sha256 $target
    if ($existing -eq $Sha256) {
        Write-Host "sample already present and verified: $target"
        return
    }
    Write-Host "sample exists but checksum differs, re-downloading: $target"
}

$temp = [System.IO.Path]::GetTempFileName()
try {
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds(180)
    try {
        Write-Host "downloading $FileName"
        $response = $client.GetAsync($Url).GetAwaiter().GetResult()
        $response.EnsureSuccessStatusCode() | Out-Null
        $bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
        if ($bytes.Length -lt 1000) {
            throw "downloaded file is suspiciously small: $($bytes.Length) bytes"
        }
        [System.IO.File]::WriteAllBytes($temp, $bytes)
    } finally {
        $client.Dispose()
    }

    $actual = Get-Sha256 $temp
    if ($actual -ne $Sha256) {
        throw "checksum mismatch for $FileName`nexpected: $Sha256`nactual:   $actual"
    }
    Move-Item -LiteralPath $temp -Destination $target -Force
    Write-Host "verified and stored: $target"
} finally {
    if (Test-Path -LiteralPath $temp) {
        Remove-Item -LiteralPath $temp -Force -ErrorAction SilentlyContinue
    }
}
