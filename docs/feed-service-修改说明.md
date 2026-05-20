# Feed Service 修改说明

## 1. 服务定位

本次完成的是 Feed Service，服务端口为 `8083`。该服务负责 BiteBlog 平台的关注 Feed 流能力，核心目标是让用户能够看到自己关注的人发布的探店笔记，按时间倒序排列。


## 2. 接口设计

### 2.1 关注 Feed 流

```http
GET /feed/timeline?cursor=1714972800000&size=20
```

请求头：`X-User-Id: <当前用户ID>`

参数说明：

| 参数 | 类型 | 是否必填 | 说明 |
|------|------|----------|------|
| cursor | Long | 否 | 游标（毫秒时间戳），首次不传 |
| size | Integer | 否 | 每页数量，默认 20 |

响应格式（游标分页）：

```json
{
  "code": 200,
  "data": {
    "list": [
      {
        "postId": 52,
        "authorId": 12,
        "authorName": null,
        "title": "武汉户部巷必吃三鲜豆皮",
        "coverUrl": null,
        "shopName": "老通城豆皮",
        "likeCount": 0,
        "collectCount": 0,
        "commentCount": 0,
        "createdAt": "2026-05-12T15:22:53"
      }
    ],
    "cursor": 1714972800000,
    "hasMore": true
  }
}
```

返回当前用户关注的人发布的笔记，按时间倒序。采用推拉结合策略（详见第 4 节）。

## 3. 核心业务逻辑 — 推拉结合 Feed 流

### 3.1 设计思路

Feed 流面临的核心问题是：当用户 A 发布笔记时，如何让 A 的粉丝高效地看到这条笔记？

**纯推（Push）**：发布时把笔记 ID 写入每个粉丝的 inbox。读取快（直接查 inbox），但粉丝多时写入代价高。

**纯拉（Pull）**：粉丝读取时实时查询关注列表中所有人的最新笔记。写入快（只写一次），但读取慢（关注的人越多越慢）。

**推拉结合**：对普通用户（粉丝数 < 50）采用推策略，对大V用户（粉丝数 >= 50）采用拉策略。兼顾读写性能。

### 3.2 写入路径（笔记发布时）

```
用户发布笔记
  → post-service 存入 MySQL
  → 发送 RabbitMQ 事件 {noteId, authorId} 到 biteblog.post 交换机
  → feed-service 消费事件
    → 检查作者粉丝数
      → 粉丝 < 50（普通用户）：fanout 到所有粉丝的 inbox
      → 粉丝 >= 50（大V）：只写入作者自己的 inbox，不 fanout
```

### 3.3 读取路径（用户打开首页时）

```
用户请求 GET /feed/timeline
  → 读取 inbox:{userId} ZSet（已推送的笔记）
  → 查出关注的大V列表（follow:{userId} ∩ feed:bigv）
  → 拉取每个大V的 inbox（实时拉取）
  → 合并去重，过滤已删除笔记
  → 查询 MySQL 获取笔记详情
  → 如果 inbox 为空，降级到数据库直接查询（冷启动兜底）
```

### 3.4 Redis Key 设计

| Key | 类型 | 说明 |
|-----|------|------|
| `feed:inbox:{userId}` | ZSet | 收件箱。member = 笔记 ID，score = 发布时间戳（毫秒） |
| `feed:bigv` | Set | 大V用户 ID 集合（粉丝数 >= 50） |
| `feed:deleted` | Set | 已删除笔记 ID 集合（惰性过滤） |
| `follow:{userId}` | Set | 该用户关注了谁（user-service 维护） |
| `fans:{userId}` | Set | 谁关注了该用户（user-service 维护） |
| `cache:user:{userId}` | Hash | 用户缓存，含 followerCount（user-service 维护） |

### 3.5 大V判定逻辑

在 RabbitMQ 事件消费时，读取 `cache:user:{authorId}` 中的 `followerCount`。如果 >= 50，将作者 ID 加入 `feed:bigv` 集合，且不执行 fanout。

### 3.6 冷启动兜底

当用户 inbox 为空（首次使用或 RabbitMQ 事件未消费到），直接从 MySQL 查询关注用户的最新笔记，同时将结果预热写入 inbox，下次读取走 Redis 缓存。

## 4. RabbitMQ 事件消费

| 事件来源 | 交换机 | 路由键 | 队列 | 处理逻辑 |
|---|---|---|---|---|
| 笔记发布 | `biteblog.post` | `note.published` | `feed.note.published.queue` | 判断作者是否大V，普通用户 fanout 到粉丝 inbox，大V只写自己 inbox |
| 笔记删除 | `biteblog.post` | `note.deleted` | `feed.note.deleted.queue` | 将笔记 ID 加入 `feed:deleted` 集合，timeline 读取时过滤 |

### 4.1 反序列化配置

Spring AMQP 3.x 默认禁止反序列化 `HashMap`，而 post-service 使用 Java 原生序列化发送 `Map<String, Object>` 类型的事件。在 `application.yml` 中配置：

```yaml
spring:
  rabbitmq:
    listener:
      simple:
        deserialization:
          trust:
            all: true
```

允许反序列化所有类，解决 `SecurityException: Attempt to deserialize unauthorized class java.util.HashMap` 错误。


## 5. 非功能需求处理

### 5.1 性能

| 场景与挑战 | 解决办法 | 测试 |
|-----------|----------|------|
| Feed 流读取需在 300ms 内返回 | 优先走 Redis ZSet（O(log N) 范围查询），inbox 已缓存笔记 ID 和发布时间戳，避免直接扫 MySQL；笔记详情查询使用子查询一次性获取封面图，避免 N+1 问题 | F-1 |
| 大V 粉丝量大，实时拉取可能拉过多数据 | 大V 路径仅拉取 inbox 中全部笔记作为候选，在后续合并去重和排序后才分页，实际返回受 size 限制 | — |
| 冷启动时 inbox 为空，需全量扫 MySQL | `getTimelineFromDb` 使用 `LIMIT` 限制结果集，仅查关注用户的最新笔记，并通过子查询一次获取封面图 | F-8 |
| 高并发 800 线程同时请求 | 扩容 Tomcat threads.max→1000、HikariCP maximum-pool-size→200、Lettuce max-active→200，三层连接池匹配高并发量 | F-10 |
| Fanout 推送需秒级完成 | 通过 RabbitMQ 异步事件驱动，发布者无需等待 fanout 完成即可返回；粉丝首次轮询（500ms）即命中 | F-3 |

### 5.2 一致性

| 场景与挑战 | 解决办法 | 测试 |
|-----------|----------|------|
| 普通用户发布后，所有粉丝 inbox 必须包含该笔记 | FeedEventListener 消费 `note.published`，取出 `fans:{authorId}` 集合，逐个 `ZADD` 到粉丝 inbox，score 为发布时间戳。所有粉丝的 ZSCORE 一致 | F-6 (FC-1) |
| 大V 笔记不应通过 fanout 推送到粉丝 inbox | 消费时读取 `cache:user:{authorId}` 的 `followerCount`，≥50 则标记为 bigv，仅写入自身 inbox 供拉取，不执行 fanout | F-7 (FC-2) |
| 删除笔记后需从 feed 中消失 | 消费 `note.deleted`，将 noteId 加入 `feed:deleted` Set，timeline 读取时惰性过滤。无需主动清理所有粉丝 inbox，降低写入代价 | F-9 (FC-4) |
| 游标分页连续翻页不丢不重 | 基于时间戳游标 offset，按 `created_at DESC` 排序，过滤 `cursor` 之后的数据，每页 size 条无重叠 | F-2 |
| inbox 容量需限制，防止无限增长 | `inbox:trim` 保留最新 500 条，ZSet score 为时间戳，自然淘汰老数据 | F-4 |
| 大V 标记需与实际情况一致 | 大V 集合 `feed:bigv` 可被 redis-cli SMEMBERS 验证，每个大V 的 inbox 作为数据源独立维护 | F-5 |

### 5.3 可靠性

| 场景与挑战 | 解决办法 | 测试 |
|-----------|----------|------|
| Redis 宕机或 inbox 被清空 | 自动降级到 `getTimelineFromDb` 直接查 MySQL，并将结果通过 `warmUpInbox` 回填到 inbox，保证下次请求恢复缓存命中 | F-8 |
| RabbitMQ 消息丢失导致 inbox 长期为空 | 冷启动兜底：inbox 为空时自动走 MySQL 降级并回填；且 Queue/Exchange 声明为 durable、消息 PERSISTENT 投递，Broker 重启不丢 | F-8 |
| 消息消费解析失败 | FeedEventListener 抛 RuntimeException，Spring AMQP 自动重试（AUTO ack 模式）；已知差距：未配置独立 DLQ，重试耗尽后消息丢弃 | — |
| Fanout 中途 Redis 断连 | fanout 逐粉丝 ZADD 非事务操作，部分粉丝可能漏收；当前已记录为已知可靠性差距，后续建议加补偿重试 | — |

### 5.4 安全性

| 场景与挑战 | 解决办法 | 测试 |
|-----------|----------|------|
| 未登录用户不能访问 feed | 接口通过 Gateway 统一 JWT 鉴权，`X-User-Id` 由 Gateway 解析 Token 后注入，Feed Service 直接信任该请求头 | — |
| 已删除或禁用笔记不能出现在 feed 中 | SQL 查询只取 `status=1` 的公开笔记；Redis 侧通过 `feed:deleted` Set 在读取路径中过滤 | F-9 |
| SQL 注入 | 所有查询使用 JdbcTemplate 参数绑定，不拼接用户输入 | — |

### 5.5 可维护性

| 场景与挑战 | 解决办法 | 测试 |
|-----------|----------|------|
| 大V 阈值可能需要调整 | 阈值定义在 `FeedEventListener` 常量 `BIG_V_THRESHOLD = 50`，一处修改全局生效 | — |
| Redis Key 管理 | 统一前缀规范：`feed:inbox:` / `feed:bigv` / `feed:deleted` / `follow:` / `fans:`，便于运维定位和清理 | — |
| 代码分层 | Controller → Service（业务逻辑 + 降级）→ EventListener（MQ 消费），职责清晰，无跨层耦合 | — |
| 配置集中 | 所有基础设施连接（MySQL/Redis/RabbitMQ/ES/Nacos）均在 `application.yml` 中统一管理，连接池参数可独立调优 | — |

