[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$Container = 'rag-postgres',
    [string]$DbUser = 'rag',
    [string]$DbName = 'ragdb',
    [int]$ChunksPerStrategy = 10000,
    [int]$RequestsPerStrategy = 50,
    [string]$ReportJson = 'docs/acceptance/phase2-perf.json',
    [switch]$KeepData
)

# Reproducible retrieval performance probe for docs/05-non-functional.md 2.1.
# It inserts synthetic chunks directly (no embedding cost) and measures the real HTTP API,
# including query embedding via Ollama. This script is NOT part of CI.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Invoke-Psql {
    param([string]$Sql)
    $out = & docker exec $Container psql -U $DbUser -d $DbName -t -A -v ON_ERROR_STOP=1 -c $Sql 2>&1
    if ($LASTEXITCODE -ne 0) { throw (($out | Out-String).Trim()) }
    return (($out | Out-String).Trim())
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
$embeddingHealth = $health.components.embedding.status
if ($embeddingHealth -ne 'UP') { throw "embedding is not UP: $embeddingHealth" }

$vectorVersion = Invoke-Psql "SELECT extversion FROM pg_extension WHERE extname='vector';"
$existing = [int](Invoke-Psql "SELECT count(*) FROM kb_chunk;")
Write-Host "pgvector=$vectorVersion existingChunks=$existing"

Invoke-Psql "DELETE FROM kb_document WHERE doc_key='perf-synthetic';" | Out-Null
$docId = [guid]::NewGuid().ToString()
$checksum = ([guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N'))
$today = Get-Date -Format 'yyyy-MM-dd'

Invoke-Psql @"
INSERT INTO kb_document
  (id, doc_key, title, source_type, source_uri, checksum, version, embedding_model,
   effective_from, effective_to, status, metadata)
VALUES
  ('$docId'::uuid, 'perf-synthetic', 'Perf synthetic corpus', 'TXT', 'synthetic://perf',
   '$checksum', 'perf', 'bge-m3', DATE '2020-01-01', NULL, 'ACTIVE', '{}'::jsonb);
"@ | Out-Null

try {
    $seedSubquery = @"
COALESCE(
  (SELECT embedding FROM kb_chunk WHERE embedding IS NOT NULL LIMIT 1),
  array_prepend(1.0::real, array_fill(0.0::real, ARRAY[1023]))::vector
)
"@
    foreach ($strategy in @('fixed', 'heading')) {
        $started = Get-Date
        Invoke-Psql @"
INSERT INTO kb_chunk
  (id, document_id, chunk_index, chunk_strategy, content, token_count,
   clause_id, clause_path, page_no, metadata, embedding)
SELECT gen_random_uuid(), '$docId'::uuid, i, '$strategy',
       'perf synthetic chunk ' || i || ' hypertension internal-control retrieval-latency',
       20, NULL, 'perf > section ' || (i + 1), (i % 500) + 1, '{}'::jsonb,
       $seedSubquery
FROM generate_series(0, $($ChunksPerStrategy - 1)) AS s(i);
"@ | Out-Null
        Write-Host ("inserted {0} chunks strategy={1} in {2:N1}s" -f
            $ChunksPerStrategy, $strategy, ((Get-Date) - $started).TotalSeconds)
    }

    $counts = Invoke-Psql @"
SELECT chunk_strategy || '=' || count(*) FROM kb_chunk
WHERE document_id = '$docId'::uuid GROUP BY chunk_strategy ORDER BY chunk_strategy;
"@
    Write-Host "chunk counts: $counts"
    if ($counts -notmatch "fixed=$ChunksPerStrategy" -or $counts -notmatch "heading=$ChunksPerStrategy") {
        throw "synthetic chunk count mismatch: $counts"
    }

    $runs = @()
    foreach ($strategy in @('fixed', 'heading')) {
        # Warm-up, not measured.
        for ($i = 0; $i -lt 5; $i++) {
            $body = (@{ query = 'perf retrieval latency'; topK = 5; chunkStrategy = $strategy; strategy = 'hybrid'; asOf = $today } | ConvertTo-Json -Compress)
            Invoke-RestMethod -Uri "$BaseUrl/api/v1/search" -Method Post `
                -ContentType 'application/json; charset=utf-8' `
                -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 30 | Out-Null
        }

        $latencies = New-Object System.Collections.Generic.List[double]
        $errors = 0
        $resultCounts = New-Object System.Collections.Generic.List[int]
        for ($i = 0; $i -lt $RequestsPerStrategy; $i++) {
            $body = (@{ query = "perf retrieval latency $i"; topK = 5; chunkStrategy = $strategy; strategy = 'hybrid'; asOf = $today } | ConvertTo-Json -Compress)
            $sw = [System.Diagnostics.Stopwatch]::StartNew()
            try {
                $response = Invoke-RestMethod -Uri "$BaseUrl/api/v1/search" -Method Post `
                    -ContentType 'application/json; charset=utf-8' `
                    -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 30
                $latencies.Add($sw.Elapsed.TotalMilliseconds)
                $resultCounts.Add(@($response.results).Count)
            } catch {
                $errors++
            } finally {
                $sw.Stop()
            }
        }
        [double[]]$arr = $latencies.ToArray()
        $runs += [pscustomobject]@{
            chunkStrategy = $strategy
            chunks = $ChunksPerStrategy
            requests = $RequestsPerStrategy
            errors = $errors
            errorRate = [Math]::Round($errors / [double]$RequestsPerStrategy, 4)
            p50Ms = (Get-Percentile $arr 0.50)
            p95Ms = (Get-Percentile $arr 0.95)
            p99Ms = (Get-Percentile $arr 0.99)
            minMs = if ($arr.Count) { [Math]::Round(($arr | Measure-Object -Minimum).Minimum, 2) } else { $null }
            maxMs = if ($arr.Count) { [Math]::Round(($arr | Measure-Object -Maximum).Maximum, 2) } else { $null }
            avgResults = if ($resultCounts.Count) { [Math]::Round(($resultCounts | Measure-Object -Average).Average, 2) } else { 0 }
        }
        Write-Host ("{0}: p50={1}ms p95={2}ms p99={3}ms errors={4}" -f
            $strategy, $runs[-1].p50Ms, $runs[-1].p95Ms, $runs[-1].p99Ms, $errors)
    }

    $report = [pscustomobject]@{
        timestamp = (Get-Date).ToString('o')
        baseUrl = $BaseUrl
        pgvectorVersion = $vectorVersion
        gitCommit = (& git rev-parse HEAD)
        chunksPerStrategy = $ChunksPerStrategy
        totalSyntheticChunks = $ChunksPerStrategy * 2
        existingChunksBeforeTest = $existing
        requestsPerStrategy = $RequestsPerStrategy
        topK = 5
        strategy = 'hybrid'
        embedding = 'bge-m3'
        runs = $runs
        note = 'Numbers are measured on the local machine; perf is not part of CI. Synthetic vectors reuse one existing embedding, so this measures SQL/HTTP/RRF latency, not retrieval quality.'
    }
    $dir = Split-Path -Parent $ReportJson
    if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force $dir | Out-Null }
    $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $ReportJson -Encoding UTF8
    Write-Host "report: $ReportJson"
} finally {
    if (-not $KeepData) {
        Invoke-Psql "DELETE FROM kb_document WHERE doc_key='perf-synthetic';" | Out-Null
        Write-Host "synthetic perf data removed"
    }
}
