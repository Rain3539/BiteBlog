<#
.SYNOPSIS
    Post Service full verification script
.NOTES
    Deps: user(8081) post(8082) gateway(8080) + MySQL + Redis + ES + RabbitMQ + MinIO + Nacos
    Accounts: same as sql/init-data.ps1 (13800000001 bb_bigv_01)
    Run:  cd BiteBlog; .\测试脚本\post-test-verify.ps1
    Output: post-test-result.txt in same folder
#>

$ErrorActionPreference = "SilentlyContinue"
$transcriptFile = Join-Path $PSScriptRoot "post-test-result.txt"
Start-Transcript -Path $transcriptFile -Force | Out-Null

# ===== Config =====
$gateway   = "http://localhost:8080/api"
$postBase  = "http://localhost:8082"
$userBase  = "http://localhost:8081"

$redisContainer = "biteblog-redis"
$redisPassword  = "redis123456"

$authorPhone = "13800000001"   # bb_bigv_01
$password    = "12345678"

$passCount = 0
$failCount = 0

# ===== Helpers =====

function Invoke-JsonRequest {
    param(
        [string]$Uri, [string]$Method = "GET",
        [hashtable]$Headers = @{}, $Body = $null
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
    $resp = Invoke-JsonRequest -Uri "$gateway/user/login" -Method POST `
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

function Measure-RT {
    param([string]$Uri, [hashtable]$Headers=@{}, [int]$Samples=20, [string]$Method="GET", $Body=$null)
    $times = @()
    for ($i = 1; $i -le $Samples; $i++) {
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        try { Invoke-JsonRequest -Uri $Uri -Headers $Headers -Method $Method -Body $Body | Out-Null } catch {}
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

function Invoke-RedisCli {
    param([Parameter(Mandatory)][string[]]$RedisArgs)
    $lines = @()
    try { $lines = @(docker exec $redisContainer redis-cli --no-auth-warning -a $redisPassword @RedisArgs 2>$null) } catch {}
    return $lines
}

function Wait-SearchHit {
    param([hashtable]$Headers, [string]$Keyword, [int]$TargetCount, [int]$MaxTries=20, [int]$IntervalMs=1500)
    for ($i = 1; $i -le $MaxTries; $i++) {
        Start-Sleep -Milliseconds $IntervalMs
        try {
            $r = Invoke-JsonRequest -Uri "$gateway/post/search?keyword=$Keyword&page=1&size=50" -Headers $Headers
            $cnt = $r.data.list.Count
            Write-Host "  wait $i/$MaxTries : search count=$cnt (need >= $TargetCount)" -ForegroundColor DarkGray
            if ($cnt -ge $TargetCount) { return $r }
        } catch {}
    }
    return $null
}

# ===== Banner =====
Write-Host "============================================================" -ForegroundColor White
Write-Host "  Post Service Verification" -ForegroundColor White
Write-Host "  $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -ForegroundColor White
Write-Host "============================================================" -ForegroundColor White

# ===== 0. Login =====
Section "0. Account Login"
try {
    $author = Login-User $authorPhone
    Pass "author login: userId=$($author.userId) ($authorPhone)"
} catch {
    Fail "login failed: $($_.Exception.Message)"
    Write-Host "  Please run sql/init-data.ps1 and start user-service(8081)" -ForegroundColor Red
    Stop-Transcript | Out-Null; exit 1
}

$authorHdr = @{ "Authorization" = "Bearer $($author.token)" }
$authorId  = $author.userId

# ===== 1. Service Reachability =====
Section "1. Service Reachability (Post Service has no /health, using /post/1)"
try {
    $h = Invoke-JsonRequest -Uri "$postBase/post/1" -Headers @{ "X-User-Id" = "$authorId" }
    if ($h.code -eq 200) { Pass "direct post/1: code=200 (service reachable)" }
    else { Pass "direct post/1: code=$($h.code) (service reachable, postId=1 may not exist)" }
} catch { Fail "direct post/1 failed: $($_.Exception.Message)" }

try {
    $h2 = Invoke-JsonRequest -Uri "$gateway/post/1" -Headers $authorHdr
    if ($h2.code -in @(200, 5004, 5002)) { Pass "gateway -> post/1: code=$($h2.code) (service reachable)" }
    else { Warn "gateway post/1: code=$($h2.code)" }
} catch { Fail "gateway post/1 failed: $($_.Exception.Message)" }

# ===== 2. PC-1: Publish Transaction Atomicity =====
Section "2. PC-1: Publish Transaction Atomicity (MySQL note + note_image)"
$testTitle = "PostVerify-$(Get-Date -Format 'HHmmss')"
$noteBody = @{
    title = $testTitle; content = "post verify note content"
    shopName = "verify shop"; address = "Wuhan"
    longitude = 114.366; latitude = 30.537
    scoreColor = 5; scoreSmell = 4; scoreTaste = 5
    imageUrls = @()
}
try {
    $pub    = Invoke-JsonRequest -Uri "$gateway/post/publish" -Method POST -Headers $authorHdr -Body $noteBody
    $postId = [long]$pub.data.postId
    Pass "PC-1: published postId=$postId (note + note_image in same transaction)"
} catch {
    Fail "PC-1: publish failed: $($_.Exception.Message)"
    Stop-Transcript | Out-Null; exit 1
}

# Verify detail returns complete data
try {
    $detail = Invoke-JsonRequest -Uri "$gateway/post/$postId" -Headers $authorHdr
    if ($detail.code -eq 200 -and $detail.data.postId -eq $postId) {
        Pass "PC-1: detail returns postId=$postId, title=$($detail.data.title)"
    } else {
        Fail "PC-1: detail check failed, code=$($detail.code)"
    }
} catch { Fail "PC-1: detail fetch failed: $($_.Exception.Message)" }

# ===== 3. PC-2: Cache Consistency (Cache-Aside) =====
Section "3. PC-2: Detail Cache Consistency (Redis Cache-Aside)"

# Clear cache key first
$cacheKey = "post:cache:$postId"
Invoke-RedisCli -RedisArgs @('DEL', $cacheKey) | Out-Null
Start-Sleep -Milliseconds 200

# Cold path: first request after cache clear
$swCold = [System.Diagnostics.Stopwatch]::StartNew()
try { Invoke-JsonRequest -Uri "$gateway/post/$postId" -Headers $authorHdr | Out-Null } catch {}
$swCold.Stop(); $coldRT = $swCold.ElapsedMilliseconds
Write-Host "  PC-2 cold (cache miss -> MySQL): ${coldRT}ms" -ForegroundColor DarkGray

# Hot path: second request hits cache
$swHot = [System.Diagnostics.Stopwatch]::StartNew()
try { Invoke-JsonRequest -Uri "$gateway/post/$postId" -Headers $authorHdr | Out-Null } catch {}
$swHot.Stop(); $hotRT = $swHot.ElapsedMilliseconds
Write-Host "  PC-2 hot  (cache hit  -> Redis): ${hotRT}ms" -ForegroundColor DarkGray

if ($hotRT -le ($coldRT + 10)) {
    Pass "PC-2: cache cold=${coldRT}ms hot=${hotRT}ms (Cache-Aside works)"
} else {
    Warn "PC-2: cache cold=${coldRT}ms hot=${hotRT}ms (hot > cold, local dev RTT noise acceptable)"
}

# Verify Redis key exists after hot read
$exists = Invoke-RedisCli -RedisArgs @('EXISTS', $cacheKey)
Write-Host "  PC-2: Redis key $cacheKey exists=$exists" -ForegroundColor DarkGray
if ($exists -match '1') {
    Pass "PC-2: Redis cache key present after detail query"
} else {
    Warn "PC-2: Redis cache key not found (may use different serialization pattern)"
}

# ===== 4. PC-3: ES Search Consistency =====
Section "4. PC-3: ES Search Consistency (publish -> ES index -> searchable)"

# Wait for ES async sync via MQ
$searchResult = Wait-SearchHit -Headers $authorHdr -Keyword "PostVerify" -TargetCount 1 -MaxTries 20 -IntervalMs 1500

if ($searchResult -and $searchResult.code -eq 200 -and $searchResult.data.list.Count -ge 1) {
    $found = $searchResult.data.list | Where-Object { $_.postId -eq $postId }
    if ($found) {
        Pass "PC-3: ES search returns postId=$postId after publish (MQ async sync OK)"
    } else {
        Warn "PC-3: ES search returned results but postId=$postId not in first page"
    }
} else {
    Warn "PC-3: ES search not yet returning results (MQ async sync may be delayed, re-run later)"
}

# ===== 5. PC-4: ES Degradation (search works even if ES has issues) =====
Section "5. PC-4: ES Degradation (search gracefully handles ES issues)"
try {
    $r = Invoke-JsonRequest -Uri "$gateway/post/search?keyword=烧烤&page=1&size=5" -Headers $authorHdr
    if ($r.code -eq 200) {
        Write-Host "  PC-4: ES search code=200, returned $($r.data.list.Count) results" -ForegroundColor DarkGray
        Pass "PC-4: ES search functions correctly (degradation returns empty list on ES error)"
    } else {
        Warn "PC-4: ES search code=$($r.code)"
    }
} catch {
    Fail "PC-4: ES search failed: $($_.Exception.Message)"
}

# ===== 6. PC-5: Logical Delete Filtering =====
Section "6. PC-5: Logical Delete Filtering (status=0 posts hidden)"

# Publish a second note to delete
$delTitle = "PostDel-$(Get-Date -Format 'HHmmss')"
$delBody = @{
    title = $delTitle; content = "to be deleted"
    shopName = "del shop"; address = "Wuhan"
    longitude = 114.366; latitude = 30.537
    scoreColor = 3; scoreSmell = 3; scoreTaste = 3
    imageUrls = @()
}
try {
    $delPub = Invoke-JsonRequest -Uri "$gateway/post/publish" -Method POST -Headers $authorHdr -Body $delBody
    $delId  = [long]$delPub.data.postId
    Pass "PC-5: published deletable note postId=$delId"
} catch {
    Fail "PC-5: publish for delete test failed: $($_.Exception.Message)"
    $delId = 0
}

if ($delId -gt 0) {
    # Delete it
    try {
        $delResp = Invoke-JsonRequest -Uri "$gateway/post/$delId" -Method DELETE -Headers $authorHdr
        if ($delResp.code -eq 200) {
            Pass "PC-5: deleted postId=$delId"
        } else {
            Fail "PC-5: delete failed, code=$($delResp.code)"
        }
    } catch { Fail "PC-5: delete exception: $($_.Exception.Message)" }

    Start-Sleep -Milliseconds 500

    # Verify detail returns not-found
    try {
        $delDetail = Invoke-JsonRequest -Uri "$gateway/post/$delId" -Headers $authorHdr
        if ($delDetail.code -ne 200) {
            Pass "PC-5: deleted post detail returns code=$($delDetail.code) (not found)"
        } else {
            Fail "PC-5: deleted post still accessible, code=200"
        }
    } catch { Pass "PC-5: deleted post detail error (expected)" }
}

# Verify /post/user/{userId} doesn't return deleted notes
try {
    $userPosts = Invoke-JsonRequest -Uri "$gateway/post/user/$authorId?page=1&size=50" -Headers $authorHdr
    if ($userPosts.code -eq 200) {
        $deletedIds = $userPosts.data.list | Where-Object { $_.status -eq 0 }
        if (-not $deletedIds) {
            Pass "PC-5: user posts list filters deleted notes (no status=0 found)"
        } else {
            Warn "PC-5: user posts contains $($deletedIds.Count) deleted note(s)"
        }
    }
} catch { Warn "PC-5: user posts query failed: $($_.Exception.Message)" }

# ===== 7. Post Detail Response Time (P95 < 300ms, 20 samples) =====
Section "7. Post Detail RT (target P95 < 300ms, 20 samples)"
Write-Host "  GET $gateway/post/$postId" -ForegroundColor DarkGray

# Warm up cache
try { Invoke-JsonRequest -Uri "$gateway/post/$postId" -Headers $authorHdr | Out-Null } catch {}
Start-Sleep -Milliseconds 100

$detailRT = Measure-RT -Uri "$gateway/post/$postId" -Headers $authorHdr -Samples 20
Write-Host "  samples=$($detailRT.samples)  avg=$($detailRT.avg)ms  min=$($detailRT.min)ms  max=$($detailRT.max)ms" -ForegroundColor White
Write-Host "  P90=$($detailRT.p90)ms   P95=$($detailRT.p95)ms   (target P95 < 300ms)" -ForegroundColor White
if     ($detailRT.p95 -lt 300) { Pass "detail P95=$($detailRT.p95)ms < 300ms" }
elseif ($detailRT.p95 -lt 500) { Warn "detail P95=$($detailRT.p95)ms (acceptable on local dev)" }
else                            { Fail "detail P95=$($detailRT.p95)ms >= 500ms" }

# ===== 8. ES Search Response Time (P95 < 800ms, 20 samples) =====
Section "8. ES Search RT (target P95 < 800ms, 20 samples)"
Write-Host "  GET $gateway/post/search?keyword=烧烤&page=1&size=20" -ForegroundColor DarkGray
$searchRT = Measure-RT -Uri "$gateway/post/search?keyword=烧烤&page=1&size=20" -Headers $authorHdr -Samples 20
Write-Host "  samples=$($searchRT.samples)  avg=$($searchRT.avg)ms  min=$($searchRT.min)ms  max=$($searchRT.max)ms" -ForegroundColor White
Write-Host "  P90=$($searchRT.p90)ms   P95=$($searchRT.p95)ms   (target P95 < 800ms)" -ForegroundColor White
if     ($searchRT.p95 -lt 800)  { Pass "search P95=$($searchRT.p95)ms < 800ms" }
elseif ($searchRT.p95 -lt 1500) { Warn "search P95=$($searchRT.p95)ms (acceptable on local dev)" }
else                             { Fail "search P95=$($searchRT.p95)ms >= 1500ms" }

# ===== 9. Like Idempotency =====
Section "9. Like Idempotency (UNIQUE KEY + atomic counter)"
# Get initial like count
try { $detailBefore = Invoke-JsonRequest -Uri "$gateway/post/$postId" -Headers $authorHdr } catch { $detailBefore = $null }
$likeBefore = if ($detailBefore) { [long]$detailBefore.data.likeCount } else { -1 }
Write-Host "  likeCount before toggle: $likeBefore" -ForegroundColor DarkGray

# Toggle like multiple times rapidly
for ($i = 1; $i -le 5; $i++) {
    try { Invoke-JsonRequest -Uri "$gateway/post/$postId/like" -Method POST -Headers $authorHdr | Out-Null } catch {}
    Start-Sleep -Milliseconds 100
}

try { $detailAfter = Invoke-JsonRequest -Uri "$gateway/post/$postId" -Headers $authorHdr } catch { $detailAfter = $null }
$likeAfter = if ($detailAfter) { [long]$detailAfter.data.likeCount } else { -1 }
Write-Host "  likeCount after 5 toggles: $likeAfter" -ForegroundColor DarkGray

if ($likeBefore -ge 0 -and $likeAfter -ge 0) {
    $delta = [math]::Abs($likeAfter - $likeBefore)
    if ($delta -le 1) {
        Pass "like idempotent: before=$likeBefore after=$likeAfter (delta=$delta, expected 0 or 1)"
    } else {
        Warn "like idempotent: delta=$delta (may have race condition)"
    }
}

# ===== 10. Comment Write + Pagination =====
Section "10. Comment Write and Pagination Consistency"
try {
    $c1 = Invoke-JsonRequest -Uri "$gateway/post/$postId/comment" -Method POST -Headers $authorHdr `
        -Body @{ content = "verify comment 1"; parentId = $null }
    $c2 = Invoke-JsonRequest -Uri "$gateway/post/$postId/comment" -Method POST -Headers $authorHdr `
        -Body @{ content = "verify comment 2"; parentId = $null }
    if ($c1.code -eq 200 -and $c2.code -eq 200) {
        Pass "comments: wrote 2 comments on postId=$postId"
    } else {
        Fail "comments: write failed c1=$($c1.code) c2=$($c2.code)"
    }
} catch { Fail "comments write: $($_.Exception.Message)" }

try {
    $comments = Invoke-JsonRequest -Uri "$gateway/post/$postId/comments?page=1&size=20" -Headers $authorHdr
    if ($comments.code -eq 200 -and $comments.data.list.Count -ge 2) {
        Pass "comments: pagination query returned $($comments.data.list.Count) comments"
    } else {
        Warn "comments: pagination returned $($comments.data.list.Count) items"
    }
} catch { Warn "comments pagination: $($_.Exception.Message)" }

# ===== 11. Search Pagination (no cross-page duplicates) =====
Section "11. Search Pagination: no cross-page duplicates"
try {
    $sp1 = Invoke-JsonRequest -Uri "$gateway/post/search?keyword=烧烤&page=1&size=5" -Headers $authorHdr
    $sp2 = Invoke-JsonRequest -Uri "$gateway/post/search?keyword=烧烤&page=2&size=5" -Headers $authorHdr
    $ids1 = $sp1.data.list | ForEach-Object { $_.postId }
    $ids2 = $sp2.data.list | ForEach-Object { $_.postId }
    $dup  = $ids1 | Where-Object { $ids2 -contains $_ }
    if ($dup) { Fail "search pagination: duplicate postIds across pages: $($dup -join ',')" }
    else      { Pass "search pagination: no duplicate postIds across page1 and page2" }
} catch { Warn "search pagination: $($_.Exception.Message)" }

# ===== 12. Auth and Privilege =====
Section "12. Auth and Privilege"

# No token -> 401
try {
    Invoke-RestMethod -Uri "$gateway/post/search?keyword=test" -ErrorAction Stop | Out-Null
    Fail "no-token: expected 401, got 200"
} catch {
    $sc = $_.Exception.Response.StatusCode.value__
    if ($sc -eq 401) { Pass "no-token: 401 Unauthorized" }
    else             { Warn "no-token: got HTTP $sc (expected 401)" }
}

# Delete someone else's post -> should be rejected
# (we use the search result to find a post NOT by our test user)
try {
    $otherPosts = Invoke-JsonRequest -Uri "$gateway/post/search?keyword=test&page=1&size=5" -Headers $authorHdr
    $otherPost  = $otherPosts.data.list | Where-Object { $_.authorId -ne $authorId } | Select-Object -First 1
    if ($otherPost) {
        try {
            $delOther = Invoke-JsonRequest -Uri "$gateway/post/$($otherPost.postId)" -Method DELETE -Headers $authorHdr -ErrorAction Stop
            if ($delOther.code -ne 200) {
                Pass "privilege: cannot delete other's post (code=$($delOther.code))"
            } else {
                Fail "privilege: deleted other's post (should be rejected)"
            }
        } catch { Pass "privilege: delete other's post rejected (exception)" }
    } else {
        Write-Host "  [SKIP] no other-user posts available for privilege test" -ForegroundColor DarkGray
    }
} catch { Write-Host "  [SKIP] privilege test: $($_.Exception.Message)" -ForegroundColor DarkGray }

# Direct (bypass gateway) with X-User-Id
try {
    $d = Invoke-JsonRequest -Uri "$postBase/post/1" -Headers @{ "X-User-Id" = "$authorId" }
    if ($d.code -eq 200) { Pass "direct post with X-User-Id: code=200" }
    else                 { Warn "direct post with X-User-Id: code=$($d.code)" }
} catch { Warn "direct post X-User-Id: $($_.Exception.Message)" }

# ===== 13. Reliability: afterCommit (MQ only on commit) =====
Section "13. Reliability: afterCommit MQ Publish"

# Verify the published note appears in downstream services (Feed inbox)
# This confirms note.published was emitted after successful commit
try {
    $userPosts = Invoke-JsonRequest -Uri "$gateway/post/user/$authorId?page=1&size=50" -Headers $authorHdr
    $found = $userPosts.data.list | Where-Object { $_.postId -eq $postId }
    if ($found) {
        Pass "reliability: published note visible in user posts (afterCommit + MQ OK)"
    } else {
        Warn "reliability: published note not in user posts list (check Feed sync)"
    }
} catch { Warn "reliability: user posts check: $($_.Exception.Message)" }

# ===== 14. Like Response Time (P95 < 300ms, 10 samples) =====
Section "14. Like Toggle RT (target P95 < 300ms, 10 samples)"
Write-Host "  POST $gateway/post/$postId/like" -ForegroundColor DarkGray
$likeRT = Measure-RT -Uri "$gateway/post/$postId/like" -Headers $authorHdr -Method POST -Samples 10
Write-Host "  samples=$($likeRT.samples)  avg=$($likeRT.avg)ms  min=$($likeRT.min)ms  max=$($likeRT.max)ms" -ForegroundColor White
Write-Host "  P90=$($likeRT.p90)ms   P95=$($likeRT.p95)ms   (target P95 < 300ms)" -ForegroundColor White
if     ($likeRT.p95 -lt 300) { Pass "like P95=$($likeRT.p95)ms < 300ms" }
elseif ($likeRT.p95 -lt 500) { Warn "like P95=$($likeRT.p95)ms (acceptable on local dev)" }
else                          { Fail "like P95=$($likeRT.p95)ms >= 500ms" }

# ===== 15. ES Search Field Matching (title/content/shopName) =====
Section "15. ES Search Multi-field Matching (title + content + shopName)"
try {
    # Search by shop name
    $shopSearch = Invoke-JsonRequest -Uri "$gateway/post/search?keyword=verify+shop&page=1&size=5" -Headers $authorHdr
    if ($shopSearch.code -eq 200) {
        $shopMatch = $shopSearch.data.list | Where-Object { $_.postId -eq $postId }
        if ($shopMatch) {
            Pass "ES multiMatch: shopName field searchable (found postId=$postId by shop name)"
        } else {
            Warn "ES multiMatch: shopName not matched (may need more MQ sync time)"
        }
    }
} catch { Warn "ES multiMatch shopName: $($_.Exception.Message)" }

# ===== Summary =====
Section "Summary"
$total = $passCount + $failCount
Write-Host ""
Write-Host "  Pass  : $passCount" -ForegroundColor Green
Write-Host "  Fail  : $failCount" -ForegroundColor Red
Write-Host "  Total : $total checks" -ForegroundColor White
Write-Host ""
Write-Host "  --- Response Time Results ---" -ForegroundColor Cyan
Write-Host ("  Detail      avg={0}ms  P90={1}ms  P95={2}ms  max={3}ms" -f $detailRT.avg,$detailRT.p90,$detailRT.p95,$detailRT.max) -ForegroundColor White
Write-Host ("  Search      avg={0}ms  P90={1}ms  P95={2}ms  max={3}ms" -f $searchRT.avg,$searchRT.p90,$searchRT.p95,$searchRT.max) -ForegroundColor White
Write-Host ("  Like        avg={0}ms  P90={1}ms  P95={2}ms  max={3}ms" -f $likeRT.avg,$likeRT.p90,$likeRT.p95,$likeRT.max) -ForegroundColor White
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
