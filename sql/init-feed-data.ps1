
$ErrorActionPreference = "SilentlyContinue"

$userBase = "http://localhost:8081"
$postBase = "http://localhost:8082"
$redisContainer = "biteblog-redis"
$redisPassword = "redis123456"

Write-Host "===== 初始化 Feed 测试数据 =====" -ForegroundColor Cyan

$users = @(
    @{ phone = "13800000001"; password = "12345678"; username = "bigv_foodking" },
    @{ phone = "13800000002"; password = "12345678"; username = "bigv_cheflife" },
    @{ phone = "13800000003"; password = "12345678"; username = "bigv_tastehunter" },
    @{ phone = "13800000004"; password = "12345678"; username = "user_hotpot" },
    @{ phone = "13800000005"; password = "12345678"; username = "user_bbq" },
    @{ phone = "13800000006"; password = "12345678"; username = "user_dessert" }
)

$accounts = @()

foreach ($u in $users) {
    $json = @{ phone = $u.phone; password = $u.password } | ConvertTo-Json -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    try {
        $resp = Invoke-RestMethod -Uri "$userBase/user/login" -Method POST -ContentType "application/json; charset=utf-8" -Body $bytes
        if ($resp.code -eq 200) {
            Write-Host "[OK] 登录 $($u.username), userId=$($resp.data.userId)" -ForegroundColor Green
            $accounts += @{ userId = $resp.data.userId; token = $resp.data.token; username = $u.username }
        } else {
            Write-Host "[FAIL] $($u.username): code=$($resp.code)" -ForegroundColor Red
        }
    } catch {
        Write-Host "[ERR] $($u.username): 登录失败" -ForegroundColor Red
    }
}

if ($accounts.Count -lt 6) {
    Write-Host "[STOP] 需要 6 个用户，实际 $($accounts.Count)" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "===== 发布笔记 =====" -ForegroundColor Cyan

$allNotes = @(
    @{ author = 0; title = "武汉户部巷必吃三鲜豆皮"; content = "来武汉怎么能不吃三鲜豆皮！户部巷这家老字号，豆皮金黄酥脆，馅料饱满，糯米粒粒分明。配上一碗蛋酒，完美早餐。"; shopName = "老通城豆皮"; address = "武汉市武昌区户部巷28号"; longitude = 114.310; latitude = 30.588; scoreColor = 5; scoreSmell = 5; scoreTaste = 5 },
    @{ author = 0; title = "长沙文和友小龙虾测评"; content = "排队2小时终于吃到了！小龙虾个头大，麻辣味十足，蒜蓉的也很香。环境复古风很出片。建议工作日去，周末人太多。"; shopName = "文和友龙虾馆"; address = "长沙市天心区湘江中路36号"; longitude = 112.979; latitude = 28.196; scoreColor = 4; scoreSmell = 4; scoreTaste = 5 },
    @{ author = 0; title = "成都宽窄巷子火锅实录"; content = "来成都第一顿必须是火锅！这家牛油锅底醇厚，毛肚七上八下15秒，口感弹脆。小酥肉现炸，外酥里嫩。辣度可调，微辣也够味。"; shopName = "小龙坎火锅"; address = "成都市青羊区宽窄巷子12号"; longitude = 104.062; latitude = 30.672; scoreColor = 4; scoreSmell = 5; scoreTaste = 5 },
    @{ author = 1; title = "广州陶陶居早茶攻略"; content = "虾饺皮薄透明，整颗虾仁弹牙。叉烧包松软，馅料甜咸适中。凤爪入味脱骨，必点！流沙包趁热吃会爆浆。人均80，性价比很高。"; shopName = "陶陶居"; address = "广州市荔湾区第十甫路20号"; longitude = 113.243; latitude = 23.110; scoreColor = 5; scoreSmell = 5; scoreTaste = 5 },
    @{ author = 1; title = "杭州楼外楼西湖醋鱼"; content = "西湖醋鱼酸甜适口，鱼肉鲜嫩无腥味。东坡肉肥而不腻，入口即化。龙井虾仁清爽，茶叶的清香和虾仁的鲜甜完美融合。"; shopName = "楼外楼"; address = "杭州市西湖区孤山路30号"; longitude = 120.141; latitude = 30.258; scoreColor = 5; scoreSmell = 4; scoreTaste = 5 },
    @{ author = 1; title = "西安回民街肉夹馍"; content = "腊汁肉肥瘦相间，卤得软烂入味，馍是现烤的白吉馍，外脆内软。再来一碗羊肉泡馍，汤浓肉烂，冬天吃浑身暖和。"; shopName = "老孙家肉夹馍"; address = "西安市莲湖区回民街156号"; longitude = 108.940; latitude = 34.265; scoreColor = 3; scoreSmell = 4; scoreTaste = 5 },
    @{ author = 2; title = "南京大排档盐水鸭"; content = "南京必吃！盐水鸭皮白肉嫩，咸鲜适中，不柴不腻。鸭血粉丝汤料足味正，汤头鲜美。美龄粥清甜，是饭后甜品首选。"; shopName = "南京大牌档"; address = "南京市秦淮区建康路1号"; longitude = 118.793; latitude = 32.043; scoreColor = 4; scoreSmell = 5; scoreTaste = 4 },
    @{ author = 2; title = "重庆解放碑小面探店"; content = "正宗重庆小面！豌杂面的豌豆软糯，杂酱香辣，面条劲道。凉糕冰凉解辣，红糖糍粑外酥内糯。价格亲民，8块钱一碗。"; shopName = "花市豌杂面"; address = "重庆市渝中区解放碑八一路"; longitude = 106.577; latitude = 29.556; scoreColor = 3; scoreSmell = 4; scoreTaste = 5 },
    @{ author = 3; title = "武汉光谷韩式烤肉"; content = "五花肉烤到微焦，包着生菜加蒜片，绝了！牛舌薄切，10秒翻面刚刚好。泡菜锅酸辣开胃，年糕软糯。适合朋友聚餐。"; shopName = "姜虎东白丁烤肉"; address = "武汉市洪山区光谷步行街3期"; longitude = 114.398; latitude = 30.507; scoreColor = 4; scoreSmell = 4; scoreTaste = 5 },
    @{ author = 3; title = "武汉江汉路日料"; content = "三文鱼刺身新鲜厚切，甜虾入口即化。鳗鱼饭酱汁浓郁，鳗鱼烤得外焦里嫩。环境安静雅致，适合约会。人均150左右。"; shopName = "和民居食屋"; address = "武汉市江汉区江汉路步行街"; longitude = 114.298; latitude = 30.595; scoreColor = 5; scoreSmell = 4; scoreTaste = 4 },
    @{ author = 4; title = "武汉楚河汉街烧烤夜宵"; content = "深夜觅食好去处！烤羊腿外焦里嫩，撒上孜然辣椒粉，香气四溢。烤茄子蒜香浓郁，金针菇烤得入味。啤酒配烧烤，夏夜标配。"; shopName = "木屋烧烤"; address = "武汉市武昌区楚河汉街J4区"; longitude = 114.352; latitude = 30.563; scoreColor = 4; scoreSmell = 3; scoreTaste = 5 },
    @{ author = 4; title = "武汉吉庆街大排档体验"; content = "最有烟火气的地方！油焖大虾麻辣鲜香，炒花饭粒粒分明。老板热情，上菜快。虽然环境简陋，但味道地道，价格实惠。"; shopName = "吉庆街排档"; address = "武汉市江岸区吉庆街"; longitude = 114.302; latitude = 30.596; scoreColor = 3; scoreSmell = 3; scoreTaste = 4 },
    @{ author = 5; title = "武大周边甜品店打卡"; content = "抹茶千层层次分明，奶油轻盈不腻。提拉米苏入口即化，咖啡味和酒香平衡得很好。环境小清新，适合下午茶。拍照很出片。"; shopName = "猫山王甜品"; address = "武汉市武昌区珞珈山路8号"; longitude = 114.367; latitude = 30.540; scoreColor = 5; scoreSmell = 5; scoreTaste = 4 },
    @{ author = 5; title = "武汉天地网红奶茶测评"; content = "点了招牌芋泥波波奶茶，芋泥很扎实，波波Q弹。杨枝甘露芒果新鲜，椰浆香浓。排队20分钟，值得。冬天限定的烤红薯奶茶也很好喝。"; shopName = "茶颜悦色"; address = "武汉市江岸区武汉天地A5区"; longitude = 114.310; latitude = 30.610; scoreColor = 5; scoreSmell = 4; scoreTaste = 5 }
)

$publishedCount = 0

foreach ($note in $allNotes) {
    $author = $accounts[$note.author]
    $headers = @{ "Authorization" = "Bearer $($author.token)"; "X-User-Id" = "$($author.userId)" }

    $body = @{
        title      = $note.title
        content    = $note.content
        shopName   = $note.shopName
        address    = $note.address
        longitude  = $note.longitude
        latitude   = $note.latitude
        scoreColor = $note.scoreColor
        scoreSmell = $note.scoreSmell
        scoreTaste = $note.scoreTaste
        imageUrls  = @()
    }

    $json = $body | ConvertTo-Json -Depth 5 -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)

    try {
        $resp = Invoke-RestMethod -Uri "$postBase/post/publish" -Method POST -Headers $headers -ContentType "application/json; charset=utf-8" -Body $bytes
        if ($resp.code -eq 200) {
            $publishedCount++
            $postId = $resp.data.postId
            $score = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
            # 写入作者自己的 Redis inbox
            docker exec $redisContainer redis-cli -a $redisPassword ZADD "feed:inbox:$($author.userId)" $score $postId 2>$null | Out-Null
            Write-Host "[OK] $($author.username) -> $($note.title) (postId=$postId)" -ForegroundColor Green
        } else {
            Write-Host "[FAIL] $($note.title): code=$($resp.code)" -ForegroundColor Red
        }
    } catch {
        Write-Host "[ERR] $($note.title): $($_.Exception.Message)" -ForegroundColor Red
    }
}

Write-Host ""
Write-Host "===== 标记大V =====" -ForegroundColor Cyan
foreach ($acc in $accounts[0..2]) {
    docker exec $redisContainer redis-cli -a $redisPassword SADD "feed:bigv" $acc.userId 2>$null | Out-Null
    Write-Host "[BigV] $($acc.username) userId=$($acc.userId)" -ForegroundColor Cyan
}

Write-Host ""
Write-Host "===== 完成 =====" -ForegroundColor Cyan
Write-Host "发布: $publishedCount 条笔记" -ForegroundColor Green
Write-Host "用户: 13800000001 ~ 13800000006, 密码 12345678" -ForegroundColor White
