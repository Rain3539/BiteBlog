# Notify Service 测试说明

## 1. 测试目标与范围

本文件记录 Notify Service 的测试方法与测试结果，覆盖功能验证、非功能验证和跨服务集成验证。

### 1.1 测试脚本与报告文件


| 文件                                       | 用途                                          |
| ---------------------------------------- | ------------------------------------------- |
| `sql/init-notify-data.ps1`               | 初始化测试数据（使用 init-data.ps1 账号，不额外注册用户）        |
| `sql/notify-init-result.txt`             | 初始化脚本输出（自动生成）                               |
| `测试脚本/notify-test-verify.ps1`            | PowerShell 全量验证脚本（功能 + 性能 + 集成 + 安全，27 项检查） |
| `测试脚本/notify-test-result.txt`            | 验证脚本输出（自动生成）                                |
| `jmeter/notify-service-test.jmx`         | JMeter 并发压测脚本                               |
| `jmeter/notify-service-result.jtl`       | JMeter 原始结果（执行后生成）                          |
| `jmeter/notifyservice-report/index.html` | JMeter HTML 报告（执行后生成）                       |


---

## 2. 启动前准备

### 2.1 中间件检查

```powershell
docker ps --format "table {{.Names}}\t{{.Status}}"
```

确认 `biteblog-mysql`、`biteblog-redis`、`biteblog-rabbitmq`、`biteblog-nacos` 状态为 `Up`。

### 2.2 数据库初始化

```powershell
# 执行 init.sql（含 notification 与 notification_archive 表）
Get-Content "e:\WHU\DistributedGroupWork\BiteBlog\sql\init.sql" | docker exec -i biteblog-mysql mysql -u root -proot123456 biteblog
```

验证：

```powershell
docker exec biteblog-mysql mysql -u root -proot123456 biteblog -e "SHOW TABLES LIKE 'notification%';"
# 预期：notification  notification_archive
```

### 2.3 基础用户数据

```powershell
cd e:\WHU\DistributedGroupWork\BiteBlog\sql
.\init-data.ps1        # 创建 13800000001~13800000060
```

### 2.4 微服务启动顺序

```powershell
cd biteblog-backend
mvn -pl biteblog-user    -am spring-boot:run   # 8081
mvn -pl biteblog-post    -am spring-boot:run   # 8082
mvn -pl biteblog-notify  -am spring-boot:run   # 8087
mvn -pl biteblog-gateway -am spring-boot:run   # 8080
```

### 2.5 初始化 Notify 测试数据

```powershell
cd sql
.\init-notify-data.ps1
```

预期输出：

```
[OK] author: bb_bigv_01 userId=1
[OK] fan   : bb_user_04 userId=4
[OK] published postId=...
[OK] like     liked=True
[OK] favorite favorited=True
[OK] comment  posted
[OK] /notify/list  total=3
[OK] /notify/unread-count unreadCount=3
```

---

## 3. 功能测试

### 3.1 全量验证脚本（首选方式）

```powershell
cd 测试脚本
.\notify-test-verify.ps1
```

结果自动保存至 `notify-test-result.txt`，截图测试结果页即可作为验证证据。

**脚本覆盖的 14 个测试节（共 27 项检查）：**


| 节   | 检查内容                        | 关键通过标准                                     |
| --- | --------------------------- | ------------------------------------------ |
| 0   | 账号登录                        | 13800000001/13800000004 登录成功               |
| 1   | 健康检查                        | 直连 8087 和经网关 8080 均返回 `status=UP`          |
| 2   | 发布测试笔记                      | postId 成功返回                                |
| 3   | MQ 消费（like/collect/comment） | API total 增加 ≥ 3，类型分布正确                    |
| 4   | 自操作过滤                       | 作者给自己点赞，通知数不变                              |
| 5   | 幂等去重（5 分钟窗口）                | 同一 like 在窗口内重复投递，only 1 条通知                |
| 6   | 单条已读                        | 指定通知 `readStatus` 变为 1                     |
| 7   | 全部已读 + unreadCount 归零       | `unreadCount=0` after `read-all`           |
| 8   | 列表 RT（20 次采样）               | P95 < 300ms                                |
| 9   | 未读数 RT（20 次采样，Redis 热路径）    | P95 < 100ms                                |
| 10  | Redis 缓存冷热对比                | cold(DB) ≥ hot(Redis)（本地 RTT 很小时允许误差 5ms）  |
| 11  | 列表过滤（type/readStatus）       | 返回数据类型/已读状态均与过滤参数一致                        |
| 12  | 分页 total 一致 + 无跨页重复         | 两页 total 相同，id 无交集                         |
| 13  | 鉴权（无 Token 401）+ 越权拒绝       | 无 Token 返回 401；fan 读 author 通知被拒（code 403） |
| 14  | RabbitMQ 队列状态               | consumers=1, Unacked=0, DLQ Ready=0        |


### 3.2 测试结果（实测数据）

以下为本地运行 `notify-test-verify.ps1` 的实测输出（截图来源：`notify-test-result.txt`）：

```
===== Summary =====

  Pass  : 27
  Fail  : 0
  Total : 27 checks

  --- Response Time Results ---
  List        avg=26.8ms  P90=32ms  P95=32ms  max=38ms
  Unread-cnt  avg=9ms     P90=10ms  P95=10ms  max=12ms
  Cache       cold(DB)=10ms  hot(Redis)=9ms
```

### 3.3 跨服务功能验证

Notify 与其他服务的交叉功能验证：


| 测试场景                          | 涉及服务             | 验证方式                           | 结果  |
| ----------------------------- | ---------------- | ------------------------------ | --- |
| 用户 A 发布笔记，用户 B 点赞 → A 收到通知    | Post + Notify    | init-notify-data.ps1 + 验证脚本节 3 | 通过  |
| 用户 B 收藏笔记 → A 收到 collect 类型通知 | Post + Notify    | 验证脚本节 3 + 节 11 过滤              | 通过  |
| 用户 B 评论笔记 → A 收到 comment 类型通知 | Post + Notify    | 验证脚本节 3                        | 通过  |
| 作者对自己笔记点赞 → 不产生通知             | Post + Notify    | 验证脚本节 4                        | 通过  |
| 通知列表展示发送者昵称（而不只是 id）          | Notify + User    | 验证脚本节 3 senderUsername 字段      | 通过  |
| JWT Token 由网关解析并透传 X-User-Id  | Gateway + Notify | 验证脚本节 13 无 Token 401 测试        | 通过  |
| WebSocket 实时推送                | Notify + 前端      | 前端通知中心页「实时已连接」+ 点赞后实时出现新条目     | 通过  |


---

## 4. 非功能测试

### 4.1 响应时间

**测试方式**：验证脚本 `Measure-RT` 函数，每个接口 20 次采样，用 `Stopwatch` 精确计时。

**实测结果**：


| 接口                                    | avg    | Min  | P90  | P95  | Max  | 达标（目标）      |
| ------------------------------------- | ------ | ---- | ---- | ---- | ---- | ----------- |
| `GET /api/notify/list?page=1&size=20` | 26.8ms | 24ms | 32ms | 32ms | 38ms | P95 < 300ms |
| `GET /api/notify/unread-count`        | 9ms    | 8ms  | 10ms | 10ms | 12ms | P95 < 100ms |


Redis 缓存冷热对比：

- **冷路径**（首次请求，cache miss → DB COUNT）：10ms
- **热路径**（后续请求，cache hit → Redis GET）：9ms

单次对比差异因本地 RTT 极小，两者接近；在高并发或网络存在延迟的环境中热路径优势更显著。

### 4.2 并发压测（JMeter）

**执行方式**：

```powershell
cd BiteBlog
& "你的JMeter路径\bin\jmeter.bat" `
  -n -t jmeter\notify-service-test.jmx `
  -l jmeter\notify-service-result.jtl `
  -e -o jmeter\notifyservice-report
```

**脚本配置**：setUp Thread Group 登录一次（保证 token 正确），主 Thread Group 10 线程 × 20 循环。

**压测结果（本地 10 并发）**：


| 接口                             | 样本数 | avg   | P95 | Error% | TPS   |
| ------------------------------ | --- | ----- | --- | ------ | ----- |
| `GET /api/notify/health`       | 200 | 1.5ms | 4ms | 0%     | ~50/s |
| `GET /api/notify/list`         | 200 | 1.2ms | 7ms | 0%     | ~50/s |
| `GET /api/notify/unread-count` | 200 | 1.2ms | 9ms | 0%     | ~50/s |


截图说明：报告位于 `jmeter/notifyservice-report/index.html`，Statistics 页查看各接口 P90/P95 和 Error%。

### 4.3 Redis 缓存命中率测试

**测试方式**：

```powershell
# 1. 删除缓存 key（模拟冷启动）
docker exec biteblog-redis redis-cli --no-auth-warning -a redis123456 DEL notify:unread:1

# 2. 第一次请求（cache miss → DB COUNT，记录时间）
$sw = [System.Diagnostics.Stopwatch]::StartNew()
Invoke-RestMethod -Uri "http://localhost:8080/api/notify/unread-count" -Headers @{ Authorization="Bearer $token" }
Write-Host "cold: $($sw.ElapsedMilliseconds)ms"

# 3. 后续请求（cache hit → Redis GET，记录时间）
$sw.Restart()
Invoke-RestMethod -Uri "http://localhost:8080/api/notify/unread-count" -Headers @{ Authorization="Bearer $token" }
Write-Host "hot: $($sw.ElapsedMilliseconds)ms"
```

**结果**：cold = 10ms，hot = 9ms（本地 Redis 和 DB 均在同机，冷热差距小；生产环境 Redis 远比 MySQL COUNT 快）。

### 4.4 RabbitMQ 消息可靠性

**测试方式**：观察 RabbitMQ 管理台（`http://localhost:15672`）队列指标。

**实测结果**：


| 指标                                   | 实测值 | 说明           |
| ------------------------------------ | --- | ------------ |
| `notify.interaction.queue` Consumers | 1   | Notify 服务已连接 |
| Ready                                | 0   | 所有消息均被消费     |
| Unacked                              | 0   | 消费成功且已 ack   |
| `notify.dead.queue` Ready            | 0   | 无消费失败消息      |


**死信队列（DLQ）验证**：手动制造消费异常（临时抛 RuntimeException），消息正确流入 `notify.dead.queue`，不无限重投，主队列 Unacked 不堆积。

### 4.5 幂等去重验证

**测试方式**：验证脚本节 5 — 5 分钟窗口内对同一笔记连续两次点赞（中间取消一次），观察 `type=like` 的通知总数。

**结果**：第二次点赞（在 5 分钟窗口内）被去重过滤，通知总数不增加。日志输出 `notify dedup skip`。

### 4.6 安全性验证


| 测试项                | 方式                                       | 结果            |
| ------------------ | ---------------------------------------- | ------------- |
| 无 Token 访问         | 不带 Authorization 头请求 `/api/notify/list`  | 网关返回 HTTP 401 |
| 越权已读               | 用 fan 的 token 调用 author 通知的 `/{id}/read` | 业务返回 code 403 |
| WebSocket 无效 Token | 连接 `/ws-notify?token=invalid`            | 握手被拒，WS 连接失败  |


---

## 5. 常见问题


| 现象                        | 原因                                                | 处理                                                                                                                            |
| ------------------------- | ------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| MQ 消费失败，Unacked 持续增加      | 业务异常或 `CollSer` 反序列化失败                            | 检查 notify 日志；确认 `System.setProperty("spring.amqp.deserialization.trust.all","true")` 已在 main() 中设置；在 RabbitMQ 管理台 Purge 队列后重试 |
| `/notify/list` total 始终 0 | MyBatis-Plus 分页插件未加载                              | 确认 `config/MybatisPlusConfig.java` 存在，重新编译启动                                                                                  |
| 分页出现重复记录                  | 只按 `created_at` 排序，批量插入时顺序不稳定                     | 已修复为 `created_at DESC, id DESC` 双列排序；重启 notify-service                                                                        |
| 前端「实时未连接」                 | WebSocket 无法连接 8087，或 `sockjs-client` global 变量报错 | 确认 notify-service 8087 可访问；确认 `vite.config.js` 含 `define: { global: 'globalThis' }` 并重启 dev server                            |
| JMeter 全部 401             | setUp 登录失败，token 为 NOT_FOUND，后续请求无效 token         | 先运行 `init-data.ps1`；检查 JMeter Console 中 setUp 阶段的 Groovy 日志；确认网关和 user-service 正常                                             |
| 通知列表无 senderUsername      | user-service 未启动，Feign 调用失败                       | 启动 user-service 8081；超时降级为 null（前端显示"用户{id}"）                                                                                 |


