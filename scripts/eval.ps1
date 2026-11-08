[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$QaPath = 'eval/qa.smoke.jsonl',
    [string]$ReportPath = 'eval/report.md',
    [string]$ReportJson = 'eval/report.json',
    [string[]]$ChunkStrategies = @('fixed', 'heading'),
    [int]$TopK = 5,
    [string]$AsOf = (Get-Date -Format 'yyyy-MM-dd')
)

# Windows PowerShell 5.1 compatible retrieval evaluation.
# Prerequisite: the app is running and the 3 docs/samples PDFs were ingested with
# chunkStrategies=fixed,heading. This script does not upload documents itself.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if ($ChunkStrategies.Count -eq 1 -and $ChunkStrategies[0] -like '*,*') {
    $ChunkStrategies = $ChunkStrategies[0].Split(',') | ForEach-Object { $_.Trim() }
}

if (-not (Test-Path -LiteralPath $QaPath)) { throw "QA dataset not found: $QaPath" }

$health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 10
if ($health.status -ne 'UP') { throw "app is not healthy: $($health.status)" }

$qaRows = @(Get-Content -LiteralPath $QaPath -Encoding UTF8 | Where-Object { $_.Trim().Length -gt 0 } | ForEach-Object { $_ | ConvertFrom-Json })
if ($qaRows.Count -eq 0) { throw "QA dataset is empty: $QaPath" }

$answerable = @($qaRows | Where-Object { $_.answerable -eq $true })
$unanswerable = @($qaRows | Where-Object { $_.answerable -ne $true })
$datasetHash = (Get-FileHash -LiteralPath $QaPath -Algorithm SHA256).Hash.ToLower()
$gitCommit = (& git rev-parse HEAD 2>$null)
if (-not $gitCommit) { $gitCommit = 'unknown' }

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
    $rank = 0
    foreach ($result in @($Results)) {
        $rank++
        if ($result.docKey -ne $Gold.docKey) { continue }
        $clause = $Gold.clauseId
        $resultClause = [string]$result.clauseId
        if ($null -ne $clause -and ([string]$clause).Length -gt 0 -and $resultClause.Length -gt 0) {
            if ($resultClause -eq [string]$clause) { return $rank }
            continue
        }
        # Clause-first matching. Fixed-window chunks do not carry clause_id (Phase 1 design),
        # so when the result has no clause id we fall back to gold.pageRange. This keeps the
        # fixed/heading comparison honest instead of scoring fixed as an automatic miss.
        $range = @($Gold.pageRange)
        if ($range.Count -ge 2 -and $null -ne $result.pageNo) {
            if ([int]$result.pageNo -ge [int]$range[0] -and [int]$result.pageNo -le [int]$range[1]) {
                return $rank
            }
        }
    }
    return 0
}

$strategyReports = @()
$allDetails = @()

# Warm up the Ollama model / JIT outside the measured window. Without this, the first
# request after a fresh app start can include multi-second model load and pollute p95.
for ($warm = 0; $warm -lt 2; $warm++) {
    $warmBody = (@{ query = $qaRows[0].question; topK = $TopK; chunkStrategy = 'heading';
                    strategy = 'hybrid'; asOf = $AsOf } | ConvertTo-Json -Compress)
    Invoke-RestMethod -Uri "$BaseUrl/api/v1/search" -Method Post `
        -ContentType 'application/json; charset=utf-8' `
        -Body ([Text.Encoding]::UTF8.GetBytes($warmBody)) -TimeoutSec 60 | Out-Null
}

foreach ($strategy in $ChunkStrategies) {
    $latencies = New-Object System.Collections.Generic.List[double]
    $errors = 0
    $hit1 = 0; $hit3 = 0; $hit5 = 0; $mrr = 0.0
    $falsePositive = 0
    $details = @()

    foreach ($row in $qaRows) {
        $body = (@{ query = $row.question; topK = $TopK; chunkStrategy = $strategy;
                    strategy = 'hybrid'; asOf = $AsOf } | ConvertTo-Json -Compress)
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        try {
            $response = Invoke-RestMethod -Uri "$BaseUrl/api/v1/search" -Method Post `
                -ContentType 'application/json; charset=utf-8' `
                -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 60
            $sw.Stop()
            $latencies.Add($sw.Elapsed.TotalMilliseconds)
            $results = @($response.results)
            if ($row.answerable -eq $true) {
                $rank = Find-Rank -Results $results -Gold $row.gold
                if ($rank -ge 1 -and $rank -le $TopK) { $hit5++ }
                if ($rank -ge 1 -and $rank -le 3) { $hit3++ }
                if ($rank -eq 1) { $hit1++ }
                if ($rank -ge 1) { $mrr += 1.0 / $rank }
                $details += [pscustomobject]@{ id = $row.id; answerable = $true;
                    rank = $rank; results = $results.Count; error = $false }
            } else {
                if ($results.Count -gt 0) { $falsePositive++ }
                $details += [pscustomobject]@{ id = $row.id; answerable = $false;
                    rank = 0; results = $results.Count; error = $false }
            }
        } catch {
            $sw.Stop()
            $errors++
            $details += [pscustomobject]@{ id = $row.id; answerable = [bool]$row.answerable;
                rank = 0; results = 0; error = $true; message = $_.Exception.Message }
        }
    }

    $lat = [double[]]$latencies.ToArray()
    $answered = $answerable.Count
    $report = [pscustomobject]@{
        chunkStrategy = $strategy
        hitAt1 = if ($answered) { [Math]::Round($hit1 / [double]$answered, 4) } else { 0 }
        hitAt3 = if ($answered) { [Math]::Round($hit3 / [double]$answered, 4) } else { 0 }
        hitAt5 = if ($answered) { [Math]::Round($hit5 / [double]$answered, 4) } else { 0 }
        mrr = if ($answered) { [Math]::Round($mrr / [double]$answered, 4) } else { 0 }
        p50Ms = Get-Percentile $lat 0.50
        p95Ms = Get-Percentile $lat 0.95
        errors = $errors
        answered = $answered
        unanswered = $unanswerable.Count
        falsePositiveRate = if ($unanswerable.Count) { [Math]::Round($falsePositive / [double]$unanswerable.Count, 4) } else { 0 }
        noAnswerAccuracy = if ($unanswerable.Count) { [Math]::Round(($unanswerable.Count - $falsePositive) / [double]$unanswerable.Count, 4) } else { 0 }
        hit5ThresholdMet = if ($answered) { ($hit5 / [double]$answered) -ge 0.85 } else { $false }
        mrrThresholdMet = if ($answered) { ($mrr / [double]$answered) -ge 0.65 } else { $false }
    }
    $strategyReports += $report
    foreach ($d in $details) {
        $allDetails += [pscustomobject]@{ chunkStrategy = $strategy; id = $d.id;
            answerable = $d.answerable; rank = $d.rank; results = $d.results;
            error = $d.error }
    }
    Write-Host ("{0}: hit@1={1} hit@3={2} hit@5={3} MRR={4} p50={5}ms p95={6}ms errors={7}" -f
        $strategy, $report.hitAt1, $report.hitAt3, $report.hitAt5, $report.mrr,
        $report.p50Ms, $report.p95Ms, $report.errors)
}

$result = [pscustomobject]@{
    timestamp = (Get-Date).ToString('o')
    dataset = $QaPath
    datasetSha256 = $datasetHash
    gitCommit = $gitCommit
    embedding = 'bge-m3'
    topK = $TopK
    asOf = $AsOf
    qaCount = $qaRows.Count
    answerable = $answerable.Count
    unanswerable = $unanswerable.Count
    strategies = $strategyReports
    details = $allDetails
}
$jsonDir = Split-Path -Parent $ReportJson
if ($jsonDir -and -not (Test-Path -LiteralPath $jsonDir)) {
    New-Item -ItemType Directory -Force -Path $jsonDir | Out-Null
}
$result | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $ReportJson -Encoding UTF8

$lines = @(
    '# RAG Eval Report (Phase 2 smoke)',
    '',
    ('- dataset: `{0}` (sha256={1})' -f $QaPath, $datasetHash),
    ('- git commit: `{0}`' -f $gitCommit),
    ('- embedding: bge-m3; topK={0}; asOf={1}; QA={2} ({3} answerable + {4} unanswerable)' -f
        $TopK, $AsOf, $qaRows.Count, $answerable.Count, $unanswerable.Count),
    '- NOTE: this is a smoke set for Phase 2. The full 40-question Phase 3 dataset is not fabricated here.',
    '',
    '| chunkStrategy | hit@1 | hit@3 | hit@5 | MRR | p50 | p95 | errors |',
    '|---|---:|---:|---:|---:|---:|---:|---:|'
)
foreach ($r in $strategyReports) {
    $lines += ('| {0} | {1} | {2} | {3} | {4} | {5} | {6} | {7} |' -f
        $r.chunkStrategy, $r.hitAt1, $r.hitAt3, $r.hitAt5, $r.mrr, $r.p50Ms, $r.p95Ms, $r.errors)
}
$lines += @(
    '',
    'Unanswerable (reported separately, not mixed into hit@k):',
    '',
    '| chunkStrategy | falsePositiveRate | noAnswerAccuracy |',
    '|---|---:|---:|'
)
foreach ($r in $strategyReports) {
    $lines += ('| {0} | {1} | {2} |' -f $r.chunkStrategy, $r.falsePositiveRate, $r.noAnswerAccuracy)
}
$lines += @(
    '',
    'Thresholds from docs/05-non-functional.md: hit@5 >= 0.85, MRR >= 0.65. The smoke set is small;',
    'treat threshold flags as informational and do not tune them by changing gold labels.',
    '',
    'Per-question detail:',
    '',
    '| strategy | id | answerable | rank | results | error |',
    '|---|---|---|---:|---:|---|'
)
foreach ($d in $allDetails) {
    $lines += ('| {0} | {1} | {2} | {3} | {4} | {5} |' -f
        $d.chunkStrategy, $d.id, $d.answerable, $d.rank, $d.results, $d.error)
}
$mdDir = Split-Path -Parent $ReportPath
if ($mdDir -and -not (Test-Path -LiteralPath $mdDir)) {
    New-Item -ItemType Directory -Force -Path $mdDir | Out-Null
}
$lines | Set-Content -LiteralPath $ReportPath -Encoding UTF8
Write-Host "report: $ReportPath"
Write-Host "json:   $ReportJson"

$totalErrors = ($strategyReports | Measure-Object -Property errors -Sum).Sum
if ($totalErrors -gt 0) { exit 1 }
exit 0
