$ErrorActionPreference = "Stop"

$mysqlContainer = "biteblog-mysql"
$redisContainer = "biteblog-redis"
$mysqlPassword = "root123456"
$redisPassword = "redis123456"

Write-Host "===== Init Recommend Service Test Data =====" -ForegroundColor Cyan
Write-Host "This script reuses users from sql/init-data.ps1 and does not create extra users."

function Assert-ContainerRunning($containerName) {
    $running = docker inspect -f "{{.State.Running}}" $containerName 2>$null
    if ($LASTEXITCODE -ne 0 -or $running.Trim() -ne "true") {
        throw "Docker container '$containerName' is not running. Run 'docker compose up -d' first."
    }
}

Assert-ContainerRunning $mysqlContainer
Assert-ContainerRunning $redisContainer

$sql = @"
USE biteblog;

SELECT id INTO @bigv_author FROM user WHERE phone = '13800000001';
SELECT id INTO @normal_author FROM user WHERE phone = '13800000004';
SELECT id INTO @foodie FROM user WHERE phone = '13800000005';
SELECT id INTO @tea FROM user WHERE phone = '13800000006';
SELECT id INTO @neighbor FROM user WHERE phone = '13800000007';
SELECT id INTO @cold_user FROM user WHERE phone = '13800000060';

SET @missing =
  IF(@bigv_author IS NULL, 1, 0) +
  IF(@normal_author IS NULL, 1, 0) +
  IF(@foodie IS NULL, 1, 0) +
  IF(@tea IS NULL, 1, 0) +
  IF(@neighbor IS NULL, 1, 0) +
  IF(@cold_user IS NULL, 1, 0);

DELETE ub FROM user_behavior ub
JOIN note n ON ub.note_id = n.id
WHERE n.title LIKE 'Recommend Test%';

DELETE nl FROM note_like nl
JOIN note n ON nl.note_id = n.id
WHERE n.title LIKE 'Recommend Test%';

DELETE nf FROM note_favorite nf
JOIN note n ON nf.note_id = n.id
WHERE n.title LIKE 'Recommend Test%';

DELETE c FROM comment c
JOIN note n ON c.note_id = n.id
WHERE n.title LIKE 'Recommend Test%';

DELETE ni FROM note_image ni
JOIN note n ON ni.note_id = n.id
WHERE n.title LIKE 'Recommend Test%';

DELETE FROM note WHERE title LIKE 'Recommend Test%';

INSERT INTO note (
  author_id, title, content, shop_name, address, longitude, latitude,
  score_color, score_smell, score_taste, like_count, collect_count, comment_count,
  status, created_at, updated_at
)
SELECT * FROM (
  SELECT
         CASE WHEN seed.n <= 30 THEN @bigv_author ELSE @normal_author END author_id,
         CONCAT(
           'Recommend Test ',
           LPAD(seed.n, 2, '0'),
           ' ',
           CASE seed.n % 10
             WHEN 1 THEN 'Hotpot'
             WHEN 2 THEN 'BBQ'
             WHEN 3 THEN 'Dessert'
             WHEN 4 THEN 'Tea'
             WHEN 5 THEN 'Noodles'
             WHEN 6 THEN 'Coffee'
             WHEN 7 THEN 'Sushi'
             WHEN 8 THEN 'Brunch'
             WHEN 9 THEN 'Cantonese'
             ELSE 'Bakery'
           END
         ) title,
         CONCAT(
           'Tags: ',
           CASE seed.n % 10
             WHEN 1 THEN 'hotpot, spicy, friends'
             WHEN 2 THEN 'bbq, night food, meat'
             WHEN 3 THEN 'dessert, sweet, afternoon'
             WHEN 4 THEN 'tea, quiet, work'
             WHEN 5 THEN 'noodles, quick meal, comfort'
             WHEN 6 THEN 'coffee, brunch, reading'
             WHEN 7 THEN 'sushi, seafood, date'
             WHEN 8 THEN 'brunch, bakery, weekend'
             WHEN 9 THEN 'cantonese, dimsum, family'
             ELSE 'bakery, bread, breakfast'
           END,
           '. Recommend paging sample #',
           seed.n,
           '.'
         ) content,
         CONCAT(
           'Demo ',
           CASE seed.n % 10
             WHEN 1 THEN 'Hotpot House'
             WHEN 2 THEN 'BBQ Shop'
             WHEN 3 THEN 'Dessert Bar'
             WHEN 4 THEN 'Tea Room'
             WHEN 5 THEN 'Noodle Shop'
             WHEN 6 THEN 'Coffee Lab'
             WHEN 7 THEN 'Sushi Table'
             WHEN 8 THEN 'Brunch Cafe'
             WHEN 9 THEN 'Cantonese Kitchen'
             ELSE 'Bakery'
           END,
           ' ',
           seed.n
         ) shop_name,
         CASE seed.n % 6
           WHEN 1 THEN 'Guangzhou Tianhe'
           WHEN 2 THEN 'Guangzhou Yuexiu'
           WHEN 3 THEN 'Guangzhou Haizhu'
           WHEN 4 THEN 'Guangzhou Liwan'
           WHEN 5 THEN 'Guangzhou Panyu'
           ELSE 'Guangzhou Baiyun'
         END address,
         113.2000000 + seed.n / 1000.0 longitude,
         23.0000000 + seed.n / 1000.0 latitude,
         3 + seed.n % 3 score_color,
         3 + (seed.n + 1) % 3 score_smell,
         3 + (seed.n + 2) % 3 score_taste,
         GREATEST(1, 95 - seed.n) like_count,
         GREATEST(1, 50 - FLOOR(seed.n / 2)) collect_count,
         GREATEST(1, 30 - FLOOR(seed.n / 3)) comment_count,
         1 status,
         NOW() - INTERVAL seed.n HOUR created_at,
         NOW() updated_at
  FROM (
    SELECT 1 n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5
    UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10
    UNION ALL SELECT 11 UNION ALL SELECT 12 UNION ALL SELECT 13 UNION ALL SELECT 14 UNION ALL SELECT 15
    UNION ALL SELECT 16 UNION ALL SELECT 17 UNION ALL SELECT 18 UNION ALL SELECT 19 UNION ALL SELECT 20
    UNION ALL SELECT 21 UNION ALL SELECT 22 UNION ALL SELECT 23 UNION ALL SELECT 24 UNION ALL SELECT 25
    UNION ALL SELECT 26 UNION ALL SELECT 27 UNION ALL SELECT 28 UNION ALL SELECT 29 UNION ALL SELECT 30
    UNION ALL SELECT 31 UNION ALL SELECT 32 UNION ALL SELECT 33 UNION ALL SELECT 34 UNION ALL SELECT 35
    UNION ALL SELECT 36 UNION ALL SELECT 37 UNION ALL SELECT 38 UNION ALL SELECT 39 UNION ALL SELECT 40
    UNION ALL SELECT 41 UNION ALL SELECT 42 UNION ALL SELECT 43 UNION ALL SELECT 44 UNION ALL SELECT 45
    UNION ALL SELECT 46 UNION ALL SELECT 47 UNION ALL SELECT 48 UNION ALL SELECT 49 UNION ALL SELECT 50
    UNION ALL SELECT 51 UNION ALL SELECT 52 UNION ALL SELECT 53 UNION ALL SELECT 54 UNION ALL SELECT 55
    UNION ALL SELECT 56 UNION ALL SELECT 57 UNION ALL SELECT 58 UNION ALL SELECT 59 UNION ALL SELECT 60
  ) seed
) seed
WHERE @missing = 0;

INSERT INTO user_behavior (user_id, note_id, behavior_type, weight, dwell_time, created_at)
SELECT @foodie, id, 'view', 1, 45, NOW() FROM note WHERE title IN ('Recommend Test 01 Hotpot', 'Recommend Test 02 BBQ');

INSERT INTO user_behavior (user_id, note_id, behavior_type, weight, dwell_time, created_at)
SELECT @foodie, id, 'like', 5, NULL, NOW() FROM note WHERE title IN ('Recommend Test 01 Hotpot', 'Recommend Test 02 BBQ');

INSERT INTO user_behavior (user_id, note_id, behavior_type, weight, dwell_time, created_at)
SELECT @foodie, id, 'collect', 8, NULL, NOW() FROM note WHERE title = 'Recommend Test 01 Hotpot';

INSERT INTO user_behavior (user_id, note_id, behavior_type, weight, dwell_time, created_at)
SELECT @tea, id, 'view', 1, 60, NOW() FROM note WHERE title IN ('Recommend Test 03 Dessert', 'Recommend Test 04 Tea');

INSERT INTO user_behavior (user_id, note_id, behavior_type, weight, dwell_time, created_at)
SELECT @tea, id, 'like', 5, NULL, NOW() FROM note WHERE title IN ('Recommend Test 03 Dessert', 'Recommend Test 04 Tea');

INSERT INTO user_behavior (user_id, note_id, behavior_type, weight, dwell_time, created_at)
SELECT @tea, id, 'collect', 8, NULL, NOW() FROM note WHERE title = 'Recommend Test 03 Dessert';

INSERT INTO user_behavior (user_id, note_id, behavior_type, weight, dwell_time, created_at)
SELECT @neighbor, id, 'like', 5, NULL, NOW() FROM note WHERE title IN ('Recommend Test 01 Hotpot', 'Recommend Test 02 BBQ', 'Recommend Test 05 Noodles');

INSERT INTO user_behavior (user_id, note_id, behavior_type, weight, dwell_time, created_at)
SELECT @neighbor, id, 'collect', 8, NULL, NOW() FROM note WHERE title = 'Recommend Test 05 Noodles';

INSERT INTO note_like (note_id, user_id, created_at)
SELECT id, @foodie, NOW() FROM note WHERE title IN ('Recommend Test 01 Hotpot', 'Recommend Test 02 BBQ')
ON DUPLICATE KEY UPDATE created_at = VALUES(created_at);

INSERT INTO note_like (note_id, user_id, created_at)
SELECT id, @tea, NOW() FROM note WHERE title IN ('Recommend Test 03 Dessert', 'Recommend Test 04 Tea')
ON DUPLICATE KEY UPDATE created_at = VALUES(created_at);

INSERT INTO note_favorite (note_id, user_id, created_at)
SELECT id, @foodie, NOW() FROM note WHERE title = 'Recommend Test 01 Hotpot'
ON DUPLICATE KEY UPDATE created_at = VALUES(created_at);

INSERT INTO note_favorite (note_id, user_id, created_at)
SELECT id, @tea, NOW() FROM note WHERE title = 'Recommend Test 03 Dessert'
ON DUPLICATE KEY UPDATE created_at = VALUES(created_at);

SELECT 'missing_required_users', @missing;
SELECT 'bigv_author_13800000001', @bigv_author;
SELECT 'normal_author_13800000004', @normal_author;
SELECT 'foodie_user_13800000005', @foodie;
SELECT 'tea_user_13800000006', @tea;
SELECT 'neighbor_user_13800000007', @neighbor;
SELECT 'cold_start_user_13800000060', @cold_user;
SELECT id, author_id, title FROM note WHERE title LIKE 'Recommend Test%' ORDER BY id;
"@

Write-Host "Writing MySQL recommend test data..." -ForegroundColor Cyan
$sql | docker exec -i $mysqlContainer mysql -uroot "-p$mysqlPassword" biteblog

Write-Host ""
Write-Host "Writing Redis recommend hot pool and exposure sample..." -ForegroundColor Cyan

$missing = docker exec $mysqlContainer mysql -N -uroot "-p$mysqlPassword" biteblog -e "SELECT IF(COUNT(*) = 6, 0, 1) FROM user WHERE phone IN ('13800000001','13800000004','13800000005','13800000006','13800000007','13800000060');"
if ($missing.Trim() -ne "0") {
    throw "Required users are missing. Run .\sql\init-data.ps1 first."
}

$noteIds = docker exec $mysqlContainer mysql -N -uroot "-p$mysqlPassword" biteblog -e "SELECT id FROM note WHERE title LIKE 'Recommend Test%' ORDER BY id;"
$foodieUserId = docker exec $mysqlContainer mysql -N -uroot "-p$mysqlPassword" biteblog -e "SELECT id FROM user WHERE phone='13800000005';"

$rankDailyKey = "rank:daily:$(Get-Date -Format 'yyyy-MM-dd')"
docker exec -e "REDISCLI_AUTH=$redisPassword" $redisContainer redis-cli DEL recommend:hot:pool $rankDailyKey "behavior:$foodieUserId" "exposure:$foodieUserId" | Out-Null

$score = 300
foreach ($id in $noteIds) {
    if ([string]::IsNullOrWhiteSpace($id)) {
        continue
    }
    docker exec -e "REDISCLI_AUTH=$redisPassword" $redisContainer redis-cli DEL "recommend:itemcf:similar:$id" | Out-Null
    docker exec -e "REDISCLI_AUTH=$redisPassword" $redisContainer redis-cli ZADD recommend:hot:pool $score $id | Out-Null
    docker exec -e "REDISCLI_AUTH=$redisPassword" $redisContainer redis-cli ZADD $rankDailyKey $score $id | Out-Null
    $score -= 5
}

$noteIdList = @($noteIds | Where-Object { ![string]::IsNullOrWhiteSpace($_) })
if ($noteIdList.Count -ge 9) {
    docker exec -e "REDISCLI_AUTH=$redisPassword" $redisContainer redis-cli ZADD "recommend:itemcf:similar:$($noteIdList[0])" 0.93 $noteIdList[1] 0.81 $noteIdList[4] 0.72 $noteIdList[5] | Out-Null
    docker exec -e "REDISCLI_AUTH=$redisPassword" $redisContainer redis-cli ZADD "recommend:itemcf:similar:$($noteIdList[1])" 0.93 $noteIdList[0] 0.86 $noteIdList[4] 0.68 $noteIdList[6] | Out-Null
    docker exec -e "REDISCLI_AUTH=$redisPassword" $redisContainer redis-cli ZADD "recommend:itemcf:similar:$($noteIdList[2])" 0.90 $noteIdList[3] 0.77 $noteIdList[7] | Out-Null
    docker exec -e "REDISCLI_AUTH=$redisPassword" $redisContainer redis-cli ZADD "recommend:itemcf:similar:$($noteIdList[3])" 0.90 $noteIdList[2] 0.74 $noteIdList[8] | Out-Null
    docker exec -e "REDISCLI_AUTH=$redisPassword" $redisContainer redis-cli ZADD "recommend:itemcf:similar:$($noteIdList[4])" 0.81 $noteIdList[0] 0.86 $noteIdList[1] | Out-Null
}

if ($noteIdList.Count -gt 0) {
    docker exec -e "REDISCLI_AUTH=$redisPassword" $redisContainer redis-cli SADD "exposure:$foodieUserId" $noteIdList[0] | Out-Null
    docker exec -e "REDISCLI_AUTH=$redisPassword" $redisContainer redis-cli EXPIRE "exposure:$foodieUserId" 604800 | Out-Null
}

Write-Host ""
Write-Host "Syncing recommend samples to Elasticsearch post_index..." -ForegroundColor Cyan
try {
    $rows = docker exec $mysqlContainer mysql -N -B -uroot "-p$mysqlPassword" biteblog -e "SELECT id, author_id, title, content, shop_name, like_count, collect_count, comment_count, score_color, score_smell, score_taste, DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s') FROM note WHERE title LIKE 'Recommend Test%' ORDER BY id;"
    foreach ($row in $rows) {
        if ([string]::IsNullOrWhiteSpace($row)) {
            continue
        }
        $cols = $row -split "`t"
        if ($cols.Count -lt 12) {
            continue
        }
        $doc = @{
            postId = "$($cols[0])"
            user_id = "$($cols[1])"
            title = $cols[2]
            content = $cols[3]
            store_name = $cols[4]
            shopName = $cols[4]
            tags = @($cols[2] -replace '^Recommend Test \d+ ', '')
            like_count = [long]$cols[5]
            collect_count = [long]$cols[6]
            comment_count = [long]$cols[7]
            score_color = [int]$cols[8]
            score_smell = [int]$cols[9]
            score_taste = [int]$cols[10]
            status = 1
            created_at = $cols[11]
        }
        $body = $doc | ConvertTo-Json -Depth 8 -Compress
        Invoke-RestMethod -Uri "http://localhost:9200/post_index/_doc/$($cols[0])" -Method Put -ContentType "application/json; charset=utf-8" -Body $body | Out-Null
    }
} catch {
    Write-Host "[WARN] ES sync skipped or failed: $($_.Exception.Message)" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "Syncing Recommend ItemCF samples to Elasticsearch item_sim_index..." -ForegroundColor Cyan
try {
    if ($noteIdList.Count -ge 9) {
        $relations = @(
            @{ item_id = [long]$noteIdList[0]; similar_item_id = [long]$noteIdList[1]; score = 0.93 },
            @{ item_id = [long]$noteIdList[0]; similar_item_id = [long]$noteIdList[4]; score = 0.81 },
            @{ item_id = [long]$noteIdList[0]; similar_item_id = [long]$noteIdList[5]; score = 0.72 },
            @{ item_id = [long]$noteIdList[1]; similar_item_id = [long]$noteIdList[0]; score = 0.93 },
            @{ item_id = [long]$noteIdList[1]; similar_item_id = [long]$noteIdList[4]; score = 0.86 },
            @{ item_id = [long]$noteIdList[1]; similar_item_id = [long]$noteIdList[6]; score = 0.68 },
            @{ item_id = [long]$noteIdList[2]; similar_item_id = [long]$noteIdList[3]; score = 0.90 },
            @{ item_id = [long]$noteIdList[2]; similar_item_id = [long]$noteIdList[7]; score = 0.77 },
            @{ item_id = [long]$noteIdList[3]; similar_item_id = [long]$noteIdList[2]; score = 0.90 },
            @{ item_id = [long]$noteIdList[3]; similar_item_id = [long]$noteIdList[8]; score = 0.74 },
            @{ item_id = [long]$noteIdList[4]; similar_item_id = [long]$noteIdList[0]; score = 0.81 },
            @{ item_id = [long]$noteIdList[4]; similar_item_id = [long]$noteIdList[1]; score = 0.86 }
        )
        foreach ($relation in $relations) {
            $body = $relation | ConvertTo-Json -Depth 5 -Compress
            $docId = "$($relation.item_id)_$($relation.similar_item_id)"
            Invoke-RestMethod -Uri "http://localhost:9200/item_sim_index/_doc/$docId" -Method Put -ContentType "application/json; charset=utf-8" -Body $body | Out-Null
        }
    }
} catch {
    Write-Host "[WARN] ES item_sim_index sync skipped or failed: $($_.Exception.Message)" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "===== Recommend Test Data Ready =====" -ForegroundColor Green
Write-Host "Reused users:" -ForegroundColor White
Write-Host "  13800000001 big-V author"
Write-Host "  13800000004 normal author"
Write-Host "  13800000005 foodie behavior user"
Write-Host "  13800000006 tea behavior user"
Write-Host "  13800000007 ItemCF neighbor user"
Write-Host "  13800000060 cold-start user"
Write-Host "Redis keys:" -ForegroundColor White
Write-Host "  recommend:hot:pool"
Write-Host "  recommend:itemcf:similar:<postId>"
Write-Host "  exposure:<13800000005 userId>"
Write-Host "Elasticsearch indexes:" -ForegroundColor White
Write-Host "  post_index"
Write-Host "  item_sim_index"
