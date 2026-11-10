#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$QaPath = 'eval/qa.jsonl',
    [string]$ReportPath = 'docs/acceptance/phase10-pages.md',
    [string]$ReportJson = 'docs/acceptance/phase10-pages.json',
    [int]$TopK = 5,
    [string]$AsOf = (Get-Date -Format 'yyyy-MM-dd'),
    [string]$ChunkStrategy = 'heading',
    [string]$AdminToken = $env:ADMIN_TOKEN,
    [switch]$IncludeSuperseded
)

# Phase 10 A/B harness: chunk granularity (POST /api/v1/search) vs page granularity
# (POST /api/v1/pages/search) on the same eval/qa.jsonl, same asOf and same topK=5.
#
# Fairness rules (this is the point of the script):
#   * chunk side: the Find-Rank implementation is copied VERBATIM from scripts/eval.ps1
#     (docKey + clauseId, pageRange intersection when the result has no clauseId). eval/qa.jsonl
#     and the matching rule are not modified.
#   * page side: TWO metrics are always reported together.
#       loose  : gold.clauseId is in page.clauseIds OR the page span intersects gold.pageRange.
#                This is easy to inflate because a page covers more text than a chunk.
#       strict : the page's own leaf clause id (leafClauseId == the clauseId of the leaf heading in
#                its clausePath) must equal gold.clauseId. A page does NOT score just because the
#                right clause appears somewhere inside it. When gold has no clauseId, strict falls
#                back to the pageRange intersection, which is the only locator left (same as the
#                chunk rule); those questions are called out in the report.
#   * neither side gets a different dataset, asOf, topK or abstention treatment.
#
# Prerequisites:
#   1) the app is running (Ollama bge-m3 + PostgreSQL/pgvector, 3 docs ingested);
#   2) pages were rebuilt: POST /api/v1/admin/pages/rebuild with the admin token;
#   3) -AdminToken (or ADMIN_TOKEN) is set, because page statistics come from
#      GET /api/v1/admin/pages/stats.
# The script does not mutate data. It is Windows PowerShell 5.1 compatible and decodes every
# response as UTF-8 (no server-side charset guessing).

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not (Test-Path -LiteralPath $QaPath)) { throw "QA dataset not found: $QaPath" }
if ([string]::IsNullOrWhiteSpace($AdminToken)) {
    throw "AdminToken is required for page statistics (-AdminToken or `$env:ADMIN_TOKEN)."
}

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
        throw ("HTTP {0}: {1}" -f [int]$response.StatusCode, $text)
    }
    return ($text | ConvertFrom-Json)
}

function Invoke-AdminGet {
    param([string]$Path)
    $request = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Get, "$BaseUrl$Path")
    $request.Headers.Add('X-Admin-Token', $AdminToken)
    $response = $httpClient.SendAsync($request).GetAwaiter().GetResult()
    $bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
    $text = [System.Text.Encoding]::UTF8.GetString($bytes)
    if (-not $response.IsSuccessStatusCode) {
        throw ("HTTP {0} on {1}: {2}" -f [int]$response.StatusCode, $Path, $text)
    }
    return ($text | ConvertFrom-Json)
}

# ---------------------------------------------------------------------------------------------
# Chunk-side matching: VERBATIM copy of Find-Rank from scripts/eval.ps1. Do not edit in place;
# the two scripts must agree on every question or the A/B comparison is meaningless.
# ---------------------------------------------------------------------------------------------
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

function Find-ChunkRank {
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
            if ($hasRange -and (Test-PageOverlap $result $range)) { return $rank }
            continue
        }

        if ($hasRange) {
            if (Test-PageOverlap $result $range) { return $rank }
            continue
        }
        # docKey-only gold: first chunk of the right document counts.
        return $rank
    }
    return 0
}

# ---------------------------------------------------------------------------------------------
# Page-side matching, both口径. loose is deliberately generous; strict is the honest one.
# ---------------------------------------------------------------------------------------------
function Test-PageSpanOverlap {
    param($PageResult, $Range)
    if ($null -eq $PageResult.pageRange -or $null -eq $PageResult.pageRange.pageNo) { return $false }
    $start = [int]$Range[0]
    $end = [int]$Range[1]
    $page = [int]$PageResult.pageRange.pageNo
    $pageEnd = $page
    if ($null -ne $PageResult.pageRange.pageEnd) { $pageEnd = [int]$PageResult.pageRange.pageEnd }
    if ($pageEnd -lt $page) { $pageEnd = $page }
    return ($page -le $end -and $pageEnd -ge $start)
}

function New-PageMatch {
    param([int]$Rank, $Result)
    if ($Rank -le 0 -or $null -eq $Result) {
        return [pscustomobject]@{
            rank = 0
            leaf = ''
            clauseIds = @()
            title = ''
            pageNo = $null
            pageEnd = $null
            citation = ''
        }
    }
    $leaf = ''
    if ($null -ne $Result.leafClauseId) { $leaf = [string]$Result.leafClauseId }
    $ids = @()
    if ($null -ne $Result.clauseIds) { $ids = @($Result.clauseIds) }
    $pageNo = $null
    $pageEnd = $null
    if ($null -ne $Result.pageRange) {
        if ($null -ne $Result.pageRange.pageNo) { $pageNo = [int]$Result.pageRange.pageNo }
        if ($null -ne $Result.pageRange.pageEnd) { $pageEnd = [int]$Result.pageRange.pageEnd }
    }
    $title = ''
    if ($null -ne $Result.title) { $title = [string]$Result.title }
    $citation = ''
    if ($null -ne $Result.citation) { $citation = [string]$Result.citation }
    return [pscustomobject]@{
        rank = $Rank
        leaf = $leaf
        clauseIds = $ids
        title = $title
        pageNo = $pageNo
        pageEnd = $pageEnd
        citation = $citation
    }
}

# Returns the matched page itself (rank + its own leaf clause/page span), so the report can show
# WHICH page a loose hit used. That detail is the difference between "the page is right" and
# "a page covering the same page number happened to rank".
function Find-PageMatch {
    param($Results, $Gold, [ValidateSet('loose', 'strict')][string]$Mode)
    if ($null -eq $Gold -or -not $Gold.docKey) { return (New-PageMatch -Rank 0 -Result $null) }
    $goldDocKey = [string]$Gold.docKey
    $goldClause = ''
    if ($null -ne $Gold.clauseId) { $goldClause = ([string]$Gold.clauseId).Trim() }
    $range = @($Gold.pageRange)
    $hasRange = ($null -ne $Gold.pageRange -and $range.Count -ge 2)

    $rank = 0
    foreach ($result in @($Results)) {
        $rank++
        $docKey = ''
        if ($null -ne $result.document) { $docKey = [string]$result.document.docKey }
        if ($docKey -ne $goldDocKey) { continue }

        if ($Mode -eq 'strict') {
            if ($goldClause.Length -gt 0) {
                $leaf = ''
                if ($null -ne $result.leafClauseId) { $leaf = ([string]$result.leafClauseId).Trim() }
                if ($leaf.Length -gt 0 -and $leaf -eq $goldClause) { return (New-PageMatch -Rank $rank -Result $result) }
                # No page-range fallback when gold names a clause: that is the whole point of
                # strict, otherwise a page that merely contains the clause would count.
                continue
            }
            if ($hasRange) {
                if (Test-PageSpanOverlap $result $range) { return (New-PageMatch -Rank $rank -Result $result) }
            } else {
                return (New-PageMatch -Rank $rank -Result $result)
            }
            continue
        }

        # loose: clause containment OR page intersection.
        if ($goldClause.Length -gt 0) {
            $ids = @()
            if ($null -ne $result.clauseIds) { $ids = @($result.clauseIds) }
            $matched = $false
            foreach ($id in $ids) {
                if ($null -ne $id -and ([string]$id).Trim() -eq $goldClause) { $matched = $true; break }
            }
            if ($matched) { return (New-PageMatch -Rank $rank -Result $result) }
            if ($hasRange -and (Test-PageSpanOverlap $result $range)) {
                return (New-PageMatch -Rank $rank -Result $result)
            }
            continue
        }
        if ($hasRange) {
            if (Test-PageSpanOverlap $result $range) { return (New-PageMatch -Rank $rank -Result $result) }
            continue
        }
        return (New-PageMatch -Rank $rank -Result $result)
    }
    return (New-PageMatch -Rank 0 -Result $null)
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
    param([int]$BaseRank, [int]$PageRank)
    if ($BaseRank -eq $PageRank) { return '' }
    if ($BaseRank -eq 0 -and $PageRank -gt 0) { return "新增命中 (0 -> $PageRank)" }
    if ($PageRank -eq 0 -and $BaseRank -gt 0) { return "丢失命中 ($BaseRank -> 0)" }
    if ($PageRank -lt $BaseRank) { return ("提升 {0} -> {1}" -f $BaseRank, $PageRank) }
    return ("下降 {0} -> {1}" -f $BaseRank, $PageRank)
}

function Get-PageMatchNote {
    param($Row)
    $gold = $Row.gold
    if ($null -eq $gold -or $null -eq $gold.clauseId -or ([string]$gold.clauseId).Trim().Length -eq 0) {
        return 'gold 无 clauseId，strict 退化为页码相交'
    }
    return ('gold clauseId=' + ([string]$gold.clauseId).Trim())
}

# --- page statistics (read-only admin endpoint) ---------------------------------------------
$stats = Invoke-AdminGet -Path '/api/v1/admin/pages/stats'
if ($stats.pageCount -eq 0) {
    throw "No pages found. Run POST /api/v1/admin/pages/rebuild (X-Admin-Token) before the A/B run."
}

# Warm up both routes outside the measured window (Ollama model load / JIT).
for ($warm = 0; $warm -lt 2; $warm++) {
    $warmBase = @{ query = $qaRows[0].question; topK = $TopK; asOf = $AsOf
                   includeSuperseded = [bool]$IncludeSuperseded }
    $warmChunk = $warmBase.Clone()
    $warmChunk.chunkStrategy = $ChunkStrategy
    $warmChunk.strategy = 'hybrid'
    Invoke-PostJson -Path '/api/v1/search' -Body ($warmChunk | ConvertTo-Json -Compress) | Out-Null
    Invoke-PostJson -Path '/api/v1/pages/search' -Body ($warmBase | ConvertTo-Json -Compress) | Out-Null
}

$chunkRanks = New-Object System.Collections.Generic.List[int]
$chunkVectorRanks = New-Object System.Collections.Generic.List[int]
$pageLooseRanks = New-Object System.Collections.Generic.List[int]
$pageStrictRanks = New-Object System.Collections.Generic.List[int]
$chunkLatencies = New-Object System.Collections.Generic.List[double]
$chunkVectorLatencies = New-Object System.Collections.Generic.List[double]
$pageLatencies = New-Object System.Collections.Generic.List[double]
$details = @()
$chunkErrors = 0
$chunkVectorErrors = 0
$pageErrors = 0
$chunkAbstainedAnswerable = 0
$chunkAbstainedUnanswerable = 0
$pageReturnedUnanswerable = 0

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
    $pageBody = @{ query = $row.question; topK = $TopK; asOf = $effectiveAsOf
                   includeSuperseded = $effectiveInclude }

    $chunkRank = 0
    $chunkError = $false
    $chunkErrorMessage = ''
    $chunkAbstained = $false
    $chunkAbstainReason = ''
    $chunkTopCitation = ''
    $chunkServerMs = $null
    try {
        $chunkResponse = Invoke-PostJson -Path '/api/v1/search' -Body ($chunkBody | ConvertTo-Json -Compress)
        if ($null -ne $chunkResponse.tookMs) {
            $chunkServerMs = [double]$chunkResponse.tookMs
            $chunkLatencies.Add($chunkServerMs)
        }
        $chunkResults = @($chunkResponse.results)
        if ($chunkResponse.abstained -eq $true) {
            $chunkAbstained = $true
            if ($null -ne $chunkResponse.abstainReason) {
                $chunkAbstainReason = [string]$chunkResponse.abstainReason
            }
        }
        if ($chunkResults.Count -gt 0 -and $null -ne $chunkResults[0].citation) {
            $chunkTopCitation = [string]$chunkResults[0].citation
        }
        if ($row.answerable -eq $true) {
            $chunkRank = Find-ChunkRank -Results $chunkResults -Gold $row.gold
        }
    } catch {
        $chunkError = $true
        $chunkErrorMessage = $_.Exception.Message
        $chunkErrors++
    }

    # Supplementary diagnostic (not the primary A/B row): the same chunk records, vector route
    # only. Page search is vector-only, so this isolates "granularity" from "hybrid+RRF+rerank".
    $chunkVectorRank = 0
    $chunkVectorError = $false
    $chunkVectorErrorMessage = ''
    if ($row.answerable -eq $true) {
        $chunkVectorBody = @{ query = $row.question; topK = $TopK; chunkStrategy = $ChunkStrategy
                              strategy = 'vector'; asOf = $effectiveAsOf
                              includeSuperseded = $effectiveInclude }
        try {
            $chunkVectorResponse = Invoke-PostJson -Path '/api/v1/search' `
                -Body ($chunkVectorBody | ConvertTo-Json -Compress)
            if ($null -ne $chunkVectorResponse.tookMs) {
                $chunkVectorLatencies.Add([double]$chunkVectorResponse.tookMs)
            }
            $chunkVectorRank = Find-ChunkRank -Results @($chunkVectorResponse.results) -Gold $row.gold
            $chunkVectorRanks.Add([int]$chunkVectorRank)
        } catch {
            $chunkVectorError = $true
            $chunkVectorErrorMessage = $_.Exception.Message
            $chunkVectorErrors++
        }
    }

    $pageLooseRank = 0
    $pageStrictRank = 0
    $pageLooseMatch = New-PageMatch -Rank 0 -Result $null
    $pageStrictMatch = New-PageMatch -Rank 0 -Result $null
    $pageError = $false
    $pageErrorMessage = ''
    $pageTopTitle = ''
    $pageTopLeaf = ''
    $pageTopClauses = @()
    $pageTopCitation = ''
    $pageServerMs = $null
    try {
        $pageResponse = Invoke-PostJson -Path '/api/v1/pages/search' -Body ($pageBody | ConvertTo-Json -Compress)
        if ($null -ne $pageResponse.tookMs) {
            $pageServerMs = [double]$pageResponse.tookMs
            $pageLatencies.Add($pageServerMs)
        }
        $pageResults = @($pageResponse.results)
        if ($pageResults.Count -gt 0 -and $null -ne $pageResults[0].citation) {
            $pageTopCitation = [string]$pageResults[0].citation
        }
        if ($pageResults.Count -gt 0) {
            $pageTopTitle = [string]$pageResults[0].title
            if ($null -ne $pageResults[0].leafClauseId) {
                $pageTopLeaf = [string]$pageResults[0].leafClauseId
            }
            if ($null -ne $pageResults[0].clauseIds) { $pageTopClauses = @($pageResults[0].clauseIds) }
        }
        if ($row.answerable -eq $true) {
            $pageLooseMatch = Find-PageMatch -Results $pageResults -Gold $row.gold -Mode 'loose'
            $pageStrictMatch = Find-PageMatch -Results $pageResults -Gold $row.gold -Mode 'strict'
            $pageLooseRank = $pageLooseMatch.rank
            $pageStrictRank = $pageStrictMatch.rank
        } elseif ($pageResults.Count -gt 0) {
            $pageReturnedUnanswerable++
        }
    } catch {
        $pageError = $true
        $pageErrorMessage = $_.Exception.Message
        $pageErrors++
    }

    if ($row.answerable -eq $true) {
        if (-not $chunkError) { $chunkRanks.Add([int]$chunkRank) }
        if (-not $pageError) {
            $pageLooseRanks.Add([int]$pageLooseRank)
            $pageStrictRanks.Add([int]$pageStrictRank)
        }
        if ($chunkAbstained) { $chunkAbstainedAnswerable++ }
    } elseif ($chunkAbstained) {
        $chunkAbstainedUnanswerable++
    }

    $chunkRankValue = $null
    $chunkVectorRankValue = $null
    $pageLooseRankValue = $null
    $pageStrictRankValue = $null
    $errorMessage = ''
    if (-not $chunkError) { $chunkRankValue = [int]$chunkRank }
    if ($row.answerable -eq $true -and -not $chunkVectorError) {
        $chunkVectorRankValue = [int]$chunkVectorRank
    }
    if (-not $pageError) {
        $pageLooseRankValue = [int]$pageLooseRank
        $pageStrictRankValue = [int]$pageStrictRank
    }
    if ($chunkError) { $errorMessage = $chunkErrorMessage } elseif ($pageError) { $errorMessage = $pageErrorMessage }

    $details += [pscustomobject]@{
        id = $row.id
        answerable = [bool]($row.answerable -eq $true)
        asOf = $effectiveAsOf
        includeSuperseded = $effectiveInclude
        chunkRank = $chunkRankValue
        chunkVectorRank = $chunkVectorRankValue
        pageLooseRank = $pageLooseRankValue
        pageStrictRank = $pageStrictRankValue
        chunkError = $chunkError
        pageError = $pageError
        errorMessage = $errorMessage
        chunkAbstained = $chunkAbstained
        chunkAbstainReason = $chunkAbstainReason
        chunkServerMs = $chunkServerMs
        pageServerMs = $pageServerMs
        chunkTopCitation = $chunkTopCitation
        pageTopTitle = $pageTopTitle
        pageTopLeafClauseId = $pageTopLeaf
        pageTopClauseIds = $pageTopClauses
        pageTopCitation = $pageTopCitation
        pageLooseMatchLeaf = $pageLooseMatch.leaf
        pageLooseMatchClauseIds = $pageLooseMatch.clauseIds
        pageLooseMatchPageNo = $pageLooseMatch.pageNo
        pageLooseMatchPageEnd = $pageLooseMatch.pageEnd
        pageLooseMatchCitation = $pageLooseMatch.citation
        pageStrictMatchLeaf = $pageStrictMatch.leaf
        pageStrictMatchCitation = $pageStrictMatch.citation
        pageMatchNote = Get-PageMatchNote $row
    }
}

$chunkMetrics = Measure-Ranks -Ranks $chunkRanks.ToArray() -ServerLatencies $chunkLatencies.ToArray()
$chunkVectorMetrics = Measure-Ranks -Ranks $chunkVectorRanks.ToArray() -ServerLatencies $chunkVectorLatencies.ToArray()
$pageLooseMetrics = Measure-Ranks -Ranks $pageLooseRanks.ToArray() -ServerLatencies $pageLatencies.ToArray()
$pageStrictMetrics = Measure-Ranks -Ranks $pageStrictRanks.ToArray() -ServerLatencies $pageLatencies.ToArray()

$answerableDetails = @($details | Where-Object { $_.answerable -eq $true })
$pageLooseBetter = @($answerableDetails | Where-Object {
    $null -ne $_.pageLooseRank -and $null -ne $_.chunkRank -and $_.pageLooseRank -gt 0 -and
    ($_.chunkRank -eq 0 -or $_.pageLooseRank -lt $_.chunkRank) })
$pageLooseWorse = @($answerableDetails | Where-Object {
    $null -ne $_.pageLooseRank -and $null -ne $_.chunkRank -and $_.chunkRank -gt 0 -and
    ($_.pageLooseRank -eq 0 -or $_.pageLooseRank -gt $_.chunkRank) })
$pageStrictBetter = @($answerableDetails | Where-Object {
    $null -ne $_.pageStrictRank -and $null -ne $_.chunkRank -and $_.pageStrictRank -gt 0 -and
    ($_.chunkRank -eq 0 -or $_.pageStrictRank -lt $_.chunkRank) })
$pageStrictWorse = @($answerableDetails | Where-Object {
    $null -ne $_.pageStrictRank -and $null -ne $_.chunkRank -and $_.chunkRank -gt 0 -and
    ($_.pageStrictRank -eq 0 -or $_.pageStrictRank -gt $_.chunkRank) })
$looseInflation = @($answerableDetails | Where-Object {
    $null -ne $_.pageLooseRank -and $null -ne $_.pageStrictRank -and
    $_.pageLooseRank -gt 0 -and $_.pageStrictRank -eq 0 })

# --- JSON report -----------------------------------------------------------------------------
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
    pageStats = $stats
    routes = [pscustomobject]@{
        chunk = [pscustomobject]@{ matching = 'eval.ps1 Find-Rank (docKey + clauseId, pageRange fallback)'
                                   metrics = $chunkMetrics; errors = $chunkErrors
                                   abstainedAnswerable = $chunkAbstainedAnswerable
                                   abstainedUnanswerable = $chunkAbstainedUnanswerable }
        chunkVectorOnly = [pscustomobject]@{ matching = 'eval.ps1 Find-Rank, strategy=vector (supplementary diagnostic, not the primary row)'
                                             metrics = $chunkVectorMetrics; errors = $chunkVectorErrors }
        pageLoose = [pscustomobject]@{ matching = 'gold.clauseId in page.clauseIds OR page span intersects gold.pageRange'
                                       metrics = $pageLooseMetrics; errors = $pageErrors }
        pageStrict = [pscustomobject]@{ matching = 'page.leafClauseId == gold.clauseId (pageRange fallback only when gold has no clauseId)'
                                        metrics = $pageStrictMetrics; errors = $pageErrors
                                        note = 'loose can inflate: a page covers many clauses; strict is the honest comparison' }
    }
    differences = [pscustomobject]@{
        pageLooseBetter = @($pageLooseBetter | ForEach-Object { $_.id })
        pageLooseWorse = @($pageLooseWorse | ForEach-Object { $_.id })
        pageStrictBetter = @($pageStrictBetter | ForEach-Object { $_.id })
        pageStrictWorse = @($pageStrictWorse | ForEach-Object { $_.id })
        looseHitStrictMiss = @($looseInflation | ForEach-Object { $_.id })
    }
    unanswerableDetail = [pscustomobject]@{
        pageReturnedNonEmpty = $pageReturnedUnanswerable
        chunkAbstained = $chunkAbstainedUnanswerable
        note = 'the page route has no abstention guard (Phase 10 is an A/B probe, not the product route)'
    }
    details = $details
}
Write-Utf8NoBom -Path $ReportJson -Text ($result | ConvertTo-Json -Depth 12)

# --- Markdown report -------------------------------------------------------------------------
$lines = @(
    '# Phase 10 验收报告：页面（page）粒度 vs 块（chunk）粒度检索 A/B',
    '',
    ('- 生成时间：{0}' -f (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')),
    ('- 分支/提交：`{0}` (dirty={1})' -f $gitCommit, $gitDirty),
    ('- 数据集：`{0}` (sha256={1})，{2} 条（{3} 可回答 + {4} 不可回答）' -f
        $QaPath, $datasetHash, $qaRows.Count, $answerableQuestions.Count, $unanswerableQuestions.Count),
    ('- 运行参数：topK={0}，默认 asOf={1}，includeSuperseded={2}，chunkStrategy={3}' -f
        $TopK, $AsOf, [bool]$IncludeSuperseded, $ChunkStrategy),
    '- 两路请求：chunk = `POST /api/v1/search`（hybrid + RRF + rerank，`chunkStrategy=heading`）；page = `POST /api/v1/pages/search`（页面向量路，余弦）',
    '- 数据集、gold、asOf、topK 完全相同；本脚本不修改 `eval/qa.jsonl` 或任何匹配规则。',
    '',
    '## 1. 匹配口径（两种页面口径并列，不挑有利的）',
    '',
    '| 路线 | 口径 | 规则 |',
    '|---|---|---|',
    '| chunk | eval.ps1 原规则 | `docKey` 相同，且 `clauseId == gold.clauseId`；结果无 `clauseId` 时用页码相交；不同 `clauseId` 视为未命中 |',
    '| page | **loose** | `gold.clauseId ∈ page.clauseIds` **或** 页面页码区间与 `gold.pageRange` 相交 |',
    '| page | **strict** | 页面自身叶子标题对应的 `leafClauseId == gold.clauseId`；仅当 gold 无 `clauseId` 时退化为页码相交 |',
    '',
    '**strict 比 loose 严格。** 页面天然覆盖更多 clause，loose 的"页面里恰好包含 gold clause"或"页码相交"都会命中，',
    '因此 loose 容易虚高；strict 要求页面自己的叶子 clause 就是 gold clause，不允许靠包含关系得分。',
    '两套口径使用同一批响应与同一批题目，下面并列展示。',
    '',
    '## 2. 页面统计（来自 `GET /api/v1/admin/pages/stats`）',
    '',
    '| docKey | version | status | 页面数（每文档） | 叶子小节数 | 平均 token | 最大 token |',
    '|---|---|---|---:|---:|---:|---:|'
)
foreach ($doc in @($stats.documents)) {
    $lines += ('| {0} | {1} | {2} | {3} | {4} | {5} | {6} |' -f
        (ConvertTo-MdCell $doc.docKey), (ConvertTo-MdCell $doc.version),
        (ConvertTo-MdCell $doc.status), $doc.pageCount, $doc.pageGroupCount,
        $doc.avgTokens, $doc.maxTokens)
}
$lines += ('| **合计** | | | **{0}** | **{1}** | **{2}** | **{3}** |' -f
    $stats.pageCount,
    (@($stats.documents) | Measure-Object -Property pageGroupCount -Sum).Sum,
    $stats.avgTokens,
    (@($stats.documents) | Measure-Object -Property maxTokens -Maximum).Maximum)
$lines += @(
    '',
    ('说明：页面数按行统计（`part` 切分后每行算一页）；叶子小节数是切分前的 `page_key` 数；平均 token = 总 token / 页面数。数据集共 {0} 篇文档、{1} 条页面。' -f
        $stats.documentCount, $stats.pageCount),
    '',
    '## 3. 四指标（同一 asOf、topK=5、同一 40 题）',
    '',
    '| 路线 | 口径 | 可回答题数 | hit@1 | hit@3 | hit@5 | MRR | p50(ms) | p95(ms) |',
    '|---|---|---:|---:|---:|---:|---:|---:|---:|'
)
$lines += ('| chunk | eval.ps1 原规则 | {0} | {1} | {2} | {3} | {4} | {5} | {6} |' -f
    $chunkMetrics.answered, $chunkMetrics.hitAt1, $chunkMetrics.hitAt3, $chunkMetrics.hitAt5,
    $chunkMetrics.mrr, $chunkMetrics.p50Ms, $chunkMetrics.p95Ms)
$lines += ('| chunk（**诊断用**：vector only） | eval.ps1 原规则 | {0} | {1} | {2} | {3} | {4} | {5} | {6} |' -f
    $chunkVectorMetrics.answered, $chunkVectorMetrics.hitAt1, $chunkVectorMetrics.hitAt3,
    $chunkVectorMetrics.hitAt5, $chunkVectorMetrics.mrr, $chunkVectorMetrics.p50Ms,
    $chunkVectorMetrics.p95Ms)
$lines += ('| page | loose | {0} | {1} | {2} | {3} | {4} | {5} | {6} |' -f
    $pageLooseMetrics.answered, $pageLooseMetrics.hitAt1, $pageLooseMetrics.hitAt3,
    $pageLooseMetrics.hitAt5, $pageLooseMetrics.mrr, $pageLooseMetrics.p50Ms, $pageLooseMetrics.p95Ms)
$lines += ('| page | **strict** | {0} | {1} | {2} | {3} | {4} | {5} | {6} |' -f
    $pageStrictMetrics.answered, $pageStrictMetrics.hitAt1, $pageStrictMetrics.hitAt3,
    $pageStrictMetrics.hitAt5, $pageStrictMetrics.mrr, $pageStrictMetrics.p50Ms, $pageStrictMetrics.p95Ms)
$lines += @(
    '',
    ('差值（page − chunk）：loose hit@1 {0:0.0000} / hit@3 {1:0.0000} / hit@5 {2:0.0000} / MRR {3:0.0000}；strict hit@1 {4:0.0000} / hit@3 {5:0.0000} / hit@5 {6:0.0000} / MRR {7:0.0000}。' -f
        ($pageLooseMetrics.hitAt1 - $chunkMetrics.hitAt1),
        ($pageLooseMetrics.hitAt3 - $chunkMetrics.hitAt3),
        ($pageLooseMetrics.hitAt5 - $chunkMetrics.hitAt5),
        ($pageLooseMetrics.mrr - $chunkMetrics.mrr),
        ($pageStrictMetrics.hitAt1 - $chunkMetrics.hitAt1),
        ($pageStrictMetrics.hitAt3 - $chunkMetrics.hitAt3),
        ($pageStrictMetrics.hitAt5 - $chunkMetrics.hitAt5),
        ($pageStrictMetrics.mrr - $chunkMetrics.mrr)),
    '',
    ('补充（不是主 A/B 行）：chunk 只走向量路时 hit@1={0} / hit@3={1} / hit@5={2} / MRR={3}。' -f
        $chunkVectorMetrics.hitAt1, $chunkVectorMetrics.hitAt3, $chunkVectorMetrics.hitAt5,
        $chunkVectorMetrics.mrr),
    '页面路由是纯向量路，主 chunk 行是 hybrid+RRF+rerank，所以 page−chunk 的差值同时包含"粒度"与"检索管线"两个因素；vector-only 这一行用于把粒度因素单独看出来。',
    '',
    '## 4. 逐题差异（只列与 chunk 不同的题）',
    '',
    '| 题 | gold | chunk 名次 | page loose 名次 | page strict 名次 | loose vs chunk | strict vs chunk |',
    '|---|---|---:|---:|---:|---|---|'
)
foreach ($d in $answerableDetails) {
    if ($d.chunkError -or $d.pageError) { continue }
    $looseDelta = Format-Delta -BaseRank ([int]$d.chunkRank) -PageRank ([int]$d.pageLooseRank)
    $strictDelta = Format-Delta -BaseRank ([int]$d.chunkRank) -PageRank ([int]$d.pageStrictRank)
    if ($looseDelta.Length -eq 0 -and $strictDelta.Length -eq 0) { continue }
    $lines += ('| {0} | {1} | {2} | {3} | {4} | {5} | {6} |' -f
        $d.id, (ConvertTo-MdCell $d.pageMatchNote), $d.chunkRank, $d.pageLooseRank,
        $d.pageStrictRank, (ConvertTo-MdCell $looseDelta), (ConvertTo-MdCell $strictDelta))
}
if (@($answerableDetails | Where-Object {
            $null -ne $_.chunkRank -and $null -ne $_.pageLooseRank -and
            $_.chunkRank -eq $_.pageLooseRank -and $_.chunkRank -eq $_.pageStrictRank }).Count -eq $answerableDetails.Count) {
    $lines += '| （无） | | | | | | |'
}
$lines += @(
    '',
    ('汇总：loose 更好 {0} 题、更差 {1} 题；strict 更好 {2} 题、更差 {3} 题；loose 命中但 strict 未命中的题：{4}。' -f
        $pageLooseBetter.Count, $pageLooseWorse.Count, $pageStrictBetter.Count, $pageStrictWorse.Count,
        (($looseInflation | ForEach-Object { $_.id }) -join ', ')),
    '',
    '### 4.1 loose 虚高（loose 命中、strict 未命中）明细',
    '',
    '| 题 | gold | loose 名次 | strict 名次 | loose 命中页自身 leafClauseId | loose 命中页 clauseIds | loose 命中页页码 |',
    '|---|---|---:|---:|---|---|---|'
)
if ($looseInflation.Count -eq 0) {
    $lines += '| （无） | | | | | | |'
} else {
    foreach ($d in $looseInflation) {
        $ids = (@($d.pageLooseMatchClauseIds) | ForEach-Object { [string]$_ }) -join ', '
        $pageSpan = ''
        if ($null -ne $d.pageLooseMatchPageNo) {
            $pageSpan = [string]$d.pageLooseMatchPageNo
            if ($null -ne $d.pageLooseMatchPageEnd -and $d.pageLooseMatchPageEnd -gt $d.pageLooseMatchPageNo) {
                $pageSpan = "$($d.pageLooseMatchPageNo)-$($d.pageLooseMatchPageEnd)"
            }
        }
        $lines += ('| {0} | {1} | {2} | {3} | {4} | {5} | {6} |' -f
            $d.id, (ConvertTo-MdCell $d.pageMatchNote), $d.pageLooseRank, $d.pageStrictRank,
            (ConvertTo-MdCell $d.pageLooseMatchLeaf), (ConvertTo-MdCell $ids),
            (ConvertTo-MdCell $pageSpan))
    }
}
$lines += @(
    '',
    '## 5. 不可回答题（单独报告，不计入 hit@k）',
    '',
    ('- chunk 路：{0}/5 题被拒答（abstain），其中可回答题被误拒答 {1} 题。' -f
        $chunkAbstainedUnanswerable, $chunkAbstainedAnswerable),
    ('- page 路：{0}/5 题返回了非空页面结果。页面端点**没有拒答守卫**，这是刻意的：本阶段只回答"页面粒度检索是否更好"，' -f
        $pageReturnedUnanswerable),
    '  不把页面层接入主检索，也不改动 Phase 7 的拒答契约。页面路由一旦产品化，必须补同样的拒答策略。',
    '',
    '## 6. 自动结论（数据事实）',
    ''
)
$strictWorse = ($pageStrictMetrics.hitAt5 -lt $chunkMetrics.hitAt5) -or ($pageStrictMetrics.mrr -lt $chunkMetrics.mrr)
$looseBetter = ($pageLooseMetrics.hitAt5 -gt $chunkMetrics.hitAt5) -or ($pageLooseMetrics.mrr -gt $chunkMetrics.mrr)
$lines += ('- chunk（heading，hybrid+RRF+rerank）：hit@1={0}、hit@3={1}、hit@5={2}、MRR={3}。' -f
    $chunkMetrics.hitAt1, $chunkMetrics.hitAt3, $chunkMetrics.hitAt5, $chunkMetrics.mrr)
$lines += ('- page（loose）：hit@1={0}、hit@3={1}、hit@5={2}、MRR={3}。' -f
    $pageLooseMetrics.hitAt1, $pageLooseMetrics.hitAt3, $pageLooseMetrics.hitAt5, $pageLooseMetrics.mrr)
$lines += ('- page（strict）：hit@1={0}、hit@3={1}、hit@5={2}、MRR={3}。' -f
    $pageStrictMetrics.hitAt1, $pageStrictMetrics.hitAt3, $pageStrictMetrics.hitAt5, $pageStrictMetrics.mrr)
if ($looseBetter) {
    $lines += '- loose 口径下页面至少有一项指标高于 chunk；这不等于页面粒度更好，见 4.1 的包含/页码相交虚高。'
} else {
    $lines += '- loose 口径下页面也没有超过 chunk。'
}
if ($strictWorse) {
    $lines += '- **strict 口径下页面明显更差**：hit@5 与/或 MRR 低于 chunk。按验收要求如实报告，不做美化；原因分析见第 7 节。'
} else {
    $lines += '- strict 口径下页面没有明显更差（hit@5 与 MRR 均不低于 chunk）。'
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
        '## 7. 结论、原因分析与风险',
        '',
        '（人工分析待补：在本次运行数据上填写结论、strict 变差的原因、以及页面层的关键风险。）'
    )
}
Write-Utf8NoBom -Path $ReportPath -Text (($lines -join "`r`n") + "`r`n")

$httpClient.Dispose()
Write-Host ("chunk : hit@1={0} hit@3={1} hit@5={2} MRR={3}" -f
    $chunkMetrics.hitAt1, $chunkMetrics.hitAt3, $chunkMetrics.hitAt5, $chunkMetrics.mrr)
Write-Host ("page  loose : hit@1={0} hit@3={1} hit@5={2} MRR={3}" -f
    $pageLooseMetrics.hitAt1, $pageLooseMetrics.hitAt3, $pageLooseMetrics.hitAt5, $pageLooseMetrics.mrr)
Write-Host ("page  strict: hit@1={0} hit@3={1} hit@5={2} MRR={3}" -f
    $pageStrictMetrics.hitAt1, $pageStrictMetrics.hitAt3, $pageStrictMetrics.hitAt5, $pageStrictMetrics.mrr)
Write-Host "report: $ReportPath"
Write-Host "json:   $ReportJson"

if ($chunkErrors -gt 0 -or $pageErrors -gt 0 -or $chunkVectorErrors -gt 0) { exit 1 }
exit 0
