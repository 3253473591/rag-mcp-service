#requires -Version 5.1
<#
.SYNOPSIS
  Seeds the three sample documents that the eval harness expects.

.DESCRIPTION
  CI and reproducible local eval need the same corpus as docs/acceptance/*:
  hypertension-guideline 2024 (superseded by 2025) and enterprise-internal-control 2026,
  each ingested with chunkStrategies=fixed,heading.

  Uploads are idempotent at the API level (same checksum returns 200 with duplicate=true),
  so this script can be re-run against a populated database.

.PARAMETER BaseUrl
  Running application base URL.

.PARAMETER SamplesDir
  Directory holding the sample PDFs.

.EXAMPLE
  pwsh scripts/seed-samples.ps1 -BaseUrl http://127.0.0.1:8080
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$SamplesDir = 'docs/samples',
    [int]$HealthTimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

$httpClient = [System.Net.Http.HttpClient]::new()
$httpClient.Timeout = [TimeSpan]::FromSeconds(600)

function Wait-Health {
    $deadline = (Get-Date).AddSeconds($HealthTimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $response = $httpClient.GetAsync("$BaseUrl/actuator/health").GetAwaiter().GetResult()
            if ([int]$response.StatusCode -eq 200) {
                $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                if ($text -match '"status"\s*:\s*"UP"') {
                    Write-Host "health: UP"
                    return
                }
            }
        } catch {
            # not ready yet
        }
        Start-Sleep -Seconds 2
    }
    throw "application did not become healthy within $HealthTimeoutSeconds s: $BaseUrl"
}

function Send-Upload {
    param(
        [string]$FilePath,
        [hashtable]$Parameters
    )
    if (-not (Test-Path -LiteralPath $FilePath)) {
        throw "sample not found: $FilePath"
    }
    $form = [System.Net.Http.MultipartFormDataContent]::new()
    $bytes = [System.IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $FilePath).Path)
    $fileContent = [System.Net.Http.ByteArrayContent]::new($bytes)
    $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse('application/pdf')
    $form.Add($fileContent, 'file', [System.IO.Path]::GetFileName($FilePath))
    foreach ($key in $Parameters.Keys) {
        $field = [System.Net.Http.StringContent]::new($Parameters[$key], [System.Text.Encoding]::UTF8)
        $form.Add($field, $key)
    }

    $response = $httpClient.PostAsync("$BaseUrl/api/v1/documents", $form).GetAwaiter().GetResult()
    $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $status = [int]$response.StatusCode
    if ($status -ne 200 -and $status -ne 201) {
        throw "upload failed ($status) for $FilePath : $text"
    }
    return $text | ConvertFrom-Json
}

Write-Host "seeding $BaseUrl from $SamplesDir"
Wait-Health

$year2024 = Send-Upload -FilePath (Join-Path $SamplesDir 'hypertension-2024.pdf') -Parameters @{
    docKey          = 'hypertension-guideline'
    version         = '2024'
    effectiveFrom   = '2024-01-01'
    effectiveTo     = '2024-12-31'
    chunkStrategies = 'fixed,heading'
}
Write-Host ("uploaded hypertension-2024 status={0} duplicate={1} chunks={2}" -f
    $year2024.status, $year2024.duplicate, $year2024.chunkCount)

$supersede = @{
    docKey          = 'hypertension-guideline'
    version         = '2025'
    effectiveFrom   = '2025-01-01'
    chunkStrategies = 'fixed,heading'
}
if (-not $year2024.duplicate -and $year2024.docId) {
    $supersede.supersedesDocId = $year2024.docId
}
$year2025 = Send-Upload -FilePath (Join-Path $SamplesDir 'hypertension-2025.pdf') -Parameters $supersede
Write-Host ("uploaded hypertension-2025 status={0} duplicate={1} chunks={2}" -f
    $year2025.status, $year2025.duplicate, $year2025.chunkCount)

$enterprise = Send-Upload -FilePath (Join-Path $SamplesDir 'enterprise-policy-2026.pdf') -Parameters @{
    docKey          = 'enterprise-internal-control'
    version         = '2026'
    effectiveFrom   = '2026-01-01'
    chunkStrategies = 'fixed,heading'
}
Write-Host ("uploaded enterprise-policy-2026 status={0} duplicate={1} chunks={2}" -f
    $enterprise.status, $enterprise.duplicate, $enterprise.chunkCount)

$httpClient.Dispose()
Write-Host "seed complete"
Write-Host ("corpus: 2024={0} 2025={1} 2026={2} chunks (fixed+heading)" -f
    $year2024.chunkCount, $year2025.chunkCount, $enterprise.chunkCount)
