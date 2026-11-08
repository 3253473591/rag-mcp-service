
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$QaPath = 'eval/qa.jsonl',
    [string]$ReportPath = 'eval/report.md',
    [string]$ReportJson = 'eval/report.json',
    [string[]]$ChunkStrategies = @('fixed', 'heading'),
    [int]$TopK = 5,
    [string]$AsOf = (Get-Date -Format 'yyyy-MM-dd'),
    [switch]$IncludeSuperseded
)

# Windows PowerShell 5.1 compatible retrieval evaluation.
# Prerequisite: the app is running and the 3 docs/samples PDFs were ingested with
# chunkStrategies=fixed,heading. This script does not upload documents itself.
#
# Per-question overrides:
#   {"asOf":"2024-06-01"}                -> historical question against a superseded version
#   {"includeSuperseded":true}           -> widen the version window for that question
# Without an override the run-wide -AsOf / -IncludeSuperseded values are used.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if ($ChunkStrategies.Count -eq 1 -and $ChunkStrategies[0] -like '*,*') {
    $ChunkStrategies = $ChunkStrategies[0].Split(',') | ForEach-Object { $_.Trim() }
}

if (-not (Test-Path -LiteralPath $QaPath)) { throw "QA dataset not found: $QaPath" }

$health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 10
if ($health.status -ne 'UP') { throw "app is not healthy: $($health.status)" }

# Windows PowerShell 5.1's Invoke-WebRequest/Invoke-RestMethod are unreliable for
# UTF-8 + chunked JSON responses and occasionally add multi-second client-side stalls.
# A reusable HttpClient gives stable end-to-end timing and explicit UTF-8 decoding.
Add-Type -AssemblyName System.Net.Http
$httpClient = New-Object System.Net.Http.HttpClient
$httpClient.Timeout = [TimeSpan]::FromSeconds(60)

$qaRows = @(Get-Content -LiteralPath $QaPath -Encoding UTF8 | Where-Object { $_.Trim().Length -gt 0 } | ForEach-Object { $_ | ConvertFrom-Json })
if ($qaRows.Count -eq 0) { throw "QA dataset is empty: $QaPath" }
$answerableQuestions = @($qaRows | Where-Object { $_.answerable -eq $true })
$unanswerableQuestions = @($qaRows | Where-Object { $_.answerable -ne $true })
$datasetHash = (Get-FileHash -LiteralPath $QaPath -Algorithm SHA256).Hash.ToLower()
$gitCommit = (& git rev-parse HEAD 2>$null)
if (-not $gitCommit) { $gitCommit = 'unknown' }
$gitDirty = ((& git status --porcelain 2>$null | Out-String).Trim().Length -gt 0)

function Get-OptionalProperty {
    param($Object, [string]$Name)
    if ($null -eq $Object) { return $null }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
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

function Find-Rank {
    param($Results, $Gold)
    if ($null -eq $Gold -or -not $Gold.docKey) { return 0 }
    $goldDocKey = [string]$Gold.docKey
    $goldClause = ''
    if ($null -ne $Gold.clauseId) { $goldClause = ([string]$Gold.clauseId).Trim() }
    $range = @($Gold.pageRange)
    $hasRange = ($null -ne $Gold.pageRange -and $range.Count -ge 2)

    $rank = 0
    foreach ($result in @($Results)) {
        $rank++
        if ([string]$result.docKey -ne $goldDocKey) { continue }

        $resultClause = ''
        if ($null -ne $result.clauseId) { $resultClause = ([string]$result.clauseId).Trim() }

        if ($goldClause.Length -gt 0) {
            if ($resultClause.Length -gt 0) {
                if ($resultClause -eq $goldClause) { return $rank }
                # A different clause id is a real miss even when the page overlaps.
                continue
            }
            if ($hasRange -and $null -ne $result.pageNo) {
                $start = [int]$range[0]
                $end = [int]$range[1]
                $page = [int]$result.pageNo
                if ($page -ge $start -and $page -le $end) { return $rank }
            }
            continue
        }

        if ($hasRange) {
            if ($null -ne $result.pageNo) {
                $start = [int]$range[0]
                $end = [int]$range[1]
                $page = [int]$result.pageNo
                if ($page -ge $start -and $page -le $end) { return $rank }
            }
            continue
        }
        # docKey-only gold: first chunk of the right document counts.
        return $rank
    }
    return 0
}

function Invoke-SearchJson {
    param([string]$Body)
    $content = New-Object System.Net.Http.StringContent($Body, [System.Text.Encoding]::UTF8, 'application/json')
    $response = $httpClient.PostAsync("$BaseUrl/api/v1/search", $content).GetAwaiter().GetResult()
    $bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
    $text = [System.Text.Encoding]::UTF8.GetString($bytes)
    if (-not $response.IsSuccessStatusCode) {
        throw ("HTTP {0}: {1}" -f [int]$response.StatusCode, $text)
    }
    return ($text | ConvertFrom-Json)
}

function ConvertTo-MdCell {
    param($Value)
    if ($null -eq $Value) { return '' }
    return ([string]$Value).Replace('|', '\|').Replace("`r", ' ').Replace("`n", ' ')
}

function Write-Utf8NoBom {
    param([string]$Path, [string]$Text)
    $directory = Split-Path -Parent $Path
    if ($directory -and -not (Test-Path -LiteralPath $directory)) {
        New-Item -ItemType Directory -Force -Path $directory | Out-Null
    }
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText((Join-Path (Get-Location) $Path), $Text, $encoding)
}

# Warm up the Ollama model / JIT outside the measured window. Without this, the first
# request after a fresh app start can include multi-second model load and pollute p95.
for ($warm = 0; $warm -lt 2; $warm++) {
    $warmBody = (@{ query = $qaRows[0].question; topK = $TopK; chunkStrategy = 'heading';
                    strategy = 'hybrid'; asOf = $AsOf;
                    includeSuperseded = [bool]$IncludeSuperseded } | ConvertTo-Json -Compress)
    Invoke-SearchJson -Body $warmBody | Out-Null
}

$strategyReports = @()
$allDetails = @()

foreach ($strategy in $ChunkStrategies) {
    $latencies = New-Object System.Collections.Generic.List[double]
    $serverLatencies = New-Object System.Collections.Generic.List[double]
    $answered = 0
    $errors = 0
    $answerableErrors = 0
    $unanswerableErrors = 0
    $hit1 = 0
    $hit3 = 0
    $hit5 = 0
    $mrrSum = 0.0
    $unanswerableAnswered = 0
    $falsePositive = 0
    $details = @()

    foreach ($row in $qaRows) {
        $questionAsOf = Get-OptionalProperty $row 'asOf'
        $effectiveAsOf = $AsOf
        if ($null -ne $questionAsOf -and ([string]$questionAsOf).Trim().Length -gt 0) {
            $effectiveAsOf = ([string]$questionAsOf).Trim()
        }
        $questionInclude = Get-OptionalProperty $row 'includeSuperseded'
        $effectiveInclude = [bool]$IncludeSuperseded
        if ($null -ne $questionInclude) { $effectiveInclude = [bool]$questionInclude }

        $body = (@{ query = $row.question; topK = $TopK; chunkStrategy = $strategy;
                    strategy = 'hybrid'; asOf = $effectiveAsOf;
                    includeSuperseded = $effectiveInclude } | ConvertTo-Json -Compress)
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        try {
            $response = Invoke-SearchJson -Body $body
            $sw.Stop()
            $latencies.Add($sw.Elapsed.TotalMilliseconds)
            $results = @($response.results)
            $top = @()
            for ($i = 0; $i -lt $results.Count -and $i -lt 3; $i++) {
                $top += [pscustomobject]@{
                    docKey = $results[$i].docKey
                    docVersion = $results[$i].docVersion
                    clauseId = $results[$i].clauseId
                    pageNo = $results[$i].pageNo
                    citation = $results[$i].citation
                }
            }
            $topCitation = ''
            if ($results.Count -gt 0) { $topCitation = [string]$results[0].citation }

            $latencyMs = [Math]::Round($sw.Elapsed.TotalMilliseconds, 2)
            $serverMs = $null
            if ($null -ne $response.tookMs) {
                $serverMs = [double]$response.tookMs
                $serverLatencies.Add($serverMs)
            }
            if ($row.answerable -eq $true) {
                $answered++
                $rank = Find-Rank -Results $results -Gold $row.gold
                if ($rank -ge 1 -and $rank -le $TopK) { $hit5++ }
                if ($rank -ge 1 -and $rank -le 3) { $hit3++ }
                if ($rank -eq 1) { $hit1++ }
                if ($rank -ge 1) { $mrrSum += 1.0 / $rank }
                $details += [pscustomobject]@{ id = $row.id; answerable = $true;
                    rank = $rank; results = $results.Count; error = $false; errorMessage = '';
                    latencyMs = $latencyMs; serverMs = $serverMs; asOf = $effectiveAsOf;
                    includeSuperseded = $effectiveInclude; topCitation = $topCitation;
                    topResults = $top }
            } else {
                $unanswerableAnswered++
                if ($results.Count -gt 0) { $falsePositive++ }
                $details += [pscustomobject]@{ id = $row.id; answerable = $false;
                    rank = 0; results = $results.Count; error = $false; errorMessage = '';
                    latencyMs = $latencyMs; serverMs = $serverMs; asOf = $effectiveAsOf;
                    includeSuperseded = $effectiveInclude; topCitation = $topCitation;
                    topResults = $top }
            }
        } catch {
            $sw.Stop()
            $errors++
            if ($row.answerable -eq $true) { $answerableErrors++ } else { $unanswerableErrors++ }
            $details += [pscustomobject]@{ id = $row.id; answerable = [bool]($row.answerable -eq $true);
                rank = 0; results = 0; error = $true; errorMessage = $_.Exception.Message;
                latencyMs = [Math]::Round($sw.Elapsed.TotalMilliseconds, 2); serverMs = $null;
                asOf = $effectiveAsOf; includeSuperseded = $effectiveInclude;
                topCitation = ''; topResults = @() }
        }
    }

    $lat = [double[]]$latencies.ToArray()
    $serverLat = [double[]]$serverLatencies.ToArray()
    $hitAt1 = if ($answered -gt 0) { [Math]::Round($hit1 / [double]$answered, 4) } else { 0 }
    $hitAt3 = if ($answered -gt 0) { [Math]::Round($hit3 / [double]$answered, 4) } else { 0 }
    $hitAt5 = if ($answered -gt 0) { [Math]::Round($hit5 / [double]$answered, 4) } else { 0 }
    $mrr = if ($answered -gt 0) { [Math]::Round($mrrSum / [double]$answered, 4) } else { 0 }
    $falsePositiveRate = if ($unanswerableAnswered -gt 0) { [Math]::Round($falsePositive / [double]$unanswerableAnswered, 4) } else { 0 }
    $noAnswerAccuracy = if ($unanswerableAnswered -gt 0) { [Math]::Round(($unanswerableAnswered - $falsePositive) / [double]$unanswerableAnswered, 4) } else { 0 }

    $report = [pscustomobject]@{
        chunkStrategy = $strategy
        answered = $answered
        answerableErrors = $answerableErrors
        unanswerableAnswered = $unanswerableAnswered
        unanswerableErrors = $unanswerableErrors
        hit1 = $hit1
        hit3 = $hit3
        hit5 = $hit5
        hitAt1 = $hitAt1
        hitAt3 = $hitAt3
        hitAt5 = $hitAt5
        mrr = $mrr
        p50Ms = Get-Percentile $serverLat 0.50
        p95Ms = Get-Percentile $serverLat 0.95
        clientP50Ms = Get-Percentile $lat 0.50
        clientP95Ms = Get-Percentile $lat 0.95
        errors = $errors
        answeredCount = $answerableQuestions.Count
        unansweredCount = $unanswerableQuestions.Count
        falsePositiveRate = $falsePositiveRate
        noAnswerAccuracy = $noAnswerAccuracy
        hit5ThresholdMet = if ($answered -gt 0) { ($hit5 / [double]$answered) -ge 0.85 } else { $false }
        mrrThresholdMet = if ($answered -gt 0) { ($mrrSum / [double]$answered) -ge 0.65 } else { $false }
    }
    $strategyReports += $report
    foreach ($d in $details) {
        $allDetails += [pscustomobject]@{ chunkStrategy = $strategy; id = $d.id;
            answerable = $d.answerable; rank = $d.rank; results = $d.results;
            error = $d.error; errorMessage = $d.errorMessage; latencyMs = $d.latencyMs;
            serverMs = $d.serverMs; asOf = $d.asOf; includeSuperseded = $d.includeSuperseded;
            topCitation = $d.topCitation; topResults = $d.topResults }
    }
    Write-Host ("{0}: hit@1={1} hit@3={2} hit@5={3} MRR={4} p50={5}ms p95={6}ms clientP95={7}ms errors={8}" -f
        $strategy, $report.hitAt1, $report.hitAt3, $report.hitAt5, $report.mrr,
        $report.p50Ms, $report.p95Ms, $report.clientP95Ms, $report.errors)
}

$result = [pscustomobject]@{
    timestamp = (Get-Date).ToString('o')
    dataset = $QaPath
    datasetSha256 = $datasetHash
    gitCommit = $gitCommit
    gitDirty = $gitDirty
    embedding = 'bge-m3'
    embeddingDimension = 1024
    topK = $TopK
    asOf = $AsOf
    includeSuperseded = [bool]$IncludeSuperseded
    chunkStrategies = @($ChunkStrategies)
    qaCount = $qaRows.Count
    answerable = $answerableQuestions.Count
    unanswerable = $unanswerableQuestions.Count
    strategies = $strategyReports
    details = $allDetails
}
$resultJson = $result | ConvertTo-Json -Depth 10
Write-Utf8NoBom -Path $ReportJson -Text $resultJson

$lines = @(
    '# RAG Eval Report',
    '',
    ('- dataset: `{0}` (sha256={1})' -f $QaPath, $datasetHash),
    ('- git commit: `{0}` (dirty={1})' -f $gitCommit, $gitDirty),
    ('- embedding: bge-m3 (1024d); topK={0}; default asOf={1}; includeSuperseded={2}' -f
        $TopK, $AsOf, [bool]$IncludeSuperseded),
    ('- chunk strategies: {0}' -f ($ChunkStrategies -join ', ')),
    ('- QA: {0} ({1} answerable + {2} unanswerable)' -f
        $qaRows.Count, $answerableQuestions.Count, $unanswerableQuestions.Count),
    ('- generated: {0}' -f (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')),
    '',
    '## Answerable metrics',
    '',
    '| chunkStrategy | answered | hit@1 | hit@3 | hit@5 | MRR | p50 | p95 | errors |',
    '|---|---:|---:|---:|---:|---:|---:|---:|---:|'
)
foreach ($r in $strategyReports) {
    $lines += ('| {0} | {1} | {2} | {3} | {4} | {5} | {6} | {7} | {8} |' -f
        $r.chunkStrategy, $r.answered, $r.hitAt1, $r.hitAt3, $r.hitAt5, $r.mrr,
        $r.p50Ms, $r.p95Ms, $r.errors)
}
$lines += @(
    '',
    'Unanswerable (reported separately, not mixed into hit@k):',
    '',
    '| chunkStrategy | falsePositiveRate | noAnswerAccuracy | errors |',
    '|---|---:|---:|---:|'
)
foreach ($r in $strategyReports) {
    $lines += ('| {0} | {1} | {2} | {3} |' -f
        $r.chunkStrategy, $r.falsePositiveRate, $r.noAnswerAccuracy, $r.unanswerableErrors)
}
$lines += @(
    '',
    'Thresholds from docs/05-non-functional.md: hit@5 >= 0.85, MRR >= 0.65 (heading/default;',
    'fixed is reported as measured). Errors are counted separately and excluded from the',
    'hit@k/MRR denominator. Gold was not changed to tune these numbers.',
    '',
    'p50/p95 are the app-reported server-side retrieval time (`tookMs`, includes query',
    'embedding + SQL + RRF). Client wall-clock p50/p95 (including PowerShell/HTTP) is in',
    '`report.json` as `clientP50Ms`/`clientP95Ms`.',
    ''
)
$failed = @($allDetails | Where-Object { $_.error -eq $true })
if ($failed.Count -gt 0) {
    $lines += '## Failures'
    $lines += ''
    $lines += '| strategy | id | message |'
    $lines += '|---|---|---|'
    foreach ($f in $failed) {
        $lines += ('| {0} | {1} | {2} |' -f $f.chunkStrategy, $f.id, (ConvertTo-MdCell $f.errorMessage))
    }
    $lines += ''
}

$lines += @(
    '## Per-question detail',
    '',
    '| strategy | id | answerable | rank | results | server(ms) | client(ms) | error | asOf | top citation |',
    '|---|---|---|---:|---:|---:|---:|---|---|---|'
)
foreach ($d in $allDetails) {
    $lines += ('| {0} | {1} | {2} | {3} | {4} | {5} | {6} | {7} | {8} | {9} |' -f
        $d.chunkStrategy, $d.id, $d.answerable, $d.rank, $d.results, $d.serverMs,
        $d.latencyMs, $d.error, $d.asOf, (ConvertTo-MdCell $d.topCitation))
}
$lines += @(
    '',
    'Run command:',
    '',
    '```powershell',
    'powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\eval.ps1',
    '```'
)
Write-Utf8NoBom -Path $ReportPath -Text (($lines -join "`r`n") + "`r`n")
Write-Host "report: $ReportPath"
Write-Host "json:   $ReportJson"

$httpClient.Dispose()
$totalErrors = ($strategyReports | Measure-Object -Property errors -Sum).Sum
if ($totalErrors -gt 0) { exit 1 }
exit 0
