$ErrorActionPreference = "SilentlyContinue"

$userBase = "http://localhost:8081"
$postBase = "http://localhost:8082"

Write-Host "===== Init Location Service Test Data =====" -ForegroundColor Cyan

# 注册/登录测试用户
$users = @(
    @{ phone = "13900002001"; password = "12345678"; username = "loc_user_01" },
    @{ phone = "13900002002"; password = "12345678"; username = "loc_user_02" },
    @{ phone = "13900002003"; password = "12345678"; username = "loc_user_03" },
    @{ phone = "13900002004"; password = "12345678"; username = "loc_user_04" },
    @{ phone = "13900002005"; password = "12345678"; username = "loc_user_05" }
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
Write-Host "===== Publish Location Notes (with real coordinates) =====" -ForegroundColor Cyan

# 以武汉为中心，半径 5km 内散布坐标（用于测试 GEORADIUS）
$notes = @(
    @{ title = "Location Test 01 武汉热干面"; content = "武汉中山大道老字号热干面，芝麻酱浓郁。"; shopName = "老武汉热干面"; address = "武汉市江汉区中山大道100号"; longitude = 114.305; latitude = 30.593; scoreColor = 4; scoreSmell = 5; scoreTaste = 5; imageUrls = @() },
    @{ title = "Location Test 02 户部巷三鲜豆皮"; content = "户部巷经典小吃，豆皮金黄酥脆。"; shopName = "户部巷三鲜豆皮"; address = "武汉市武昌区户部巷15号"; longitude = 114.310; latitude = 30.588; scoreColor = 4; scoreSmell = 4; scoreTaste = 4; imageUrls = @() },
    @{ title = "Location Test 03 江汉路小龙虾"; content = "夏天必吃，麻辣小龙虾。"; shopName = "巴厘龙虾"; address = "武汉市江岸区江汉路步行街88号"; longitude = 114.298; latitude = 30.595; scoreColor = 5; scoreSmell = 4; scoreTaste = 5; imageUrls = @() },
    @{ title = "Location Test 04 光谷周黑鸭"; content = "甜辣风味鸭脖，武汉名片。"; shopName = "周黑鸭光谷总店"; address = "武汉市洪山区光谷广场B1"; longitude = 114.398; latitude = 30.507; scoreColor = 4; scoreSmell = 3; scoreTaste = 5; imageUrls = @() },
    @{ title = "Location Test 05 楚河汉街武昌鱼"; content = "清蒸武昌鱼，鲜嫩不腥。"; shopName = "楚河食府"; address = "武汉市武昌区楚河汉街56号"; longitude = 114.352; latitude = 30.563; scoreColor = 5; scoreSmell = 5; scoreTaste = 4; imageUrls = @() },
    @{ title = "Location Test 06 吉庆街烧烤"; content = "深夜食堂，烟火气十足。"; shopName = "吉庆街大排档"; address = "武汉市江岸区吉庆街22号"; longitude = 114.302; latitude = 30.596; scoreColor = 3; scoreSmell = 4; scoreTaste = 4; imageUrls = @() },
    @{ title = "Location Test 07 武大樱花奶茶"; content = "武大樱花季限定，颜值在线。"; shopName = "樱花茶社"; address = "武汉市武昌区珞珈山路16号"; longitude = 114.367; latitude = 30.540; scoreColor = 5; scoreSmell = 4; scoreTaste = 3; imageUrls = @() },
    @{ title = "Location Test 08 黄鹤楼藕汤"; content = "洪湖莲藕炖排骨，汤浓藕粉。"; shopName = "黄鹤楼酒家"; address = "武汉市武昌区黄鹤楼路1号"; longitude = 114.308; latitude = 30.547; scoreColor = 4; scoreSmell = 5; scoreTaste = 5; imageUrls = @() },
    @{ title = "Location Test 09 汉阳牛肉面"; content = "深夜牛骨熬汤，面条筋道。"; shopName = "汉阳肖记牛肉面"; address = "武汉市汉阳区钟家村45号"; longitude = 114.265; latitude = 30.551; scoreColor = 3; scoreSmell = 4; scoreTaste = 4; imageUrls = @() },
    @{ title = "Location Test 10 汉口豆皮大王"; content = "老字号豆皮，每天排队。"; shopName = "汉口豆皮大王"; address = "武汉市江汉区前进四路12号"; longitude = 114.290; latitude = 30.590; scoreColor = 4; scoreSmell = 4; scoreTaste = 5; imageUrls = @() },
    @{ title = "Location Test 11 武昌鱼头泡饼"; content = "大鱼头炖汤泡饼，一绝。"; shopName = "鱼头泡饼"; address = "武汉市武昌区徐东大街98号"; longitude = 114.342; latitude = 30.580; scoreColor = 4; scoreSmell = 5; scoreTaste = 4; imageUrls = @() },
    @{ title = "Location Test 12 青山麻辣烫"; content = "青山特色麻辣烫，便宜好吃。"; shopName = "青山老周麻辣烫"; address = "武汉市青山区建设一路33号"; longitude = 114.386; latitude = 30.628; scoreColor = 3; scoreSmell = 3; scoreTaste = 4; imageUrls = @() }
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
        } else {
            Write-Host "[FAIL] publish: $($n.title), code=$($resp.code), msg=$($resp.msg)" -ForegroundColor Red
        }
    } catch {
        Write-Host "[ERR] publish failed: $($n.title), $($_.Exception.Message)" -ForegroundColor Red
    }
}

Write-Host ""
Write-Host "===== Wait for RabbitMQ GEO write =====" -ForegroundColor Cyan
Start-Sleep -Seconds 3

Write-Host ""
Write-Host "===== Verify Redis GEO Data =====" -ForegroundColor Cyan
try {
    $geoResult = docker exec biteblog-redis redis-cli -a redis123456 ZRANGE location:notes 0 -1 WITHSCORES 2>$null
    Write-Host $geoResult
} catch {
    Write-Host "[WARN] Cannot verify Redis GEO. Ensure location-service is running and RabbitMQ is working." -ForegroundColor Yellow
}

Write-Host ""
Write-Host "===== Quick API Test =====" -ForegroundColor Cyan
$tokenA = $accounts[1].token
$locHeaders = @{ "Authorization" = "Bearer $tokenA"; "X-User-Id" = "$($accounts[1].userId)" }

# 1. 附近查询（以武汉中山公园为中心，5km 半径）
Write-Host "1. Nearby markers (longitude=114.3, latitude=30.59, radius=5):"
try {
    $resp = Invoke-RestMethod -Uri "http://localhost:8085/location/nearby/markers?longitude=114.3&latitude=30.59&radius=5" -Method GET -Headers $locHeaders
    $resp.data.markers | Format-Table noteId, title, shopName, distance, longitude, latitude
} catch {
    Write-Host "[ERR] $($_.Exception.Message)" -ForegroundColor Red
}

Write-Host ""
Write-Host "===== Done =====" -ForegroundColor Cyan
Write-Host "Test accounts: 13900002001 ~ 13900002005, Password: 12345678" -ForegroundColor White
Write-Host "Center point: lng=114.3, lat=30.59 (武汉中山公园附近)" -ForegroundColor White
