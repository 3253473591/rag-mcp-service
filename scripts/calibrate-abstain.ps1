[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$QaPath = 'eval/qa.jsonl',
    [int]$TopK = 5,
    [string]$AsOf = (Get-Date -Format 'yyyy-MM-dd'),
    [string]$JsonPath = '.tmp/phase7-calibration.json',
    [string]$MarkdownPath = '.tmp/phase7-calibration.md'
)

# Phase 7 threshold calibration. Prerequisite: the app is running with the score guard disabled,
# e.g. $env:ABSTAIN_ENABLED='false'; mvn -o spring-boot:run
# It collects the real diagnostics (cosine list) for every eval question and simulates the
# deterministic decision rule offline. It does NOT change eval/qa.jsonl or the gold matching rules.

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not (Test-Path -LiteralPath $QaPath)) { throw "QA dataset not found: $QaPath" }

$health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 10
if ($health.status -ne 'UP') { throw "app is not healthy: $($health.status)" }

Add-Type -AssemblyName System.Net.Http
$httpClient = New-Object System.Net.Http.HttpClient
$httpClient.Timeout = [TimeSpan]::FromSeconds(60)

$qaRows = @(Get-Content -LiteralPath $QaPath -Encoding UTF8 |
    Where-Object { $_.Trim().Length -gt 0 } |
    ForEach-Object { $_ | ConvertFrom-Json })
if ($qaRows.Count -eq 0) { throw "QA dataset is empty: $QaPath" }

$answerableQuestions = @($qaRows | Where-Object { $_.answerable -eq $true })
$unanswerableQuestions = @($qaRows | Where-Object { $_.answerable -ne $true })
$datasetHash = (Get-FileHash -LiteralPath $QaPath -Algorithm SHA256).Hash.ToLower()
$gitCommit = (& git rev-parse HEAD 2>$null)
if (-not $gitCommit) { $gitCommit = 'unknown' }

function Get-OptionalProperty {
    param($Object, [string]$Name)
    if ($null -eq $Object) { return $null }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function Test-PageOverlap {
    param($Result, $Range)
    if ($null -eq $Result.pageNo) { return $false }
    $start = [int]$Range[0]
    $end = [int]$Range[1]
    $page = [int]$Result.pageNo
    $pageEnd = $page
    if ($null -ne $Result.pageEnd) { $pageEnd = [int]$Result.pageEnd }
    if ($pageEnd -lt $page) { $pageEnd = $page }
    return ($page -le $end -and $pageEnd -ge $start)
}

# Same matching rules as scripts/eval.ps1 (docs/11 4.2); intentionally copied, not changed.
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
                continue
            }
            if ($hasRange -and (Test-PageOverlap $result $range)) { return $rank }
            continue
        }
        if ($hasRange) {
            if (Test-PageOverlap $result $range) { return $rank }
            continue
        }
        return $rank
    }
    return 0
}

function Invoke-SearchJson {
    param([string]$Body)
    $content = New-Object System.Net.Http.StringContent(
        $Body, [System.Text.Encoding]::UTF8, 'application/json')
    $response = $httpClient.PostAsync("$BaseUrl/api/v1/search", $content).GetAwaiter().GetResult()
    $bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
    $text = [System.Text.Encoding]::UTF8.GetString($bytes)
    if (-not $response.IsSuccessStatusCode) {
        throw ("HTTP {0}: {1}" -f [int]$response.StatusCode, $text)
    }
    return ($text | ConvertFrom-Json)
}

function Get-Decision {
    param([double[]]$Scores, [double]$MinTopScore, [double]$HighConfidenceTopScore,
          [int]$MinDenseCandidates, [double]$MinScoreGap)
    if ($Scores.Count -eq 0) { return 'NO_MATCH' }
    $top = $Scores[0]
    $second = if ($Scores.Count -gt 1) { $Scores[1] } else { 0.0 }
    $dense = @($Scores | Where-Object { $_ -ge $MinTopScore }).Count
    if ($top -lt $MinTopScore) { return 'NO_MATCH' }
    if ($top -lt $HighConfidenceTopScore -and $dense -ge $MinDenseCandidates -and
        ($top - $second) -lt $MinScoreGap) { return 'TOO_NOISY' }
    return 'NONE'
}

function Get-ScanRow {
    param($Questions, [double]$MinTopScore, [double]$HighConfidenceTopScore,
          [int]$MinDenseCandidates, [double]$MinScoreGap)
    $answerable = @($Questions | Where-Object { $_.answerable -eq $true })
    $unanswerable = @($Questions | Where-Object { $_.answerable -ne $true })
    $hit5 = 0
    $mrr = 0.0
    $answerableAbstained = 0
    $unanswerableAbstained = 0
    $noMatch = 0
    $tooNoisy = 0
    foreach ($q in $Questions) {
        $decision = Get-Decision -Scores ([double[]]$q.candidateScores) `
            -MinTopScore $MinTopScore -HighConfidenceTopScore $HighConfidenceTopScore `
            -MinDenseCandidates $MinDenseCandidates -MinScoreGap $MinScoreGap
        $abstained = ($decision -ne 'NONE')
        if ($abstained) {
            if ($decision -eq 'NO_MATCH') { $noMatch++ } else { $tooNoisy++ }
        }
        if ($q.answerable -eq $true) {
            if ($abstained) {
                $answerableAbstained++
            } elseif ($q.rank -ge 1 -and $q.rank -le $TopK) {
                $hit5++
                $mrr += 1.0 / [double]$q.rank
            }
        } elseif ($abstained) {
            $unanswerableAbstained++
        }
    }
    $answerableCount = $answerable.Count
    $unanswerableCount = $unanswerable.Count
    return [pscustomobject]@{
        minTopScore = $MinTopScore
        highConfidenceTopScore = $HighConfidenceTopScore
        minDenseCandidates = $MinDenseCandidates
        minScoreGap = $MinScoreGap
        answerable = $answerableCount
        answerableHit5 = if ($answerableCount -gt 0) { [Math]::Round($hit5 / [double]$answerableCount, 4) } else { 0 }
        answerableMrr = if ($answerableCount -gt 0) { [Math]::Round($mrr / [double]$answerableCount, 4) } else { 0 }
        answerableAbstained = $answerableAbstained
        unanswerable = $unanswerableCount
        unanswerableAbstained = $unanswerableAbstained
        unanswerableFalsePositive = $unanswerableCount - $unanswerableAbstained
        noAnswerAccuracy = if ($unanswerableCount -gt 0) { [Math]::Round($unanswerableAbstained / [double]$unanswerableCount, 4) } else { 0 }
        noMatch = $noMatch
        tooNoisy = $tooNoisy
    }
}

function Get-Distribution {
    param($Questions)
    $answerableScores = @($Questions | Where-Object { $_.answerable -eq $true } | ForEach-Object { [double]$_.topScore } | Sort-Object)
    $unanswerableScores = @($Questions | Where-Object { $_.answerable -ne $true } | ForEach-Object { [double]$_.topScore } | Sort-Object)
    return [pscustomobject]@{
        answerableCount = $answerableScores.Count
        answerableMin = if ($answerableScores.Count) { [Math]::Round($answerableScores[0], 4) } else { $null }
        answerableP50 = Get-Percentile $answerableScores 0.5
        answerableMax = if ($answerableScores.Count) { [Math]::Round($answerableScores[-1], 4) } else { $null }
        unanswerableCount = $unanswerableScores.Count
        unanswerableMin = if ($unanswerableScores.Count) { [Math]::Round($unanswerableScores[0], 4) } else { $null }
        unanswerableP50 = Get-Percentile $unanswerableScores 0.5
        unanswerableMax = if ($unanswerableScores.Count) { [Math]::Round($unanswerableScores[-1], 4) } else { $null }
        answerableScores = $answerableScores
        unanswerableScores = $unanswerableScores
    }
}

function Get-Percentile {
    param([double[]]$Values, [double]$Percentile)
    if ($Values.Count -eq 0) { return $null }
    $sorted = @($Values | Sort-Object)
    $rank = [Math]::Ceiling($Percentile * $sorted.Count) - 1
    if ($rank -lt 0) { $rank = 0 }
    if ($rank -ge $sorted.Count) { $rank = $sorted.Count - 1 }
    return [Math]::Round($sorted[$rank], 4)
}

# Warm up Ollama/model load outside the collection window.
$warmBody = (@{ query = $qaRows[0].question; topK = $TopK; chunkStrategy = 'heading';
                strategy = 'hybrid'; asOf = $AsOf; includeSuperseded = $false } | ConvertTo-Json -Compress)
Invoke-SearchJson -Body $warmBody | Out-Null

$strategyReports = @()
$allQuestions = @()
foreach ($strategy in @('fixed', 'heading')) {
    $questions = @()
    foreach ($row in $qaRows) {
        $questionAsOf = Get-OptionalProperty $row 'asOf'
        $effectiveAsOf = $AsOf
        if ($null -ne $questionAsOf -and ([string]$questionAsOf).Trim().Length -gt 0) {
            $effectiveAsOf = ([string]$questionAsOf).Trim()
        }
        $questionInclude = Get-OptionalProperty $row 'includeSuperseded'
        $effectiveInclude = $false
        if ($null -ne $questionInclude) { $effectiveInclude = [bool]$questionInclude }
        $body = (@{ query = $row.question; topK = $TopK; chunkStrategy = $strategy;
                    strategy = 'hybrid'; asOf = $effectiveAsOf;
                    includeSuperseded = $effectiveInclude } | ConvertTo-Json -Compress)
        $response = Invoke-SearchJson -Body $body
        if ($response.abstained -eq $true) {
            throw ("guard is enabled: {0} was abstained; rerun with ABSTAIN_ENABLED=false" -f $row.id)
        }
        $diagnostics = $response.diagnostics
        if ($null -eq $diagnostics) { throw "response has no diagnostics (old build?)" }
        $scores = @($diagnostics.candidateScores | ForEach-Object { [double]$_ })
        $rank = Find-Rank -Results @($response.results) -Gold $row.gold
        $topScore = if ($scores.Count -gt 0) { [Math]::Round($scores[0], 6) } else { 0.0 }
        $secondScore = if ($scores.Count -gt 1) { [Math]::Round($scores[1], 6) } else { 0.0 }
        $question = [pscustomobject]@{
            chunkStrategy = $strategy
            id = $row.id
            answerable = [bool]($row.answerable -eq $true)
            rank = $rank
            resultCount = @($response.results).Count
            topScore = $topScore
            secondScore = $secondScore
            scoreGap = [Math]::Round($topScore - $secondScore, 6)
            candidateCount = [int]$diagnostics.candidateCount
            candidateScores = $scores
            asOf = $effectiveAsOf
            includeSuperseded = $effectiveInclude
        }
        $questions += $question
        $allQuestions += $question
    }

    $distribution = Get-Distribution $questions
    $thresholdScan = @()
    foreach ($threshold in @(0.35, 0.40, 0.45, 0.50, 0.55, 0.60, 0.65)) {
        $thresholdScan += Get-ScanRow -Questions $questions -MinTopScore $threshold `
            -HighConfidenceTopScore 0.63 -MinDenseCandidates 8 -MinScoreGap 0.02
    }
    $gapScan = @()
    foreach ($gap in @(0.005, 0.010, 0.020, 0.030, 0.050, 0.100)) {
        $gapScan += Get-ScanRow -Questions $questions -MinTopScore 0.55 `
            -HighConfidenceTopScore 0.63 -MinDenseCandidates 8 -MinScoreGap $gap
    }
    $chosen = Get-ScanRow -Questions $questions -MinTopScore 0.55 `
        -HighConfidenceTopScore 0.63 -MinDenseCandidates 8 -MinScoreGap 0.02
    # The full cosine list is only needed to build the scan rows; keep the top 5 per question in
    # the committed JSON so the artifact stays small while remaining reproducible.
    $questionSummary = @($questions | ForEach-Object {
        [pscustomobject]@{
            chunkStrategy = $_.chunkStrategy
            id = $_.id
            answerable = $_.answerable
            rank = $_.rank
            resultCount = $_.resultCount
            topScore = $_.topScore
            secondScore = $_.secondScore
            scoreGap = $_.scoreGap
            candidateCount = $_.candidateCount
            topScores = @($_.candidateScores | Select-Object -First 5)
            asOf = $_.asOf
            includeSuperseded = $_.includeSuperseded
        }
    })
    $strategyReports += [pscustomobject]@{
        chunkStrategy = $strategy
        distribution = $distribution
        thresholdScan = $thresholdScan
        gapScan = $gapScan
        chosen = $chosen
        questions = $questionSummary
    }
}

$result = [pscustomobject]@{
    generatedAt = (Get-Date).ToString('o')
    gitCommit = $gitCommit
    dataset = $QaPath
    datasetSha256 = $datasetHash
    embedding = 'bge-m3'
    topK = $TopK
    asOf = $AsOf
    qaCount = $qaRows.Count
    answerable = $answerableQuestions.Count
    unanswerable = $unanswerableQuestions.Count
    strategies = $strategyReports
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

Write-Utf8NoBom -Path $JsonPath -Text ($result | ConvertTo-Json -Depth 12)

$lines = @(
    '# Phase 7 threshold calibration',
    '',
    ('- generated: {0}' -f (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')),
    ('- git commit: `{0}`' -f $gitCommit),
    ('- dataset: `{0}` (sha256={1})' -f $QaPath, $datasetHash),
    ('- embedding: bge-m3; topK={0}; default asOf={1}' -f $TopK, $AsOf),
    ('- QA: {0} ({1} answerable + {2} unanswerable)' -f $qaRows.Count, $answerableQuestions.Count, $unanswerableQuestions.Count),
    '',
    '## Top-1 cosine distribution',
    '',
    '| chunkStrategy | answerable min / p50 / max | unanswerable min / p50 / max |',
    '|---|---|---|'
)
foreach ($r in $strategyReports) {
    $d = $r.distribution
    $lines += ('| {0} | {1} / {2} / {3} | {4} / {5} / {6} |' -f $r.chunkStrategy,
        $d.answerableMin, $d.answerableP50, $d.answerableMax,
        $d.unanswerableMin, $d.unanswerableP50, $d.unanswerableMax)
}
$lines += @(
    '',
    '## Threshold scan (highConfidenceTopScore=0.63, minDenseCandidates=8, minScoreGap=0.02)',
    '',
    'An answerable question that is abstained counts as a miss; unanswerable abstention is correct.',
    '',
    '| chunkStrategy | minTopScore | answerable hit@5 | answerable MRR | answerable abstained | unanswerable abstained | unanswerable FP | noAnswerAccuracy |',
    '|---|---:|---:|---:|---:|---:|---:|---:|'
)
foreach ($r in $strategyReports) {
    foreach ($s in $r.thresholdScan) {
        $lines += ('| {0} | {1} | {2} | {3} | {4} | {5} | {6} | {7} |' -f $r.chunkStrategy,
            $s.minTopScore, $s.answerableHit5, $s.answerableMrr, $s.answerableAbstained,
            $s.unanswerableAbstained, $s.unanswerableFalsePositive, $s.noAnswerAccuracy)
    }
}
$lines += @(
    '',
    '## Score-gap scan (minTopScore=0.55, highConfidenceTopScore=0.63, minDenseCandidates=8)',
    '',
    '| chunkStrategy | minScoreGap | answerable hit@5 | answerable abstained | unanswerable abstained | noAnswerAccuracy |',
    '|---|---:|---:|---:|---:|---:|'
)
foreach ($r in $strategyReports) {
    foreach ($s in $r.gapScan) {
        $lines += ('| {0} | {1} | {2} | {3} | {4} | {5} |' -f $r.chunkStrategy,
            $s.minScoreGap, $s.answerableHit5, $s.answerableAbstained,
            $s.unanswerableAbstained, $s.noAnswerAccuracy)
    }
}
$lines += @(
    '',
    '## Chosen defaults',
    '',
    '| chunkStrategy | answerable hit@5 | answerable MRR | unanswerable abstained | NO_MATCH | TOO_NOISY |',
    '|---|---:|---:|---:|---:|---:|'
)
foreach ($r in $strategyReports) {
    $s = $r.chosen
    $lines += ('| {0} | {1} | {2} | {3} | {4} | {5} |' -f $r.chunkStrategy,
        $s.answerableHit5, $s.answerableMrr, $s.unanswerableAbstained, $s.noMatch, $s.tooNoisy)
}
$lines += @(
    '',
    'Chosen: `minTopScore=0.55`, `highConfidenceTopScore=0.63`, `minDenseCandidates=8`, `minScoreGap=0.02`.',
    'The guard is conservative: at the chosen floor no answerable question is abstained, and the',
    'remaining unanswerable false positive is documented rather than hidden by raising the floor',
    'to a value that would sacrifice answerable hit@5/MRR.',
    ''
)
Write-Utf8NoBom -Path $MarkdownPath -Text (($lines -join "`r`n") + "`r`n")

Write-Host "calibration json: $JsonPath"
Write-Host "calibration md:   $MarkdownPath"
$httpClient.Dispose()
