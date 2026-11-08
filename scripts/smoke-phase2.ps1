[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$QaPath = 'eval/qa.smoke.jsonl',
    [string]$ReportJson = 'docs/acceptance/phase2-smoke-results.json',
    [string]$ReportMd = 'docs/acceptance/phase2-smoke.md',
    [string]$AsOf = (Get-Date -Format 'yyyy-MM-dd')
)

# Real-app smoke evidence. Prerequisite: the 3 docs/samples PDFs were ingested with
# chunkStrategies=fixed,heading and Ollama bge-m3 is up.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Invoke-Search {
    param([hashtable]$Payload)
    $json = $Payload | ConvertTo-Json -Compress
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds(60)
    try {
        $content = [System.Net.Http.StringContent]::new(
            $json, [System.Text.Encoding]::UTF8, 'application/json')
        $response = $client.PostAsync("$BaseUrl/api/v1/search", $content).GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) {
            throw "search failed HTTP $([int]$response.StatusCode)"
        }
        $bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
        $text = [System.Text.Encoding]::UTF8.GetString($bytes)
        return ($text | ConvertFrom-Json)
    } finally {
        $client.Dispose()
    }
}

$qa = @{}
Get-Content -LiteralPath $QaPath -Encoding UTF8 | Where-Object { $_.Trim() } | ForEach-Object {
    $row = $_ | ConvertFrom-Json
    $qa[$row.id] = $row
}

$cases = @(
    @{ name = 'S01_heading_hybrid_current'; id = 'S01'; asOf = $AsOf; chunkStrategy = 'heading'; strategy = 'hybrid'; includeSuperseded = $false },
    @{ name = 'S02_heading_hybrid_current'; id = 'S02'; asOf = $AsOf; chunkStrategy = 'heading'; strategy = 'hybrid'; includeSuperseded = $false },
    @{ name = 'S03_heading_hybrid_current'; id = 'S03'; asOf = $AsOf; chunkStrategy = 'heading'; strategy = 'hybrid'; includeSuperseded = $false },
    @{ name = 'S04_heading_hybrid_current'; id = 'S04'; asOf = $AsOf; chunkStrategy = 'heading'; strategy = 'hybrid'; includeSuperseded = $false },
    @{ name = 'S05_heading_hybrid_current'; id = 'S05'; asOf = $AsOf; chunkStrategy = 'heading'; strategy = 'hybrid'; includeSuperseded = $false },
    @{ name = 'S01_asOf_2024_06_01'; id = 'S01'; asOf = '2024-06-01'; chunkStrategy = 'heading'; strategy = 'hybrid'; includeSuperseded = $false },
    @{ name = 'S01_includeSuperseded'; id = 'S01'; asOf = $AsOf; chunkStrategy = 'heading'; strategy = 'hybrid'; includeSuperseded = $true },
    @{ name = 'S01_fixed_hybrid'; id = 'S01'; asOf = $AsOf; chunkStrategy = 'fixed'; strategy = 'hybrid'; includeSuperseded = $false },
    @{ name = 'S03_keyword'; id = 'S03'; asOf = $AsOf; chunkStrategy = 'heading'; strategy = 'keyword'; includeSuperseded = $false }
)

$evidence = @()
$md = New-Object System.Collections.Generic.List[string]
$md.Add('# Phase 2 real-app smoke')
$md.Add('')
$md.Add(('- generated: {0}' -f (Get-Date).ToString('o')))
$md.Add(('- baseUrl: {0}; asOf: {1}; QA: {2}' -f $BaseUrl, $AsOf, $QaPath))
$md.Add('- corpus: docs/samples/hypertension-2024.pdf (v2024), hypertension-2025.pdf (v2025, supersedes), enterprise-policy-2026.pdf (v2026); chunkStrategies=fixed,heading')
$md.Add('- embedding: Ollama bge-m3 (1024 dim)')
$md.Add('')
$md.Add('| case | query | tookMs | statuses | results |')
$md.Add('|---|---|---:|---|---:|')

foreach ($case in $cases) {
    $question = $qa[$case.id].question
    $payload = @{
        query = $question
        topK = 5
        asOf = $case.asOf
        chunkStrategy = $case.chunkStrategy
        strategy = $case.strategy
        includeSuperseded = $case.includeSuperseded
    }
    $response = Invoke-Search -Payload $payload
    $statuses = @($response.results | ForEach-Object { $_.docStatus } | Select-Object -Unique) -join ','
    $evidence += [pscustomobject]@{
        case = $case.name
        question = $question
        request = [pscustomobject]@{
            asOf = $case.asOf; chunkStrategy = $case.chunkStrategy;
            strategy = $case.strategy; includeSuperseded = $case.includeSuperseded; topK = 5
        }
        tookMs = $response.tookMs
        resultCount = @($response.results).Count
        statuses = @($response.results | ForEach-Object { $_.docStatus } | Select-Object -Unique)
        results = @($response.results)
    }
    $md.Add(('| {0} | {1} | {2} | {3} | {4} |' -f
        $case.name, $question, $response.tookMs, $statuses, @($response.results).Count))
    foreach ($r in @($response.results)) {
        $md.Add(('|  |  |  | {0} v{1} {2} score={3:N4} | {4} |' -f
            $r.docKey, $r.docVersion, $r.docStatus, $r.score, $r.citation))
    }
}

$checks = [pscustomobject]@{
    defaultOnlyActive = (@($evidence | Where-Object { $_.case -like '*_current' } |
        ForEach-Object { $_.statuses } | Where-Object { $_ -ne 'ACTIVE' }).Count -eq 0)
    historicalReturnsSuperseded = (@($evidence | Where-Object { $_.case -eq 'S01_asOf_2024_06_01' } |
        Select-Object -ExpandProperty statuses) -contains 'SUPERSEDED')
    includeSupersededReturnsBoth = (@($evidence | Where-Object { $_.case -eq 'S01_includeSuperseded' } |
        Select-Object -ExpandProperty statuses).Count -eq 2)
    noEmptyCurrentResults = (@($evidence | Where-Object { $_.case -like '*_current' } |
        Where-Object { $_.resultCount -eq 0 }).Count -eq 0)
}
$md.Add('')
$md.Add('Checks:')
$md.Add('')
$md.Add('```json')
$md.Add(($checks | ConvertTo-Json -Compress))
$md.Add('```')

$jsonDir = Split-Path -Parent $ReportJson
if ($jsonDir -and -not (Test-Path -LiteralPath $jsonDir)) { New-Item -ItemType Directory -Force -Path $jsonDir | Out-Null }
$output = [pscustomobject]@{
    generatedAt = (Get-Date).ToString('o')
    baseUrl = $BaseUrl
    corpus = @('hypertension-2024.pdf', 'hypertension-2025.pdf', 'enterprise-policy-2026.pdf')
    cases = $evidence
    checks = $checks
}
$output | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $ReportJson -Encoding UTF8
$md | Set-Content -LiteralPath $ReportMd -Encoding UTF8
Write-Host "smoke evidence: $ReportJson"
Write-Host "smoke report:   $ReportMd"
Write-Host ($checks | ConvertTo-Json -Compress)
