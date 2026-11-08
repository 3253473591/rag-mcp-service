[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$Container = 'rag-postgres',
    [string]$DbUser = 'rag',
    [string]$DbName = 'ragdb',
    [int]$ChunksPerStrategy = 10000,
    [int]$Concurrency = 10,
    [int]$DurationSeconds = 60,
    [string]$ChunkStrategy = 'heading',
    [string]$SearchStrategy = 'hybrid',
    [string]$ReportJson = 'docs/acceptance/phase3-concurrency.json',
    [switch]$KeepData
)

# T-PERF-02: 10 concurrent clients for 60s against 10k chunks per strategy.
# Windows PowerShell 5.1 has no ForEach-Object -Parallel, so each client is a Start-Job
# process using a reusable HttpClient. Server tookMs and client wall-clock are both recorded.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

Add-Type -AssemblyName System.Net.Http

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

function Invoke-Warmup {
    param([string]$Strategy, [string]$AsOf, [string]$SearchStrategy)
    $client = New-Object System.Net.Http.HttpClient
    $client.Timeout = [TimeSpan]::FromSeconds(60)
    # Warm HNSW pages + Ollama before the measured window. The first Phase 3 cold run had
    # p95=808ms; the same run after warm-up was ~550ms. Warm-up is not measured.
    for ($i = 0; $i -lt 50; $i++) {
        $body = (@{ query = 'concurrency warmup'; topK = 5; chunkStrategy = $Strategy;
                    strategy = $SearchStrategy; asOf = $AsOf } | ConvertTo-Json -Compress)
        $content = New-Object System.Net.Http.StringContent($body, [System.Text.Encoding]::UTF8, 'application/json')
        $client.PostAsync("$BaseUrl/api/v1/search", $content).GetAwaiter().GetResult() | Out-Null
    }
    $client.Dispose()
}

$health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 10
if ($health.status -ne 'UP') { throw "app is not healthy: $($health.status)" }
$embeddingHealth = $health.components.embedding.status
if ($embeddingHealth -ne 'UP') { throw "embedding is not UP: $embeddingHealth" }

$vectorVersion = Invoke-Psql "SELECT extversion FROM pg_extension WHERE extname='vector';"
$existing = [int](Invoke-Psql "SELECT count(*) FROM kb_chunk;")
if ($existing -lt 1) { throw "no existing chunks; ingest docs/samples first" }
$gitCommit = (& git rev-parse HEAD 2>$null)
if (-not $gitCommit) { $gitCommit = 'unknown' }
$asOf = Get-Date -Format 'yyyy-MM-dd'

Invoke-Psql "DELETE FROM kb_document WHERE doc_key='perf-synthetic';" | Out-Null
Invoke-Psql "VACUUM (ANALYZE) kb_chunk;" | Out-Null
$docId = [guid]::NewGuid().ToString()
$checksum = ([guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N'))

Invoke-Psql @"
INSERT INTO kb_document
  (id, doc_key, title, source_type, source_uri, checksum, version, embedding_model,
   effective_from, effective_to, status, metadata)
VALUES
  ('$docId'::uuid, 'perf-synthetic', 'Perf synthetic corpus', 'TXT', 'synthetic://perf',
   '$checksum', 'perf', 'bge-m3', DATE '2020-01-01', NULL, 'ACTIVE', '{}'::jsonb);
"@ | Out-Null

$worker = {
    param($BaseUrl, $DurationSeconds, $Strategy, $AsOf, $Worker, $Root, $SearchStrategy)
    Add-Type -AssemblyName System.Net.Http
    $client = New-Object System.Net.Http.HttpClient
    $client.Timeout = [TimeSpan]::FromSeconds(30)
    $samples = New-Object System.Collections.Generic.List[object]
    $errors = 0
    $messages = New-Object System.Collections.Generic.List[string]
    $deadline = (Get-Date).AddSeconds($DurationSeconds)
    $i = 0
    while ((Get-Date) -lt $deadline) {
        $i++
        $body = (@{ query = "concurrency worker $Worker request $i"; topK = 5;
                    chunkStrategy = $Strategy; strategy = $SearchStrategy; asOf = $AsOf } | ConvertTo-Json -Compress)
        $content = New-Object System.Net.Http.StringContent($body, [System.Text.Encoding]::UTF8, 'application/json')
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        try {
            $response = $client.PostAsync("$BaseUrl/api/v1/search", $content).GetAwaiter().GetResult()
            $bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
            $sw.Stop()
            if (-not $response.IsSuccessStatusCode) {
                $errors++
                if ($messages.Count -lt 5) { $messages.Add("HTTP $([int]$response.StatusCode)") }
                continue
            }
            $json = ([System.Text.Encoding]::UTF8.GetString($bytes)) | ConvertFrom-Json
            $samples.Add([pscustomobject]@{
                clientMs = [Math]::Round($sw.Elapsed.TotalMilliseconds, 2)
                serverMs = [double]$json.tookMs
                resultCount = @($json.results).Count
            })
        } catch {
            $sw.Stop()
            $errors++
            if ($messages.Count -lt 5) { $messages.Add($_.Exception.Message) }
        }
    }
    $client.Dispose()
    $path = Join-Path $Root (".tmp/concurrency-worker-{0}.json" -f $Worker)
    # ConvertTo-Json in a background job cannot serialize a generic List directly; materialize
    # arrays first (otherwise the job writes an empty file with "参数类型不匹配").
    $result = [pscustomobject]@{ worker = $Worker; samples = $samples.ToArray();
        errors = $errors; messages = $messages.ToArray() }
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($path, ($result | ConvertTo-Json -Depth 6), $encoding)
    return $path
}

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
    Write-Host ("inserted {0} chunks/strategy in {1:N1}s" -f
        $ChunksPerStrategy, ((Get-Date) - $insertStarted).TotalSeconds)

    $counts = Invoke-Psql @"
SELECT chunk_strategy || '=' || count(*) FROM kb_chunk
WHERE document_id = '$docId'::uuid GROUP BY chunk_strategy ORDER BY chunk_strategy;
"@
    Write-Host "chunk counts: $counts"
    if ($counts -notmatch "fixed=$ChunksPerStrategy" -or $counts -notmatch "heading=$ChunksPerStrategy") {
        throw "synthetic chunk count mismatch: $counts"
    }

    Invoke-Warmup -Strategy $ChunkStrategy -AsOf $asOf -SearchStrategy $SearchStrategy
    Get-ChildItem .tmp/concurrency-worker-*.json -ErrorAction SilentlyContinue | Remove-Item -Force

    $jobs = @()
    for ($workerId = 1; $workerId -le $Concurrency; $workerId++) {
        $jobs += Start-Job -ScriptBlock $worker -ArgumentList @(
            $BaseUrl, $DurationSeconds, $ChunkStrategy, $asOf, $workerId, $root, $SearchStrategy)
    }
    Write-Host ("started {0} jobs for {1}s, chunkStrategy={2}, searchStrategy={3}" -f
        $Concurrency, $DurationSeconds, $ChunkStrategy, $SearchStrategy)
    $jobs | Wait-Job | Out-Null
    foreach ($job in $jobs) {
        if ($job.State -eq 'Failed') {
            Write-Warning ("worker job failed: " + ($job.ChildJobs[0].JobStateInfo.Reason))
        }
    }
    $jobs | Remove-Job -Force

    $files = @(Get-ChildItem .tmp/concurrency-worker-*.json -ErrorAction Stop)
    if ($files.Count -ne $Concurrency) {
        throw ("expected {0} worker result files, got {1}" -f $Concurrency, $files.Count)
    }

    $clientSamples = New-Object System.Collections.Generic.List[double]
    $serverSamples = New-Object System.Collections.Generic.List[double]
    $resultCounts = New-Object System.Collections.Generic.List[int]
    $totalErrors = 0
    $workerReports = @()
    foreach ($file in $files) {
        $data = Get-Content -LiteralPath $file.FullName -Encoding UTF8 | ConvertFrom-Json
        $workerClient = @()
        $workerServer = @()
        foreach ($sample in @($data.samples)) {
            $clientSamples.Add([double]$sample.clientMs)
            $serverSamples.Add([double]$sample.serverMs)
            $resultCounts.Add([int]$sample.resultCount)
            $workerClient += [double]$sample.clientMs
            $workerServer += [double]$sample.serverMs
        }
        $totalErrors += [int]$data.errors
        [double[]]$wc = $workerClient
        [double[]]$ws = $workerServer
        $workerReports += [pscustomobject]@{
            worker = $data.worker
            requests = @($data.samples).Count
            errors = $data.errors
            clientP95Ms = Get-Percentile $wc 0.95
            serverP95Ms = Get-Percentile $ws 0.95
            messages = $data.messages
        }
        Remove-Item -LiteralPath $file.FullName -Force
    }

    $totalRequests = $clientSamples.Count + $totalErrors
    $client = [double[]]$clientSamples.ToArray()
    $server = [double[]]$serverSamples.ToArray()
    $counts = [int[]]$resultCounts.ToArray()
    $summary = [pscustomobject]@{
        timestamp = (Get-Date).ToString('o')
        gitCommit = $gitCommit
        pgvectorVersion = $vectorVersion
        baseUrl = $BaseUrl
        concurrency = $Concurrency
        durationSeconds = $DurationSeconds
        chunkStrategy = $ChunkStrategy
        searchStrategy = $SearchStrategy
        warmupRequests = 50
        chunksPerStrategy = $ChunksPerStrategy
        totalSyntheticChunks = $ChunksPerStrategy * 2
        requests = $totalRequests
        successes = $clientSamples.Count
        errors = $totalErrors
        errorRate = if ($totalRequests -gt 0) { [Math]::Round($totalErrors / [double]$totalRequests, 6) } else { 1 }
        clientP50Ms = Get-Percentile $client 0.50
        clientP95Ms = Get-Percentile $client 0.95
        clientP99Ms = Get-Percentile $client 0.99
        clientMaxMs = if ($client.Count) { [Math]::Round(($client | Measure-Object -Maximum).Maximum, 2) } else { $null }
        serverP50Ms = Get-Percentile $server 0.50
        serverP95Ms = Get-Percentile $server 0.95
        serverP99Ms = Get-Percentile $server 0.99
        serverMaxMs = if ($server.Count) { [Math]::Round(($server | Measure-Object -Maximum).Maximum, 2) } else { $null }
        avgResults = if ($counts.Count) { [Math]::Round(($counts | Measure-Object -Average).Average, 2) } else { 0 }
        minResults = if ($counts.Count) { ($counts | Measure-Object -Minimum).Minimum } else { 0 }
        maxResults = if ($counts.Count) { ($counts | Measure-Object -Maximum).Maximum } else { 0 }
        workers = $workerReports
        note = '10 concurrent PowerShell worker processes, each with a reusable HttpClient; errors exclude non-2xx and exceptions. clientMs is end-to-end; serverMs is app tookMs (query embedding + SQL + RRF). Synthetic vectors are cycled from the real chunk pool; this measures latency, not retrieval quality.'
    }
    $dir = Split-Path -Parent $ReportJson
    if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force $dir | Out-Null }
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText((Join-Path (Get-Location) $ReportJson),
        ($summary | ConvertTo-Json -Depth 8), $encoding)

    Write-Host ("concurrency={0} duration={1}s requests={2} errors={3} errorRate={4}" -f
        $Concurrency, $DurationSeconds, $totalRequests, $totalErrors, $summary.errorRate)
    Write-Host ("client p50={0} p95={1} p99={2} max={3}; server p50={4} p95={5} p99={6} max={7}" -f
        $summary.clientP50Ms, $summary.clientP95Ms, $summary.clientP99Ms, $summary.clientMaxMs,
        $summary.serverP50Ms, $summary.serverP95Ms, $summary.serverP99Ms, $summary.serverMaxMs)
    Write-Host "report: $ReportJson"
} finally {
    if (-not $KeepData) {
        Invoke-Psql "DELETE FROM kb_document WHERE doc_key='perf-synthetic';" | Out-Null
        Write-Host "synthetic perf data removed"
    }
    Get-ChildItem .tmp/concurrency-worker-*.json -ErrorAction SilentlyContinue | Remove-Item -Force
}
