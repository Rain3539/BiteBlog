# Notify Service 修改说明

## 1. 服务定位

本次完成的是组员 6 负责的 `Notify Service`，服务端口为 **8087**。该服务在 BiteBlog 微服务体系中承担**消息通知**职责：

- 订阅 RabbitMQ `biteblog.interaction` 交换机，消费 Post 服务产生的点赞/收藏/评论互动事件；
- 将通知持久化到 MySQL `notification` 表，并维护未读计数；
- 通过 **STOMP over WebSocket（SockJS）** 向在线用户实时推送通知；
- 提供 HTTP 查询、已读、过滤等接口，供前端通知中心页面和导航栏未读角标使用。

与其他服务的协作关系：

| 依赖方向 | 服务 | 协作内容 |
|----------|------|---------|
| 消费方 | Post Service | 订阅 `biteblog.interaction` 交换机，消费 `interaction.like/collect/comment` 事件 |
| 调用方 | User Service | OpenFeign `GET /user/{id}` 查询发送者昵称，在列表中补全展示名 |
| 路由方 | Gateway | 所有 HTTP 接口经 `/api/notify/**` 网关路由，JWT 由网关校验并以 `X-User-Id` 透传 |
| 数据库 | MySQL `biteblog` | `notification`（热表）、`notification_archive`（冷归档表） |
| 缓存 | Redis | Cache-Aside 模式维护 `notify:unread:{userId}`，降低高频未读接口的 DB 压力 |

---

## 2. 新增/修改文件列表

### 2.1 后端（biteblog-backend/biteblog-notify）

| 文件 | 说明 |
|------|------|
| `pom.xml` | 引入 Web、Validation、MyBatis-Plus、MySQL、Nacos、AMQP、OpenFeign、LoadBalancer、WebSocket、Redis（通过 biteblog-common 传递）依赖 |
| `NotifyApplication.java` | 启动类；`main()` 中 `System.setProperty("spring.amqp.deserialization.trust.all","true")` 解决 Post 侧 `Map.of()` JDK 序列化产生 `CollSer` 类被 Spring AMQP 3.x 白名单拦截的问题 |
| `config/NotifyRabbitConfig.java` | 声明 Topic 交换机 `biteblog.interaction`、主队列 `notify.interaction.queue`（绑定 `interaction.*`）、死信交换机 `notify.dlx`、死信队列 `notify.dead.queue`，主队列通过 `x-dead-letter-exchange` 绑定死信链路 |
| `config/MybatisPlusConfig.java` | 注册 `PaginationInnerInterceptor`（MySQL 方言），使 `Page.getTotal()` 能正确执行 `COUNT(*)` 并返回分页总数 |
| `config/WebSocketConfig.java` | 注册 SockJS 端点 `/ws-notify`，启用 STOMP 简单消息代理，设置应用前缀 `/app`、用户前缀 `/user` |
| `config/NotifyJwtHandshakeInterceptor.java` | WebSocket 握手拦截器：从 Query 参数 `token` 解析 JWT，鉴权失败直接拒绝握手 |
| `config/NotifyStompHandshakeHandler.java` | 握手完成后将 `Principal.getName()` 设为 `userId` 字符串，供 `convertAndSendToUser` 精准路由 |
| `entity/Notification.java` | 映射 `notification` 表，含 `receiverId`、`senderId`、`type`、`bizId`、`content`、`readStatus`、`createdAt` |
| `entity/NotificationArchive.java` | 映射 `notification_archive` 冷归档表，额外包含 `archivedAt` 字段 |
| `mapper/NotificationMapper.java` | 继承 `BaseMapper<Notification>`，无额外自定义方法 |
| `mapper/NotificationArchiveMapper.java` | 继承 `BaseMapper`，新增 `insertIgnore()` 方法（`INSERT IGNORE`，保证归档任务幂等） |
| `dto/NotificationVO.java` | 列表返回 VO，含 `notificationId`、`senderUsername` 等展示字段 |
| `dto/NotifyPushPayload.java` | WebSocket 推送载荷，`createdAt` 用 ISO-8601 字符串而非 `LocalDateTime`，避免 STOMP Jackson 序列化异常 |
| `service/NotifyService.java` | 核心业务逻辑（详见第 3 节） |
| `service/NotifyEventListener.java` | `@RabbitListener(ackMode="MANUAL")`，业务成功 `basicAck`，业务异常 `basicNack(requeue=false)` 转死信 |
| `client/UserFeignClient.java` | `@FeignClient(name="user-service")` 声明，调用 `GET /user/{id}` |

### 2.2 前端（frontend/src）

| 文件 | 说明 |
|------|------|
| `api/notify.js` | 封装 `getNotifyList`、`getNotifyUnreadCount`、`markNotifyRead`、`markAllNotifyRead` 四个 HTTP 方法 |
| `views/NotifyView.vue` | 通知中心页：分页列表、类型 Tab 筛选（全部/未读/点赞/收藏/评论）、整行卡片点击跳帖、标为已读、全部已读、STOMP WebSocket 实时订阅 |
| `components/layout/AppLayout.vue` | 导航栏铃铛角标，路由切换时自动刷新未读数 |
| `vite.config.js` | 增加 `define: { global: 'globalThis' }`，解决 `sockjs-client` 在 Vite ESM 环境下 `global is not defined` 错误 |

### 2.3 数据库与脚本

| 文件 | 说明 |
|------|------|
| `sql/init.sql` | 新增 `notification_archive` 冷归档表 DDL（`IF NOT EXISTS`，不影响已有数据） |
| `sql/init-notify-data.ps1` | 复用 init-data.ps1 的 `13800000001`/`13800000004`，发布笔记、执行互动、验证通知条数与未读数；结果保存至 `notify-init-result.txt` |
| `jmeter/notify-service-test.jmx` | 压测脚本：setUp Thread Group 登录一次共享 token，主 Thread Group（10 线程 × 20 循环）压测 health/list/unread-count；加业务码断言，防止 token 提取失败导致假通过 |
| `测试脚本/notify-test-verify.ps1` | PowerShell 全量验证脚本，27 个检查项，结果保存至 `notify-test-result.txt` |

---

## 3. 接口设计

### 3.1 健康检查

```http
GET /notify/health
```

返回 `{"service":"notify-service","status":"UP"}`，无需鉴权，可直连 8087 或经网关访问。

### 3.2 通知列表（支持过滤）

```http
GET /notify/list?page=1&size=20&type=like&readStatus=0
Authorization: Bearer <JWT>（经网关时由网关透传 X-User-Id）
```

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `page` | int | 1 | 页码 |
| `size` | int | 20 | 每页条数，上限 50 |
| `type` | string | 不过滤 | `like` / `collect` / `comment`；为空则返回全部类型 |
| `readStatus` | int | 不过滤 | `0`=未读 `1`=已读；不传则返回全部 |

返回结构：

```json
{
  "code": 200,
  "data": {
    "list": [
      {
        "notificationId": 1,
        "senderId": 2,
        "senderUsername": "bb_user_04",
        "type": "like",
        "bizId": 10,
        "content": "赞了你的笔记",
        "readStatus": 0,
        "createdAt": "2026-05-18T10:00:00"
      }
    ],
    "total": 3
  }
}
```

排序规则：`created_at DESC, id DESC`（双列排序，防止批量写入时时间戳相同导致分页结果不稳定）。

### 3.3 未读数

```http
GET /notify/unread-count
Authorization: Bearer <JWT>
```

返回 `{"unreadCount": 3}`，导航栏角标轮询此接口。底层先读 Redis 缓存，miss 时查 DB 并回填（Cache-Aside，TTL 5 分钟）。

### 3.4 全部标为已读

```http
POST /notify/read-all
Authorization: Bearer <JWT>
```

将当前用户所有 `read_status=0` 批量更新为 `1`，同时清除 Redis 未读缓存 key。

### 3.5 单条标为已读

```http
POST /notify/{id}/read
Authorization: Bearer <JWT>
```

校验 `notification.receiver_id == 当前用户`，防止越权操作（返回业务码 403）。

---

## 4. 业务功能设计

### 4.1 通知写入流程

```
Post 服务互动成功（like/collect/comment）
  → convertAndSend("biteblog.interaction", "interaction.like", Map.of(...))
  → RabbitMQ notify.interaction.queue
  → NotifyEventListener.onInteraction()（手动 ack）
      ├── 解析 noteId / userId / authorId
      ├── 过滤：authorId == userId → 自操作，直接 ack 跳过
      ├── 去重：5 分钟窗口内相同 receiver+sender+type+bizId → 已存在，ack 跳过（防 MQ 重投重复写）
      └── NotifyService.saveAndPush()（@Transactional）
              ├── DB insert（notification 表）
              ├── Redis INCR notify:unread:{receiverId}
              └── afterCommit 回调：Feign 查昵称 → WS 推送
                      └── messagingTemplate.convertAndSendToUser(userId, "/queue/notify", payload)
```

### 4.2 自操作过滤

互动事件载荷包含 `userId`（操作者）和 `authorId`（笔记作者），`authorId == userId` 时跳过写入，不产生通知。

### 4.3 幂等去重（5 分钟窗口）

消费前查询 `notification` 表：相同 `receiver_id + sender_id + type + biz_id` 且 `created_at > NOW()-5分钟` 的记录若已存在，则视为 MQ 重投导致的重复投递，跳过写入。

### 4.4 事务边界设计

`handleInteractionEvent` 标注 `@Transactional`，通过 Spring AOP 代理进入时事务生效。内部 `saveAndPush` 同 Bean 调用不过代理：DB insert 在事务内，Feign HTTP（查用户名）和 WebSocket 推送通过 `TransactionSynchronizationManager.registerSynchronization(afterCommit→...)` 延迟到事务提交后执行，避免数据库连接持有期间被远程 HTTP 阻塞。

### 4.5 冷热数据分离

`notification` 表（热表）只保留近 30 天数据；每天凌晨 3 点 `@Scheduled` 任务将 **已读** 的过期记录迁移到 `notification_archive`（冷表）：

1. 每批最多 500 条，分批处理，防止大事务锁表；
2. 归档写入用 `INSERT IGNORE`，任务重启重跑保证幂等；
3. 先写冷表再删热表，失败可重试；
4. **未读通知不归档**，保证用户历史未读不丢失。

---

## 5. 非功能需求处理

### 5.1 可靠性（消息不丢失）

**手动 Ack + 死信队列（DLQ）**：

- `NotifyEventListener` 使用 `ackMode = MANUAL`；
- 业务成功 → `basicAck`，消息从队列删除；
- 业务异常 → `basicNack(requeue=false)`，消息转投 `notify.dlx` → `notify.dead.queue`，不无限重投；
- 死信队列供运维检查和手动补偿。

### 5.2 性能

**Redis Cache-Aside（未读数缓存）**：

- 高频导航栏接口 `GET /notify/unread-count` 不每次做 `COUNT(*)` 全表扫描；
- 命中 Redis 时 P95 响应时间 **< 15ms**（本地测试实测 P95 = 10ms）；
- Redis 不可用时自动降级查 DB，接口不报错。

**Feign 并发查昵称**：

- 列表页多个不同发送者时，串行调 N 次 Feign 时延为 N×RTT；
- 改为 `CompletableFuture` 提交到专用线程池（核心 5 线程，最大 10 线程），并发请求 user-service，总耗时约等于最慢一次 RTT；
- 整批设置 3 秒超时，超时的 userId 昵称降级为 null（前端显示"用户{id}"），不影响其余结果。

**分页稳定排序**：

- 通知列表按 `created_at DESC, id DESC` 排序；
- 次级排序 `id DESC` 保证批量写入（时间戳相同）时分页结果稳定，无跨页重复。

### 5.3 可用性

- WebSocket 推送失败仅打 warn 日志，不影响事务提交和通知持久化；
- Redis 故障自动降级 DB；
- RabbitMQ 连接中断后 Spring AMQP 自动重连。

### 5.4 数据治理

- 定时归档避免 `notification` 热表无限增长；
- 冷表 `notification_archive` 含 `archived_at`，可用于数据审计；
- 已读数据 30 天后归档，未读数据永久保留。

### 5.5 安全性

- HTTP 接口鉴权：JWT 由网关校验，网关写入 `X-User-Id`，下游服务信任网关；
- 越权保护：`POST /notify/{id}/read` 校验 `receiverId == 当前用户`；
- WebSocket 鉴权：握手时从 Query 参数 `token` 解析 JWT，校验失败拒绝连接；
- 分页参数边界控制：`size` 上限 50。

---

## 6. 联调中发现并修复的问题

| 问题 | 根因 | 修复方式 |
|------|------|---------|
| MQ 消息一直 Unacked，通知写不进 DB | Post 用 `Map.of()` 发送 JDK 序列化消息，产生 `java.util.CollSer`；Spring AMQP 3.x 白名单拒绝反序列化 | `NotifyApplication.main()` 中 `System.setProperty("spring.amqp.deserialization.trust.all","true")` |
| `/notify/list` 的 `total` 字段始终为 0 | 项目未注册 MyBatis-Plus 分页拦截器，`Page.getTotal()` 不执行 COUNT 查询 | 新增 `MybatisPlusConfig`，注册 `PaginationInnerInterceptor` |
| 分页跨页出现重复记录 | 仅按 `created_at DESC` 排序，批量插入时时间戳相同，MySQL 排序不稳定 | 加 `.orderByDesc(Notification::getId)` 次级排序 |
| 前端点击铃铛报 `global is not defined` | `sockjs-client` v1.6 内部使用 Node.js `global`，Vite ESM 环境不存在此变量 | `vite.config.js` 加 `define: { global: 'globalThis' }` |
| `@Transactional` 失效，Feign 在事务内阻塞连接池 | `handleInteractionEvent` 无事务，内部同 Bean 调用 `saveAndPush` 不过代理，事务注解无效；且 Feign/WS 在同一调用栈内占用 DB 连接 | 事务标注移至外部入口；`TransactionSynchronizationManager.afterCommit` 延迟执行 Feign/WS |
