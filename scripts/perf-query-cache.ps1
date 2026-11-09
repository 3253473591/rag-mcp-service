[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$Query = '',
    [int]$Iterations = 100,
    [string]$ReportJson = '.tmp/query-cache-experiment.json'
)

# Phase 6b: repeated-query experiment only. This proves the bounded query cache works for a
# real client that asks the same question repeatedly. It is NOT evidence for the 10-concurrency
# unique-query p95 target: perf-concurrency.ps1 changes every query, so its hit rate is ~0.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
Add-Type -AssemblyName System.Net.Http

if ([string]::IsNullOrWhiteSpace($Query)) {
    $Query = 'query cache experiment ' + (Get-Date).ToString('o')
}

function Get-ActuatorMeasurement {
    param([string]$Name, [string]$Statistic)
    try {
        $metric = Invoke-RestMethod -Uri ("{0}/actuator/metrics/{1}" -f $BaseUrl, $Name) -TimeoutSec 5
        foreach ($measurement in @($metric.measurements)) {
            if ($measurement.statistic -eq $Statistic) { return [double]$measurement.value }
        }
    } catch {
        return $null
    }
    return $null
}

function Get-Snapshot {
    return [pscustomobject]@{
        requests = Get-ActuatorMeasurement 'rag.query.embedding.requests' 'COUNT'
        cacheHits = Get-ActuatorMeasurement 'rag.query.embedding.cache.hits' 'COUNT'
        cacheMisses = Get-ActuatorMeasurement 'rag.query.embedding.cache.misses' 'COUNT'
        batches = Get-ActuatorMeasurement 'rag.query.embedding.batches' 'COUNT'
    }
}

function Get-Percentile {
    param([double[]]$Values, [double]$Percentile)
    if ($Values.Count -eq 0) { return $null }
    $sorted = @($Values | Sort-Object)
    $rank = [Math]::Ceiling($Percentile * $sorted.Count) - 1
    if ($rank -lt 0) { $rank = 0 }
    if ($rank -ge $sorted.Count) { $rank = $sorted.Count - 1 }
    return [Math]::Round($sorted[$rank], 2)
}

$health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 10
if ($health.status -ne 'UP') { throw "app is not healthy: $($health.status)" }

$before = Get-Snapshot
if ($null -eq $before.requests) {
    throw "embedding metrics are not exposed; start the app with /actuator/metrics enabled"
}

$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromSeconds(60)
$asOf = Get-Date -Format 'yyyy-MM-dd'
$clientLatencies = New-Object System.Collections.Generic.List[double]
$serverLatencies = New-Object System.Collections.Generic.List[double]
$errors = 0

for ($i = 1; $i -le $Iterations; $i++) {
    $body = (@{ query = $Query; topK = 5; chunkStrategy = 'heading';
                strategy = 'vector'; asOf = $asOf } | ConvertTo-Json -Compress)
    $content = New-Object System.Net.Http.StringContent($body, [System.Text.Encoding]::UTF8, 'application/json')
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $response = $client.PostAsync("$BaseUrl/api/v1/search", $content).GetAwaiter().GetResult()
        $bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
        $sw.Stop()
        if (-not $response.IsSuccessStatusCode) {
            $errors++
            continue
        }
        $json = ([System.Text.Encoding]::UTF8.GetString($bytes)) | ConvertFrom-Json
        $clientLatencies.Add([Math]::Round($sw.Elapsed.TotalMilliseconds, 2))
        $serverLatencies.Add([double]$json.tookMs)
    } catch {
        $sw.Stop()
        $errors++
    }
}
$client.Dispose()
$after = Get-Snapshot

$lat = [double[]]$clientLatencies.ToArray()
$server = [double[]]$serverLatencies.ToArray()
$requests = [double]$after.requests - [double]$before.requests
$hits = [double]$after.cacheHits - [double]$before.cacheHits
$misses = [double]$after.cacheMisses - [double]$before.cacheMisses
$batches = [double]$after.batches - [double]$before.batches
$summary = [pscustomobject]@{
    timestamp = (Get-Date).ToString('o')
    gitCommit = (& git rev-parse HEAD 2>$null)
    baseUrl = $BaseUrl
    query = $Query
    iterations = $Iterations
    note = 'Repeated identical query; first call is a miss, the rest should hit the bounded query cache. Not evidence for the unique-query 10-concurrency benchmark.'
    requests = [int]$requests
    cacheHits = [int]$hits
    cacheMisses = [int]$misses
    cacheHitRate = if ($requests -gt 0) { [Math]::Round($hits / $requests, 6) } else { 0 }
    embeddingBatches = [int]$batches
    errors = $errors
    clientP50Ms = Get-Percentile $lat 0.50
    clientP95Ms = Get-Percentile $lat 0.95
    clientP99Ms = Get-Percentile $lat 0.99
    serverP50Ms = Get-Percentile $server 0.50
    serverP95Ms = Get-Percentile $server 0.95
    serverP99Ms = Get-Percentile $server 0.99
}

$directory = Split-Path -Parent $ReportJson
if ($directory -and -not (Test-Path $directory)) { New-Item -ItemType Directory -Force $directory | Out-Null }
$encoding = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText((Join-Path (Get-Location) $ReportJson),
    ($summary | ConvertTo-Json -Depth 6), $encoding)

Write-Host ("iterations={0} requests={1} cacheHits={2} cacheMisses={3} hitRate={4} embeddingBatches={5} errors={6}" -f
    $Iterations, $summary.requests, $summary.cacheHits, $summary.cacheMisses,
    $summary.cacheHitRate, $summary.embeddingBatches, $summary.errors)
Write-Host ("client p50={0} p95={1} p99={2}; server p50={3} p95={4} p99={5}" -f
    $summary.clientP50Ms, $summary.clientP95Ms, $summary.clientP99Ms,
    $summary.serverP50Ms, $summary.serverP95Ms, $summary.serverP99Ms)
Write-Host "report: $ReportJson"
