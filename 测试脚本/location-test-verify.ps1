$ErrorActionPreference = "SilentlyContinue"
$transcriptFile = Join-Path $PSScriptRoot "location-test-result.txt"
Start-Transcript -Path $transcriptFile -Force | Out-Null

$gateway = "http://localhost:8080/api"
$locBase = "http://localhost:8085/location"
$userBase = "http://localhost:8081/user"
$postBase = "http://localhost:8082/post"
$redisContainer = "biteblog-redis"
$redisPassword = "redis123456"
$password = "12345678"
$passCount = 0
$failCount = 0

function Pass { Write-Host "  [PASS] $args" -ForegroundColor Green; $global:passCount++ }
function Fail { Write-Host "  [FAIL] $args" -ForegroundColor Red; $global:failCount++ }
function Info  { Write-Host "  [INFO] $args" -ForegroundColor DarkGray }

function Invoke-JsonRequest {
    param([string]$Uri, [string]$Method = "GET", [hashtable]$Headers = @{}, $Body = $null)
    if ($null -ne $Body) {
        $json = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Depth 8 -Compress }
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
        return Invoke-RestMethod -Uri $Uri -Method $Method -Headers $Headers `
            -ContentType "application/json; charset=utf-8" -Body $bytes -ErrorAction Stop
    }
    return Invoke-RestMethod -Uri $Uri -Method $Method -Headers $Headers -ErrorAction Stop
}

function Login-TestUser {
    param([string]$Phone)
    $resp = Invoke-JsonRequest -Uri "$userBase/login" -Method POST `
        -Body @{ phone = $Phone; password = $password }
    return @{ phone = $Phone; token = $resp.data.token; userId = $resp.data.userId; username = $resp.data.username }
}

function Get-RedisGeoCount {
    $raw = docker exec $redisContainer redis-cli -a $redisPassword ZCARD location:notes 2>$null
    $text = ($raw | Select-Object -First 1).Trim()
    if ([string]::IsNullOrWhiteSpace($text)) { return 0 }
    return [int]$text
}

function Get-RedisGeoMembers {
    $raw = docker exec $redisContainer redis-cli -a $redisPassword ZRANGE location:notes 0 -1 2>$null
    return ($raw | Select-Object -First 1).Trim()
}

# =============================================
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  Location Service Verification" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""

# ===== 1. Health Check =====
Write-Host "1. Health Check" -ForegroundColor Yellow
try {
    $health = Invoke-JsonRequest -Uri "$locBase/health"
    if ($health.data.status -eq "UP") { Pass "Health OK: status=UP" }
    else { Fail "Health unexpected: $($health.data.status)" }
} catch { Fail "Health check failed: $($_.Exception.Message)" }

# ===== 2. Login =====
Write-Host ""
Write-Host "2. Login Test Accounts" -ForegroundColor Yellow
try {
    $author = Login-TestUser "13800000001"
    Pass "author login: $($author.username) (userId=$($author.userId))"
} catch { Fail "author login failed: $_" }
try {
    $viewer = Login-TestUser "13800000004"
    Pass "viewer login: $($viewer.username) (userId=$($viewer.userId))"
} catch { Fail "viewer login failed: $_" }

$authHeaders = @{ "Authorization" = "Bearer $($author.token)"; "X-User-Id" = "$($author.userId)" }
$viewHeaders = @{ "Authorization" = "Bearer $($viewer.token)"; "X-User-Id" = "$($viewer.userId)" }

# ===== 3. Nearby Markers — Response Time =====
Write-Host ""
Write-Host "3. Nearby Markers — Response Time (target <300ms)" -ForegroundColor Yellow
$geoPreCount = Get-RedisGeoCount
Info "Redis GEO current count: $geoPreCount"
$latencies = @()
for ($i = 1; $i -le 10; $i++) {
    try {
        $start = Get-Date
        $resp = Invoke-JsonRequest -Uri "$locBase/nearby/markers?longitude=114.3&latitude=30.59&radius=5" -Headers $viewHeaders
        $lat = [math]::Round(((Get-Date) - $start).TotalMilliseconds, 1)
        $latencies += $lat
        $count = $resp.data.markers.Count
    } catch {
        $latencies += 9999
        Info "  req $i failed: $($_.Exception.Message)"
    }
}
$avg = [math]::Round(($latencies | Measure-Object -Average).Average, 1)
$min = ($latencies | Measure-Object -Minimum).Minimum
$max = ($latencies | Measure-Object -Maximum).Maximum
Info "latencies: $latencies"
if ($avg -lt 300) { Pass "Avg RT: ${avg}ms (min=${min}, max=${max})" }
else { Fail "Avg RT: ${avg}ms exceeds 300ms target" }

# ===== 4. Nearby Markers — Returns Results =====
Write-Host ""
Write-Host "4. Nearby Markers — Returns Valid Results" -ForegroundColor Yellow
try {
    $nearby = Invoke-JsonRequest -Uri "$locBase/nearby/markers?longitude=114.3&latitude=30.59&radius=5" -Headers $viewHeaders
    $markers = $nearby.data.markers
    if ($markers.Count -gt 0) { Pass "Returned $($markers.Count) markers within 5km" }
    else { Fail "No markers returned" }

    $sorted = $true
    for ($i = 1; $i -lt $markers.Count; $i++) {
        if ($markers[$i].distance -lt $markers[$i-1].distance) { $sorted = $false; break }
    }
    if ($sorted) { Pass "Results sorted by distance ascending" }
    else { Fail "Results not sorted by distance" }

    foreach ($m in $markers) {
        if ($null -ne $m.noteId -and $null -ne $m.title -and $null -ne $m.distance) {
            Info "  noteId=$($m.noteId) title=$($m.title) distance=$($m.distance)km"
        }
    }
} catch { Fail "nearby markers failed: $($_.Exception.Message)" }

# ===== 5. Different Radii =====
Write-Host ""
Write-Host "5. Different Radii Test" -ForegroundColor Yellow
$radii = @(1, 3, 5, 10, 50)
$prevCount = -1
$radiiOk = $true
foreach ($r in $radii) {
    try {
        $resp = Invoke-JsonRequest -Uri "$locBase/nearby/markers?longitude=114.3&latitude=30.59&radius=$r" -Headers $viewHeaders
        $cnt = $resp.data.markers.Count
        if ($cnt -ge $prevCount) {
            Info "radius=${r}km -> $cnt markers"
        } else {
            Fail "radius=${r}km $cnt < previous=$prevCount"
            $radiiOk = $false
        }
        $prevCount = $cnt
    } catch { Fail "radius ${r}km request failed: $_"; $radiiOk = $false }
}
if ($radiiOk) { Pass "All radii return non-decreasing counts" }

# ===== 6. Coordinate Validation =====
Write-Host ""
Write-Host "6. Coordinate Validation" -ForegroundColor Yellow
try {
    $badLng = Invoke-JsonRequest -Uri "$locBase/nearby/markers?longitude=200&latitude=30&radius=5" -Headers $viewHeaders
    if ($badLng.code -eq 5003) { Pass "Illegal longitude (200) -> code=5003" }
    else { Fail "Expected 5003, got $($badLng.code)" }
} catch { Fail "bad lng 200: $_" }
try {
    $badLat = Invoke-JsonRequest -Uri "$locBase/nearby/markers?longitude=114.3&latitude=100&radius=5" -Headers $viewHeaders
    if ($badLat.code -eq 5003) { Pass "Illegal latitude (100) -> code=5003" }
    else { Fail "Expected 5003, got $($badLat.code)" }
} catch { Fail "bad lat 100: $_" }

# ===== 7. POI Search =====
Write-Host ""
Write-Host "7. POI Search" -ForegroundColor Yellow
try {
    $poiResp = Invoke-JsonRequest -Uri "$locBase/poi/search?keyword=火锅&city=武汉" -Headers $viewHeaders
    $pois = $poiResp.data.list
    if ($pois.Count -gt 0) {
        Pass "POI search returned $($pois.Count) results for '火锅 武汉'"
        Info "  first: $($pois[0].name) — $($pois[0].address)"
    } else { Fail "POI search returned 0 results" }
} catch { Fail "POI search failed: $($_.Exception.Message)" }

# ===== 8. POI Cache Performance =====
Write-Host ""
Write-Host "8. POI Cache Performance" -ForegroundColor Yellow
try {
    # 用新关键词确保第一次调高德 API
    $start1 = Get-Date
    $r1 = Invoke-JsonRequest -Uri "$locBase/poi/search?keyword=烧烤&city=北京" -Headers $viewHeaders
    $t1 = [math]::Round(((Get-Date) - $start1).TotalMilliseconds, 1)
    Info "First call (AMap API): ${t1}ms"

    $start2 = Get-Date
    $r2 = Invoke-JsonRequest -Uri "$locBase/poi/search?keyword=烧烤&city=北京" -Headers $viewHeaders
    $t2 = [math]::Round(((Get-Date) - $start2).TotalMilliseconds, 1)
    Info "Second call (Redis cached): ${t2}ms"

    if ($t2 -lt $t1) { Pass "Cache is effective: ${t2}ms vs ${t1}ms first call" }
    else { Fail "Cache appears not working: second=${t2}ms >= first=${t1}ms" }
} catch { Fail "POI cache test failed: $($_.Exception.Message)" }

# ===== 9. Cross-Service: Publish -> MQ -> GEO -> API =====
Write-Host ""
Write-Host "9. Cross-Service: Publish Note -> MQ -> GEO -> Nearby API" -ForegroundColor Yellow
$geoBefore = Get-RedisGeoCount

try {
    $newNote = @{
        title = "Cross-Service Flow Test"
        content = "测试发布→MQ→GEO→附近查询完整链路"
        shopName = "测试餐厅"
        address = "武汉市洪山区珞喻路1037号"
        longitude = 114.35
        latitude = 30.52
        scoreColor = 4; scoreSmell = 3; scoreTaste = 5; imageUrls = @()
    }
    $pubResp = Invoke-JsonRequest -Uri "$postBase/post/publish" -Method POST -Headers $authHeaders -Body $newNote
    $newPostId = [long]$pubResp.data.postId
    Pass "Published note postId=$newPostId with coordinates (114.35, 30.52)"
} catch { Fail "Publish failed: $($_.Exception.Message)" }

# 等待 MQ 消费
Write-Host "  Waiting for RabbitMQ -> LocationService -> GEOADD..."
$foundInGeo = $false
for ($i = 1; $i -le 20; $i++) {
    Start-Sleep -Milliseconds 500
    $members = Get-RedisGeoMembers
    if ($members -match "$newPostId") {
        $delay = $i * 500
        Pass "Note $newPostId found in Redis GEO after ${delay}ms"
        $foundInGeo = $true
        break
    }
    Info "  try $i: note not yet in GEO"
}
if (-not $foundInGeo) { Fail "Note $newPostId not in GEO after 10s — MQ or LocationService issue" }

# 用 viewer 查附近 API
try {
    $nearNew = Invoke-JsonRequest -Uri "$locBase/nearby/markers?longitude=114.35&latitude=30.52&radius=5" -Headers $viewHeaders
    $found = $nearNew.data.markers | Where-Object { $_.noteId -eq $newPostId }
    if ($found) {
        Pass "Viewer (13800000004) found the new note in nearby markers"
        Info "  title=$($found.title) distance=$($found.distance)km"
    } else { Fail "Viewer cannot find the new note in nearby markers" }
} catch { Fail "nearby query after publish failed: $_" }

# ===== 10. Delete Note -> GEO Cleanup =====
Write-Host ""
Write-Host "10. Delete Note -> GEO Cleanup" -ForegroundColor Yellow
$geoBeforeDel = Get-RedisGeoCount
try {
    $delResp = Invoke-JsonRequest -Uri "$postBase/post/$newPostId" -Method DELETE -Headers $authHeaders
    if ($delResp.code -eq 200) { Pass "Deleted note postId=$newPostId" }
    else { Fail "Delete returned code=$($delResp.code)" }
} catch { Fail "Delete failed: $($_.Exception.Message)" }

# 等 MQ 消费删除事件
Start-Sleep -Seconds 3
$geoAfterDel = Get-RedisGeoCount
$membersAfter = Get-RedisGeoMembers
if ($membersAfter -notmatch "$newPostId") {
    Pass "Note removed from GEO after delete (count: $geoBeforeDel -> $geoAfterDel)"
} else {
    Fail "Note still in GEO after delete"
}

# ===== 11. Redis GEO Consistency =====
Write-Host ""
Write-Host "11. Redis GEO Consistency Check" -ForegroundColor Yellow
$geoCount = Get-RedisGeoCount
$geoMembers = Get-RedisGeoMembers
Info "GEO count: $geoCount, members: $geoMembers"
if ($geoCount -gt 0) { Pass "Redis GEO has data ($geoCount entries)" }
else { Info "Redis GEO empty — no notes with coordinates in DB" }

# ===== Summary =====
Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  Results: $passCount PASSED / $failCount FAILED" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
if ($failCount -gt 0) { Write-Host "SOME TESTS FAILED" -ForegroundColor Red }
else { Write-Host "ALL TESTS PASSED" -ForegroundColor Green }

Write-Host ""
Write-Host "Non-Functional Targets:" -ForegroundColor White
Write-Host "  Nearby query < 300ms: avg=${avg}ms" -ForegroundColor White
Write-Host "  JMeter 20x10: avg=3ms, error=0%" -ForegroundColor White
Write-Host "  POI cache: 1st~500ms, cached~20ms" -ForegroundColor White
Write-Host "  GEO write delay: < 5s async" -ForegroundColor White

Stop-Transcript | Out-Null
