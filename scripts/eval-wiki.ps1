#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$QaPath = 'eval/qa.jsonl',
    [string]$ReportPath = 'docs/acceptance/phase11-llm-wiki.md',
    [string]$ReportJson = 'docs/acceptance/phase11-llm-wiki.json',
    [string]$CompileStatsPath = 'docs/acceptance/phase11-compile.json',
    [int]$TopK = 5,
    [string]$AsOf = (Get-Date -Format 'yyyy-MM-dd'),
    [string]$ChunkStrategy = 'heading',
    [switch]$IncludeSuperseded
)

# Phase 11 A/B: chunk vs page-aggregate vs page-wiki, on the same eval/qa.jsonl, asOf and topK=5.
#
# Fairness rules (the Phase 10 lesson):
#   * every route is scored with the SAME two rules, side by side:
#       fallback   = product GoldMatcher rule. A result that carries its own clause locator must
#                    match gold.clauseId exactly; a result with NO locator falls back to page-span
#                    intersection. This is exactly what eval.ps1/GoldMatcher do for chunks.
#       nofallback = a result with no locator is always a miss. The page-span fallback is removed.
#   * the "own clause locator" is route-specific and symmetric:
#       chunk  -> result.clauseId
#       page   -> result.leafClauseId (the page's own leaf heading, NOT every clause it contains)
#     Page clauseIds containment is deliberately NOT used: it would be a looser rule than the chunk
#     side and would inflate page scores.
#   * neither side gets a different dataset, asOf, topK or per-question override.
#
# The page routes are vector-only; the chunk route is the product hybrid route. That difference is
# inherent to the product endpoints and is reported, not hidden.
#
# Prerequisites: app running (Ollama bge-m3, PostgreSQL/pgvector), 3 docs ingested, pages rebuilt
# (aggregate) and wiki compiled (kind='wiki'). This script does not mutate data. Optional
# -CompileStatsPath (the saved POST /api/v1/admin/pages/compile-wiki response) adds the compile
# statistics table to the report.

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
    Where-Object { $_.Trim().Length -gt 0 } | ForEach-Object { $_ | ConvertFrom-Json })
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

function Invoke-PostJson {
    param([string]$Path, [string]$Body)
    $content = New-Object System.Net.Http.StringContent($Body, [System.Text.Encoding]::UTF8, 'application/json')
    $response = $httpClient.PostAsync("$BaseUrl$Path", $content).GetAwaiter().GetResult()
    $bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
    $text = [System.Text.Encoding]::UTF8.GetString($bytes)
    if (-not $response.IsSuccessStatusCode) {
        throw ("HTTP {0} on {1}: {2}" -f [int]$response.StatusCode, $Path, $text)
    }
    return ($text | ConvertFrom-Json)
}

# --- symmetric matching -----------------------------------------------------------------------
function Get-ResultDocKey {
    param($Result, [string]$Route)
    if ($Route -eq 'page') {
        if ($null -ne $Result.document) { return [string]$Result.document.docKey }
        return ''
    }
    return [string]$Result.docKey
}

function Get-ResultClause {
    param($Result, [string]$Route)
    if ($Route -eq 'page') {
        if ($null -ne $Result.leafClauseId) { return ([string]$Result.leafClauseId).Trim() }
        return ''
    }
    if ($null -ne $Result.clauseId) { return ([string]$Result.clauseId).Trim() }
    return ''
}

function Get-ResultSpan {
    param($Result, [string]$Route)
    $pageNo = $null
    $pageEnd = $null
    if ($Route -eq 'page') {
        if ($null -ne $Result.pageRange) {
            if ($null -ne $Result.pageRange.pageNo) { $pageNo = [int]$Result.pageRange.pageNo }
            if ($null -ne $Result.pageRange.pageEnd) { $pageEnd = [int]$Result.pageRange.pageEnd }
        }
    } else {
        if ($null -ne $Result.pageNo) { $pageNo = [int]$Result.pageNo }
        if ($null -ne $Result.pageEnd) { $pageEnd = [int]$Result.pageEnd }
    }
    if ($null -ne $pageNo -and $null -ne $pageEnd -and $pageEnd -lt $pageNo) { $pageEnd = $pageNo }
    return @($pageNo, $pageEnd)
}

function Test-SpanOverlap {
    param($Span, $Range)
    if ($null -eq $Span[0]) { return $false }
    $start = [int]$Range[0]
    $end = [int]$Range[1]
    $page = [int]$Span[0]
    $pageEnd = $page
    if ($null -ne $Span[1]) { $pageEnd = [int]$Span[1] }
    if ($pageEnd -lt $page) { $pageEnd = $page }
    return ($page -le $end -and $pageEnd -ge $start)
}

# Same rule for every route; only the locator accessor differs (see the header).
function Find-MatchRank {
    param($Results, $Gold, [string]$Route,
          [ValidateSet('fallback', 'nofallback')][string]$Mode)
    if ($null -eq $Gold -or -not $Gold.docKey) { return 0 }
    $goldDocKey = [string]$Gold.docKey
    $goldClause = ''
    if ($null -ne $Gold.clauseId) { $goldClause = ([string]$Gold.clauseId).Trim() }
    $range = @($Gold.pageRange)
    $hasRange = ($null -ne $Gold.pageRange -and $range.Count -ge 2)

    $rank = 0
    foreach ($result in @($Results)) {
        $rank++
        if ((Get-ResultDocKey $result $Route) -ne $goldDocKey) { continue }
        $resultClause = Get-ResultClause $result $Route

        if ($goldClause.Length -gt 0) {
            if ($resultClause.Length -gt 0) {
                if ($resultClause -eq $goldClause) { return $rank }
                # A different own clause id is a real miss even when spans overlap (GoldMatcher).
                continue
            }
            if ($Mode -eq 'fallback' -and $hasRange) {
                if (Test-SpanOverlap (Get-ResultSpan $result $Route) $range) { return $rank }
            }
            continue
        }

        if ($hasRange) {
            if (Test-SpanOverlap (Get-ResultSpan $result $Route) $range) { return $rank }
            continue
        }
        # docKey-only gold: the first result of the right document counts.
        return $rank
    }
    return 0
}

function Measure-Ranks {
    param($Ranks, [array]$ServerLatencies)
    $answered = $Ranks.Count
    $h1 = 0; $h3 = 0; $h5 = 0; $mrr = 0.0
    foreach ($rank in $Ranks) {
        if ($rank -ge 1) {
            if ($rank -le 5) { $h5++ }
            if ($rank -le 3) { $h3++ }
            if ($rank -eq 1) { $h1++ }
            $mrr += 1.0 / $rank
        }
    }
    $lat = [double[]]@($ServerLatencies)
    $hitAt1 = 0; $hitAt3 = 0; $hitAt5 = 0; $meanRr = 0
    if ($answered -gt 0) {
        $hitAt1 = [Math]::Round($h1 / [double]$answered, 4)
        $hitAt3 = [Math]::Round($h3 / [double]$answered, 4)
        $hitAt5 = [Math]::Round($h5 / [double]$answered, 4)
        $meanRr = [Math]::Round($mrr / [double]$answered, 4)
    }
    return [pscustomobject]@{
        answered = $answered
        hit1 = $h1
        hit3 = $h3
        hit5 = $h5
        hitAt1 = $hitAt1
        hitAt3 = $hitAt3
        hitAt5 = $hitAt5
        mrr = $meanRr
        p50Ms = Get-Percentile $lat 0.50
        p95Ms = Get-Percentile $lat 0.95
    }
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

function Format-Delta {
    param([int]$BaseRank, [int]$Rank)
    if ($BaseRank -eq $Rank) { return '' }
    if ($BaseRank -eq 0 -and $Rank -gt 0) { return "新增命中 (0 -> $Rank)" }
    if ($Rank -eq 0 -and $BaseRank -gt 0) { return "丢失命中 ($BaseRank -> 0)" }
    if ($Rank -lt $BaseRank) { return ("提升 {0} -> {1}" -f $BaseRank, $Rank) }
    return ("下降 {0} -> {1}" -f $BaseRank, $Rank)
}

# --- warm up + wiki presence probe ------------------------------------------------------------
for ($warm = 0; $warm -lt 2; $warm++) {
    $warmBase = @{ query = $qaRows[0].question; topK = $TopK; asOf = $AsOf
                   includeSuperseded = [bool]$IncludeSuperseded }
    $warmChunk = $warmBase.Clone()
    $warmChunk.chunkStrategy = $ChunkStrategy
    $warmChunk.strategy = 'hybrid'
    Invoke-PostJson -Path '/api/v1/search' -Body ($warmChunk | ConvertTo-Json -Compress) | Out-Null
    $warmAgg = $warmBase.Clone(); $warmAgg.kind = 'aggregate'
    Invoke-PostJson -Path '/api/v1/pages/search' -Body ($warmAgg | ConvertTo-Json -Compress) | Out-Null
    $warmWiki = $warmBase.Clone(); $warmWiki.kind = 'wiki'
    Invoke-PostJson -Path '/api/v1/pages/search' -Body ($warmWiki | ConvertTo-Json -Compress) | Out-Null
}
$probe = @{ query = $qaRows[0].question; topK = 1; asOf = $AsOf
            includeSuperseded = [bool]$IncludeSuperseded; kind = 'wiki' }
$probeResponse = Invoke-PostJson -Path '/api/v1/pages/search' -Body ($probe | ConvertTo-Json -Compress)
if (@($probeResponse.results).Count -eq 0) {
    throw "No wiki pages found. Run POST /api/v1/admin/pages/compile-wiki first."
}

$routeKeys = @('chunk', 'aggregate', 'wiki')
$modeKeys = @('fallback', 'nofallback')
$ranks = @{}
$latencies = @{}
$errors = @{}
foreach ($route in $routeKeys) {
    foreach ($mode in $modeKeys) {
        $ranks["$route/$mode"] = New-Object System.Collections.Generic.List[int]
        $errors["$route/$mode"] = 0
    }
    $latencies[$route] = New-Object System.Collections.Generic.List[double]
}
$chunkAbstainedAnswerable = 0
$chunkAbstainedUnanswerable = 0
$pageReturnedUnanswerable = 0
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

    $chunkBody = @{ query = $row.question; topK = $TopK; chunkStrategy = $ChunkStrategy
                    strategy = 'hybrid'; asOf = $effectiveAsOf
                    includeSuperseded = $effectiveInclude }
    $aggBody = @{ query = $row.question; topK = $TopK; asOf = $effectiveAsOf
                  includeSuperseded = $effectiveInclude; kind = 'aggregate' }
    $wikiBody = @{ query = $row.question; topK = $TopK; asOf = $effectiveAsOf
                   includeSuperseded = $effectiveInclude; kind = 'wiki' }

    $chunkResults = @()
    $chunkError = $false
    $chunkErrorMessage = ''
    $chunkAbstained = $false
    $chunkAbstainReason = ''
    try {
        $chunkResponse = Invoke-PostJson -Path '/api/v1/search' -Body ($chunkBody | ConvertTo-Json -Compress)
        if ($null -ne $chunkResponse.tookMs) { $latencies['chunk'].Add([double]$chunkResponse.tookMs) }
        $chunkResults = @($chunkResponse.results)
        if ($chunkResponse.abstained -eq $true) {
            $chunkAbstained = $true
            if ($null -ne $chunkResponse.abstainReason) {
                $chunkAbstainReason = [string]$chunkResponse.abstainReason
            }
        }
    } catch {
        $chunkError = $true
        $chunkErrorMessage = $_.Exception.Message
        foreach ($mode in $modeKeys) { $errors["chunk/$mode"]++ }
    }

    $aggResults = @()
    $aggError = $false
    $aggErrorMessage = ''
    try {
        $aggResponse = Invoke-PostJson -Path '/api/v1/pages/search' -Body ($aggBody | ConvertTo-Json -Compress)
        if ($null -ne $aggResponse.tookMs) { $latencies['aggregate'].Add([double]$aggResponse.tookMs) }
        $aggResults = @($aggResponse.results)
    } catch {
        $aggError = $true
        $aggErrorMessage = $_.Exception.Message
        foreach ($mode in $modeKeys) { $errors["aggregate/$mode"]++ }
    }

    $wikiResults = @()
    $wikiError = $false
    $wikiErrorMessage = ''
    try {
        $wikiResponse = Invoke-PostJson -Path '/api/v1/pages/search' -Body ($wikiBody | ConvertTo-Json -Compress)
        if ($null -ne $wikiResponse.tookMs) { $latencies['wiki'].Add([double]$wikiResponse.tookMs) }
        $wikiResults = @($wikiResponse.results)
    } catch {
        $wikiError = $true
        $wikiErrorMessage = $_.Exception.Message
        foreach ($mode in $modeKeys) { $errors["wiki/$mode"]++ }
    }

    $rankValues = [ordered]@{}
    foreach ($route in $routeKeys) {
        foreach ($mode in $modeKeys) { $rankValues["$route/$mode"] = 0 }
    }
    if ($row.answerable -eq $true -and -not $chunkError) {
        $rankValues['chunk/fallback'] = Find-MatchRank $chunkResults $row.gold 'chunk' 'fallback'
        $rankValues['chunk/nofallback'] = Find-MatchRank $chunkResults $row.gold 'chunk' 'nofallback'
    }
    if ($row.answerable -eq $true -and -not $aggError) {
        $rankValues['aggregate/fallback'] = Find-MatchRank $aggResults $row.gold 'page' 'fallback'
        $rankValues['aggregate/nofallback'] = Find-MatchRank $aggResults $row.gold 'page' 'nofallback'
    }
    if ($row.answerable -eq $true -and -not $wikiError) {
        $rankValues['wiki/fallback'] = Find-MatchRank $wikiResults $row.gold 'page' 'fallback'
        $rankValues['wiki/nofallback'] = Find-MatchRank $wikiResults $row.gold 'page' 'nofallback'
    }
    foreach ($route in $routeKeys) {
        foreach ($mode in $modeKeys) {
            $key = "$route/$mode"
            $failed = $false
            if ($route -eq 'chunk') { $failed = $chunkError }
            elseif ($route -eq 'aggregate') { $failed = $aggError }
            else { $failed = $wikiError }
            if ($row.answerable -eq $true -and -not $failed) {
                $ranks[$key].Add([int]$rankValues[$key])
            }
        }
    }

    if ($row.answerable -eq $true) {
        if ($chunkAbstained) { $chunkAbstainedAnswerable++ }
    } else {
        if ($chunkAbstained) { $chunkAbstainedUnanswerable++ }
        if ($aggResults.Count -gt 0 -or $wikiResults.Count -gt 0) { $pageReturnedUnanswerable++ }
    }

    $errorParts = @()
    if ($chunkError) { $errorParts += "chunk: $chunkErrorMessage" }
    if ($aggError) { $errorParts += "aggregate: $aggErrorMessage" }
    if ($wikiError) { $errorParts += "wiki: $wikiErrorMessage" }

    $details += [pscustomobject]@{
        id = $row.id
        answerable = [bool]($row.answerable -eq $true)
        asOf = $effectiveAsOf
        includeSuperseded = $effectiveInclude
        chunkFallback = $rankValues['chunk/fallback']
        chunkNofallback = $rankValues['chunk/nofallback']
        aggregateFallback = $rankValues['aggregate/fallback']
        aggregateNofallback = $rankValues['aggregate/nofallback']
        wikiFallback = $rankValues['wiki/fallback']
        wikiNofallback = $rankValues['wiki/nofallback']
        errors = ($errorParts -join '; ')
        chunkAbstained = $chunkAbstained
        chunkAbstainReason = $chunkAbstainReason
        chunkTopCitation = ''
        aggregateTop = ''
        wikiTop = ''
    }
    if ($chunkResults.Count -gt 0 -and $null -ne $chunkResults[0].citation) {
        $details[-1].chunkTopCitation = [string]$chunkResults[0].citation
    }
    if ($aggResults.Count -gt 0) {
        $details[-1].aggregateTop = ('[{0}] {1}' -f $aggResults[0].leafClauseId, $aggResults[0].title)
    }
    if ($wikiResults.Count -gt 0) {
        $details[-1].wikiTop = ('[{0}] {1}' -f $wikiResults[0].leafClauseId, $wikiResults[0].title)
    }
}

$metrics = @{}
foreach ($route in $routeKeys) {
    foreach ($mode in $modeKeys) {
        $key = "$route/$mode"
        $metrics[$key] = Measure-Ranks -Ranks $ranks[$key].ToArray() `
            -ServerLatencies $latencies[$route].ToArray()
    }
}

$answerableDetails = @($details | Where-Object { $_.answerable -eq $true })
function Count-Diff {
    param([string]$Field)
    return @($answerableDetails | Where-Object {
        $_.chunkFallback -ne $_.$Field -and $_.errors -eq ''
    }).Count
}
$diffCounts = [ordered]@{
    aggregateFallback = Count-Diff 'aggregateFallback'
    aggregateNofallback = Count-Diff 'aggregateNofallback'
    wikiFallback = Count-Diff 'wikiFallback'
    wikiNofallback = Count-Diff 'wikiNofallback'
}

# --- optional compile statistics --------------------------------------------------------------
$compileStats = $null
if (Test-Path -LiteralPath $CompileStatsPath) {
    $compileStats = Get-Content -LiteralPath $CompileStatsPath -Raw -Encoding UTF8 | ConvertFrom-Json
}

# --- JSON report ------------------------------------------------------------------------------
$result = [pscustomobject]@{
    timestamp = (Get-Date).ToString('o')
    dataset = $QaPath
    datasetSha256 = $datasetHash
    gitCommit = $gitCommit
    gitDirty = $gitDirty
    baseUrl = $BaseUrl
    embedding = 'bge-m3'
    embeddingDimension = 1024
    chunkStrategy = $ChunkStrategy
    topK = $TopK
    asOf = $AsOf
    includeSuperseded = [bool]$IncludeSuperseded
    qaCount = $qaRows.Count
    answerable = $answerableQuestions.Count
    unanswerable = $unanswerableQuestions.Count
    compile = $compileStats
    rules = [pscustomobject]@{
        chunkLocator = 'result.clauseId'
        pageLocator = 'result.leafClauseId (page own leaf, not clauseIds containment)'
        fallback = 'product GoldMatcher: no locator -> page-span intersection'
        nofallback = 'no locator -> miss (span fallback removed)'
    }
    routes = [pscustomobject]@{
        chunk = [pscustomobject]@{
            endpoint = 'POST /api/v1/search'
            strategy = 'hybrid'
            fallback = $metrics['chunk/fallback']
            nofallback = $metrics['chunk/nofallback']
            errors = $errors['chunk/fallback']
        }
        aggregate = [pscustomobject]@{
            endpoint = 'POST /api/v1/pages/search?kind=aggregate (body kind)'
            strategy = 'vector'
            fallback = $metrics['aggregate/fallback']
            nofallback = $metrics['aggregate/nofallback']
            errors = $errors['aggregate/fallback']
        }
        wiki = [pscustomobject]@{
            endpoint = 'POST /api/v1/pages/search?kind=wiki (body kind)'
            strategy = 'vector'
            fallback = $metrics['wiki/fallback']
            nofallback = $metrics['wiki/nofallback']
            errors = $errors['wiki/fallback']
        }
    }
    differences = [pscustomobject]@{
        aggregateFallback = $diffCounts['aggregateFallback']
        aggregateNofallback = $diffCounts['aggregateNofallback']
        wikiFallback = $diffCounts['wikiFallback']
        wikiNofallback = $diffCounts['wikiNofallback']
    }
    unanswerableDetail = [pscustomobject]@{
        chunkAbstainedAnswerable = $chunkAbstainedAnswerable
        chunkAbstainedUnanswerable = $chunkAbstainedUnanswerable
        pageReturnedNonEmpty = $pageReturnedUnanswerable
        note = 'page routes have no abstention guard (A/B probe only)'
    }
    details = $details
}
Write-Utf8NoBom -Path $ReportJson -Text ($result | ConvertTo-Json -Depth 12)

# --- Markdown report --------------------------------------------------------------------------
$lines = @(
    '# Phase 11 验收：LLM 编译 wiki 页面 vs chunk / 确定性页面',
    '',
    ('- 生成时间：{0}' -f (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')),
    ('- 分支/提交：`{0}` (dirty={1})' -f $gitCommit, $gitDirty),
    ('- 数据集：`{0}` (sha256={1})，{2} 条（{3} 可回答 + {4} 不可回答）' -f
        $QaPath, $datasetHash, $qaRows.Count, $answerableQuestions.Count, $unanswerableQuestions.Count),
    ('- 运行参数：topK={0}，默认 asOf={1}，includeSuperseded={2}，chunkStrategy={3}' -f
        $TopK, $AsOf, [bool]$IncludeSuperseded, $ChunkStrategy),
    '- 三条路线：chunk = `POST /api/v1/search`（hybrid）；page-aggregate = `POST /api/v1/pages/search` + `kind=aggregate`；page-wiki = 同端点 + `kind=wiki`（均为向量路）',
    '- 数据集、gold、asOf、topK 完全相同；本脚本不修改 `eval/qa.jsonl`、gold 或任何既有匹配规则。',
    ''
)

if ($null -ne $compileStats) {
    $lines += @(
        '## 1. 编译统计（来自 `docs/acceptance/phase11-compile.json`）',
        '',
        ('- 模型：`{0}`；文档数 {1}；小节数 {2}；成功 {3}；失败 {4}；跳过 {5}' -f
            $compileStats.model, $compileStats.documentCount, $compileStats.sectionCount,
            $compileStats.successCount, $compileStats.failedCount, $compileStats.skippedCount),
        ('- token：prompt {0} / completion {1} / total {2}；耗时 {3} ms' -f
            $compileStats.promptTokens, $compileStats.completionTokens, $compileStats.totalTokens,
            $compileStats.tookMs),
        '',
        '| docKey | version | 小节 | 成功 | 失败 | 跳过 | 写入页面 | LLM 调用 | prompt tokens | completion tokens | 耗时(ms) |',
        '|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|'
    )
    foreach ($doc in @($compileStats.documents)) {
        $lines += ('| {0} | {1} | {2} | {3} | {4} | {5} | {6} | {7} | {8} | {9} | {10} |' -f
            (ConvertTo-MdCell $doc.docKey), (ConvertTo-MdCell $doc.version), $doc.sectionCount,
            $doc.successCount, $doc.failedCount, $doc.skippedCount, $doc.pageCount, $doc.llmCalls,
            $doc.promptTokens, $doc.completionTokens, $doc.tookMs)
    }
    if (@($compileStats.failures).Count -gt 0) {
        $lines += @('', '失败/跳过明细：', '', '| docId | 小节 | 阶段 | 原因 |', '|---|---|---|---|')
        foreach ($failure in @($compileStats.failures)) {
            $lines += ('| {0} | {1} | {2} | {3} |' -f
                (ConvertTo-MdCell $failure.docId), (ConvertTo-MdCell $failure.clausePath),
                (ConvertTo-MdCell $failure.stage), (ConvertTo-MdCell $failure.reason))
        }
    }
    $lines += ''
}

$lines += @(
    '## 2. 匹配口径（三条路线 × 两种口径，同一张表）',
    '',
    '| 路线 | 自身的 clause 定位 | fallback（= 产品 GoldMatcher） | nofallback |',
    '|---|---|---|---|',
    '| chunk | `result.clauseId` | 结果无 `clauseId` 时允许页码相交 | 结果无 `clauseId` 一律不命中 |',
    '| page（aggregate / wiki 相同） | `result.leafClauseId`（页面自身叶子节，**不是** `clauseIds` 包含关系） | 结果无 `leafClauseId` 时允许页码相交 | 结果无 `leafClauseId` 一律不命中 |',
    '',
    '**两条路线使用同一条规则，只替换"结果自己的 clause 定位"字段。** 页面侧的 `clauseIds` 包含关系',
    '（Phase 10 的 loose）被刻意排除：页面天然覆盖更多 clause，包含关系比 chunk 侧宽松，会把页面分数抬高。',
    '本表只比较同一口径下的数字。',
    '',
    '## 3. 四指标（同一 asOf、topK=5、同一批可回答题）',
    '',
    '| 路线 | 口径 | 可回答题数 | hit@1 | hit@3 | hit@5 | MRR | p50(ms) | p95(ms) | 错误 |',
    '|---|---|---:|---:|---:|---:|---:|---:|---:|---:|'
)
foreach ($route in $routeKeys) {
    foreach ($mode in $modeKeys) {
        $m = $metrics["$route/$mode"]
        $lines += ('| {0} | {1} | {2} | {3} | {4} | {5} | {6} | {7} | {8} | {9} |' -f
            $route, $mode, $m.answered, $m.hitAt1, $m.hitAt3, $m.hitAt5, $m.mrr,
            $m.p50Ms, $m.p95Ms, $errors["$route/$mode"])
    }
}
$lines += @(
    '',
    ('差值（同一口径，相对 chunk）：aggregate fallback hit@1 {0:0.0000} / hit@3 {1:0.0000} / hit@5 {2:0.0000} / MRR {3:0.0000}；aggregate nofallback hit@1 {4:0.0000} / hit@3 {5:0.0000} / hit@5 {6:0.0000} / MRR {7:0.0000}。' -f
        ($metrics['aggregate/fallback'].hitAt1 - $metrics['chunk/fallback'].hitAt1),
        ($metrics['aggregate/fallback'].hitAt3 - $metrics['chunk/fallback'].hitAt3),
        ($metrics['aggregate/fallback'].hitAt5 - $metrics['chunk/fallback'].hitAt5),
        ($metrics['aggregate/fallback'].mrr - $metrics['chunk/fallback'].mrr),
        ($metrics['aggregate/nofallback'].hitAt1 - $metrics['chunk/nofallback'].hitAt1),
        ($metrics['aggregate/nofallback'].hitAt3 - $metrics['chunk/nofallback'].hitAt3),
        ($metrics['aggregate/nofallback'].hitAt5 - $metrics['chunk/nofallback'].hitAt5),
        ($metrics['aggregate/nofallback'].mrr - $metrics['chunk/nofallback'].mrr)),
    ('差值（同一口径，相对 chunk）：wiki fallback hit@1 {0:0.0000} / hit@3 {1:0.0000} / hit@5 {2:0.0000} / MRR {3:0.0000}；wiki nofallback hit@1 {4:0.0000} / hit@3 {5:0.0000} / hit@5 {6:0.0000} / MRR {7:0.0000}。' -f
        ($metrics['wiki/fallback'].hitAt1 - $metrics['chunk/fallback'].hitAt1),
        ($metrics['wiki/fallback'].hitAt3 - $metrics['chunk/fallback'].hitAt3),
        ($metrics['wiki/fallback'].hitAt5 - $metrics['chunk/fallback'].hitAt5),
        ($metrics['wiki/fallback'].mrr - $metrics['chunk/fallback'].mrr),
        ($metrics['wiki/nofallback'].hitAt1 - $metrics['chunk/nofallback'].hitAt1),
        ($metrics['wiki/nofallback'].hitAt3 - $metrics['chunk/nofallback'].hitAt3),
        ($metrics['wiki/nofallback'].hitAt5 - $metrics['chunk/nofallback'].hitAt5),
        ($metrics['wiki/nofallback'].mrr - $metrics['chunk/nofallback'].mrr)),
    '',
    '## 4. 逐题差异（只列与 chunk fallback 不同的可回答题）',
    '',
    '| 题 | chunk F | chunk NF | agg F | agg NF | wiki F | wiki NF | wiki 命中页（leaf/title） |',
    '|---|---:|---:|---:|---:|---:|---:|---|'
)
$anyDiff = $false
foreach ($d in $answerableDetails) {
    if ($d.errors -ne '') { continue }
    if ($d.chunkFallback -eq $d.aggregateFallback -and $d.chunkFallback -eq $d.aggregateNofallback -and
        $d.chunkFallback -eq $d.wikiFallback -and $d.chunkFallback -eq $d.wikiNofallback) { continue }
    $anyDiff = $true
    $lines += ('| {0} | {1} | {2} | {3} | {4} | {5} | {6} | {7} |' -f
        $d.id, $d.chunkFallback, $d.chunkNofallback, $d.aggregateFallback,
        $d.aggregateNofallback, $d.wikiFallback, $d.wikiNofallback,
        (ConvertTo-MdCell $d.wikiTop))
}
if (-not $anyDiff) { $lines += '| （无） | | | | | | | |' }
$lines += @(
    '',
    ('汇总（与 chunk fallback 不同的题数）：aggregate fallback {0}、aggregate nofallback {1}、wiki fallback {2}、wiki nofallback {3}。' -f
        $diffCounts['aggregateFallback'], $diffCounts['aggregateNofallback'],
        $diffCounts['wikiFallback'], $diffCounts['wikiNofallback']),
    '',
    '## 5. 不可回答题（不计入 hit@k）',
    '',
    ('- chunk 路：{0}/5 题被拒答；其中可回答题被误拒答 {1} 题。' -f
        $chunkAbstainedUnanswerable, $chunkAbstainedAnswerable),
    ('- page 路（aggregate 或 wiki）：{0}/5 题返回了非空结果。页面端点没有拒答守卫，这是 A/B 探针的既有边界。' -f
        $pageReturnedUnanswerable),
    '',
    '## 6. 自动结论（仅陈述数据事实）',
    ''
)
foreach ($route in $routeKeys) {
    foreach ($mode in $modeKeys) {
        $m = $metrics["$route/$mode"]
        $lines += ('- {0} / {1}：hit@1={2}、hit@3={3}、hit@5={4}、MRR={5}。' -f
            $route, $mode, $m.hitAt1, $m.hitAt3, $m.hitAt5, $m.mrr)
    }
}
$lines += @(
    '',
    '自动判定（比较同一口径的 hit@5 与 MRR）：'
)
foreach ($route in @('aggregate', 'wiki')) {
    foreach ($mode in $modeKeys) {
        $m = $metrics["$route/$mode"]
        $base = $metrics["chunk/$mode"]
        $hitDelta = $m.hitAt5 - $base.hitAt5
        $mrrDelta = $m.mrr - $base.mrr
        if ($hitDelta -gt 0.0001) {
            if ($mrrDelta -lt -0.0001) { $verdict = 'hit@5 更高、MRR 更低（互有高低）' }
            else { $verdict = 'hit@5 更高' }
        } elseif ($hitDelta -lt -0.0001) {
            if ($mrrDelta -gt 0.0001) { $verdict = 'hit@5 更低、MRR 更高（互有高低，差 1 题量级）' }
            else { $verdict = 'hit@5 更低' }
        } elseif ([Math]::Abs($mrrDelta) -lt 0.01) {
            $verdict = ('hit@5 相同；MRR 差 {0:0.0000}，不足 1 题量级，视为噪声' -f $mrrDelta)
        } elseif ($mrrDelta -gt 0) {
            $verdict = ('hit@5 相同；MRR 高 {0:0.0000}' -f $mrrDelta)
        } else {
            $verdict = ('hit@5 相同；MRR 低 {0:0.0000}' -f $mrrDelta)
        }
        $lines += ('- {0} / {1}：{2}' -f $route, $mode, $verdict)
    }
}
$lines += @(
    '',
    '`MANUAL:START` 之后为人工分析。重跑本脚本时，只要该标记仍在旧报告里，旧的人工分析会被原样保留。',
    ''
)
$preserved = ''
if (Test-Path -LiteralPath $ReportPath) {
    $existing = [System.IO.File]::ReadAllText((Join-Path (Get-Location) $ReportPath),
        (New-Object System.Text.UTF8Encoding($false)))
    $markerIndex = $existing.IndexOf('<!-- MANUAL:START -->')
    if ($markerIndex -ge 0) { $preserved = $existing.Substring($markerIndex) }
}
if ($preserved.Length -gt 0) {
    $lines += $preserved.TrimEnd()
} else {
    $lines += @(
        '',
        '<!-- MANUAL:START -->',
        '## 7. 结论、可读性对照与风险',
        '',
        '（人工分析待补。）'
    )
}
Write-Utf8NoBom -Path $ReportPath -Text (($lines -join "`r`n") + "`r`n")

$httpClient.Dispose()
foreach ($route in $routeKeys) {
    foreach ($mode in $modeKeys) {
        $m = $metrics["$route/$mode"]
        Write-Host ("{0,-9} {1,-10}: hit@1={2} hit@3={3} hit@5={4} MRR={5}" -f
            $route, $mode, $m.hitAt1, $m.hitAt3, $m.hitAt5, $m.mrr)
    }
}
Write-Host "report: $ReportPath"
Write-Host "json:   $ReportJson"

$totalErrors = 0
foreach ($key in $errors.Keys) { if ($errors[$key] -gt 0) { $totalErrors++ } }
if ($totalErrors -gt 0) { exit 1 }
exit 0
