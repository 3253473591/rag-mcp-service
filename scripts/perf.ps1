[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$Container = 'rag-postgres',
    [string]$DbUser = 'rag',
    [string]$DbName = 'ragdb',
    [int]$ChunksPerStrategy = 10000,
    [int]$RequestsPerStrategy = 50,
    [int]$Rounds = 1,
    [string]$ReportJson = 'docs/acceptance/phase3-perf.json',
    [switch]$KeepData
)

# Reproducible retrieval performance probe for docs/05-non-functional.md 2.1.
# Inserts synthetic chunks directly (no embedding cost) and measures the real HTTP API,
# including query embedding via Ollama. Multiple rounds run against the same inserted data.
# This script is NOT part of CI.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

Add-Type -AssemblyName System.Net.Http
$httpClient = New-Object System.Net.Http.HttpClient
$httpClient.Timeout = [TimeSpan]::FromSeconds(60)

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

function Invoke-Search {
    param([string]$Body)
    $content = New-Object System.Net.Http.StringContent($Body, [System.Text.Encoding]::UTF8, 'application/json')
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $response = $httpClient.PostAsync("$BaseUrl/api/v1/search", $content).GetAwaiter().GetResult()
    $bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
    $sw.Stop()
    $text = [System.Text.Encoding]::UTF8.GetString($bytes)
    if (-not $response.IsSuccessStatusCode) {
        throw ("HTTP {0}: {1}" -f [int]$response.StatusCode, $text)
    }
    $json = $text | ConvertFrom-Json
    return [pscustomobject]@{
        clientMs = $sw.Elapsed.TotalMilliseconds
        serverMs = [double]$json.tookMs
        resultCount = @($json.results).Count
    }
}

function Invoke-Warmup {
    param([string]$Strategy, [string]$AsOf)
    for ($i = 0; $i -lt 10; $i++) {
        $body = (@{ query = 'perf retrieval latency warmup'; topK = 5; chunkStrategy = $Strategy;
                    strategy = 'hybrid'; asOf = $AsOf } | ConvertTo-Json -Compress)
        Invoke-Search -Body $body | Out-Null
    }
}

$health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 10
if ($health.status -ne 'UP') { throw "app is not healthy: $($health.status)" }
$embeddingHealth = $health.components.embedding.status
if ($embeddingHealth -ne 'UP') { throw "embedding is not UP: $embeddingHealth" }

$vectorVersion = Invoke-Psql "SELECT extversion FROM pg_extension WHERE extname='vector';"
$existing = [int](Invoke-Psql "SELECT count(*) FROM kb_chunk;")
if ($existing -lt 1) { throw "no existing chunks; ingest docs/samples first (or use a seed corpus)" }
Write-Host "pgvector=$vectorVersion existingChunks=$existing rounds=$Rounds"
$gitCommit = (& git rev-parse HEAD 2>$null)
if (-not $gitCommit) { $gitCommit = 'unknown' }

Invoke-Psql "DELETE FROM kb_document WHERE doc_key='perf-synthetic';" | Out-Null
# Remove dead tuples from a previous run before measuring; an autovacuum triggered mid-round
# is a real source of p99 variance (seen in the first Phase 3 attempt).
Invoke-Psql "VACUUM (ANALYZE) kb_chunk;" | Out-Null
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
    $insertStarted = Get-Date
    foreach ($strategy in @('fixed', 'heading')) {
        Invoke-Psql @"
WITH seeds AS (
  SELECT embedding,
         (row_number() OVER (ORDER BY id)) - 1 AS rn,
         count(*) OVER () AS n
  FROM kb_chunk
  WHERE embedding IS NOT NULL
)
INSERT INTO kb_chunk
  (id, document_id, chunk_index, chunk_strategy, content, token_count,
   clause_id, clause_path, page_no, metadata, embedding)
SELECT gen_random_uuid(), '$docId'::uuid, g.i, '$strategy',
       'perf synthetic chunk ' || g.i || ' hypertension internal-control retrieval-latency',
       20, NULL, 'perf > section ' || (g.i + 1), (g.i % 500) + 1, '{}'::jsonb,
       seeds.embedding
FROM generate_series(0, $($ChunksPerStrategy - 1)) AS g(i)
JOIN seeds ON seeds.rn = (g.i % seeds.n);
"@ | Out-Null
    }
    Invoke-Psql "VACUUM (ANALYZE) kb_chunk;" | Out-Null
    Write-Host ("inserted {0} chunks/strategy (total synthetic {1}) in {2:N1}s" -f
        $ChunksPerStrategy, ($ChunksPerStrategy * 2), ((Get-Date) - $insertStarted).TotalSeconds)

    $counts = Invoke-Psql @"
SELECT chunk_strategy || '=' || count(*) FROM kb_chunk
WHERE document_id = '$docId'::uuid GROUP BY chunk_strategy ORDER BY chunk_strategy;
"@
    Write-Host "chunk counts: $counts"
    if ($counts -notmatch "fixed=$ChunksPerStrategy" -or $counts -notmatch "heading=$ChunksPerStrategy") {
        throw "synthetic chunk count mismatch: $counts"
    }

    $runs = @()
    for ($round = 1; $round -le $Rounds; $round++) {
        foreach ($strategy in @('fixed', 'heading')) {
            Invoke-Warmup -Strategy $strategy -AsOf $today

            $clientSamples = New-Object System.Collections.Generic.List[double]
            $serverSamples = New-Object System.Collections.Generic.List[double]
            $errors = 0
            $resultCounts = New-Object System.Collections.Generic.List[int]
            for ($i = 0; $i -lt $RequestsPerStrategy; $i++) {
                $body = (@{ query = "perf retrieval latency $round-$i"; topK = 5; chunkStrategy = $strategy;
                            strategy = 'hybrid'; asOf = $today } | ConvertTo-Json -Compress)
                try {
                    $sample = Invoke-Search -Body $body
                    $clientSamples.Add($sample.clientMs)
                    $serverSamples.Add($sample.serverMs)
                    $resultCounts.Add($sample.resultCount)
                } catch {
                    $errors++
                }
            }
            $client = [double[]]$clientSamples.ToArray()
            $server = [double[]]$serverSamples.ToArray()
            $run = [pscustomobject]@{
                round = $round
                chunkStrategy = $strategy
                chunks = $ChunksPerStrategy
                requests = $RequestsPerStrategy
                errors = $errors
                errorRate = [Math]::Round($errors / [double]$RequestsPerStrategy, 4)
                # p50/p95/p99 are client wall-clock end-to-end (the phase 2口径).
                p50Ms = Get-Percentile $client 0.50
                p95Ms = Get-Percentile $client 0.95
                p99Ms = Get-Percentile $client 0.99
                minMs = if ($client.Count) { [Math]::Round(($client | Measure-Object -Minimum).Minimum, 2) } else { $null }
                maxMs = if ($client.Count) { [Math]::Round(($client | Measure-Object -Maximum).Maximum, 2) } else { $null }
                # Server-side tookMs isolates embedding + SQL + RRF from local HTTP overhead.
                serverP50Ms = Get-Percentile $server 0.50
                serverP95Ms = Get-Percentile $server 0.95
                serverP99Ms = Get-Percentile $server 0.99
                serverMaxMs = if ($server.Count) { [Math]::Round(($server | Measure-Object -Maximum).Maximum, 2) } else { $null }
                avgResults = if ($resultCounts.Count) { [Math]::Round(($resultCounts | Measure-Object -Average).Average, 2) } else { 0 }
            }
            $runs += $run
            Write-Host ("round {0} {1}: p50={2}ms p95={3}ms p99={4}ms serverP95={5}ms serverP99={6}ms errors={7}" -f
                $round, $strategy, $run.p50Ms, $run.p95Ms, $run.p99Ms,
                $run.serverP95Ms, $run.serverP99Ms, $errors)
        }
    }

    $report = [pscustomobject]@{
        timestamp = (Get-Date).ToString('o')
        baseUrl = $BaseUrl
        pgvectorVersion = $vectorVersion
        gitCommit = $gitCommit
        rounds = $Rounds
        chunksPerStrategy = $ChunksPerStrategy
        totalSyntheticChunks = $ChunksPerStrategy * 2
        existingChunksBeforeTest = $existing
        requestsPerStrategy = $RequestsPerStrategy
        topK = 5
        strategy = 'hybrid'
        embedding = 'bge-m3'
        runs = $runs
        note = 'p50/p95/p99 = client end-to-end wall clock. serverP50/P95/P99 = app tookMs (query embedding + SQL + RRF), used to separate local HTTP/client jitter. Synthetic vectors are cycled from the existing real chunk embedding pool, so this measures SQL/HTTP/RRF latency, not retrieval quality. Each round/strategy has 10 unmeasured warm-up requests.'
    }
    $dir = Split-Path -Parent $ReportJson
    if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force $dir | Out-Null }
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText((Join-Path (Get-Location) $ReportJson),
        ($report | ConvertTo-Json -Depth 8), $encoding)
    Write-Host "report: $ReportJson"
} finally {
    $httpClient.Dispose()
    if (-not $KeepData) {
        Invoke-Psql "DELETE FROM kb_document WHERE doc_key='perf-synthetic';" | Out-Null
        Write-Host "synthetic perf data removed"
    }
}
