$ErrorActionPreference = "Continue"
$transcriptFile = Join-Path $PSScriptRoot "recommend-test-result.txt"
Start-Transcript -Path $transcriptFile -Force | Out-Null

$gatewayBase = "http://localhost:8080/api"
$recommendBase = "http://localhost:8084/recommend"
$redisContainer = "biteblog-redis"
$redisPassword = "redis123456"
$rankDailyKey = "rank:daily:$(Get-Date -Format 'yyyy-MM-dd')"

function Invoke-Json($uri, $method = "GET", $body = $null, $headers = $null) {
    if ($body) {
        $json = $body | ConvertTo-Json -Depth 8 -Compress
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
        return Invoke-RestMethod -Uri $uri -Method $method -Headers $headers -ContentType "application/json; charset=utf-8" -Body $bytes -ErrorAction Stop
    }
    return Invoke-RestMethod -Uri $uri -Method $method -Headers $headers -ErrorAction Stop
}

function Measure-Api($name, [scriptblock]$block) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $result = & $block
        $sw.Stop()
        Write-Host ("  {0}: {1}ms OK" -f $name, $sw.ElapsedMilliseconds) -ForegroundColor Green
        return @{ ok = $true; elapsed = $sw.ElapsedMilliseconds; data = $result }
    } catch {
        $sw.Stop()
        Write-Host ("  {0}: {1}ms FAIL - {2}" -f $name, $sw.ElapsedMilliseconds, $_.Exception.Message) -ForegroundColor Red
        return @{ ok = $false; elapsed = $sw.ElapsedMilliseconds; data = $null }
    }
}

function Login-TestUser($phone) {
    $resp = Invoke-Json "$gatewayBase/user/login" "POST" @{ phone = $phone; password = "12345678" }
    if ($resp.code -ne 200) {
        throw "Login failed for ${phone}: $($resp.msg)"
    }
    return @{
        phone = $phone
        userId = [long]$resp.data.userId
        username = $resp.data.username
        token = $resp.data.token
        headers = @{ "Authorization" = "Bearer $($resp.data.token)"; "X-User-Id" = "$($resp.data.userId)" }
        directHeaders = @{ "X-User-Id" = "$($resp.data.userId)" }
    }
}

function Clear-Exposure($userId) {
    docker exec -e "REDISCLI_AUTH=$redisPassword" $redisContainer redis-cli DEL "exposure:$userId" 2>$null | Out-Null
}

function Write-LatencyStats($name, $times, $targetMs = 600) {
    if ($times.Count -eq 0) {
        Write-Host "  $name latency: no successful samples" -ForegroundColor Yellow
        return
    }
    $avg = ($times | Measure-Object -Average).Average
    $max = ($times | Measure-Object -Maximum).Maximum
    $min = ($times | Measure-Object -Minimum).Minimum
    $color = if ($avg -lt $targetMs) { "Green" } else { "Yellow" }
    Write-Host ("  {0} latency avg={1}ms, min={2}ms, max={3}ms, target<{4}ms" -f $name, [math]::Round($avg), $min, $max, $targetMs) -ForegroundColor $color
}

try {
    Write-Host "===== Recommend Service verification =====" -ForegroundColor Cyan
    Write-Host "Time: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -ForegroundColor White
    Write-Host "Transcript: $transcriptFile" -ForegroundColor White

    Write-Host ""
    Write-Host "===== 0. Login test users =====" -ForegroundColor Cyan
    $coldUser = Login-TestUser "13800000060"
    $foodieUser = Login-TestUser "13800000005"
    $bigvUser = Login-TestUser "13800000001"
    Write-Host "  cold-start user: $($coldUser.username), phone=$($coldUser.phone), userId=$($coldUser.userId)" -ForegroundColor White
    Write-Host "  personalized user: $($foodieUser.username), phone=$($foodieUser.phone), userId=$($foodieUser.userId)" -ForegroundColor White
    Write-Host "  MQ author user: $($bigvUser.username), phone=$($bigvUser.phone), userId=$($bigvUser.userId)" -ForegroundColor White

    Write-Host ""
    Write-Host "===== 1. Health and Gateway route =====" -ForegroundColor Cyan
    Measure-Api "Direct /recommend/health" { Invoke-Json "$recommendBase/health" } | Out-Null
    Measure-Api "Gateway /api/recommend/health" { Invoke-Json "$gatewayBase/recommend/health" "GET" $null $coldUser.headers } | Out-Null
    Measure-Api "Manual precompute hot pool and item_sim_index" {
        Invoke-Json "$recommendBase/internal/precompute" "POST"
    } | Out-Null
    $rankDailyCount = docker exec -e "REDISCLI_AUTH=$redisPassword" $redisContainer redis-cli ZCARD $rankDailyKey 2>$null
    Write-Host "  Redis rank daily key=$rankDailyKey, count=$rankDailyCount" -ForegroundColor White

    Write-Host ""
    Write-Host "===== 1.1 MQ event consumption: publish and interaction =====" -ForegroundColor Cyan
    $mqTitle = "Recommend MQ Test $(Get-Date -Format 'MMddHHmmss')"
    $publishBody = @{
        title = $mqTitle
        content = "Recommend Service MQ event verification: note.published should refresh ES post_index and Redis hot pool."
        shopName = "Recommend MQ Demo Shop"
        address = "Guangzhou Tianhe"
        longitude = 113.320000
        latitude = 23.120000
        scoreColor = 4
        scoreSmell = 4
        scoreTaste = 5
        imageUrls = @()
    }
    $publishResult = Measure-Api "publish note via Post Service" {
        Invoke-Json "$gatewayBase/post/publish" "POST" $publishBody $bigvUser.headers
    }
    if ($publishResult.ok) {
        $mqPostId = [long]$publishResult.data.data.postId
        Start-Sleep -Seconds 2
        $esDoc = Measure-Api "MQ note.published -> ES post_index" {
            Invoke-Json "http://localhost:9200/post_index/_doc/$mqPostId"
        }
        $hotScoreBefore = docker exec -e "REDISCLI_AUTH=$redisPassword" $redisContainer redis-cli ZSCORE recommend:hot:pool $mqPostId 2>$null
        if ($esDoc.ok -and $hotScoreBefore) {
            Write-Host "  note.published consumed: postId=$mqPostId, ES found=True, hotScore=$hotScoreBefore" -ForegroundColor Green
        } else {
            Write-Host "  note.published consumed: check ES/Redis result manually, postId=$mqPostId" -ForegroundColor Yellow
        }

        Measure-Api "like note to publish interaction.like" {
            Invoke-Json "$gatewayBase/post/$mqPostId/like" "POST" $null $foodieUser.headers
        } | Out-Null
        Start-Sleep -Seconds 3
        $hotScoreAfter = docker exec -e "REDISCLI_AUTH=$redisPassword" $redisContainer redis-cli ZSCORE recommend:hot:pool $mqPostId 2>$null
        Write-Host "  interaction.like consumed: hotScoreBefore=$hotScoreBefore, hotScoreAfter=$hotScoreAfter" -ForegroundColor White
    }

    Write-Host ""
    Write-Host "===== 2. Cold-start latency: 10 requests, target avg < 600ms =====" -ForegroundColor Cyan
    Clear-Exposure $coldUser.userId
    $times = @()
    $lastColdResp = $null
    for ($i = 1; $i -le 10; $i++) {
        $uri = "$recommendBase/discover?cursor=0&size=20"
        $r = Measure-Api "cold-start-$i" {
            Invoke-Json $uri "GET" $null $coldUser.directHeaders
        }
        if ($r.ok) {
            $times += $r.elapsed
            $lastColdResp = $r.data
            Clear-Exposure $coldUser.userId
        }
    }
    if ($times.Count -gt 0) {
        Write-LatencyStats "cold-start" $times 600
        Write-Host "  result count=$($lastColdResp.data.list.Count), hasMore=$($lastColdResp.data.hasMore), cursor=$($lastColdResp.data.cursor)" -ForegroundColor White
    }

    Write-Host ""
    Write-Host "===== 2.1 Personalized latency: 10 requests, target avg < 600ms =====" -ForegroundColor Cyan
    Clear-Exposure $foodieUser.userId
    $personalTimes = @()
    $lastPersonalResp = $null
    for ($i = 1; $i -le 10; $i++) {
        $uri = "$recommendBase/discover?cursor=0&size=20"
        $r = Measure-Api "personalized-$i" {
            Invoke-Json $uri "GET" $null $foodieUser.directHeaders
        }
        if ($r.ok) {
            $personalTimes += $r.elapsed
            $lastPersonalResp = $r.data
            Clear-Exposure $foodieUser.userId
        }
    }
    Write-LatencyStats "personalized" $personalTimes 600
    if ($lastPersonalResp) {
        Write-Host "  result count=$($lastPersonalResp.data.list.Count), hasMore=$($lastPersonalResp.data.hasMore), cursor=$($lastPersonalResp.data.cursor)" -ForegroundColor White
    }
    $behaviorTtl = docker exec -e "REDISCLI_AUTH=$redisPassword" $redisContainer redis-cli TTL "behavior:$($foodieUser.userId)" 2>$null
    Write-Host "  behavior cache key=behavior:$($foodieUser.userId), TTL=$behaviorTtl seconds" -ForegroundColor White

    Write-Host ""
    Write-Host "===== 2.2 Tag recall latency: 10 requests, target avg < 600ms =====" -ForegroundColor Cyan
    Clear-Exposure $foodieUser.userId
    $tagTimes = @()
    $lastTagResp = $null
    for ($i = 1; $i -le 10; $i++) {
        $uri = "$recommendBase/discover?cursor=0&size=20&tag=Hotpot&city=Guangzhou"
        $r = Measure-Api "tag-recall-$i" {
            Invoke-Json $uri "GET" $null $foodieUser.directHeaders
        }
        if ($r.ok) {
            $tagTimes += $r.elapsed
            $lastTagResp = $r.data
            Clear-Exposure $foodieUser.userId
        }
    }
    Write-LatencyStats "tag-recall" $tagTimes 600
    if ($lastTagResp) {
        Write-Host "  result count=$($lastTagResp.data.list.Count), hasMore=$($lastTagResp.data.hasMore), cursor=$($lastTagResp.data.cursor)" -ForegroundColor White
    }

    Write-Host ""
    Write-Host "===== 3. Pagination consistency =====" -ForegroundColor Cyan
    Clear-Exposure $coldUser.userId
    $page1Uri = "$recommendBase/discover?cursor=0&size=10"
    $page1 = Invoke-Json $page1Uri "GET" $null $coldUser.directHeaders
    $ids1 = @($page1.data.list | ForEach-Object { $_.postId })
    Write-Host "  page1 ids=[$($ids1 -join ',')] hasMore=$($page1.data.hasMore) cursor=$($page1.data.cursor)" -ForegroundColor White
    $ids2 = @()
    if ($page1.data.hasMore -and $page1.data.cursor) {
        $page2Uri = "$recommendBase/discover?cursor=$($page1.data.cursor)&size=10"
        $page2 = Invoke-Json $page2Uri "GET" $null $coldUser.directHeaders
        $ids2 = @($page2.data.list | ForEach-Object { $_.postId })
        Write-Host "  page2 ids=[$($ids2 -join ',')] hasMore=$($page2.data.hasMore) cursor=$($page2.data.cursor)" -ForegroundColor White
    }
    $dups = @($ids1 + $ids2 | Group-Object | Where-Object { $_.Count -gt 1 })
    if ($dups.Count -eq 0) {
        Write-Host "  pagination check: PASS, no duplicates in first two pages" -ForegroundColor Green
    } else {
        Write-Host "  pagination check: FAIL, duplicates=$($dups.Name -join ',')" -ForegroundColor Red
    }

    Write-Host ""
    Write-Host "===== 4. Tag recall: ES first, MySQL fallback =====" -ForegroundColor Cyan
    Clear-Exposure $foodieUser.userId
    $tagUri = "$recommendBase/discover?cursor=0&size=20&tag=Hotpot&city=Guangzhou"
    $tagResp = Measure-Api "tag=Hotpot city=Guangzhou" {
        Invoke-Json $tagUri "GET" $null $foodieUser.directHeaders
    }
    if ($tagResp.ok) {
        $titles = @($tagResp.data.data.list | Select-Object -First 5 | ForEach-Object { $_.title })
        Write-Host "  top titles=[$($titles -join ' | ')]" -ForegroundColor White
    }

    Write-Host ""
    Write-Host "===== 5. Exposure Lua preclaim and idempotent report =====" -ForegroundColor Cyan
    $redisMembers = docker exec -e "REDISCLI_AUTH=$redisPassword" $redisContainer redis-cli SMEMBERS "exposure:$($coldUser.userId)" 2>$null
    $redisTtl = docker exec -e "REDISCLI_AUTH=$redisPassword" $redisContainer redis-cli TTL "exposure:$($coldUser.userId)" 2>$null
    Write-Host "  after discover: exposure:$($coldUser.userId) members=[$(($redisMembers -join ',').Trim())]" -ForegroundColor White
    Write-Host "  TTL=$redisTtl seconds" -ForegroundColor White
    if ($ids1.Count -gt 0) {
        $sampleIds = @($ids1 | Select-Object -First 3)
        $body = @{ postIds = $sampleIds }
        Measure-Api "exposures-post-1" { Invoke-Json "$recommendBase/exposures" "POST" $body $coldUser.directHeaders } | Out-Null
        Measure-Api "exposures-post-2-repeat" { Invoke-Json "$recommendBase/exposures" "POST" $body $coldUser.directHeaders } | Out-Null
        $scard = docker exec -e "REDISCLI_AUTH=$redisPassword" $redisContainer redis-cli SCARD "exposure:$($coldUser.userId)" 2>$null
        Write-Host "  repeated exposure ids=[$($sampleIds -join ',')], Redis SCARD=$scard" -ForegroundColor White
    }

    Write-Host ""
    Write-Host "===== 6. ES item_sim_index ItemCF data =====" -ForegroundColor Cyan
    if ($ids1.Count -gt 0) {
        $query = @{
            query = @{ term = @{ item_id = [long]$ids1[0] } }
            sort = @(@{ score = @{ order = "desc" } })
            size = 5
        }
        try {
            $result = Invoke-Json "http://localhost:9200/item_sim_index/_search" "POST" $query
            $pairs = @($result.hits.hits | ForEach-Object { "$($_._source.similar_item_id):$($_._source.score)" })
            Write-Host "  item_sim_index item_id=$($ids1[0]) => [$($pairs -join ',')]" -ForegroundColor White
        } catch {
            Write-Host "  item_sim_index query failed: $($_.Exception.Message)" -ForegroundColor Yellow
        }
    }

    Write-Host ""
    Write-Host "===== 7. Boundary parameters =====" -ForegroundColor Cyan
    Clear-Exposure $coldUser.userId
    $size1Uri = "$recommendBase/discover?cursor=0&size=1"
    $size1 = Measure-Api "size=1" { Invoke-Json $size1Uri "GET" $null $coldUser.directHeaders }
    Clear-Exposure $coldUser.userId
    $size999Uri = "$recommendBase/discover?cursor=0&size=999"
    $size999 = Measure-Api "size=999 capped to 50" { Invoke-Json $size999Uri "GET" $null $coldUser.directHeaders }
    if ($size1.ok) { Write-Host "  size=1 count=$($size1.data.data.list.Count)" -ForegroundColor White }
    if ($size999.ok) { Write-Host "  size=999 count=$($size999.data.data.list.Count), expected <= 50" -ForegroundColor White }

    Write-Host ""
    Write-Host "===== 8. JMeter report note =====" -ForegroundColor Cyan
    Write-Host "  Open after JMeter run: jmeter/recommendservice-report/index.html" -ForegroundColor White
    Write-Host "  Screenshot Dashboard and Statistics table." -ForegroundColor White

    Write-Host ""
    Write-Host "===== Done =====" -ForegroundColor Cyan
} finally {
    Stop-Transcript | Out-Null
    Write-Host "Test result saved to: $transcriptFile" -ForegroundColor Green
}
