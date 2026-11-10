#requires -Version 5.1
<#
.SYNOPSIS
  Build web/ and copy the static bundle into Spring Boot's resources/static.

.DESCRIPTION
  Runs the TypeScript check and the Vite production build, then replaces
  src/main/resources/static with web/dist. Maven is deliberately untouched:
  `mvn spring-boot:run` will serve the copied files from the classpath.

  The generated directory is gitignored; nothing here changes the Java build
  or the test suite.
#>
[CmdletBinding()]
param(
    [string]$WebRoot,
    [string]$TargetDir
)

$ErrorActionPreference = 'Stop'

# Windows PowerShell 5.1 does not populate $PSScriptRoot inside the param()
# default expressions, so resolve it after binding.
$scriptRoot = $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($scriptRoot)) {
    $scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
}
if ([string]::IsNullOrWhiteSpace($WebRoot)) {
    $WebRoot = Join-Path $scriptRoot '..\web'
}
if ([string]::IsNullOrWhiteSpace($TargetDir)) {
    $TargetDir = Join-Path $scriptRoot '..\src\main\resources\static'
}

function Invoke-Npm {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    & npm @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "npm $($Arguments -join ' ') failed with exit code $LASTEXITCODE"
    }
}

$resolvedWeb = [System.IO.Path]::GetFullPath($WebRoot)
$resolvedTarget = [System.IO.Path]::GetFullPath($TargetDir)
$expectedSuffix = [System.IO.Path]::Combine('src', 'main', 'resources', 'static')

if (-not (Test-Path (Join-Path $resolvedWeb 'package.json'))) {
    throw "web/package.json not found under $resolvedWeb"
}
if (-not $resolvedTarget.EndsWith($expectedSuffix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to replace an unexpected path: $resolvedTarget"
}

Push-Location $resolvedWeb
try {
    if (-not (Test-Path (Join-Path $resolvedWeb 'node_modules'))) {
        Write-Host 'node_modules missing; installing dependencies...'
        Invoke-Npm -Arguments @('install', '--no-audit', '--no-fund')
    }

    Write-Host 'Type-checking and building web/...'
    Invoke-Npm -Arguments @('run', 'build')

    $dist = Join-Path $resolvedWeb 'dist'
    if (-not (Test-Path (Join-Path $dist 'index.html'))) {
        throw "Build output not found at $dist"
    }

    if (Test-Path $resolvedTarget) {
        Write-Host "Replacing $resolvedTarget"
        Remove-Item -LiteralPath $resolvedTarget -Recurse -Force
    }
    New-Item -ItemType Directory -Path $resolvedTarget -Force | Out-Null
    Copy-Item -Path (Join-Path $dist '*') -Destination $resolvedTarget -Recurse -Force

    Write-Host ''
    Write-Host "Copied web/dist -> $resolvedTarget"
    Write-Host 'Restart the backend, then open http://127.0.0.1:8080/'
}
finally {
    Pop-Location
}
