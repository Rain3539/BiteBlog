$ErrorActionPreference = "SilentlyContinue"

$userBase = "http://localhost:8081"
$postBase = "http://localhost:8082"
$rankBase = "http://localhost:8086"

Write-Host "===== Init Rank Service Test Data =====" -ForegroundColor Cyan

$users = @(
    @{ phone = "13900001001"; password = "12345678"; username = "rank_user_01" },
    @{ phone = "13900001002"; password = "12345678"; username = "rank_user_02" },
    @{ phone = "13900001003"; password = "12345678"; username = "rank_user_03" },
    @{ phone = "13900001004"; password = "12345678"; username = "rank_user_04" },
    @{ phone = "13900001005"; password = "12345678"; username = "rank_user_05" }
)

$accounts = @()

foreach ($u in $users) {
    $json = $u | ConvertTo-Json -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    try {
        $resp = Invoke-RestMethod -Uri "$userBase/user/register" -Method POST -ContentType "application/json; charset=utf-8" -Body $bytes
        if ($resp.code -eq 200) {
            Write-Host "[OK] registered $($u.username), userId=$($resp.data.userId)" -ForegroundColor Green
            $accounts += @{ userId = $resp.data.userId; token = $resp.data.token; username = $u.username }
            continue
        }
    } catch {}

    try {
        $loginResp = Invoke-RestMethod -Uri "$userBase/user/login" -Method POST -ContentType "application/json; charset=utf-8" -Body $bytes
        if ($loginResp.code -eq 200) {
            Write-Host "[OK] login $($u.username), userId=$($loginResp.data.userId)" -ForegroundColor Green
            $accounts += @{ userId = $loginResp.data.userId; token = $loginResp.data.token; username = $u.username }
        }
    } catch {
        Write-Host "[ERR] user init failed: $($u.username), $($_.Exception.Message)" -ForegroundColor Red
    }
}

if ($accounts.Count -lt 2) {
    Write-Host "[STOP] Not enough users. Please start user-service first." -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "===== Publish Rank Notes =====" -ForegroundColor Cyan

$notes = @(
    @{ title = "Rank Test 01 武汉热干面"; content = "Rank 服务测试笔记 01，互动较多，应该靠前。"; shopName = "江城热干面"; address = "武汉市洪山区"; longitude = 114.3660; latitude = 30.5370; scoreColor = 5; scoreSmell = 5; scoreTaste = 5; imageUrls = @() },
    @{ title = "Rank Test 02 广州早茶"; content = "Rank 服务测试笔记 02。"; shopName = "老广茶楼"; address = "广州市天河区"; longitude = 113.3245; latitude = 23.1291; scoreColor = 4; scoreSmell = 5; scoreTaste = 5; imageUrls = @() },
    @{ title = "Rank Test 03 重庆火锅"; content = "Rank 服务测试笔记 03。"; shopName = "山城火锅"; address = "重庆市渝中区"; longitude = 106.5516; latitude = 29.5630; scoreColor = 4; scoreSmell = 4; scoreTaste = 5; imageUrls = @() },
    @{ title = "Rank Test 04 杭州小笼"; content = "Rank 服务测试笔记 04。"; shopName = "西湖小笼"; address = "杭州市西湖区"; longitude = 120.1551; latitude = 30.2741; scoreColor = 4; scoreSmell = 4; scoreTaste = 4; imageUrls = @() },
    @{ title = "Rank Test 05 南京鸭血粉丝"; content = "Rank 服务测试笔记 05。"; shopName = "金陵粉丝汤"; address = "南京市秦淮区"; longitude = 118.7969; latitude = 32.0603; scoreColor = 3; scoreSmell = 4; scoreTaste = 4; imageUrls = @() }
)

$postIds = @()
$publisher = $accounts[0]
$headers = @{ "Authorization" = "Bearer $($publisher.token)"; "X-User-Id" = "$($publisher.userId)" }

foreach ($n in $notes) {
    $json = $n | ConvertTo-Json -Depth 5 -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    try {
        $resp = Invoke-RestMethod -Uri "$postBase/post/publish" -Method POST -Headers $headers -ContentType "application/json; charset=utf-8" -Body $bytes
        if ($resp.code -eq 200) {
            $postIds += $resp.data.postId
            Write-Host "[OK] published postId=$($resp.data.postId), title=$($n.title)" -ForegroundColor Green
        }
    } catch {
        Write-Host "[ERR] publish failed: $($n.title), $($_.Exception.Message)" -ForegroundColor Red
    }
}

Write-Host ""
Write-Host "===== Build Interactions =====" -ForegroundColor Cyan

for ($i = 0; $i -lt $postIds.Count; $i++) {
    $postId = $postIds[$i]
    $maxUserIndex = [Math]::Min($accounts.Count - 1, $postIds.Count - $i)
    for ($uIndex = 1; $uIndex -le $maxUserIndex; $uIndex++) {
        $user = $accounts[$uIndex]
        $h = @{ "Authorization" = "Bearer $($user.token)"; "X-User-Id" = "$($user.userId)" }
        try { Invoke-RestMethod -Uri "$postBase/post/$postId/like" -Method POST -Headers $h | Out-Null } catch {}
        if ($uIndex -le 3) {
            try { Invoke-RestMethod -Uri "$postBase/post/$postId/favorite" -Method POST -Headers $h | Out-Null } catch {}
        }
        if ($uIndex -le 2) {
            $comment = @{ content = "rank 初始化评论 from $($user.username)"; parentId = $null } | ConvertTo-Json -Compress
            $commentBytes = [System.Text.Encoding]::UTF8.GetBytes($comment)
            try { Invoke-RestMethod -Uri "$postBase/post/$postId/comment" -Method POST -Headers $h -ContentType "application/json; charset=utf-8" -Body $commentBytes | Out-Null } catch {}
        }
    }
    Write-Host "[Interaction] postId=$postId done"
}

Write-Host ""
Write-Host "===== Rebuild Rank Cache =====" -ForegroundColor Cyan
foreach ($type in @("daily", "weekly", "all")) {
    try {
        $resp = Invoke-RestMethod -Uri "$rankBase/rank/rebuild?type=$type" -Method POST
        Write-Host "[OK] rebuild $type: $($resp.code)" -ForegroundColor Green
    } catch {
        Write-Host "[WARN] rebuild $type failed. Please confirm rank-service is running. $($_.Exception.Message)" -ForegroundColor Yellow
    }
}

Write-Host ""
Write-Host "===== Query Rank Top10 =====" -ForegroundColor Cyan
try {
    $top = Invoke-RestMethod -Uri "$rankBase/rank/top10?type=daily" -Method GET
    $top.data.list | Format-Table rankNo, postId, title, likeCount, collectCount, commentCount, hotScore
} catch {
    Write-Host "[WARN] query top10 failed: $($_.Exception.Message)" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "===== Done =====" -ForegroundColor Cyan
