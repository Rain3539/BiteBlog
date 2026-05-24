# Notify Service 修改说明

## 1. 服务定位

本次完成的是组员 6 负责的 `Notify Service`，服务端口为 **8087**。该服务在 BiteBlog 微服务体系中承担**消息通知**职责：

- 订阅 RabbitMQ `biteblog.interaction` 交换机，消费 Post 服务产生的点赞/收藏/评论互动事件；
- 订阅 RabbitMQ `biteblog.post` 交换机，消费 `note.published` 事件，向粉丝推送关注者发帖通知（小 V 限定）；
- 将通知持久化到 MySQL `notification` 表，并维护未读计数；
- 通过 **STOMP over WebSocket（SockJS）** 向在线用户实时推送通知；
- 提供 HTTP 查询、已读、过滤、偏好设置等接口，供前端通知中心页面和导航栏未读角标使用。

与其他服务的协作关系：

| 依赖方向 | 服务 | 协作内容 |
|----------|------|---------|
| 消费方 | Post Service | 订阅 `biteblog.interaction`（`interaction.like/collect/comment`）；订阅 `biteblog.post`（`note.published`） |
| 调用方 | User Service | OpenFeign `GET /user/{id}` 查询发送者昵称，在列表中补全展示名 |
| 读取方 | User Service（Redis） | 读取 `fans:{authorId}` 获取粉丝列表；读取 `feed:bigv`、`cache:user:{id}` 判定大 V |
| 路由方 | Gateway | 所有 HTTP 接口经 `/api/notify/**` 网关路由，JWT 由网关校验并以 `X-User-Id` 透传 |
| 数据库 | MySQL `biteblog` | `notification`（热表）、`notification_archive`（冷归档表）、`notification_preference`（偏好表） |
| 缓存 | Redis | `notify:unread:{userId}` 未读计数；`notify:sender:{userId}` 昵称缓存；`notify:pref:{userId}` 偏好缓存 |

支持的通知类型：

| type | 触发来源 | 说明 |
|------|----------|------|
| `like` | Post 点赞 | 赞了你的笔记 |
| `collect` | Post 收藏 | 收藏了你的笔记 |
| `comment` | Post 顶级评论 | 评论了你的笔记 |
| `comment_reply` | Post 回复评论 | 回复了你的评论（含内容摘要） |
| `follow_post` | Post 发布笔记 | 你关注的人发布了新笔记（小 V fanout） |

---

## 2. 新增/修改文件列表

### 2.1 后端（biteblog-backend/biteblog-notify）

| 文件 | 说明 |
|------|------|
| `pom.xml` | 引入 Web、Validation、MyBatis-Plus、MySQL、Nacos、AMQP、OpenFeign、LoadBalancer、WebSocket、Redis（通过 biteblog-common 传递）依赖 |
| `NotifyApplication.java` | 启动类；`main()` 中 `System.setProperty("spring.amqp.deserialization.trust.all","true")` 解决 Post 侧 `Map.of()` JDK 序列化产生 `CollSer` 类被 Spring AMQP 3.x 白名单拦截的问题 |
| `config/NotifyRabbitConfig.java` | 声明 `biteblog.interaction` 主队列 `notify.interaction.queue`（绑定 `interaction.*`）；`biteblog.post` 队列 `notify.note.published.queue`（绑定 `note.published`）；死信交换机 `notify.dlx`、死信队列 `notify.dead.queue` |
| `config/MybatisPlusConfig.java` | 注册 `PaginationInnerInterceptor`（MySQL 方言），使 `Page.getTotal()` 能正确执行 `COUNT(*)` 并返回分页总数 |
| `config/WebSocketConfig.java` | 注册 SockJS 端点 `/ws-notify`，启用 STOMP 简单消息代理，设置应用前缀 `/app`、用户前缀 `/user` |
| `config/NotifyJwtHandshakeInterceptor.java` | WebSocket 握手拦截器：从 Query 参数 `token` 解析 JWT，鉴权失败直接拒绝握手 |
| `config/NotifyStompHandshakeHandler.java` | 握手完成后将 `Principal.getName()` 设为 `userId` 字符串，供 `convertAndSendToUser` 精准路由 |
| `entity/Notification.java` | 映射 `notification` 表，含 `isRetracted`（撤回软删除） |
| `entity/NotificationArchive.java` | 映射 `notification_archive` 冷归档表，额外包含 `archivedAt` 字段 |
| `entity/NotificationPreference.java` | 映射 `notification_preference` 偏好表 |
| `mapper/NotificationMapper.java` | 继承 `BaseMapper<Notification>`，无额外自定义方法 |
| `mapper/NotificationArchiveMapper.java` | 继承 `BaseMapper`，新增 `insertIgnore()` 方法（`INSERT IGNORE`，保证归档任务幂等） |
| `mapper/NotificationPreferenceMapper.java` | 继承 `BaseMapper<NotificationPreference>` |
| `dto/NotificationVO.java` | 列表返回 VO，含 `notificationId`、`senderUsername` 等展示字段 |
| `dto/NotifyPushPayload.java` | WebSocket 推送载荷，`createdAt` 用 ISO-8601 字符串而非 `LocalDateTime`，避免 STOMP Jackson 序列化异常 |
| `dto/NotificationPreferenceVO.java` | 偏好列表 VO |
| `dto/MuteTypeRequest.java` / `MuteSenderRequest.java` / `DndTimeRequest.java` | 偏好设置请求体 |
| `service/NotifyService.java` | 核心业务逻辑：MQ 消费、撤回、评论回复、follow_post fanout、未读 Redis 精准维护、昵称缓存、归档与对账（详见第 4 节） |
| `service/NotifyPreferenceService.java` | 偏好 CRUD 与消费链路 `check()`（ALLOW / MUTED / DND） |
| `service/NotifyEventListener.java` | `@RabbitListener(ackMode="MANUAL")`：`onInteraction` + `onNotePublished`；业务成功 `basicAck`，异常 `basicNack(requeue=false)` 转死信 |
| `controller/NotifyController.java` | 列表、未读数、单条/全部已读、健康检查 |
| `controller/NotifyPreferenceController.java` | 偏好查询与 mute/dnd 设置 |
| `client/UserFeignClient.java` | `@FeignClient(name="user-service")` 声明，调用 `GET /user/{id}` |

### 2.2 Post Service 极小改动（OPT-5，仅扩展 MQ 事件体）

| 文件 | 说明 |
|------|------|
| `PostEventPublisher.java` | `publishInteraction` 增加 `extras` 参数重载，将 `commentId`、`parentId`、`parentCommentUserId`、`commentContent` 等附加字段写入 MQ 消息 |
| `CommentService.java` | 发表评论时构造 `extras` Map 并调用带 extras 的 `publishInteraction` |

### 2.3 前端（frontend/src）

| 文件 | 说明 |
|------|------|
| `api/notify.js` | 封装列表/未读/已读 HTTP 方法，以及偏好 API（`getNotifyPreferences`、`muteNotifyType`、`muteNotifySender`、`setNotifyDnd`、`clearNotifyDnd`、`deleteNotifyPreference`） |
| `stores/notify.js` | Pinia 全局未读数 store（`refreshUnread` / `incrementUnread` / `decrementUnread` / `clearUnread`），解决通知页全部已读后顶栏角标不同步 |
| `views/NotifyView.vue` | 通知中心页：分页列表、类型 Tab 筛选（含 `comment_reply` / `follow_post`）、设置按钮打开偏好抽屉、Refresh 圆形图标、整行卡片点击跳帖、标为已读、全部已读、STOMP WebSocket 实时订阅 |
| `components/NotifyPreferenceDrawer.vue` | 通知偏好设置抽屉：按类型屏蔽、按发送者屏蔽、勿扰时段 |
| `components/layout/AppLayout.vue` | 导航栏铃铛角标，通过 `useNotifyStore` 与通知页共享未读数，路由切换时自动刷新 |
| `vite.config.js` | 增加 `define: { global: 'globalThis' }`，解决 `sockjs-client` 在 Vite ESM 环境下 `global is not defined` 错误 |

### 2.4 数据库与脚本

| 文件 | 说明 |
|------|------|
| `sql/init.sql` | `notification` 表含 `is_retracted` 字段与复合索引；新增 `notification_preference` 表；`notification_archive` 冷归档表 |
| `sql/init-notify-data.ps1` | 复用 init-data.ps1 的 `13800000001`/`13800000004`，发布笔记、执行互动、验证通知条数与未读数；结果保存至 `notify-init-result.txt` |
| `jmeter/notify-service-test.jmx` | 压测脚本：setUp 登录共享 token；主 Thread Group **200 线程 × 25 循环**压测 list/unread-count；业务码断言防假通过 |
| `测试脚本/notify-test-verify.ps1` | PowerShell 全量验证脚本（F-1～F-23），结果保存至 `notify-test-result.txt` |

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
| `type` | string | 不过滤 | `like` / `collect` / `comment` / `follow_post`；`comment` 会同时包含 `comment_reply`；为空则返回全部类型 |
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

排序规则：`created_at DESC, id DESC`（双列排序，防止批量写入时时间戳相同导致分页结果不稳定）。列表仅返回 `is_retracted=0` 的记录。

### 3.3 未读数

```http
GET /notify/unread-count
Authorization: Bearer <JWT>
```

返回 `{"unreadCount": 3}`，导航栏角标轮询此接口。底层优先读 Redis `notify:unread:{userId}`（`StringRedisTemplate` 纯整数字符串），miss 时查 DB 并回填（Cache-Aside，TTL 5 分钟）。

### 3.4 全部标为已读

```http
POST /notify/read-all
Authorization: Bearer <JWT>
```

将当前用户所有 `read_status=0 AND is_retracted=0` 批量更新为 `1`，同时将 Redis 未读数 **SET 为 "0"**（不删除 key，避免后续 DB COUNT 回扫）。

### 3.5 单条标为已读

```http
POST /notify/{id}/read
Authorization: Bearer <JWT>
```

校验 `notification.receiver_id == 当前用户`，防止越权操作（返回业务码 403）。DB 更新后对 Redis 执行 **DECR**（而非 DEL key）。

### 3.6 通知偏好

```http
GET /notify/preference
POST /notify/preference/mute/type      Body: { "type": "like" }
POST /notify/preference/mute/sender    Body: { "senderId": 4 }
POST /notify/preference/dnd            Body: { "timeRange": "22:00-08:00" }
DELETE /notify/preference/dnd
DELETE /notify/preference/{id}
```

| pref_type | pref_value 示例 | 效果 |
|-----------|-----------------|------|
| `mute_type` | `like` / `collect` / `comment` / `follow_post` | 消费端丢弃该类型；屏蔽 `comment` 时同时屏蔽 `comment_reply` |
| `mute_sender` | 发送者 userId | 丢弃来自该用户的所有通知 |
| `dnd_time` | `22:00-08:00` | 仍写库，但不 INCR Redis 未读、不推 WebSocket |

---

## 4. 业务功能设计

### 4.1 通知写入流程（互动事件）

```
Post 服务互动成功（like/collect/comment）
  → convertAndSend("biteblog.interaction", "interaction.like", Map.of(...))
  → RabbitMQ notify.interaction.queue
  → NotifyEventListener.onInteraction()（手动 ack）
      ├── 解析 noteId / userId / authorId / action
      ├── action=remove → handleRetract（仅 like/collect，见 4.6）
      ├── action=add + type=comment → handleCommentAdd（见 4.7）
      ├── 过滤：authorId == userId → 自操作，直接 ack 跳过
      ├── 偏好检查：MUTED → 跳过；DND → 写库但不增未读/不推 WS（见 4.9）
      ├── 去重：5 分钟窗口内相同 receiver+sender+type+bizId → 已存在，ack 跳过
      └── NotifyService.saveAndPush()（@Transactional）
              ├── DB insert（notification 表，is_retracted=0）
              ├── Redis INCR notify:unread:{receiverId}（非 DND）
              └── afterCommit 回调：Feign/缓存查昵称 → WS 推送
                      └── messagingTemplate.convertAndSendToUser(userId, "/queue/notify", payload)
```

### 4.2 自操作过滤

互动事件载荷包含 `userId`（操作者）和 `authorId`（笔记作者），`authorId == userId` 时跳过写入，不产生通知。

### 4.3 幂等去重（5 分钟窗口）

消费前查询 `notification` 表：相同 `receiver_id + sender_id + type + biz_id` 且 `is_retracted=0` 且 `created_at > NOW()-5分钟` 的记录若已存在，则视为 MQ 重投导致的重复投递，跳过写入。撤回后重新点赞可正常产生新通知（去重只查 `is_retracted=0`）。

### 4.4 事务边界设计

`handleInteractionEvent` 标注 `@Transactional`，通过 Spring AOP 代理进入时事务生效。内部 `saveAndPush` 同 Bean 调用不过代理：DB insert 在事务内，Feign HTTP（查用户名）和 WebSocket 推送通过 `TransactionSynchronizationManager.registerSynchronization(afterCommit→...)` 延迟到事务提交后执行，避免数据库连接持有期间被远程 HTTP 阻塞。

### 4.5 冷热数据分离

`notification` 表（热表）只保留近 30 天数据；每天凌晨 3 点 `@Scheduled` 任务将 **已读** 的过期记录迁移到 `notification_archive`（冷表）：

1. 每批最多 500 条，分批处理，防止大事务锁表；
2. 归档写入用 `INSERT IGNORE`，任务重启重跑保证幂等；
3. 先写冷表再删热表，失败可重试；
4. **未读通知不归档**，保证用户历史未读不丢失。

### 4.6 取消互动撤回

用户取消点赞/收藏时，Post 发送 `action=remove`：

```
LikeService/FavoriteService → interaction.like/collect + action=remove
  → NotifyService.handleRetract(receiverId=authorId, senderId=userId, type, bizId=noteId)
      ├── 查最近一条 is_retracted=0 的匹配通知
      ├── UPDATE is_retracted=1（软删除，列表不可见，DB 保留审计）
      └── 若原 read_status=0：Redis DECR notify:unread:{receiverId}
```

评论删除目前 Post 侧无 `action=remove` 事件，不在撤回范围内。用户再次点赞可产生新通知。

### 4.7 评论回复专项通知

Post `CommentService` 在 MQ 事件中附加 `commentId`、`parentId`、`parentCommentUserId`、`commentContent`（截断 20 字）：

```
顶级评论 → 通知笔记作者（type=comment）
回复评论 → 额外通知父评论作者（type=comment_reply，content 含摘要）
         → 跳过：回复者即父评论作者；父评论作者即笔记作者（避免与 comment 重复）
```

前端 Tab `type=comment` 会同时展示 `comment` 与 `comment_reply`。

### 4.8 关注者发帖通知

消费 Post `note.published` 事件（队列 `notify.note.published.queue`）：

```
作者发布笔记
  → NotifyService.handleNotePublished(noteId, authorId)
      ├── 大 V 判定（与 Feed 对齐）：feed:bigv / followerCount≥50 / fans≥500 → 跳过 fanout
      ├── 读取 Redis fans:{authorId}，粉丝数 ≥500 也跳过（防写入风暴）
      └── 对每个粉丝（排除作者本人、被 mute 的用户）
              → saveAndPush(fanId, authorId, "follow_post", noteId, "发布了新笔记")
```

### 4.9 通知偏好

`NotifyPreferenceService.check()` 在 `saveAndPush` 写库前执行：

| 结果 | 行为 |
|------|------|
| `MUTED` | 不写库、不增未读、不推 WS |
| `DND` | 写库（`read_status=0`），不 INCR Redis、不推 WS；用户次日打开列表仍可见 |
| `ALLOW` | 正常写库 + INCR + WS |

偏好数据缓存于 Redis `notify:pref:{userId}`（JSON，TTL 10 分钟），变更时 evict。

---

## 5. 非功能需求处理

### 5.1 并发

| 问题 | 解决方案 | 测试 |
|------|----------|------|
| 导航栏未读数、通知中心列表为高频读接口，百级并发下若每次走 MySQL 全表扫描或串行 Feign 会拖慢 Gateway | 未读数走 Redis Cache-Aside（`notify:unread:{userId}`，TTL 5 分钟）；列表走 MyBatis-Plus 分页 + 复合索引 `idx_receiver` | F-1、F-2 |
| 列表补全发送者昵称，N 条通知串行 Feign 导致 list P95 偏高 | `CompletableFuture` 并发 Feign（线程池核心 5、最大 10，整批 3s 超时）；**OPT-3** 增加 Redis 昵称二级缓存 `notify:sender:{userId}`（TTL 30 分钟），miss 才调 User Service | F-1、F-11 |
| 单条已读若 DEL Redis key，高频逐条阅读会反复触发 DB COUNT | **OPT-2** 单条已读对 Redis 执行 **DECR**；全部已读 **SET "0"** 而非 DEL，角标立即归零且避免 DB 回扫 | F-9（NC-3/NC-4） |
| 需验证百线程以上并发零错误 | JMeter `notify-service-test.jmx`：**200 线程** × 25 循环，压测 list/unread-count；并发 P95 以 JTL 为准 | F-11 |

### 5.2 一致性

| 问题 | 解决方案 | 测试 |
|------|----------|------|
| MQ 重投导致重复通知 | 消费前 5 分钟窗口幂等：相同 `receiver+sender+type+bizId` 且 `is_retracted=0` 已存在则跳过 | F-6（NC-5） |
| 自操作产生无效通知 | `authorId == userId` 直接 ack，不写库 | F-7（NC-6） |
| 取消点赞/收藏后通知仍显示，未读数虚高 | **OPT-1** `is_retracted=1` 软撤回；列表过滤；未读通知撤回时 Redis DECR | F-12、F-13 |
| Redis 未读数与 DB 漂移（INCR/DECR 并发、DND 写库不增 Redis） | **OPT-2** `StringRedisTemplate` 纯整数字符串 INCR/DECR/SET0，避免 Jackson 序列化与 INCR 冲突；每 10 分钟 `reconcileUnreadCache` 对账（DB>Redis 时覆盖） | F-9（NC-2/NC-3/NC-4）、F-20 |
| 分页跨页重复 | `orderByDesc(created_at).orderByDesc(id)` 双列排序 | F-8（NC-7） |
| 勿扰时段 DB 未读与 Redis 角标短期不一致 | DND 设计为写库不增 Redis；依赖用户已读、TTL miss 回填或 10min 对账修正；测试脚本登录后 `read-all` 重置基线 | F-20、NC-2 |
| 30 天前已读通知堆积热表 | 定时归档至 `notification_archive`，未读不归档 | NC-9 |

### 5.3 可靠性

| 问题 | 解决方案 | 测试 |
|------|----------|------|
| MQ 消费失败静默丢弃 | `NotifyEventListener`：`ackMode=MANUAL`，成功 `basicAck`，失败 `basicNack(requeue=false)` → `notify.dlx` / `notify.dead.queue`；`note.published` 队列同样绑定 DLX | F-5（NC-8） |
| Broker 重投导致重复通知 | 5 分钟幂等窗口覆盖秒级~分钟级重投 | F-6 |
| Redis 宕机影响未读接口 | `countUnread` catch 后降级 `SELECT COUNT(*)`，接口仍 200 | F-9（NC-10） |
| WebSocket / Feign 失败拖垮 DB 事务 | 事务提交后 `afterCommit` 再调远程；失败仅 warn | F-4 |
| Post 侧 JDK 序列化消息无法反序列化 | `NotifyApplication` 设置 `spring.amqp.deserialization.trust.all=true`；Listener 兼容 JSON 与 Java 序列化双格式 | — |
| follow_post 大 V fanout 写入风暴 | 与 Feed 对齐大 V 判定（≥50 粉丝 / `feed:bigv`），额外 fans≥500 硬阈值跳过 | F-22 |

### 5.4 安全性

| 问题 | 解决方案 | 测试 |
|------|----------|------|
| 未登录访问通知接口 | HTTP 经 Gateway JWT，`/api/notify/**` 非白名单，必须 `Authorization: Bearer`；下游使用 `X-User-Id` | F-10 |
| 跨用户标已读 | `markRead` 校验 `notification.receiver_id == 当前用户`，否则业务 403 | F-10 |
| WebSocket 未鉴权连接 | `NotifyJwtHandshakeInterceptor` 校验 Query `token`，失败拒绝握手 | F-10 |
| 偏好接口越权 | 所有偏好 CRUD 均绑定 `X-User-Id`，只能操作自己的偏好记录 | F-18～F-20 |

### 5.5 可维护性

| 问题 | 解决方案 | 测试 |
|------|----------|------|
| 幂等窗口、DLQ、归档 cron、Redis key 分散难以维护 | 常量集中：`UNREAD_KEY_PREFIX`、`DEDUP_WINDOW_MINUTES`、`UNREAD_TTL_MINUTES`、归档批大小 500、大 V 阈值 50/500 | F-5、F-9 |
| RabbitMQ 拓扑不清晰 | `NotifyRabbitConfig` 集中声明 interaction 队列、note.published 队列、DLX、死信队列 | F-5 |
| 偏好逻辑与业务写入耦合 | `NotifyPreferenceService` 独立封装 `check()` / CRUD，消费端一行调用 | F-18～F-20 |
| 前端未读数多处维护不一致 | Pinia `useNotifyStore` 统一顶栏与通知页角标 | — |
| 测试编号与 NC 编号难以回溯 | `notify-测试说明.md` 中 F-* 与 NC-* 一一对应 | F-1～F-23 |

---

## 6. 联调中发现并修复的问题

| 问题 | 根因 | 修复方式 |
|------|------|---------|
| MQ 消息一直 Unacked，通知写不进 DB | Post 用 `Map.of()` 发送 JDK 序列化消息，产生 `java.util.CollSer`；Spring AMQP 3.x 白名单拒绝反序列化 | `NotifyApplication.main()` 中 `System.setProperty("spring.amqp.deserialization.trust.all","true")` |
| `/notify/list` 的 `total` 字段始终为 0 | 项目未注册 MyBatis-Plus 分页拦截器，`Page.getTotal()` 不执行 COUNT 查询 | 新增 `MybatisPlusConfig`，注册 `PaginationInnerInterceptor` |
| 分页跨页出现重复记录 | 仅按 `created_at DESC` 排序，批量插入时时间戳相同，MySQL 排序不稳定 | 加 `.orderByDesc(Notification::getId)` 次级排序 |
| 前端点击铃铛报 `global is not defined` | `sockjs-client` v1.6 内部使用 Node.js `global`，Vite ESM 环境不存在此变量 | `vite.config.js` 加 `define: { global: 'globalThis' }` |
| `@Transactional` 失效，Feign 在事务内阻塞连接池 | `handleInteractionEvent` 无事务，内部同 Bean 调用 `saveAndPush` 不过代理，事务注解无效；且 Feign/WS 在同一调用栈内占用 DB 连接 | 事务标注移至外部入口；`TransactionSynchronizationManager.afterCommit` 延迟执行 Feign/WS |
| Redis 未读数 INCR 后无法 DECR | Cache-Aside 回填用 Jackson RedisTemplate 写入 `["java.lang.Long",5]`，与 INCR 期望的纯数字冲突 | **OPT-2** 改用 `StringRedisTemplate` 专管 `notify:unread:*` 与 `notify:sender:*` |
| RabbitMQ `PRECONDITION_FAILED` 启动失败 | 旧队列无 DLX 参数，与新版声明不一致 | 删除旧 `notify.interaction.queue` 后重启 notify-service |
| 全部已读后顶栏角标未清零 | AppLayout 与 NotifyView 各自维护未读 state | Pinia `useNotifyStore` 全局共享，`read-all` 后 `clearUnread()` |
| F-22 大 V 仍收到 follow_post | 大 V 判定未与 Feed 对齐 | `isFollowPostBigV` 检查 `feed:bigv`、缓存 followerCount≥50、`fans:{id}` SCARD≥50/500 |
| NC-2 测试 WARN（DND 历史数据漂移） | 历史 DND 通知写库不增 Redis，导致 DB 未读 > Redis | 测试脚本登录后先 `read-all` 重置基线；对账任务 10min 修正长期漂移 |
