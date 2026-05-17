# Notify Service 测试说明

## 1. 测试范围

本文件记录 Notify Service 的完整测试方法。对应验证脚本：

```text
BiteBlog/测试脚本/notify-test-verify.ps1   <- 全量验证脚本（含性能/集成/安全）
BiteBlog/测试脚本/notify-test-result.txt   <- 脚本执行后自动生成的结果文件
jmeter/notify-service-test.jmx             <- JMeter 并发压测脚本
jmeter/notifyservice-report/index.html     <- JMeter HTML 报告（执行后生成）
```

测试目标包括：

1. 服务启动与健康检查
2. HTTP：通知列表（含 type/readStatus 过滤）、未读数、已读、全部已读
3. RabbitMQ：互动事件消费（like/collect/comment → notification 表）
4. 幂等去重：5 分钟窗口内重复投递不产生重复通知
5. 自操作过滤：作者操作自己笔记不产生通知
6. Redis 未读缓存：命中率、冷热路径 RT 对比
7. 分页 total 准确性与无重复
8. 鉴权与越权安全验证
9. JMeter 并发压测：TPS、响应时间 P90/P95、错误率

---

## 2. 启动前准备

### 2.1 中间件（Docker）

```powershell
docker ps --format "table {{.Names}}\t{{.Status}}"
```

确认 `biteblog-mysql`、`biteblog-redis`、`biteblog-rabbitmq`、`biteblog-nacos` 均为 `Up`。

### 2.2 数据库表结构（含归档表）

```powershell
Get-Content "e:\WHU\DistributedGroupWork\BiteBlog\sql\init.sql" | docker exec -i biteblog-mysql mysql -u root -proot123456 biteblog
```

验证两张通知表存在：

```powershell
docker exec biteblog-mysql mysql -u root -proot123456 biteblog -e "SHOW TABLES LIKE 'notification%';"
```

### 2.3 微服务启动顺序

```powershell
cd e:\WHU\DistributedGroupWork\BiteBlog\biteblog-backend
mvn -pl biteblog-user    -am spring-boot:run   # 8081
mvn -pl biteblog-post    -am spring-boot:run   # 8082
mvn -pl biteblog-notify  -am spring-boot:run   # 8087
mvn -pl biteblog-gateway -am spring-boot:run   # 8080
```

### 2.4 初始化测试数据

```powershell
cd e:\WHU\DistributedGroupWork\BiteBlog\sql
.\init-notify-data.ps1
```

输出应包含 `[OK] /notify/list total>=3` 和 `[OK] /notify/unread-count unreadCount>=3`。

---

## 3. 全量验证脚本（推荐首选）

### 3.1 执行方式

```powershell
cd e:\WHU\DistributedGroupWork\BiteBlog\测试脚本
.\notify-test-verify.ps1
```

结果自动保存至同目录 `notify-test-result.txt`（可截图或提交 txt）。

### 3.2 脚本覆盖的检查项

| 节 | 内容 | 关键通过标准 |
|----|------|-------------|
| 0 | 账号登录 | 两个账号 token 拿到 |
| 1 | 健康检查 | 直连 8087 和经网关 8080 均 `status=UP` |
| 2 | 发布测试笔记 | postId 返回 |
| 3 | MQ消费（like/collect/comment） | DB 新增 >=3 条，type 分布正确 |
| 4 | 自操作过滤 | 作者给自己点赞，DB 无新增 |
| 5 | 幂等去重（5分钟窗口） | 同一 like 重复到达，只写 1 条 |
| 6 | 列表接口 RT（20次采样） | P95 < 300ms |
| 7 | 未读数接口 RT（20次采样） | P95 < 100ms（Redis 热路径） |
| 8 | Redis 缓存冷热对比 | cold（DB COUNT） > hot（Redis GET） |
| 9 | 全部已读 + 未读数归零 | `unreadCount=0` after `read-all` |
| 10 | 列表过滤（type/readStatus） | 返回数据 type/readStatus 均匹配过滤参数 |
| 11 | 分页 total 一致 + 无重复 | 两页 total 相同，id 无交集 |
| 12 | 鉴权（无 Token 401）+ 越权拒绝 | 正确返回 401；跨用户 read 被拒 |
| 13 | RabbitMQ 队列状态 | consumers>=1，Unacked=0，DLQ=0 |
| 汇总 | Pass/Fail 计数 + RT 汇总表 | |

### 3.3 结果示例（正常情况）

```
===== Summary =====
  Pass  : 28
  Fail  : 0
  Total : 28 checks

  --- Response Time Results ---
  List        avg=45ms  P90=68ms  P95=82ms  max=130ms
  Unread-cnt  avg=12ms  P90=18ms  P95=22ms  max=45ms
  Cache       cold(DB)=35ms  hot(Redis)=11ms
```

---

## 4. 命令行快速验证（补充手工）

### 4.1 登录获取 Token（PowerShell）

```powershell
$body = '{"phone":"13900004001","password":"12345678"}'
$r    = Invoke-RestMethod -Uri "http://localhost:8080/api/user/login" -Method POST `
        -Body $body -ContentType "application/json; charset=utf-8"
$token = $r.data.token
```

### 4.2 各接口验证

```powershell
# 健康检查（无需 Token）
Invoke-RestMethod -Uri "http://localhost:8087/notify/health"

# 列表（全部）
Invoke-RestMethod -Uri "http://localhost:8080/api/notify/list?page=1&size=20" `
  -Headers @{ Authorization = "Bearer $token" }

# 列表（仅未读）
Invoke-RestMethod -Uri "http://localhost:8080/api/notify/list?page=1&size=20&readStatus=0" `
  -Headers @{ Authorization = "Bearer $token" }

# 列表（仅点赞）
Invoke-RestMethod -Uri "http://localhost:8080/api/notify/list?page=1&size=20&type=like" `
  -Headers @{ Authorization = "Bearer $token" }

# 未读数
Invoke-RestMethod -Uri "http://localhost:8080/api/notify/unread-count" `
  -Headers @{ Authorization = "Bearer $token" }

# 全部已读
Invoke-RestMethod -Uri "http://localhost:8080/api/notify/read-all" -Method POST `
  -Headers @{ Authorization = "Bearer $token" }

# 直连 notify（调试，绕过网关）
Invoke-RestMethod -Uri "http://localhost:8087/notify/list?page=1&size=20" `
  -Headers @{ "X-User-Id" = "11" }
```

---

## 5. Redis 缓存手工验证

```powershell
# 查看未读数 key
docker exec biteblog-redis redis-cli -a redis123456 GET notify:unread:11

# 删除 key 后再请求（触发冷路径 DB 回源）
docker exec biteblog-redis redis-cli -a redis123456 DEL notify:unread:11
Invoke-RestMethod -Uri "http://localhost:8080/api/notify/unread-count" `
  -Headers @{ Authorization = "Bearer $token" }
# 立刻再请求（Redis 已回填，热路径）
Invoke-RestMethod -Uri "http://localhost:8080/api/notify/unread-count" `
  -Headers @{ Authorization = "Bearer $token" }
```

---

## 6. RabbitMQ 手工检查

打开浏览器 `http://localhost:15672`（guest/guest）：

| 检查项 | 预期值 |
|--------|--------|
| 交换机 `biteblog.interaction` | 存在，durable |
| 队列 `notify.interaction.queue` | Consumers=1，Ready≈0，Unacked≈0 |
| 队列 `notify.dead.queue` | Ready=0（正常无死信） |

---

## 7. JMeter 并发压测

### 7.1 执行（将 JMeter 路径换成本机实际路径）

```powershell
cd e:\WHU\DistributedGroupWork\BiteBlog
& "D:\apache-jmeter-5.6.3\bin\jmeter.bat" `
  -n -t jmeter\notify-service-test.jmx `
  -l jmeter\notify-service-result.jtl `
  -e -o jmeter\notifyservice-report
```

### 7.2 报告阅读（打开 `jmeter\notifyservice-report\index.html`）

| 指标 | 位置 | 建议值 |
|------|------|--------|
| Throughput（TPS） | Statistics 页 Throughput 列 | 视本机性能，记录实测值 |
| Average RT | Statistics → Average | < 200ms |
| P90 / P95 | Statistics → 90% Line / 95% Line | P95 < 300ms |
| Max RT | Statistics → Max | 记录峰值 |
| Error % | Statistics → Error % | 0% |

截图 Statistics 表格和 Response Times Over Time 图，保存至测试报告。

---

## 8. 归档任务验证（可选）

插入一条 30 天前的已读通知：

```powershell
docker exec biteblog-mysql mysql -u root -proot123456 biteblog -e "
INSERT INTO notification (receiver_id, sender_id, type, biz_id, content, read_status, created_at)
VALUES (11, 12, 'like', 999, '30天前通知', 1, DATE_SUB(NOW(), INTERVAL 31 DAY));
"
```

将 `@Scheduled(cron = "0 0 3 * * ?")` 临时改为 `@Scheduled(cron = "0/1 * * * * ?")`，重启 notify 等 1 分钟，验证：

```powershell
docker exec biteblog-mysql mysql -u root -proot123456 biteblog -e "
SELECT COUNT(*) as hot FROM notification WHERE created_at < DATE_SUB(NOW(), INTERVAL 30 DAY) AND read_status=1;
SELECT COUNT(*) as archive FROM notification_archive;
"
```

`hot` 应为 0，`archive` 应增加对应条数。验证完毕后将 cron 改回 `0 0 3 * * ?`。

---

## 9. 常见问题

| 现象 | 处理 |
|------|------|
| `login failed: 无法连接到远程服务器` | user-service(8081) 未启动 |
| `[FAIL] MQ not consumed: 0 new notifications` | RabbitMQ Unacked 堆积；Purge `notify.interaction.queue` 后重跑 |
| `[WARN] pagination: total=0` | MybatisPlusConfig 未加载；确认 `config/MybatisPlusConfig.java` 存在并重编译 |
| `[WARN] cache cold/hot: hot > cold` | Redis 与应用同机时网络开销极小，两者差异不显著，属正常 |
| `[WARN] DLQ: N messages` | 有消费失败；查 notify 日志排查根因 |
| JMeter Error% > 0 | 先确保 `init-notify-data.ps1` 执行成功且 token 有效 |
| 前端「实时未连接」 | 确认 8087 可访问；检查 `VITE_NOTIFY_WS_ORIGIN`；确认 `vite.config.js` 有 `define: { global: 'globalThis' }` 且重启 dev server |
