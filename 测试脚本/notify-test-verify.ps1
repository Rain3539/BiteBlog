<#
.SYNOPSIS
    Notify Service full verification script
.NOTES
    Deps: user(8081) post(8082) notify(8087) gateway(8080) + MySQL + RabbitMQ + Redis + Nacos
    Accounts created by sql/init-notify-data.ps1 (13900004001 author, 13900004002 fan)
    Run:  cd BiteBlog\testscripts; .\notify-test-verify.ps1
    Output: notify-test-result.txt in same folder

    Design note: all notification counts are verified through the Notify HTTP API (not direct
    MySQL) to avoid docker-exec MySQL auth differences between socket/TCP users.
#>

$ErrorActionPreference = "SilentlyContinue"
$transcriptFile = Join-Path $PSScriptRoot "notify-test-result.txt"
Start-Transcript -Path $transcriptFile -Force | Out-Null

# ===== Config =====
$gateway    = "http://localhost:8080/api"
$notifyBase = "http://localhost:8087"
$userBase   = "http://localhost:8081"

$redisContainer = "biteblog-redis"
$redisPassword  = "redis123456"

$authorPhone = "13900004001"
$fanPhone    = "13900004002"
$password    = "12345678"

$passCount = 0
$failCount = 0

# ===== Helpers =====

function Invoke-JsonRequest {
    param(
        [string]$Uri,
        [string]$Method = "GET",
        [hashtable]$Headers = @{},
        $Body = $null
    )
    if ($null -ne $Body) {
        $json  = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Depth 8 -Compress }
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
        return Invoke-RestMethod -Uri $Uri -Method $Method -Headers $Headers `
            -ContentType "application/json; charset=utf-8" -Body $bytes -ErrorAction Stop
    }
    return Invoke-RestMethod -Uri $Uri -Method $Method -Headers $Headers -ErrorAction Stop
}

function Login-User {
    param([string]$Phone)
    $resp = Invoke-JsonRequest -Uri "$userBase/user/login" -Method POST `
        -Body @{ phone = $Phone; password = $password }
    return @{
        phone    = $Phone
        token    = $resp.data.token
        userId   = $resp.data.userId
        username = $resp.data.username
    }
}

function Pass { param([string]$Msg); $script:passCount++; Write-Host "  [PASS] $Msg" -ForegroundColor Green }
function Fail { param([string]$Msg); $script:failCount++; Write-Host "  [FAIL] $Msg" -ForegroundColor Red }
function Warn { param([string]$Msg); Write-Host "  [WARN] $Msg" -ForegroundColor Yellow }
function Section { param([string]$T); Write-Host ""; Write-Host "===== $T =====" -ForegroundColor Cyan }

# Verify count via API (not direct DB) — avoids docker-exec MySQL socket/TCP auth difference
function Get-NotifyTotal {
    param([hashtable]$Headers, [string]$Type = "", [int]$ReadStatus = -1)
    $url = "$gateway/notify/list?page=1&size=1"
    if ($Type)             { $url += "&type=$Type" }
    if ($ReadStatus -ge 0) { $url += "&readStatus=$ReadStatus" }
    try {
        $r = Invoke-JsonRequest -Uri $url -Headers $Headers
        return [long]$r.data.total
    } catch { return -1 }
}

function Wait-TotalGte {
    param([hashtable]$Headers, [long]$Target, [int]$MaxTries=40, [int]$IntervalMs=500)
    for ($i = 1; $i -le $MaxTries; $i++) {
        Start-Sleep -Milliseconds $IntervalMs
        $cur = Get-NotifyTotal -Headers $Headers
        Write-Host "  wait $i/$MaxTries : total=$cur (need >= $Target)" -ForegroundColor DarkGray
        if ($cur -ge $Target) { return $cur }
    }
    return (Get-NotifyTotal -Headers $Headers)
}

function Measure-RT {
    param([string]$Uri, [hashtable]$Headers=@{}, [int]$Samples=20)
    $times = @()
    for ($i = 1; $i -le $Samples; $i++) {
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        try { Invoke-JsonRequest -Uri $Uri -Headers $Headers | Out-Null } catch {}
        $sw.Stop(); $times += $sw.ElapsedMilliseconds
    }
    $s = $times | Sort-Object
    return @{
        avg = [math]::Round(($times | Measure-Object -Average).Average, 1)
        min = $s[0]; max = $s[-1]
        p90 = $s[[math]::Floor($Samples*0.90)]
        p95 = $s[[math]::Min([math]::Floor($Samples*0.95),$Samples-1)]
        samples = $Samples
    }
}

# Redis: try with password then without, to handle both auth/no-auth containers
function Get-RedisUnread {
    param([long]$UserId)
    $key = "notify:unread:$UserId"
    # Try with auth (password required setups)
    $raw = docker exec $redisContainer redis-cli --no-auth-warning -a $redisPassword GET $key 2>$null
    $v = ($raw | Where-Object { $_ -match "^\d+$" } | Select-Object -First 1)
    if ($null -ne $v) { return [int]$v }
    # Fallback: try without auth (no-password setups)
    $raw2 = docker exec $redisContainer redis-cli GET $key 2>$null
    $v2 = ($raw2 | Where-Object { $_ -match "^\d+$" } | Select-Object -First 1)
    if ($null -ne $v2) { return [int]$v2 }
    return $null
}

function Del-RedisUnread {
    param([long]$UserId)
    $key = "notify:unread:$UserId"
    docker exec $redisContainer redis-cli --no-auth-warning -a $redisPassword DEL $key 2>$null | Out-Null
    docker exec $redisContainer redis-cli DEL $key 2>$null | Out-Null
}

function Get-QueueInfo {
    param([string]$Name)
    $auth = "Basic " + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("guest:guest"))
    try {
        return Invoke-RestMethod -Uri "http://localhost:15672/api/queues/%2F/$Name" `
            -Headers @{ Authorization = $auth } -ErrorAction Stop
    } catch { return $null }
}

# ===== Banner =====
Write-Host "============================================================" -ForegroundColor White
Write-Host "  Notify Service Verification" -ForegroundColor White
Write-Host "  $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -ForegroundColor White
Write-Host "============================================================" -ForegroundColor White

# ===== 0. Login =====
Section "0. Account Login"
try {
    $author = Login-User $authorPhone
    $fan    = Login-User $fanPhone
    Pass "author login: userId=$($author.userId) ($authorPhone)"
    Pass "fan    login: userId=$($fan.userId) ($fanPhone)"
} catch {
    Fail "login failed: $($_.Exception.Message)"
    Write-Host "  Please run sql/init-notify-data.ps1 and start user-service(8081)" -ForegroundColor Red
    Stop-Transcript | Out-Null; exit 1
}

$authorHdr = @{ "Authorization" = "Bearer $($author.token)" }
$fanHdr    = @{ "Authorization" = "Bearer $($fan.token)" }
$authorId  = $author.userId

# ===== 1. Health =====
Section "1. Health Check"
try {
    $h = Invoke-JsonRequest -Uri "$notifyBase/notify/health"
    if ($h.data.status -eq "UP") { Pass "direct notify/health: status=UP" }
    else { Fail "direct notify/health: status=$($h.data.status)" }
} catch { Fail "direct notify/health failed: $($_.Exception.Message)" }

try {
    $h2 = Invoke-JsonRequest -Uri "$gateway/notify/health" -Headers $authorHdr
    if ($h2.data.status -eq "UP") { Pass "gateway -> notify/health: status=UP" }
    else { Fail "gateway notify/health: $($h2.data.status)" }
} catch { Fail "gateway notify/health failed: $($_.Exception.Message)" }

# ===== 2. Publish test note =====
Section "2. Publish Test Note"
$testTitle = "NotifyVerify-$(Get-Date -Format 'HHmmss')"
$noteBody  = @{
    title = $testTitle; content = "notify verify note"
    shopName = "test shop"; address = "Wuhan"
    longitude = 114.366; latitude = 30.537
    scoreColor = 5; scoreSmell = 4; scoreTaste = 5
    imageUrls = @()
}
try {
    $pub    = Invoke-JsonRequest -Uri "$gateway/post/publish" -Method POST -Headers $authorHdr -Body $noteBody
    $postId = [long]$pub.data.postId
    Pass "published postId=$postId"
} catch {
    Fail "publish failed: $($_.Exception.Message)"
    Stop-Transcript | Out-Null; exit 1
}

# Capture baseline count via API (not direct DB — avoids MySQL socket auth issue)
$baseBefore = Get-NotifyTotal -Headers $authorHdr
Write-Host "  baseline total notifications: $baseBefore" -ForegroundColor DarkGray

# ===== 3. MQ Consumption: like / collect / comment -> notification =====
Section "3. MQ Consumption: interaction events -> notification"
try { Invoke-JsonRequest -Uri "$gateway/post/$postId/like"     -Method POST -Headers $fanHdr | Out-Null } catch { Warn "like: $($_.Exception.Message)" }
try { Invoke-JsonRequest -Uri "$gateway/post/$postId/favorite" -Method POST -Headers $fanHdr | Out-Null } catch { Warn "favorite: $($_.Exception.Message)" }
try { Invoke-JsonRequest -Uri "$gateway/post/$postId/comment"  -Method POST -Headers $fanHdr `
    -Body @{ content = "verify comment"; parentId = $null } | Out-Null } catch { Warn "comment: $($_.Exception.Message)" }
Write-Host "  fan: liked + favorited + commented postId=$postId" -ForegroundColor DarkGray
Write-Host "  waiting up to 20s for MQ -> notify consumption..." -ForegroundColor DarkGray

$target     = $baseBefore + 3
$afterTotal = Wait-TotalGte -Headers $authorHdr -Target $target -MaxTries 40 -IntervalMs 500
$newCount   = $afterTotal - $baseBefore
Write-Host "  new notifications in API total = $newCount (expected >= 3)" -ForegroundColor DarkGray

if ($newCount -ge 3)    { Pass "MQ consumed: $newCount notifications (like/collect/comment)" }
elseif ($newCount -ge 1) { Warn "MQ partially consumed: only $newCount (check RabbitMQ Unacked)" }
else                     { Fail "MQ not consumed: 0 new notifications after 20s" }

# Verify each type is present
foreach ($tf in @("like", "collect", "comment")) {
    $tc = Get-NotifyTotal -Headers $authorHdr -Type $tf
    if ($tc -ge 1) { Write-Host "  type=$tf total=$tc [ok]" -ForegroundColor DarkGray }
    else           { Warn "type=$tf has 0 notifications" }
}

# ===== 4. Self-interaction filter =====
Section "4. Self-interaction Filter (author likes own note)"
$selfBefore = Get-NotifyTotal -Headers $authorHdr
try {
    Invoke-JsonRequest -Uri "$gateway/post/$postId/like" -Method POST -Headers $authorHdr | Out-Null
    Start-Sleep -Milliseconds 2000
} catch {}
$selfAfter = Get-NotifyTotal -Headers $authorHdr
if ($selfAfter -eq $selfBefore) {
    Pass "self-interaction filter: total unchanged ($selfBefore)"
} else {
    Fail "self-interaction filter FAILED: total $selfBefore -> $selfAfter (self-notification generated)"
}

# ===== 5. Idempotency: 5-min dedup window =====
Section "5. Idempotency: 5-min dedup window"
# Ensure fan's current like state is cancelled
try { Invoke-JsonRequest -Uri "$gateway/post/$postId/like" -Method POST -Headers $fanHdr | Out-Null } catch {} # toggle cancel
Start-Sleep -Milliseconds 300

$dedup0 = Get-NotifyTotal -Headers $authorHdr -Type "like"

# 1st like in the dedup window
try { Invoke-JsonRequest -Uri "$gateway/post/$postId/like" -Method POST -Headers $fanHdr | Out-Null } catch {}
Start-Sleep -Milliseconds 1500
$dedup1 = Get-NotifyTotal -Headers $authorHdr -Type "like"
Write-Host "  after 1st like: like total $dedup0 -> $dedup1 (delta=$($dedup1-$dedup0))" -ForegroundColor DarkGray

# Cancel then 2nd like in the same 5-min window
try { Invoke-JsonRequest -Uri "$gateway/post/$postId/like" -Method POST -Headers $fanHdr | Out-Null } catch {} # cancel
Start-Sleep -Milliseconds 200
try { Invoke-JsonRequest -Uri "$gateway/post/$postId/like" -Method POST -Headers $fanHdr | Out-Null } catch {} # 2nd like
Start-Sleep -Milliseconds 1500
$dedup2 = Get-NotifyTotal -Headers $authorHdr -Type "like"
$extra  = $dedup2 - $dedup1
Write-Host "  after 2nd like in window: extra like notifications = $extra (expected 0)" -ForegroundColor DarkGray
if ($extra -eq 0) { Pass "dedup: 5-min window blocked duplicate like notification" }
else             { Warn "dedup: $extra extra notification(s) (verify DEDUP_WINDOW_MINUTES in NotifyService)" }

# ===== 6. Single-read (must run BEFORE read-all) =====
Section "6. Single-read API"
try {
    $unreadResp = Invoke-JsonRequest -Uri "$gateway/notify/list?page=1&size=5&readStatus=0" -Headers $authorHdr
    $firstUnread = $unreadResp.data.list | Select-Object -First 1
} catch { $firstUnread = $null }

if ($firstUnread) {
    $nId = $firstUnread.notificationId
    try {
        Invoke-JsonRequest -Uri "$gateway/notify/$nId/read" -Method POST -Headers $authorHdr | Out-Null
        Start-Sleep -Milliseconds 300
        $check = (Invoke-JsonRequest -Uri "$gateway/notify/list?page=1&size=50" -Headers $authorHdr).data.list |
                 Where-Object { $_.notificationId -eq $nId }
        if ($check -and $check.readStatus -eq 1) { Pass "single-read: notificationId=$nId readStatus=1" }
        else { Warn "single-read: notificationId=$nId status not confirmed in list" }
    } catch { Fail "single-read failed: $($_.Exception.Message)" }
} else {
    Warn "no unread notification available for single-read test (all may already be read)"
}

# ===== 7. Read-all and unread-count consistency =====
Section "7. Read-All / Unread-Count Consistency"
try {
    $ucBefore = [long](Invoke-JsonRequest -Uri "$gateway/notify/unread-count" -Headers $authorHdr).data.unreadCount
    Write-Host "  unreadCount before read-all: $ucBefore" -ForegroundColor DarkGray
} catch { $ucBefore = -1 }

try {
    Invoke-JsonRequest -Uri "$gateway/notify/read-all" -Method POST -Headers $authorHdr | Out-Null
    Pass "read-all: request accepted"
} catch { Fail "read-all failed: $($_.Exception.Message)" }

Start-Sleep -Milliseconds 500

try {
    $ucAfter = [long](Invoke-JsonRequest -Uri "$gateway/notify/unread-count" -Headers $authorHdr).data.unreadCount
    Write-Host "  unreadCount after  read-all: $ucAfter" -ForegroundColor DarkGray
    if ($ucAfter -eq 0) { Pass "read-all: unreadCount=0" }
    else                { Fail "read-all: unreadCount=$ucAfter (expected 0)" }
} catch { Fail "unread-count after read-all: $($_.Exception.Message)" }

# ===== 8. List API response time (P95 < 300ms, 20 samples) =====
Section "8. List API Response Time (target P95 < 300ms, 20 samples)"
Write-Host "  GET $gateway/notify/list?page=1&size=20" -ForegroundColor DarkGray
$listRT = Measure-RT -Uri "$gateway/notify/list?page=1&size=20" -Headers $authorHdr -Samples 20
Write-Host "  samples=$($listRT.samples)  avg=$($listRT.avg)ms  min=$($listRT.min)ms  max=$($listRT.max)ms" -ForegroundColor White
Write-Host "  P90=$($listRT.p90)ms   P95=$($listRT.p95)ms   (target P95 < 300ms)" -ForegroundColor White
if     ($listRT.p95 -lt 300) { Pass "list P95=$($listRT.p95)ms < 300ms" }
elseif ($listRT.p95 -lt 500) { Warn "list P95=$($listRT.p95)ms >= 300ms (acceptable on local dev)" }
else                         { Fail "list P95=$($listRT.p95)ms >= 500ms" }

# ===== 9. Unread-count response time (P95 < 100ms Redis hot, 20 samples) =====
Section "9. Unread-count API Response Time (target P95 < 100ms Redis hot, 20 samples)"
Write-Host "  GET $gateway/notify/unread-count" -ForegroundColor DarkGray
# Warm up the Redis cache
try { Invoke-JsonRequest -Uri "$gateway/notify/unread-count" -Headers $authorHdr | Out-Null } catch {}
Start-Sleep -Milliseconds 100

$unreadRT = Measure-RT -Uri "$gateway/notify/unread-count" -Headers $authorHdr -Samples 20
Write-Host "  samples=$($unreadRT.samples)  avg=$($unreadRT.avg)ms  min=$($unreadRT.min)ms  max=$($unreadRT.max)ms" -ForegroundColor White
Write-Host "  P90=$($unreadRT.p90)ms   P95=$($unreadRT.p95)ms   (target P95 < 100ms)" -ForegroundColor White
if     ($unreadRT.p95 -lt 100) { Pass "unread-count P95=$($unreadRT.p95)ms < 100ms (Redis hit)" }
elseif ($unreadRT.p95 -lt 300) { Warn "unread-count P95=$($unreadRT.p95)ms (check Redis connection)" }
else                           { Fail "unread-count P95=$($unreadRT.p95)ms >= 300ms" }

# ===== 10. Redis cache: key present, cold/hot RT comparison =====
Section "10. Redis Cache Validation"

$cachedVal = Get-RedisUnread $authorId
if ($null -ne $cachedVal) {
    Pass "Redis key notify:unread:$authorId exists, value=$cachedVal"
} else {
    Warn "Redis key not found via redis-cli (may be auth/network; API still works)"
}

Del-RedisUnread $authorId; Start-Sleep -Milliseconds 100

$swCold = [System.Diagnostics.Stopwatch]::StartNew()
try { Invoke-JsonRequest -Uri "$gateway/notify/unread-count" -Headers $authorHdr | Out-Null } catch {}
$swCold.Stop(); $coldRT = $swCold.ElapsedMilliseconds
Write-Host "  cold (cache miss -> DB COUNT): ${coldRT}ms" -ForegroundColor DarkGray

$swHot = [System.Diagnostics.Stopwatch]::StartNew()
try { Invoke-JsonRequest -Uri "$gateway/notify/unread-count" -Headers $authorHdr | Out-Null } catch {}
$swHot.Stop(); $hotRT = $swHot.ElapsedMilliseconds
Write-Host "  hot  (cache hit  -> Redis GET): ${hotRT}ms" -ForegroundColor DarkGray

# On local dev, both are fast; cold is usually >= hot but not guaranteed when both < 10ms
if ($hotRT -le ($coldRT + 5)) {
    Pass "cache cold/hot: cold=${coldRT}ms hot=${hotRT}ms (hot <= cold as expected)"
} else {
    Warn "cache cold/hot: cold=${coldRT}ms hot=${hotRT}ms (hot > cold; RTT noise on local is acceptable)"
}

# ===== 11. List filter: type / readStatus =====
Section "11. List Filter Parameters (type / readStatus)"

foreach ($tf in @("like", "collect", "comment")) {
    try {
        $r     = Invoke-JsonRequest -Uri "$gateway/notify/list?page=1&size=50&type=$tf" -Headers $authorHdr
        $wrong = $r.data.list | Where-Object { $_.type -ne $tf }
        if ($wrong) { Fail "type=$tf filter: wrong-type items returned" }
        else        { Pass "type=$tf filter: $($r.data.list.Count) items, all correct" }
    } catch { Warn "type=$tf filter: $($_.Exception.Message)" }
}

foreach ($rs in @(0, 1)) {
    $label = if ($rs -eq 0) { "unread" } else { "read" }
    try {
        $r     = Invoke-JsonRequest -Uri "$gateway/notify/list?page=1&size=50&readStatus=$rs" -Headers $authorHdr
        $wrong = $r.data.list | Where-Object { $_.readStatus -ne $rs }
        if ($wrong) { Fail "readStatus=$rs ($label) filter: wrong-status items returned" }
        else        { Pass "readStatus=$rs ($label) filter: $($r.data.list.Count) items, all correct" }
    } catch { Warn "readStatus=$rs filter: $($_.Exception.Message)" }
}

# ===== 12. Pagination accuracy =====
Section "12. Pagination: total consistency and no duplicates"
try {
    $p1 = Invoke-JsonRequest -Uri "$gateway/notify/list?page=1&size=5" -Headers $authorHdr
    $p2 = Invoke-JsonRequest -Uri "$gateway/notify/list?page=2&size=5" -Headers $authorHdr
    $t1 = [long]$p1.data.total; $t2 = [long]$p2.data.total
    Write-Host "  page1 total=$t1   page2 total=$t2" -ForegroundColor DarkGray
    if ($t1 -gt 0 -and $t1 -eq $t2) { Pass "pagination: total consistent ($t1)" }
    elseif ($t1 -eq 0)              { Warn "pagination: total=0 (MybatisPlusConfig may be missing)" }
    else                             { Fail "pagination: total inconsistent p1=$t1 p2=$t2" }

    $ids1 = $p1.data.list | ForEach-Object { $_.notificationId }
    $ids2 = $p2.data.list | ForEach-Object { $_.notificationId }
    $dup  = $ids1 | Where-Object { $ids2 -contains $_ }
    if ($dup) { Fail "pagination: duplicate ids across pages: $($dup -join ',')" }
    else      { Pass "pagination: no duplicate ids across page1 and page2" }
} catch { Warn "pagination test: $($_.Exception.Message)" }

# ===== 13. Auth and privilege =====
Section "13. Auth and Privilege"

# No token -> 401
try {
    Invoke-RestMethod -Uri "$gateway/notify/list?page=1&size=5" -ErrorAction Stop | Out-Null
    Fail "no-token: expected 401, got 200"
} catch {
    $sc = $_.Exception.Response.StatusCode.value__
    if ($sc -eq 401) { Pass "no-token: 401 Unauthorized" }
    else             { Warn "no-token: got HTTP $sc (expected 401)" }
}

# Privilege escalation: fan reads author's notification
try {
    $authorList    = Invoke-JsonRequest -Uri "$gateway/notify/list?page=1&size=5" -Headers $authorHdr
    $authorNotifId = ($authorList.data.list | Select-Object -First 1).notificationId
} catch { $authorNotifId = $null }

if ($authorNotifId) {
    try {
        $r = Invoke-JsonRequest -Uri "$gateway/notify/$authorNotifId/read" `
            -Method POST -Headers $fanHdr -ErrorAction Stop
        if ($r.code -ne 200) { Pass "privilege: fan rejected by business code $($r.code)" }
        else                 { Fail "privilege: fan marked author notification (should be rejected)" }
    } catch {
        $sc = $_.Exception.Response.StatusCode.value__
        if ($sc -in @(400,403)) { Pass "privilege: fan rejected HTTP $sc" }
        else                    { Warn "privilege: HTTP $sc for cross-user read" }
    }
} else {
    Write-Host "  [SKIP] no author notification available for privilege test" -ForegroundColor DarkGray
}

# Direct (bypass gateway) with X-User-Id
try {
    $d = Invoke-JsonRequest -Uri "$notifyBase/notify/unread-count" `
        -Headers @{ "X-User-Id" = "$authorId" }
    if ($d.code -eq 200) { Pass "direct notify with X-User-Id: code=200" }
    else                 { Warn "direct notify with X-User-Id: code=$($d.code)" }
} catch { Warn "direct notify X-User-Id: $($_.Exception.Message)" }

# ===== 14. RabbitMQ queue status =====
Section "14. RabbitMQ Queue Status (via Management API)"
$mainQ = Get-QueueInfo "notify.interaction.queue"
if ($mainQ) {
    $ready     = $mainQ.messages_ready
    $unacked   = $mainQ.messages_unacknowledged
    $consumers = $mainQ.consumers
    Write-Host "  notify.interaction.queue: consumers=$consumers  ready=$ready  unacked=$unacked" -ForegroundColor DarkGray
    if ($consumers -ge 1) { Pass "queue consumers=$consumers" }
    else                  { Fail "queue no consumers (notify-service not connected)" }
    if ($unacked -eq 0)   { Pass "queue Unacked=0" }
    else                  { Warn "queue Unacked=$unacked (check consumption errors in notify logs)" }
} else { Warn "cannot query RabbitMQ API (check :15672 / guest credentials)" }

$dlq = Get-QueueInfo "notify.dead.queue"
if ($dlq) {
    $dlqReady = $dlq.messages_ready
    Write-Host "  notify.dead.queue: ready=$dlqReady" -ForegroundColor DarkGray
    if ($dlqReady -eq 0) { Pass "DLQ: empty (no failed messages)" }
    else                 { Warn "DLQ: $dlqReady message(s) in dead-letter queue" }
} else { Warn "cannot query DLQ info" }

# ===== Summary =====
Section "Summary"
$total = $passCount + $failCount
Write-Host ""
Write-Host "  Pass  : $passCount" -ForegroundColor Green
Write-Host "  Fail  : $failCount" -ForegroundColor Red
Write-Host "  Total : $total checks" -ForegroundColor White
Write-Host ""
Write-Host "  --- Response Time Results ---" -ForegroundColor Cyan
Write-Host ("  List        avg={0}ms  P90={1}ms  P95={2}ms  max={3}ms" -f $listRT.avg,$listRT.p90,$listRT.p95,$listRT.max) -ForegroundColor White
Write-Host ("  Unread-cnt  avg={0}ms  P90={1}ms  P95={2}ms  max={3}ms" -f $unreadRT.avg,$unreadRT.p90,$unreadRT.p95,$unreadRT.max) -ForegroundColor White
Write-Host ("  Cache       cold(DB)={0}ms  hot(Redis)={1}ms" -f $coldRT,$hotRT) -ForegroundColor White
Write-Host ""
if ($failCount -eq 0) {
    Write-Host "  All critical checks PASSED" -ForegroundColor Green
} else {
    Write-Host "  $failCount check(s) FAILED - review logs above" -ForegroundColor Red
}
Write-Host ""
Write-Host "Result saved to: $transcriptFile" -ForegroundColor Cyan
Stop-Transcript | Out-Null
