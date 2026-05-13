#!/bin/bash
# Feed Service 非功能验证脚本
# 使用方式: bash jmeter/feed-test-verify.sh

BASE="http://localhost:8080/api"
TOKEN="your_token_here"
USER_ID=6

echo "===== Feed Service 非功能验证 ====="

# 1. Feed 流响应时间（目标 < 300ms）
echo ""
echo "===== 1. Feed 流响应时间（目标 < 300ms） ====="
for i in $(seq 1 10); do
  curl -o /dev/null -s -w "%{time_total}\n" \
    "${BASE}/feed/timeline?size=20" \
    -H "X-User-Id: ${USER_ID}" \
    -H "Authorization: Bearer ${TOKEN}"
done | awk '{sum+=$1; n++} END {printf "Feed 平均: %.3fs (目标 <0.3s)\n", sum/n}'

# 2. 游标分页连续翻页（验证不丢不重）
echo ""
echo "===== 2. 游标分页一致性 ====="
CURSOR=""
ALL_IDS=""
for page in $(seq 1 5); do
  if [ -z "$CURSOR" ]; then
    URL="${BASE}/feed/timeline?size=3"
  else
    URL="${BASE}/feed/timeline?cursor=${CURSOR}&size=3"
  fi
  R=$(curl -s "$URL" -H "X-User-Id: ${USER_ID}" -H "Authorization: Bearer ${TOKEN}")
  IDS=$(echo "$R" | python3 -c "import sys,json; [print(x['postId']) for x in json.load(sys.stdin)['data']['list']]" 2>/dev/null)
  HAS_MORE=$(echo "$R" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['hasMore'])" 2>/dev/null)
  CURSOR=$(echo "$R" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['cursor'])" 2>/dev/null)
  echo "第${page}页: postIds=[${IDS}] hasMore=${HAS_MORE}"
  ALL_IDS="${ALL_IDS} ${IDS}"
done
# 检查重复
DUPS=$(echo "$ALL_IDS" | tr ' ' '\n' | sort | uniq -d)
if [ -z "$DUPS" ]; then
  echo "分页验证: 通过（无重复）"
else
  echo "分页验证: 失败（有重复: ${DUPS}）"
fi

# 3. Fanout 延迟（发布后多久能在 Feed 流中看到）
echo ""
echo "===== 3. Fanout 延迟（目标秒级） ====="
echo "发布时间: $(date +%s%3N)"
curl -s -X POST "${BASE}/post/publish" \
  -H "X-User-Id: ${USER_ID}" -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"title":"Fanout延迟测试","content":"测试Fanout延迟","shopName":"测试店铺","address":"测试地址"}' > /dev/null

for i in $(seq 1 20); do
  sleep 0.5
  FOUND=$(curl -s "${BASE}/feed/timeline?size=50" \
    -H "X-User-Id: ${USER_ID}" -H "Authorization: Bearer ${TOKEN}" | \
    python3 -c "import sys,json; print(any('Fanout延迟测试' in str(x.get('title','')) for x in json.load(sys.stdin)['data']['list']))" 2>/dev/null)
  if [ "$FOUND" = "True" ]; then
    echo "Fanout 延迟: $((i * 500))ms"
    break
  fi
  if [ "$i" -eq 20 ]; then
    echo "Fanout 延迟: 超过 10s（可能 RabbitMQ 未消费或冷启动兜底生效）"
  fi
done

# 4. Redis inbox 空间验证
echo ""
echo "===== 4. Redis inbox 空间验证 ====="
COUNT=$(docker exec biteblog-redis redis-cli -a redis123456 ZCARD "feed:inbox:${USER_ID}" 2>/dev/null)
echo "inbox:${USER_ID} 条数: ${COUNT}（建议上限 500）"

# 5. 大V inbox 验证
echo ""
echo "===== 5. 大V inbox 验证 ====="
echo "feed:bigv 集合:"
docker exec biteblog-redis redis-cli -a redis123456 SMEMBERS feed:bigv 2>/dev/null

# 6. JMeter 压测
echo ""
echo "===== 6. JMeter 压测 ====="
echo "运行命令:"
echo '& "D:\from_browser\apache-jmeter-5.6.3\bin\jmeter.bat" -n -t jmeter\feed-service-test.jmx -l jmeter\feed-result.jtl -e -o jmeter\feedservice-report'
