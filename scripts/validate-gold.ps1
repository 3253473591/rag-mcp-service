[CmdletBinding()]
param(
    [string]$QaPath = 'eval/qa.jsonl',
    [string]$Container = 'rag-postgres',
    [string]$DbUser = 'rag',
    [string]$DbName = 'ragdb'
)

# Structural gold validation for eval/qa.jsonl against the ingested database.
# For every answerable item this proves that the document/version exists and that at least one
# chunk satisfies the gold locator (clause_id when present, page_no inside pageRange when present).
# It does not prove semantic relevance; the Phase 3 report records the 5 manual citation checks.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
if (-not (Test-Path -LiteralPath $QaPath)) { throw "QA dataset not found: $QaPath" }

function Invoke-Psql {
    param([string]$Sql)
    $out = & docker exec $Container psql -U $DbUser -d $DbName -t -A -v ON_ERROR_STOP=1 -c $Sql 2>&1
    if ($LASTEXITCODE -ne 0) { throw (($out | Out-String).Trim()) }
    return (($out | Out-String).Trim())
}

$rows = @(Get-Content -LiteralPath $QaPath -Encoding UTF8 |
    Where-Object { $_.Trim().Length -gt 0 } | ForEach-Object { $_ | ConvertFrom-Json })
$bad = @()
foreach ($row in $rows) {
    if ($row.answerable -ne $true) { continue }
    $gold = $row.gold
    if ($null -eq $gold -or -not $gold.docKey) {
        $bad += "$($row.id): answerable item has no gold.docKey"
        continue
    }

    $docWhere = "d.doc_key='$($gold.docKey)'"
    if ($gold.version) { $docWhere += " AND d.version='$($gold.version)'" }
    $docCount = [int](Invoke-Psql "SELECT count(*) FROM kb_document d WHERE $docWhere;")
    if ($docCount -lt 1) {
        $bad += "$($row.id): document $($gold.docKey)@$($gold.version) not found"
        continue
    }

    $chunkWhere = "d.doc_key='$($gold.docKey)' AND c.chunk_strategy='heading'"
    if ($gold.version) { $chunkWhere += " AND d.version='$($gold.version)'" }
    if ($gold.clauseId) { $chunkWhere += " AND c.clause_id='$($gold.clauseId)'" }
    if ($gold.pageRange) {
        $chunkWhere += " AND c.page_no <= $($gold.pageRange[1])"
        $chunkWhere += " AND COALESCE(c.page_end, c.page_no) >= $($gold.pageRange[0])"
    }
    $chunkCount = [int](Invoke-Psql @"
SELECT count(*) FROM kb_chunk c JOIN kb_document d ON d.id=c.document_id WHERE $chunkWhere;
"@)
    if ($chunkCount -lt 1) {
        $bad += ("{0}: no chunk for clause={1} pageRange={2}" -f $row.id,
            $gold.clauseId, ($gold.pageRange -join '-'))
        continue
    }
    Write-Output ("{0}: OK chunks={1} clause={2} pageRange={3}" -f
        $row.id, $chunkCount, $gold.clauseId, ($gold.pageRange -join '-'))
}

if ($bad.Count -gt 0) {
    Write-Output "INVALID GOLD:"
    $bad
    exit 1
}
Write-Output ("all {0} answerable gold locators exist in the database" -f
    (@($rows | Where-Object { $_.answerable -eq $true }).Count))
exit 0
