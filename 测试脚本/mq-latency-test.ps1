param(
    [string]$UserService = "http://localhost:8081",
    [string]$Gateway = "http://localhost:8080",
    [string]$Phone = "13800000001",
    [string]$Password = "12345678",
    [int]$PollIntervalMs = 100,
    [int]$MaxWaitMs = 10000
)

$ErrorActionPreference = "Continue"
$redisContainer = "biteblog-redis"
$redisPassword = "redis123456"

function Redis-SISMEMBER($Key, $Member) {
    $r = docker exec -e "REDISCLI_AUTH=$redisPassword" $redisContainer redis-cli SISMEMBER $Key $Member 2>$null
    return ($r -eq 1)
}
function Redis-ZSCORE($Key, $Member) {
    return docker exec -e "REDISCLI_AUTH=$redisPassword" $redisContainer redis-cli ZSCORE $Key $Member 2>$null
}
function Redis-GEOPOS($Member) {
    # GEO members stored as JSON: "171" (Jackson serialization wraps String in quotes)
    $m = '"' + $Member + '"'
    $r = docker exec -e "REDISCLI_AUTH=$redisPassword" $redisContainer redis-cli GEOPOS "location:notes" $m 2>$null
    return ($r -match "\d+\.\d+")
}
function Poll-Until($MaxMs, $IntervalMs, $CheckScript) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $n = 0
    while ($sw.ElapsedMilliseconds -lt $MaxMs) {
        $n++; if (& $CheckScript) { $sw.Stop(); return @{OK=$true;Ms=$sw.ElapsedMilliseconds;N=$n} }
        Start-Sleep -Milliseconds $IntervalMs
    }
    $sw.Stop(); return @{OK=$false;Ms=$sw.ElapsedMilliseconds;N=$n}
}
function Show($Label, $R) {
    if ($R.OK) {
        $c = if ($R.Ms -lt 500){"Green"}elseif($R.Ms -lt 2000){"Yellow"}else{"Red"}
        $msg = "  $Label : $($R.Ms)ms ($($R.N)polls)"
        Write-Host $msg -ForegroundColor $c
        Add-Content -Path $resultFile -Value $msg -Encoding UTF8
    } else {
        $msg = "  $Label : TIMEOUT"
        Write-Host $msg -ForegroundColor Red
        Add-Content -Path $resultFile -Value $msg -Encoding UTF8
    }
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$resultFile = Join-Path $scriptDir "mq-latency-result.txt"
$now = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
$txt = @"
=============================================
  BiteBlog MQ Event Latency Test
  Time: $now
  Poll: ${PollIntervalMs}ms  Max: ${MaxWaitMs}ms
=============================================

"@
$txt | Out-File -FilePath $resultFile -Encoding UTF8

Write-Host ""
Write-Host "=============================================" -ForegroundColor Cyan
Write-Host "  BiteBlog MQ Event Latency Test" -ForegroundColor Cyan
Write-Host "  Poll: ${PollIntervalMs}ms  Max: ${MaxWaitMs}ms" -ForegroundColor Cyan
Write-Host "=============================================" -ForegroundColor Cyan

# 1. Login
Write-Host ""
Write-Host "[1/4] Login..." -ForegroundColor White
$loginBody = @{phone=$Phone;password=$Password} | ConvertTo-Json -Compress
$loginBytes = [Text.Encoding]::UTF8.GetBytes($loginBody)
$loginResp = Invoke-RestMethod -Uri "$UserService/user/login" -Method POST -ContentType "application/json; charset=utf-8" -Body $loginBytes
if ($loginResp.code -ne 200) { Write-Host "  Fail" -ForegroundColor Red; exit 1 }
$token = $loginResp.data.token
$userId = $loginResp.data.userId
$headers = @{"Authorization"="Bearer $token";"X-User-Id"="$userId"}
$script:txt = @"

[$(Get-Date -Format 'HH:mm:ss')] Login: userId=$userId (phone=$Phone)
"@
Add-Content -Path $resultFile -Value $script:txt -Encoding UTF8
Write-Host "  OK: userId=$userId" -ForegroundColor Green
$today = Get-Date -Format "yyyy-MM-dd"
$rankDailyKey = "rank:daily:$today"

# 2. Publish
Write-Host ""
Write-Host "[2/4] note.published latency" -ForegroundColor White
Write-Host "---------------------------------------------"
$ts = Get-Date -Format "HHmmssfff"
$pubBody = @{title="LatencyTest-$ts";content="MQ latency test - publish";shopName="TestShop";address="test";longitude=114.3;latitude=30.59;scoreColor=4;scoreSmell=4;scoreTaste=4;imageUrls=@()} | ConvertTo-Json -Depth 5 -Compress
$pubBytes = [Text.Encoding]::UTF8.GetBytes($pubBody)
Write-Host "  Publishing..."
$pubResp = Invoke-RestMethod -Uri "$Gateway/api/post/publish" -Method POST -Headers $headers -ContentType "application/json; charset=utf-8" -Body $pubBytes
if ($pubResp.code -ne 200) { Write-Host "  Fail: code=$($pubResp.code)" -ForegroundColor Red; exit 1 }
$newId = $pubResp.data.postId
Write-Host "  OK: postId=$newId" -ForegroundColor Green
$script:txt = @"
[$(Get-Date -Format 'HH:mm:ss')] Published: postId=$newId
"@
Add-Content -Path $resultFile -Value $script:txt -Encoding UTF8

# 2.1 Feed
$r1 = Poll-Until $MaxWaitMs $PollIntervalMs { $s = Redis-ZSCORE "feed:inbox:$userId" $newId; return ($s -and $s -ne "") }
Show "Feed (inbox)            " $r1
# 2.2 Rank
$r2 = Poll-Until $MaxWaitMs $PollIntervalMs { $s = Redis-ZSCORE $rankDailyKey $newId; return ($s -and $s -ne "") }
Show "Rank (rank:daily)       " $r2
# 2.3 Location
$r3 = Poll-Until $MaxWaitMs $PollIntervalMs { return (Redis-GEOPOS $newId) }
Show "Location (GEO)          " $r3
# 2.4 ES
$r4 = Poll-Until $MaxWaitMs $PollIntervalMs {
    try {
        $esUrl = "$Gateway/api/post/search?keyword=" + [uri]::EscapeDataString("LatencyTest-$ts") + "&page=1&size=10"
        $sr = Invoke-RestMethod -Uri $esUrl -Headers $headers
        if ($sr.code -eq 200 -and $sr.data.list) { return ($sr.data.list | Where-Object {$_.postId -eq $newId}).Count -gt 0 }
    } catch {}
    return $false
}
Show "ES (search)             " $r4

# 3. Delete
Write-Host ""
Write-Host "[3/4] note.deleted latency" -ForegroundColor White
Write-Host "---------------------------------------------"
Write-Host "  Deleting postId=$newId"
Invoke-RestMethod -Uri "$Gateway/api/post/$newId" -Method DELETE -Headers $headers | Out-Null
Write-Host "  OK" -ForegroundColor Green
$script:txt = @"
[$(Get-Date -Format 'HH:mm:ss')] Deleted: postId=$newId
"@
Add-Content -Path $resultFile -Value $script:txt -Encoding UTF8

# 3.1 Feed deleted
$d1 = Poll-Until $MaxWaitMs $PollIntervalMs { return (Redis-SISMEMBER "feed:deleted" $newId) }
Show "Feed (feed:deleted)     " $d1
# 3.2 Rank remove
$d2 = Poll-Until $MaxWaitMs $PollIntervalMs { $s = Redis-ZSCORE $rankDailyKey $newId; return (-not $s -or $s -eq "") }
Show "Rank (ZREM)             " $d2
# 3.3 Location remove
$d3 = Poll-Until $MaxWaitMs $PollIntervalMs { return (-not (Redis-GEOPOS $newId)) }
Show "Location (GEO ZREM)     " $d3
# 3.4 ES gone
$d4 = Poll-Until $MaxWaitMs $PollIntervalMs {
    try {
        $esUrl = "$Gateway/api/post/search?keyword=" + [uri]::EscapeDataString("LatencyTest-$ts") + "&page=1&size=10"
        $sr = Invoke-RestMethod -Uri $esUrl -Headers $headers
        if ($sr.code -eq 200) { return -not (($sr.data.list | Where-Object {$_.postId -eq $newId}).Count -gt 0) }
    } catch {}
    return $false
}
Show "ES (gone)               " $d4

# 4. Summary
$summaryHeader = @"

=============================================
  Summary
=============================================

  note.published:
"@
Add-Content -Path $resultFile -Value $summaryHeader -Encoding UTF8

Write-Host ""
Write-Host "[4/4] Summary" -ForegroundColor White
Write-Host "============================================="
Write-Host ""
Write-Host "  note.published:" -ForegroundColor Cyan
Show "    Feed inbox         " $r1
Show "    Rank daily         " $r2
Show "    Location GEO       " $r3
Show "    ES search          " $r4

$sep = @"

  note.deleted:
"@
Add-Content -Path $resultFile -Value $sep -Encoding UTF8

Write-Host ""
Write-Host "  note.deleted:" -ForegroundColor Cyan
Show "    Feed deleted       " $d1
Show "    Rank remove        " $d2
Show "    Location GEO remove" $d3
Show "    ES gone            " $d4

$footer = @"

Done: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
"@
Add-Content -Path $resultFile -Value $footer -Encoding UTF8

Write-Host ""
Write-Host "=============================================" -ForegroundColor Cyan
Write-Host "  Result saved to: $resultFile" -ForegroundColor Green
Write-Host "  Done." -ForegroundColor Green
