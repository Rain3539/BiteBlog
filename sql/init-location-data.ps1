$ErrorActionPreference = "SilentlyContinue"

$userBase = "http://localhost:8081/user"
$postBase = "http://localhost:8082/post"
$password = "12345678"

Write-Host "===== Init Location Service Test Data =====" -ForegroundColor Cyan

# ===== 登录已有用户（复用 init-data.ps1 的账号）=====
function Login-User {
    param([string]$Phone)
    $json = (@{ phone = $Phone; password = $password } | ConvertTo-Json -Compress)
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    $resp = Invoke-RestMethod -Uri "$userBase/login" -Method POST `
        -ContentType "application/json; charset=utf-8" -Body $bytes
    if ($resp.code -ne 200) {
        Write-Host "[ERR] login failed: $Phone, code=$($resp.code), msg=$($resp.msg)" -ForegroundColor Red
        exit 1
    }
    return @{ userId = $resp.data.userId; token = $resp.data.token; username = $resp.data.username }
}

$author1 = Login-User "13800000001"
$author2 = Login-User "13800000004"
Write-Host "[OK] author1: $($author1.username) (userId=$($author1.userId))"
Write-Host "[OK] author2: $($author2.username) (userId=$($author2.userId))"

# ===== 发布测试笔记 =====
Write-Host ""
Write-Host "===== Publish Location Notes =====" -ForegroundColor Cyan

$notesByAuthor1 = @(
    @{ title = "武汉热干面·老字号"; content = "中山大道百年老店，芝麻酱浓郁，面条筋道。"; shopName = "老武汉热干面"; address = "武汉市江汉区中山大道100号"; longitude = 114.305; latitude = 30.593; scoreColor = 4; scoreSmell = 5; scoreTaste = 5; imageUrls = @() },
    @{ title = "户部巷三鲜豆皮"; content = "户部巷必吃，豆皮金黄酥脆，馅料鲜美。"; shopName = "户部巷三鲜豆皮"; address = "武汉市武昌区户部巷15号"; longitude = 114.310; latitude = 30.588; scoreColor = 4; scoreSmell = 4; scoreTaste = 4; imageUrls = @() },
    @{ title = "江汉路小龙虾"; content = "夏天必吃，麻辣小龙虾配冰啤酒。"; shopName = "巴厘龙虾"; address = "武汉市江岸区江汉路步行街88号"; longitude = 114.298; latitude = 30.595; scoreColor = 5; scoreSmell = 4; scoreTaste = 5; imageUrls = @() },
    @{ title = "黄鹤楼藕汤"; content = "洪湖莲藕炖排骨，汤浓藕粉。"; shopName = "黄鹤楼酒家"; address = "武汉市武昌区黄鹤楼路1号"; longitude = 114.308; latitude = 30.547; scoreColor = 4; scoreSmell = 5; scoreTaste = 5; imageUrls = @() },
    @{ title = "楚河汉街武昌鱼"; content = "清蒸武昌鱼，鲜嫩不腥。"; shopName = "楚河食府"; address = "武汉市武昌区楚河汉街56号"; longitude = 114.352; latitude = 30.563; scoreColor = 5; scoreSmell = 5; scoreTaste = 4; imageUrls = @() },
    @{ title = "吉庆街深夜烧烤"; content = "深夜食堂，炭火烤肉烟火气十足。"; shopName = "吉庆街大排档"; address = "武汉市江岸区吉庆街22号"; longitude = 114.302; latitude = 30.596; scoreColor = 3; scoreSmell = 4; scoreTaste = 4; imageUrls = @() }
)

$notesByAuthor2 = @(
    @{ title = "光谷周黑鸭"; content = "甜辣风味鸭脖，武汉城市名片。"; shopName = "周黑鸭光谷总店"; address = "武汉市洪山区光谷广场B1"; longitude = 114.398; latitude = 30.507; scoreColor = 4; scoreSmell = 3; scoreTaste = 5; imageUrls = @() },
    @{ title = "武大樱花奶茶"; content = "武大樱花季限定，粉色奶茶颜值在线。"; shopName = "樱花茶社"; address = "武汉市武昌区珞珈山路16号"; longitude = 114.367; latitude = 30.540; scoreColor = 5; scoreSmell = 4; scoreTaste = 3; imageUrls = @() },
    @{ title = "汉阳牛肉面"; content = "深夜牛骨熬汤，手工面条筋道弹牙。"; shopName = "汉阳肖记牛肉面"; address = "武汉市汉阳区钟家村45号"; longitude = 114.265; latitude = 30.551; scoreColor = 3; scoreSmell = 4; scoreTaste = 4; imageUrls = @() },
    @{ title = "青山麻辣烫"; content = "青山特色，便宜好吃，深夜还有。"; shopName = "青山老周麻辣烫"; address = "武汉市青山区建设一路33号"; longitude = 114.386; latitude = 30.628; scoreColor = 3; scoreSmell = 3; scoreTaste = 4; imageUrls = @() },
    @{ title = "汉口豆皮大王"; content = "老字号豆皮，每天排队。"; shopName = "汉口豆皮大王"; address = "武汉市江汉区前进四路12号"; longitude = 114.290; latitude = 30.590; scoreColor = 4; scoreSmell = 4; scoreTaste = 5; imageUrls = @() },
    @{ title = "武昌鱼头泡饼"; content = "大鱼头炖浓汤泡饼，一绝。"; shopName = "鱼头泡饼"; address = "武汉市武昌区徐东大街98号"; longitude = 114.342; latitude = 30.580; scoreColor = 4; scoreSmell = 5; scoreTaste = 4; imageUrls = @() }
)

$postIds = @()
$h1 = @{ "Authorization" = "Bearer $($author1.token)"; "X-User-Id" = "$($author1.userId)" }
$h2 = @{ "Authorization" = "Bearer $($author2.token)"; "X-User-Id" = "$($author2.userId)" }

Write-Host "--- Author1 ($($author1.username)) ---"
foreach ($n in $notesByAuthor1) {
    $json = $n | ConvertTo-Json -Depth 5 -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    try {
        $resp = Invoke-RestMethod -Uri "$postBase/post/publish" -Method POST -Headers $h1 `
            -ContentType "application/json; charset=utf-8" -Body $bytes
        if ($resp.code -eq 200) {
            $postIds += $resp.data.postId
            Write-Host "[OK] postId=$($resp.data.postId), $($n.title)" -ForegroundColor Green
        }
    } catch {
        Write-Host "[ERR] $($n.title): $($_.Exception.Message)" -ForegroundColor Red
    }
}

Write-Host "--- Author2 ($($author2.username)) ---"
foreach ($n in $notesByAuthor2) {
    $json = $n | ConvertTo-Json -Depth 5 -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    try {
        $resp = Invoke-RestMethod -Uri "$postBase/post/publish" -Method POST -Headers $h2 `
            -ContentType "application/json; charset=utf-8" -Body $bytes
        if ($resp.code -eq 200) {
            $postIds += $resp.data.postId
            Write-Host "[OK] postId=$($resp.data.postId), $($n.title)" -ForegroundColor Green
        }
    } catch {
        Write-Host "[ERR] $($n.title): $($_.Exception.Message)" -ForegroundColor Red
    }
}

# ===== 等待 RabbitMQ 消费 + GEO 写入 =====
Write-Host ""
Write-Host "===== Wait for RabbitMQ GEO Write =====" -ForegroundColor Cyan
for ($i = 1; $i -le 10; $i++) {
    Start-Sleep -Milliseconds 500
    $cnt = docker exec biteblog-redis redis-cli -a redis123456 ZCARD location:notes 2>$null | Select-Object -First 1
    Write-Host "  try ${i}: location:notes ZCARD=$cnt"
}

# ===== 验证 Redis GEO 数据 =====
Write-Host ""
Write-Host "===== Redis GEO Data =====" -ForegroundColor Cyan
docker exec biteblog-redis redis-cli -a redis123456 ZRANGE location:notes 0 -1 WITHCOORDS 2>$null

Write-Host ""
Write-Host "===== Done =====" -ForegroundColor Cyan
Write-Host "Published $($postIds.Count) notes across 2 authors"
Write-Host "Author1 (BigV): 13800000001 ($($author1.userId)) — 6 notes"
Write-Host "Author2 (Normal): 13800000004 ($($author2.userId)) — 6 notes"
Write-Host "Test center: lng=114.3, lat=30.59 (武汉中山公园)"
