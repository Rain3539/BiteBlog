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

- Feed 流读取优先走 Redis ZSet（O(log N) 范围查询），不直接查 MySQL
- 大V笔记实时拉取时限制每个大V最多拉 10 条，避免单次请求过大
- 冷启动降级查询使用 `LIMIT` 限制结果集大小
- 笔记详情查询使用子查询获取封面图，避免 N+1 问题

### 5.2 可用性

- RabbitMQ 不可用时，inbox 为空会自动降级到数据库查询，不阻塞用户使用
- Redis 不可用时，降级查询直接走 MySQL
- 冷启动查询结果会预热写入 inbox，下次读取走缓存

### 5.3 一致性

- 推拉结合策略保证：普通用户的粉丝一定能通过 inbox 看到笔记（推送完成）
- 大V的粉丝通过实时拉取保证数据一致性
- 已删除笔记通过 `feed:deleted` 集合做惰性过滤，避免主动清理所有 inbox 的代价
- 如果 RabbitMQ 事件丢失，冷启动兜底机制保证用户仍能看到内容

### 5.4 可扩展性

- 大V阈值（50）可在 `FeedEventListener` 中通过常量调整
- inbox 容量和 TTL 可按需配置

### 5.5 安全性

- 所有接口通过 Gateway 统一鉴权，`X-User-Id` 由 Gateway 注入
- Feed 流只返回公开笔记（status=1），已删除笔记不可见
- SQL 查询使用参数化，避免注入风险

