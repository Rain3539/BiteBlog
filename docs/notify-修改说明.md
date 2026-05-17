# Notify Service 修改说明

## 1. 服务定位

本次完成的是组员 6 负责的 `Notify Service`，服务端口为 `8087`。该服务负责 BiteBlog 平台的消息通知能力，核心目标是把其他服务产生的互动事件（点赞、收藏、评论）转化为用户可见的通知，并通过 WebSocket 向在线用户实时推送，同时提供 HTTP 查询与已读接口供前端展示未读角标和通知列表。

当前实现完整支持联调：消费 Post 服务发布到 `biteblog.interaction` 交换机的互动事件，将通知写入 MySQL `notification` 表，通过 STOMP over WebSocket（SockJS 降级）推送给在线用户。前端通知中心页面与导航栏未读角标均已接入。

## 2. 本次新增/修改文件

| 文件 | 说明 |
|---|---|
| `biteblog-backend/biteblog-notify/pom.xml` | 补充 Web、Validation、MyBatis-Plus、MySQL、Nacos、AMQP、OpenFeign、LoadBalancer、WebSocket 依赖。 |
| `NotifyApplication.java` | Notify 服务启动类，启用 Nacos 服务发现、Feign 客户端和 Mapper 扫描；`main()` 中通过 `System.setProperty` 放开 Spring AMQP 反序列化白名单，兼容 Post 侧用 `Map.of()` 发送的 JDK 序列化消息。 |
| `config/NotifyRabbitConfig.java` | 声明 RabbitMQ 交换机 `biteblog.interaction`、队列 `notify.interaction.queue` 和绑定关系，消费所有 `interaction.*` 路由键事件。 |
| `config/MybatisPlusConfig.java` | 注册 MyBatis-Plus 分页拦截器（`PaginationInnerInterceptor`），使 `Page.getTotal()` 能正确返回总条数。 |
| `config/WebSocketConfig.java` | 配置 SockJS 端点 `/ws-notify`，启用 STOMP 消息代理，设置应用目的地前缀 `/app` 和用户目的地前缀 `/user`。 |
| `config/NotifyJwtHandshakeInterceptor.java` | WebSocket 握手拦截器，从 Query 参数 `token` 中解析 JWT 并校验，将 `userId` 存入握手属性。 |
| `config/NotifyStompHandshakeHandler.java` | 握手处理器，将 `Principal#getName` 设为 `userId` 字符串，供 `convertAndSendToUser` 路由使用。 |
| `entity/Notification.java` | 映射数据库 `notification` 表，包含接收者、发送者、类型、关联业务 ID、内容、已读状态和创建时间。 |
| `mapper/NotificationMapper.java` | MyBatis-Plus Mapper，用于通知的查询、插入和更新操作。 |
| `dto/NotificationVO.java` | 通知列表返回对象，在实体基础上增加 `notificationId` 和 `senderUsername` 字段。 |
| `dto/NotifyPushPayload.java` | WebSocket 推送载荷，`createdAt` 使用 ISO-8601 字符串而非 `LocalDateTime`，避免 STOMP 序列化异常。 |
| `service/NotifyService.java` | 通知核心业务逻辑，包括分页查询、未读数统计、已读标记、MQ 事件处理和 WebSocket 推送。 |
| `service/NotifyEventListener.java` | 监听 RabbitMQ `notify.interaction.queue`，解析互动事件并写入通知；捕获所有异常保证消息能正常 ack，防止 Unacked 堆积。 |
| `client/UserFeignClient.java` | 通过 OpenFeign 调用 `user-service` 的 `GET /user/{id}`，为通知补全发送者昵称。 |
| `controller/NotifyController.java` | 暴露 `/notify/list`、`/notify/unread-count`、`/notify/read-all`、`/notify/{id}/read`、`/notify/health` 接口。 |
| `frontend/src/api/notify.js` | 前端通知 API 封装，包含列表、未读数、单条已读、全部已读四个方法。 |
| `frontend/src/views/NotifyView.vue` | 通知中心页面，展示分页通知列表、已读操作，并通过 STOMP 建立 WebSocket 实时连接。 |
| `frontend/src/components/layout/AppLayout.vue` | 导航栏增加未读角标，路由切换时自动刷新未读数。 |
| `frontend/vite.config.js` | 增加 `define: { global: 'globalThis' }`，解决 `sockjs-client` 在 Vite ESM 环境下 `global is not defined` 报错。 |
| `sql/init-notify-data.ps1` | Notify 服务测试数据初始化脚本。 |
| `jmeter/notify-service-test.jmx` | Notify 服务 JMeter 压测脚本。 |

## 3. 接口设计

### 3.1 健康检查

```http
GET /notify/health
```

用于确认 Notify 服务是否已经启动成功，无需鉴权。

### 3.2 通知列表

```http
GET /notify/list?page=1&size=20
Authorization: Bearer <JWT>
```

参数说明：

- `page`：页码，默认 `1`；
- `size`：每页条数，默认 `20`，最大限制为 `50`；
- `Authorization`：直连服务时也需传入 JWT（网关透传 `X-User-Id`，直连时 Controller 直接读此 Header）。

返回结果包含 `list` 和 `total`。其中 `list` 中每一项包含通知 ID、发送者 ID、发送者昵称、类型（`like`/`collect`/`comment`/`follow`）、关联笔记 ID、通知内容、已读状态和创建时间。

### 3.3 未读数

```http
GET /notify/unread-count
Authorization: Bearer <JWT>
```

返回 `unreadCount`，前端导航栏角标使用此接口。

### 3.4 全部标为已读

```http
POST /notify/read-all
Authorization: Bearer <JWT>
```

将当前用户所有未读通知标为已读。

### 3.5 单条标为已读

```http
POST /notify/{id}/read
Authorization: Bearer <JWT>
```

将指定通知标为已读，接口会校验该通知的 `receiverId` 是否与当前用户一致，防止越权操作。

## 4. 核心业务逻辑

Notify 服务的核心处理链路如下：

```text
Post 服务发布互动事件（点赞/收藏/评论）
-> RabbitMQ biteblog.interaction 交换机
-> notify.interaction.queue 队列
-> NotifyEventListener 消费
-> 过滤 authorId == userId（自己操作自己笔记不通知）
-> 写入 MySQL notification 表（事务）
-> WebSocket 推送给在线用户（非阻塞，失败只记日志）
```

过滤自身操作的逻辑：互动事件载荷中包含 `userId`（操作者）和 `authorId`（笔记作者），两者相同时认为是自操作，跳过写入通知。

通知类型与文案对应关系：

| 路由键 | type 字段 | 通知内容 |
|---|---|---|
| `interaction.like` | `like` | 赞了你的笔记 |
| `interaction.collect` | `collect` | 收藏了你的笔记 |
| `interaction.comment` | `comment` | 评论了你的笔记 |

## 5. RabbitMQ 事件消费

Notify 服务监听以下事件：

| 交换机 | 路由键 | 来源服务 | 处理逻辑 |
|---|---|---|---|
| `biteblog.interaction` | `interaction.like` | Post 服务 | 写入点赞通知，推送接收者 |
| `biteblog.interaction` | `interaction.collect` | Post 服务 | 写入收藏通知，推送接收者 |
| `biteblog.interaction` | `interaction.comment` | Post 服务 | 写入评论通知，推送接收者 |

**消息格式兼容说明**：Post 服务使用 `Map.of()` 构造事件载荷，经 JDK 默认序列化后生成 `java.util.CollSer`。Spring AMQP 3.x 默认白名单不包含该类，需在 `NotifyApplication.main()` 中通过 `System.setProperty("spring.amqp.deserialization.trust.all", "true")` 放开限制。此配置通过 `application.yml` 绑定无效，必须在 JVM 系统属性层面设置。

**关注通知**：`follow` 类型通知需由 `user-service` 在关注成功时向 `biteblog.interaction` 发送 MQ 事件，当前若 user 侧未实现则不产生关注通知。

## 6. WebSocket 实时推送

Notify 服务基于 STOMP over SockJS 提供实时推送：

- 连接地址：`{notifyHost}/ws-notify?token={JWT}`（开发环境默认 `http://localhost:8087`）
- 订阅目的地：`/user/queue/notify`
- 推送方向：服务端 → 指定用户（通过 `convertAndSendToUser` 按 userId 路由）

鉴权流程：握手时 `NotifyJwtHandshakeInterceptor` 从 Query 参数 `token` 解析 JWT，将 `userId` 存入握手属性；`NotifyStompHandshakeHandler` 将 `Principal` 名称设为 `userId`，STOMP 框架据此路由用户级订阅。

**网关与 WebSocket 的关系**：网关当前未代理 WebSocket，实时通道需前端直连 notify 端口。HTTP 查询接口走 `/api/notify/**` 网关路由，WebSocket 连接直连 `8087`。前端通过环境变量 `VITE_NOTIFY_WS_ORIGIN` 覆盖默认 origin：

```env
# frontend/.env.development（按实际部署地址修改）
VITE_NOTIFY_WS_ORIGIN=http://你的主机:8087
```

## 7. 非功能需求处理

### 7.1 性能

分页大小最大限制为 50，避免单次请求过大。未读数查询使用数据库复合索引 `idx_receiver(receiver_id, read_status)`，不做全表扫描。

### 7.2 可用性

WebSocket 推送失败只记录 warn 日志，不影响通知写入事务。MQ 消费端捕获所有异常并 ack，防止消息无限重投导致 Unacked 堆积、队列死锁；消费失败的通知可通过重新触发互动操作补发。

### 7.3 一致性

通知写入使用 `@Transactional` 保证数据库写入原子性，WebSocket 推送在事务提交后执行，不影响持久化结果。未读数来自实时数据库统计，与列表数据强一致。

### 7.4 安全性

单条已读接口校验 `receiverId == 当前用户`，防止越权修改他人通知状态。WebSocket 握手强制校验 JWT，未携带合法 token 的连接直接拒绝握手。

### 7.5 可维护性

通知类型与文案映射、路由键解析逻辑集中在 `NotifyService` 中，新增通知类型只需在对应位置添加分支。`UserFeignClient` 通过 Nacos 服务发现调用 `user-service`，无需硬编码地址。

## 8. 本地测试环境说明

本地测试依赖 MySQL、RabbitMQ 和 Nacos。Notify 服务使用 `localhost:3306` 连接数据库 `biteblog`，MySQL 密码为 `root123456`，RabbitMQ 使用默认 guest/guest。

测试数据通过以下脚本初始化：

```powershell
cd BiteBlog\sql
.\init-notify-data.ps1
```

该脚本会注册/登录两个专用账号（手机号 `13900004001` 作者 / `13900004002` 互动方，密码均为 `12345678`），创建测试笔记，由互动方执行点赞、收藏、评论，最后调用 Notify 接口验证通知条数与未读数。

若通知列表 `total` 为 0 但列表和 `unreadCount` 均有数据，说明 `MybatisPlusConfig` 未加载（分页拦截器缺失）；若列表为空说明 RabbitMQ 消费异常，可在管理台 Purge `notify.interaction.queue` 后重新运行脚本。

## 9. 分布式工程化优化说明

### 9.1 死信队列（DLQ）

`NotifyRabbitConfig` 新增了 `notify.dlx`（死信交换机）和 `notify.dead.queue`（死信队列）。`notify.interaction.queue` 通过 `x-dead-letter-exchange` 参数绑定到死信交换机。

`NotifyEventListener` 改为手动 ack（`ackMode = MANUAL`）：业务成功执行 `basicAck`；业务异常执行 `basicNack(requeue=false)`，消息转投死信队列而非无限重投或静默丢弃，符合分布式系统的**可靠消息**设计原则。

`application.yml` 中 `acknowledge-mode: manual` 与监听器配合生效。

### 9.2 未读数 Redis 缓存

`countUnread` 方法采用 **Cache-Aside 模式**：优先读 `notify:unread:{userId}` 缓存（TTL 5 分钟）；缓存 miss 时查 DB 并回填；写新通知时 `INCR` 计数；标为已读时删除缓存（evict）。Redis 不可用时自动降级查 DB，接口不报错。

高频的导航栏未读角标接口不再每次触发 `COUNT(*)` 全表扫描。

### 9.3 Feign 移出事务

`saveAndPush` 的 `@Transactional` 范围内只做 DB insert；调用 `user-service` 的 Feign 请求和 WebSocket 推送移至事务提交后执行，避免数据库连接在事务期间因等待远程 HTTP 而被长时间占用。

## 10. 本地接口验证结果

已完成本地联调验证。测试前启动 Docker 中间件，执行 `sql/init-notify-data.ps1`，依次启动 `user-service`、`post-service`、`notify-service`。

测试结果如下：

| 接口 | 测试结果 | 说明 |
|---|---|---|
| GET `/notify/health` | 通过 | 返回 `status=UP` |
| GET `/notify/list?page=1&size=20` | 通过 | `total=9`，返回 9 条通知，含发送者昵称、类型、内容 |
| GET `/notify/unread-count` | 通过 | 返回 `unreadCount=9` |
| POST `/notify/read-all` | 通过 | 全部标为已读，`unreadCount` 归零 |
| POST `/notify/{id}/read` | 通过 | 单条已读，`readStatus` 变为 1 |
| RabbitMQ 消费 | 通过 | `notify.interaction.queue` 消费后 Ready=0、Unacked=0 |
| WebSocket 实时推送 | 通过 | 前端通知中心页「实时已连接」，点赞后实时出现新通知条目 |
| 导航栏未读角标 | 通过 | 登录后铃铛角标显示未读数，路由切换后自动刷新 |

另外，联调过程中发现并修复了以下问题：

1. Post 服务使用 `Map.of()` 发送 JDK 序列化消息，Spring AMQP 3.x 默认白名单拦截 `java.util.CollSer`，导致通知无法写入。通过在 `NotifyApplication.main()` 中设置系统属性解决，`application.yml` 中配置此项无效。
2. 项目未在任何服务中配置 MyBatis-Plus 分页拦截器，导致 `Page.getTotal()` 恒为 0，`total` 字段始终返回 0。在 `notify-service` 中新增 `MybatisPlusConfig` 解决。
3. `sockjs-client` v1.6 在 Vite ESM 环境中引用了 Node.js 的 `global` 变量，浏览器端报 `ReferenceError: global is not defined`。在 `vite.config.js` 中增加 `define: { global: 'globalThis' }` 解决。
