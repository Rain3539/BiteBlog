$ErrorActionPreference = "Stop"

$mysqlContainer = "biteblog-mysql"
$redisContainer = "biteblog-redis"
$mysqlPassword = "root123456"
$redisPassword = "redis123456"

Write-Host "===== Init Recommend Service Test Data =====" -ForegroundColor Cyan

$sql = @"
USE biteblog;

INSERT INTO user (phone, username, password_hash, avatar, bio, status)
VALUES
('13900003001', 'recommend_user_foodie', '\$2a\$10\$recommend.demo.hash.0001', NULL, 'Recommend demo user: likes hotpot and bbq', 1),
('13900003002', 'recommend_user_tea', '\$2a\$10\$recommend.demo.hash.0002', NULL, 'Recommend demo user: likes tea and dessert', 1),
('13900003003', 'recommend_user_new', '\$2a\$10\$recommend.demo.hash.0003', NULL, 'Recommend cold start user', 1),
('13900003004', 'recommend_author_01', '\$2a\$10\$recommend.demo.hash.0004', NULL, 'Recommend demo author', 1),
('13900003005', 'recommend_user_neighbor', '\$2a\$10\$recommend.demo.hash.0005', NULL, 'Recommend similar user for ItemCF', 1)
ON DUPLICATE KEY UPDATE
  username = VALUES(username),
  bio = VALUES(bio),
  status = 1;

SELECT id INTO @foodie FROM user WHERE phone = '13900003001';
SELECT id INTO @tea FROM user WHERE phone = '13900003002';
SELECT id INTO @new_user FROM user WHERE phone = '13900003003';
SELECT id INTO @author FROM user WHERE phone = '13900003004';
SELECT id INTO @neighbor FROM user WHERE phone = '13900003005';

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
) VALUES
(@author, 'Recommend Test 01 Hotpot', 'Tags: hotpot, spicy, friends. High interaction candidate for tag recommendation.', 'Demo Hotpot House', 'Guangzhou Tianhe', 113.3245200, 23.1291100, 5, 5, 5, 32, 16, 8, 1, NOW() - INTERVAL 1 HOUR, NOW()),
(@author, 'Recommend Test 02 BBQ', 'Tags: bbq, night food. Similar item candidate for ItemCF.', 'Demo BBQ Shop', 'Guangzhou Yuexiu', 113.2643600, 23.1290800, 4, 5, 5, 24, 10, 6, 1, NOW() - INTERVAL 3 HOUR, NOW()),
(@author, 'Recommend Test 03 Dessert', 'Tags: dessert, tea, afternoon. Candidate for another interest group.', 'Demo Dessert Bar', 'Guangzhou Haizhu', 113.3172000, 23.0833100, 5, 4, 4, 18, 14, 5, 1, NOW() - INTERVAL 6 HOUR, NOW()),
(@author, 'Recommend Test 04 Tea', 'Tags: tea, quiet, work. Candidate for tag filtering.', 'Demo Tea Room', 'Guangzhou Liwan', 113.2442600, 23.1258600, 4, 4, 5, 15, 9, 4, 1, NOW() - INTERVAL 8 HOUR, NOW()),
(@author, 'Recommend Test 05 Noodles', 'Tags: noodles, quick meal. Cold start fallback candidate.', 'Demo Noodle Shop', 'Guangzhou Panyu', 113.3839700, 22.9359900, 4, 4, 4, 12, 5, 3, 1, NOW() - INTERVAL 12 HOUR, NOW());

INSERT INTO user_behavior (user_id, note_id, behavior_type, weight, dwell_time, created_at)
SELECT @foodie, id, 'view', 1, 45, NOW() FROM note WHERE title IN ('Recommend Test 01 Hotpot', 'Recommend Test 02 BBQ');

INSERT INTO user_behavior (user_id, note_id, behavior_type, weight, dwell_time, created_at)
SELECT @foodie, id, 'like', 3, NULL, NOW() FROM note WHERE title IN ('Recommend Test 01 Hotpot', 'Recommend Test 02 BBQ');

INSERT INTO user_behavior (user_id, note_id, behavior_type, weight, dwell_time, created_at)
SELECT @foodie, id, 'collect', 5, NULL, NOW() FROM note WHERE title = 'Recommend Test 01 Hotpot';

INSERT INTO user_behavior (user_id, note_id, behavior_type, weight, dwell_time, created_at)
SELECT @tea, id, 'view', 1, 60, NOW() FROM note WHERE title IN ('Recommend Test 03 Dessert', 'Recommend Test 04 Tea');

INSERT INTO user_behavior (user_id, note_id, behavior_type, weight, dwell_time, created_at)
SELECT @tea, id, 'like', 3, NULL, NOW() FROM note WHERE title IN ('Recommend Test 03 Dessert', 'Recommend Test 04 Tea');

INSERT INTO user_behavior (user_id, note_id, behavior_type, weight, dwell_time, created_at)
SELECT @tea, id, 'collect', 5, NULL, NOW() FROM note WHERE title = 'Recommend Test 03 Dessert';

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

SELECT 'foodie_user_id', @foodie;
SELECT 'tea_user_id', @tea;
SELECT 'cold_start_user_id', @new_user;
SELECT 'author_user_id', @author;
SELECT 'neighbor_user_id', @neighbor;
SELECT id, title FROM note WHERE title LIKE 'Recommend Test%' ORDER BY id;
"@

Write-Host "Writing MySQL recommend test data..." -ForegroundColor Cyan
$sql | docker exec -i $mysqlContainer mysql -uroot "-p$mysqlPassword" biteblog

Write-Host ""
Write-Host "Writing Redis recommend hot pool and exposure sample..." -ForegroundColor Cyan

$noteIds = docker exec $mysqlContainer mysql -N -uroot "-p$mysqlPassword" biteblog -e "SELECT id FROM note WHERE title LIKE 'Recommend Test%' ORDER BY id;"
$foodieUserId = docker exec $mysqlContainer mysql -N -uroot "-p$mysqlPassword" biteblog -e "SELECT id FROM user WHERE phone='13900003001';"

docker exec $redisContainer redis-cli -a $redisPassword DEL recommend:hot:pool "exposure:$foodieUserId" | Out-Null

$score = 100
foreach ($id in $noteIds) {
    if ([string]::IsNullOrWhiteSpace($id)) {
        continue
    }
    docker exec $redisContainer redis-cli -a $redisPassword ZADD recommend:hot:pool $score $id | Out-Null
    $score -= 10
}

if ($noteIds.Count -gt 0) {
    docker exec $redisContainer redis-cli -a $redisPassword SADD "exposure:$foodieUserId" $noteIds[0] | Out-Null
    docker exec $redisContainer redis-cli -a $redisPassword EXPIRE "exposure:$foodieUserId" 604800 | Out-Null
}

Write-Host ""
Write-Host "===== Recommend Test Data Ready =====" -ForegroundColor Green
Write-Host "Users:" -ForegroundColor White
Write-Host "  13900003001 recommend_user_foodie"
Write-Host "  13900003002 recommend_user_tea"
Write-Host "  13900003003 recommend_user_new"
Write-Host "  13900003005 recommend_user_neighbor"
Write-Host "Redis keys:" -ForegroundColor White
Write-Host "  recommend:hot:pool"
Write-Host "  exposure:<foodieUserId>"
