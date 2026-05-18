$ErrorActionPreference = "Continue"

$userBase = "http://localhost:8081"
$postBase = "http://localhost:8082"
$rankBase = "http://localhost:8086"
$password = "12345678"

Write-Host "===== Init Rank Service Test Data =====" -ForegroundColor Cyan
Write-Host "This script only uses users created by sql/init-data.ps1."
Write-Host "No extra test users will be registered."

function Get-SeedPhone($index) {
    return "138000000{0:D2}" -f $index
}

function Invoke-Json($uri, $method, $body, $headers = $null) {
    $json = $body | ConvertTo-Json -Depth 10 -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    if ($headers) {
        return Invoke-RestMethod -Uri $uri -Method $method -Headers $headers -ContentType "application/json; charset=utf-8" -Body $bytes -ErrorAction Stop
    }
    return Invoke-RestMethod -Uri $uri -Method $method -ContentType "application/json; charset=utf-8" -Body $bytes -ErrorAction Stop
}

function Login-SeedUser($index) {
    $phone = Get-SeedPhone $index
    $body = @{ phone = $phone; password = $password }
    try {
        $resp = Invoke-Json "$userBase/user/login" "POST" $body
        if ($resp.code -eq 200) {
            return @{
                phone = $phone
                userId = [long]$resp.data.userId
                username = $resp.data.username
                token = $resp.data.token
            }
        }
        Write-Host "[ERR] login failed: $phone, $($resp.msg)" -ForegroundColor Red
    } catch {
        Write-Host "[ERR] login failed: $phone, $($_.Exception.Message)" -ForegroundColor Red
    }
    return $null
}

function Get-Headers($account) {
    return @{
        "Authorization" = "Bearer $($account.token)"
        "X-User-Id" = "$($account.userId)"
    }
}

function Publish-RankNote($publisher, $note) {
    $headers = Get-Headers $publisher
    try {
        $resp = Invoke-Json "$postBase/post/publish" "POST" $note $headers
        if ($resp.code -eq 200) {
            Write-Host "[OK] published postId=$($resp.data.postId), author=$($publisher.phone), title=$($note.title)" -ForegroundColor Green
            return [long]$resp.data.postId
        }
        Write-Host "[ERR] publish failed: $($note.title), $($resp.msg)" -ForegroundColor Red
    } catch {
        Write-Host "[ERR] publish failed: $($note.title), $($_.Exception.Message)" -ForegroundColor Red
    }
    return $null
}

function Add-RankInteractions($postId, $author, $accounts, $likeCount, $collectCount, $commentCount) {
    if (!$postId) {
        return
    }

    $actors = $accounts |
        Where-Object { $_.userId -ne $author.userId } |
        Sort-Object phone

    $likers = $actors | Select-Object -First $likeCount
    foreach ($actor in $likers) {
        try {
            Invoke-RestMethod -Uri "$postBase/post/$postId/like" -Method POST -Headers (Get-Headers $actor) -ErrorAction Stop | Out-Null
        } catch {
            Write-Host "[WARN] like failed: postId=$postId, user=$($actor.phone)" -ForegroundColor Yellow
        }
    }

    $collectors = $actors | Select-Object -First $collectCount
    foreach ($actor in $collectors) {
        try {
            Invoke-RestMethod -Uri "$postBase/post/$postId/favorite" -Method POST -Headers (Get-Headers $actor) -ErrorAction Stop | Out-Null
        } catch {
            Write-Host "[WARN] favorite failed: postId=$postId, user=$($actor.phone)" -ForegroundColor Yellow
        }
    }

    $commenters = $actors | Select-Object -First $commentCount
    $index = 1
    foreach ($actor in $commenters) {
        $comment = @{
            content = "热榜初始化评论 $index - $($actor.phone)"
            parentId = $null
        }
        try {
            Invoke-Json "$postBase/post/$postId/comment" "POST" $comment (Get-Headers $actor) | Out-Null
        } catch {
            Write-Host "[WARN] comment failed: postId=$postId, user=$($actor.phone)" -ForegroundColor Yellow
        }
        $index++
    }

    Write-Host "[Interaction] postId=$postId, likes=$($likers.Count), collects=$($collectors.Count), comments=$($commenters.Count)"
}

Write-Host ""
Write-Host "===== Login Existing Seed Users =====" -ForegroundColor Cyan

$accounts = @()
for ($i = 1; $i -le 60; $i++) {
    $account = Login-SeedUser $i
    if ($account) {
        $accounts += $account
    }
}

if ($accounts.Count -lt 60) {
    Write-Host "[STOP] Prepared $($accounts.Count)/60 users. Run sql/init-data.ps1 first and confirm user-service is running." -ForegroundColor Red
    exit 1
}

$byPhone = @{}
foreach ($account in $accounts) {
    $byPhone[$account.phone] = $account
}

$authorBigV = $byPhone["13800000001"]
$authorNormal = $byPhone["13800000004"]

Write-Host ""
Write-Host "===== Publish Notes On Existing Users =====" -ForegroundColor Cyan
Write-Host "Authors: 13800000001(big V) and 13800000004(normal user)"

$notePlans = @(
    @{
        author = $authorBigV
        likeCount = 52
        collectCount = 36
        commentCount = 24
        note = @{
            title = "热榜样例01 江城热干面早餐"
            content = "用于 Rank Service 热度排序验证。该笔记由大V用户发布，并注入高点赞、高收藏、高评论互动。"
            shopName = "江城热干面"
            address = "武汉市洪山区珞喻路"
            longitude = 114.3660000
            latitude = 30.5370000
            scoreColor = 5
            scoreSmell = 5
            scoreTaste = 5
            imageUrls = @()
        }
    },
    @{
        author = $authorBigV
        likeCount = 44
        collectCount = 30
        commentCount = 18
        note = @{
            title = "热榜样例02 广州早茶点心"
            content = "用于日榜和周榜排序验证，收藏权重应明显影响热度分数。"
            shopName = "老广茶楼"
            address = "广州市天河区体育西路"
            longitude = 113.3245000
            latitude = 23.1291000
            scoreColor = 4
            scoreSmell = 5
            scoreTaste = 5
            imageUrls = @()
        }
    },
    @{
        author = $authorBigV
        likeCount = 36
        collectCount = 22
        commentCount = 12
        note = @{
            title = "热榜样例03 重庆火锅夜宵"
            content = "用于验证互动事件通过 Post Service 进入 RabbitMQ 后，Rank Service 能更新热度。"
            shopName = "山城火锅"
            address = "重庆市渝中区解放碑"
            longitude = 106.5516000
            latitude = 29.5630000
            scoreColor = 4
            scoreSmell = 4
            scoreTaste = 5
            imageUrls = @()
        }
    },
    @{
        author = $authorBigV
        likeCount = 28
        collectCount = 18
        commentCount = 10
        note = @{
            title = "热榜样例04 杭州小笼汤包"
            content = "中等热度笔记，用于分页和排名连续性验证。"
            shopName = "西湖小笼"
            address = "杭州市西湖区文三路"
            longitude = 120.1551000
            latitude = 30.2741000
            scoreColor = 4
            scoreSmell = 4
            scoreTaste = 4
            imageUrls = @()
        }
    },
    @{
        author = $authorNormal
        likeCount = 34
        collectCount = 25
        commentCount = 16
        note = @{
            title = "热榜样例05 南京鸭血粉丝汤"
            content = "普通用户发布的高质量笔记，用于验证榜单不只依赖作者身份，而是依赖内容互动热度。"
            shopName = "金陵粉丝汤"
            address = "南京市秦淮区夫子庙"
            longitude = 118.7969000
            latitude = 32.0603000
            scoreColor = 4
            scoreSmell = 5
            scoreTaste = 5
            imageUrls = @()
        }
    },
    @{
        author = $authorNormal
        likeCount = 24
        collectCount = 14
        commentCount = 8
        note = @{
            title = "热榜样例06 成都串串香"
            content = "用于验证周榜和总榜查询返回字段完整。"
            shopName = "宽窄串串"
            address = "成都市青羊区宽窄巷子"
            longitude = 104.0665000
            latitude = 30.5723000
            scoreColor = 4
            scoreSmell = 4
            scoreTaste = 5
            imageUrls = @()
        }
    },
    @{
        author = $authorNormal
        likeCount = 18
        collectCount = 10
        commentCount = 6
        note = @{
            title = "热榜样例07 上海生煎"
            content = "低中热度样例，用于验证分页第二梯队数据。"
            shopName = "弄堂生煎"
            address = "上海市黄浦区人民广场"
            longitude = 121.4737000
            latitude = 31.2304000
            scoreColor = 3
            scoreSmell = 4
            scoreTaste = 4
            imageUrls = @()
        }
    },
    @{
        author = $authorNormal
        likeCount = 12
        collectCount = 6
        commentCount = 4
        note = @{
            title = "热榜样例08 西安肉夹馍"
            content = "低热度样例，用于验证榜尾展示和总数统计。"
            shopName = "长安肉夹馍"
            address = "西安市碑林区钟楼"
            longitude = 108.9402000
            latitude = 34.3416000
            scoreColor = 3
            scoreSmell = 4
            scoreTaste = 4
            imageUrls = @()
        }
    }
)

$createdPostIds = @()
foreach ($plan in $notePlans) {
    $postId = Publish-RankNote $plan.author $plan.note
    if ($postId) {
        $createdPostIds += $postId
        Add-RankInteractions $postId $plan.author $accounts $plan.likeCount $plan.collectCount $plan.commentCount
    }
}

Write-Host ""
Write-Host "===== Rebuild Rank Cache =====" -ForegroundColor Cyan
foreach ($type in @("daily", "weekly", "all")) {
    try {
        $resp = Invoke-RestMethod -Uri "$rankBase/rank/rebuild?type=$type" -Method POST -ErrorAction Stop
        Write-Host "[OK] rebuild ${type}: $($resp.code)" -ForegroundColor Green
    } catch {
        Write-Host "[WARN] rebuild $type failed. Please confirm rank-service is running. $($_.Exception.Message)" -ForegroundColor Yellow
    }
}

Write-Host ""
Write-Host "===== Query Rank Top10 =====" -ForegroundColor Cyan
try {
    $top = Invoke-RestMethod -Uri "$rankBase/rank/top10?type=daily" -Method GET -ErrorAction Stop
    $top.data.list | Format-Table rankNo, postId, title, likeCount, collectCount, commentCount, hotScore
} catch {
    Write-Host "[WARN] query top10 failed: $($_.Exception.Message)" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "===== Done =====" -ForegroundColor Cyan
Write-Host "Created rank posts: $($createdPostIds -join ', ')"
