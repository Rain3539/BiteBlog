$ErrorActionPreference = "SilentlyContinue"
$transcriptFile = Join-Path $PSScriptRoot "rank-test-result.txt"
Start-Transcript -Path $transcriptFile -Force | Out-Null

$gateway = "http://localhost:8080/api"
$rankBase = "http://localhost:8086/rank"
$userBase = "http://localhost:8081/user"
$redisContainer = "biteblog-redis"
$redisPassword = "redis123456"
$password = "12345678"
$today = Get-Date -Format "yyyy-MM-dd"
$dailyKey = "rank:daily:$today"

function Invoke-JsonRequest {
    param(
        [string]$Uri,
        [string]$Method = "GET",
        [hashtable]$Headers = @{},
        $Body = $null
    )

    if ($null -ne $Body) {
        $json = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Depth 8 -Compress }
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
        return Invoke-RestMethod -Uri $Uri -Method $Method -Headers $Headers -ContentType "application/json; charset=utf-8" -Body $bytes -ErrorAction Stop
    }

    return Invoke-RestMethod -Uri $Uri -Method $Method -Headers $Headers -ErrorAction Stop
}

function Login-TestUser {
    param([string]$Phone)

    $resp = Invoke-JsonRequest -Uri "$userBase/login" -Method POST -Body @{ phone = $Phone; password = $password }
    return @{
        phone = $Phone
        token = $resp.data.token
        userId = $resp.data.userId
        username = $resp.data.username
    }
}

function Get-RedisScore {
    param([long]$PostId)

    $raw = docker exec $redisContainer redis-cli -a $redisPassword ZSCORE $dailyKey "$PostId" 2>$null
    $scoreText = ($raw | Select-Object -First 1)
    if ([string]::IsNullOrWhiteSpace($scoreText)) {
        return $null
    }
    return [double]$scoreText
}

function Wait-ScoreChange {
    param(
        [long]$PostId,
        [Nullable[Double]]$Before,
        [string]$ActionName
    )

    for ($i = 1; $i -le 12; $i++) {
        Start-Sleep -Milliseconds 500
        $score = Get-RedisScore $PostId
        $scoreText = if ($null -eq $score) { "null" } else { $score }
        Write-Host "  try ${i}: score=$scoreText" -ForegroundColor DarkGray
        if ($null -ne $score -and ($null -eq $Before -or $score -gt $Before)) {
            Write-Host "  $ActionName score increased: before=$Before after=$score" -ForegroundColor Green
            return $score
        }
    }

    Write-Host "  $ActionName did not increase score within 6s" -ForegroundColor Yellow
    return Get-RedisScore $PostId
}

Write-Host "===== Rank Service Verification =====" -ForegroundColor Cyan
Write-Host "Redis daily key: $dailyKey" -ForegroundColor White

try {
    $author = Login-TestUser "13800000001"
    $actor = Login-TestUser "13800000004"
    $authorHeaders = @{ "Authorization" = "Bearer $($author.token)" }
    $actorHeaders = @{ "Authorization" = "Bearer $($actor.token)" }
    Write-Host "Author: $($author.phone) $($author.username) userId=$($author.userId)" -ForegroundColor White
    Write-Host "Actor: $($actor.phone) $($actor.username) userId=$($actor.userId)" -ForegroundColor White
} catch {
    Write-Host "Login failed. Run sql/init-data.ps1 and start User/Gateway services first: $($_.Exception.Message)" -ForegroundColor Red
    Stop-Transcript | Out-Null
    exit 1
}

Write-Host ""
Write-Host "===== 1. Health Check =====" -ForegroundColor Cyan
try {
    $health = Invoke-JsonRequest -Uri "$rankBase/health"
    Write-Host "  rank-service status=$($health.data.status)" -ForegroundColor Green
} catch {
    Write-Host "  health check failed: $($_.Exception.Message)" -ForegroundColor Red
}

Write-Host ""
Write-Host "===== 2. Rebuild daily/weekly/all =====" -ForegroundColor Cyan
foreach ($type in @("daily", "weekly", "all")) {
    try {
        $resp = Invoke-JsonRequest -Uri "$rankBase/rebuild?type=$type" -Method POST
        Write-Host "  rebuild ${type}: rebuilt=$($resp.data.rebuilt)" -ForegroundColor Green
    } catch {
        Write-Host "  rebuild $type failed: $($_.Exception.Message)" -ForegroundColor Red
    }
}

Write-Host ""
Write-Host "===== 3. Top10 Whitelist Latency (target < 100ms) =====" -ForegroundColor Cyan
$times = @()
for ($i = 1; $i -le 20; $i++) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        Invoke-JsonRequest -Uri "$gateway/rank/top10?type=daily" | Out-Null
        $sw.Stop()
        $times += $sw.ElapsedMilliseconds
        Write-Host "  try ${i}: $($sw.ElapsedMilliseconds)ms" -ForegroundColor DarkGray
    } catch {
        $sw.Stop()
        Write-Host "  try ${i} failed: $($_.Exception.Message)" -ForegroundColor Red
    }
}
if ($times.Count -gt 0) {
    $avg = ($times | Measure-Object -Average).Average
    $color = if ($avg -lt 100) { "Green" } else { "Yellow" }
    Write-Host "  average: $([math]::Round($avg, 2))ms (target <100ms)" -ForegroundColor $color
}

Write-Host ""
Write-Host "===== 4. Redis ZSet Daily Key =====" -ForegroundColor Cyan
Write-Host "  command: ZREVRANGE $dailyKey 0 9 WITHSCORES" -ForegroundColor White
$redisTop = docker exec $redisContainer redis-cli -a $redisPassword ZREVRANGE $dailyKey 0 9 WITHSCORES 2>$null
if ($redisTop) {
    $redisTop | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }
} else {
    Write-Host "  no Redis daily rank data found; check rebuild and Redis container" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "===== 5. Publish Note -> Rank Event =====" -ForegroundColor Cyan
$testTitle = "RankEventTest-" + (Get-Date -Format "HHmmss")
$publishBody = @{
    title = $testTitle
    content = "Verify note.published event enters Rank Service and writes Redis Sorted Set."
    shopName = "Rank Test Shop"
    address = "Rank Test Address"
    longitude = 114.30
    latitude = 30.50
    scoreColor = 5
    scoreSmell = 5
    scoreTaste = 5
    imageUrls = @()
}
try {
    $pub = Invoke-JsonRequest -Uri "$gateway/post/publish" -Method POST -Headers $authorHeaders -Body $publishBody
    $postId = [long]$pub.data.postId
    Write-Host "  published: postId=$postId title=$testTitle" -ForegroundColor Green
} catch {
    Write-Host "  publish failed: $($_.Exception.Message)" -ForegroundColor Red
    $postId = $null
}

if ($postId) {
    $initialScore = $null
    for ($i = 1; $i -le 12; $i++) {
        Start-Sleep -Milliseconds 500
        $initialScore = Get-RedisScore $postId
        $scoreText = if ($null -eq $initialScore) { "null" } else { $initialScore }
        Write-Host "  try ${i}: score=$scoreText" -ForegroundColor DarkGray
        if ($null -ne $initialScore) {
            Write-Host "  note.published observed: postId=$postId score=$initialScore" -ForegroundColor Green
            break
        }
    }
    if ($null -eq $initialScore) {
        Write-Host "  publish event was not observed within 6s; interaction events will still be checked" -ForegroundColor Yellow
    }
}

if ($postId) {
    Write-Host ""
    Write-Host "===== 6. Like/Favorite/Comment -> Score Increase =====" -ForegroundColor Cyan

    $before = Get-RedisScore $postId
    try {
        Invoke-JsonRequest -Uri "$gateway/post/$postId/like" -Method POST -Headers $actorHeaders | Out-Null
        Write-Host "  like request sent" -ForegroundColor White
        $afterLike = Wait-ScoreChange $postId $before "like"
    } catch {
        Write-Host "  like failed: $($_.Exception.Message)" -ForegroundColor Red
        $afterLike = $before
    }

    try {
        Invoke-JsonRequest -Uri "$gateway/post/$postId/favorite" -Method POST -Headers $actorHeaders | Out-Null
        Write-Host "  favorite request sent" -ForegroundColor White
        $afterFavorite = Wait-ScoreChange $postId $afterLike "favorite"
    } catch {
        Write-Host "  favorite failed: $($_.Exception.Message)" -ForegroundColor Red
        $afterFavorite = $afterLike
    }

    try {
        $commentBody = @{ content = "Rank event test comment"; parentId = $null }
        Invoke-JsonRequest -Uri "$gateway/post/$postId/comment" -Method POST -Headers $actorHeaders -Body $commentBody | Out-Null
        Write-Host "  comment request sent" -ForegroundColor White
        $afterComment = Wait-ScoreChange $postId $afterFavorite "comment"
    } catch {
        Write-Host "  comment failed: $($_.Exception.Message)" -ForegroundColor Red
    }
}

Write-Host ""
Write-Host "===== 7. Rank List API =====" -ForegroundColor Cyan
try {
    $list = Invoke-JsonRequest -Uri "$gateway/rank/list?type=daily&page=1&size=10" -Headers $authorHeaders
    Write-Host "  page=$($list.data.page) size=$($list.data.size) total=$($list.data.total)" -ForegroundColor Green
    foreach ($item in ($list.data.list | Select-Object -First 10)) {
        Write-Host "  #$($item.rankNo) postId=$($item.postId) hotScore=$($item.hotScore) title=$($item.title)" -ForegroundColor DarkGray
    }
} catch {
    Write-Host "  rank list failed: $($_.Exception.Message)" -ForegroundColor Red
}

Write-Host ""
Write-Host "===== Done =====" -ForegroundColor Cyan
Write-Host "Result saved to: $transcriptFile" -ForegroundColor Green
Stop-Transcript | Out-Null
