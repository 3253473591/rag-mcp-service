[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$DbUser = 'rag',
    [string]$DbName = 'ragdb',
    [string]$Container = 'rag-postgres',
    [string]$ReportPath = 'docs/acceptance/phase1-last-run.md',
    [switch]$SkipMaven,
    [switch]$SkipApi
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$results = New-Object System.Collections.Generic.List[object]

function Add-Result {
    param([string]$Area, [string]$Check, [string]$Status, [string]$Evidence = '')
    $results.Add([pscustomobject]@{ Area = $Area; Check = $Check; Status = $Status; Evidence = $Evidence })
    $color = switch ($Status) { 'PASS' { 'Green' } 'FAIL' { 'Red' } default { 'Yellow' } }
    Write-Host ("[{0}] {1} - {2}" -f $Status, $Area, $Check) -ForegroundColor $color
    if ($Evidence) { Write-Host ("       " + $Evidence) -ForegroundColor DarkGray }
}

function Test-Step {
    param([string]$Area, [string]$Check, [scriptblock]$Body)
    try {
        $ev = & $Body
        Add-Result $Area $Check 'PASS' (($ev | Out-String).Trim())
    } catch {
        Add-Result $Area $Check 'FAIL' $_.Exception.Message
    }
}

function Invoke-Psql {
    param([string]$Sql)
    $out = & docker exec $Container psql -U $DbUser -d $DbName -t -A -c $Sql 2>&1
    if ($LASTEXITCODE -ne 0) { throw (($out | Out-String).Trim()) }
    return (($out | Out-String).Trim())
}

function Wait-Postgres {
    param([int]$TimeoutSec = 90)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $status = & docker inspect --format '{{.State.Health.Status}}' $Container 2>$null
        if ($status -eq 'healthy') { return }
        Start-Sleep -Seconds 2
    }
    throw "postgres container '$Container' not healthy within $TimeoutSec s"
}

# Gate 0: build / tests
Test-Step 'Gate 0' 'pom.xml exists' {
    if (-not (Test-Path 'pom.xml')) { throw 'pom.xml missing' }
    'pom.xml'
}

Test-Step 'Gate 0' 'Flyway V1 exists' {
    $p = 'src/main/resources/db/migration/V1__init.sql'
    if (-not (Test-Path $p)) { throw "$p missing" }
    $p
}

if (-not $SkipMaven) {
    Test-Step 'Gate 0' 'mvn test' {
        $out = & mvn -q test 2>&1
        if ($LASTEXITCODE -ne 0) {
            $tail = (($out | Out-String) -split "`r?`n" | Select-Object -Last 30) -join "`n"
            throw "mvn test failed:`n$tail"
        }
        'BUILD SUCCESS'
    }
}

# Gate 1: docker / flyway / schema
Test-Step 'Gate 1' 'postgres healthy' {
    # docker compose writes "Container ... Running" to stderr; with
    # ErrorActionPreference=Stop that becomes a terminating error. Ignore it and
    # let the health check be the real gate.
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & docker compose up -d postgres 2>&1 | Out-Null }
    finally { $ErrorActionPreference = $old }
    Wait-Postgres
    'healthy'
}

Test-Step 'Gate 1' 'extensions' {
    $v = Invoke-Psql "SELECT string_agg(extname, ',' ORDER BY extname) FROM pg_extension WHERE extname IN ('vector','pg_trgm');"
    if ($v -ne 'pg_trgm,vector') { throw "expected pg_trgm,vector; got: $v" }
    $v
}

Test-Step 'Gate 1' 'six tables' {
    $v = Invoke-Psql "SELECT string_agg(tablename, ',' ORDER BY tablename) FROM pg_tables WHERE schemaname='public' AND tablename IN ('kb_document','kb_chunk','work_item','mandate','mcp_audit_log','eval_run');"
    $expected = 'eval_run,kb_chunk,kb_document,mandate,mcp_audit_log,work_item'
    if ($v -ne $expected) { throw "expected $expected; got: $v" }
    $v
}

Test-Step 'Gate 1' 'chunk_strategy column' {
    $v = Invoke-Psql "SELECT count(*) FROM information_schema.columns WHERE table_name='kb_chunk' AND column_name='chunk_strategy';"
    if ($v -ne '1') { throw "chunk_strategy column missing" }
    $v
}

Test-Step 'Gate 1' 'chunk_strategy check is lowercase' {
    $v = Invoke-Psql "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='ck_kb_chunk_strategy';"
    if ($v -notmatch "'fixed'" -or $v -notmatch "'heading'") { throw "unexpected check: $v" }
    $v
}

Test-Step 'Gate 1' 'HNSW index' {
    $v = Invoke-Psql "SELECT count(*) FROM pg_indexes WHERE tablename='kb_chunk' AND indexdef LIKE '%hnsw%';"
    if ([int]$v -lt 1) { throw 'HNSW index missing' }
    $v
}

Test-Step 'Gate 1' 'trigram index' {
    $v = Invoke-Psql "SELECT count(*) FROM pg_indexes WHERE tablename='kb_chunk' AND indexdef LIKE '%gin_trgm_ops%';"
    if ([int]$v -lt 1) { throw 'trigram index missing' }
    $v
}

Test-Step 'Gate 1' 'supersedes FK is SET NULL' {
    $v = Invoke-Psql "SELECT confdeltype FROM pg_constraint WHERE conrelid='kb_document'::regclass AND confrelid='kb_document'::regclass;"
    if ($v -notmatch 'n') { throw "expected ON DELETE SET NULL (n); got: $v" }
    $v
}

# Optional: app health
if (-not $SkipApi) {
    Test-Step 'Gate 2' 'app health' {
        $h = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 5
        if ($h.status -ne 'UP') { throw "health=$($h.status)" }
        "status=$($h.status)"
    }
}

# Report
$failed = @($results | Where-Object { $_.Status -eq 'FAIL' }).Count
$conclusion = if ($failed -eq 0) { '通过' } else { '不通过' }
$now = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
$mvnVersion = (& mvn -v 2>$null | Select-Object -First 1)
$lines = @(
    '# Phase 1 验收自动检查',
    '',
    ('- 时间：' + $now),
    ('- 结论：**' + $conclusion + '**（FAIL=' + $failed + '）'),
    ('- 环境：' + [System.Environment]::OSVersion.VersionString + '；mvn=' + $mvnVersion),
    '',
    '| Gate | 检查 | 结果 | 证据 |',
    '|---|---|---|---|'
)
foreach ($r in $results) {
    $ev = ($r.Evidence -replace '\r?\n', ' ').Trim()
    if ($ev.Length -gt 300) { $ev = $ev.Substring(0, 300) + '...' }
    $lines += ('| ' + $r.Area + ' | ' + $r.Check + ' | ' + $r.Status + ' | ' + $ev + ' |')
}
$lines += @('', '> 自动检查只覆盖 Gate 0/1（以及 Gate 2 的 health）；上传/切分/embedding/幂等/版本仍需按 phase1-checklist.md 人工跑。')
$reportDir = Split-Path -Parent $ReportPath
if ($reportDir -and -not (Test-Path $reportDir)) { New-Item -ItemType Directory -Force $reportDir | Out-Null }
$lines | Set-Content -LiteralPath $ReportPath -Encoding UTF8

Write-Host ""
Write-Host "Report: $ReportPath" -ForegroundColor Cyan
if ($failed -gt 0) { exit 1 }
exit 0
