![1778640869174](image/rankservice-修改说明/1778640869174.png)
![1778640873106](image/rankservice-修改说明/1778640873106.png)
![1778640877446](image/rankservice-修改说明/1778640877446.png)

# Rank Service 修改说明

## 1. 服务定位

Rank Service 是 BiteBlog 的热度排行榜服务，服务端口为 `8086`，负责把笔记的点赞、收藏、评论、评分质量和发布时间转化为热度分数，并使用 Redis Sorted Set 维护：

- 日榜：`rank:hot:daily`
- 周榜：`rank:hot:weekly`
- 总榜：`rank:hot:all`

该服务不是独立展示模块，而是与 Post Service、Gateway、RabbitMQ、Redis、MySQL 和前端页面共同组成“热门内容发现”链路：

1. Post Service 发布/删除笔记，发送 `note.published`、`note.deleted` 事件。
2. Post Service 点赞/收藏/评论，发送 `interaction.like`、`interaction.collect`、`interaction.comment` 事件。
3. Rank Service 消费事件，实时调整 Redis ZSet 分数。
4. Rank Service 定时或手动从 MySQL 重建榜单，保证最终一致。
5. 前端 `/rank` 页面通过 Gateway 读取榜单并跳转到 Post Detail。

## 2. 新增与调整文件

| 文件 | 说明 |
|---|---|
| `frontend/src/views/RankView.vue` | 新增热榜页面，支持日榜/周榜/总榜、分页、刷新、重建缓存、跳转笔记详情 |
| `frontend/src/router/index.js` | 新增 `/rank` 路由 |
| `frontend/src/components/layout/AppLayout.vue` | 顶部导航新增“热榜”入口 |
| `frontend/src/api/rank.js` | 新增 `rebuildRank(type)` 前端 API |
| `biteblog-backend/biteblog-rank/src/main/resources/application.yml` | 开启 Nacos 服务注册与 RabbitMQ listener，支持 Gateway 路由和跨服务事件消费 |
| `sql/init-data.ps1` | 统一创建/复用 `13800000001` ~ `13800000060` 用户，并建立大 V 粉丝关系 |
| `sql/init-rank-data.ps1` | 改为只登录既有用户，发布热榜样例笔记并注入互动，不再创建额外账号 |
| `sql/数据说明.md` | 新增测试用户、关注关系、Rank 样例笔记和互动数据说明 |
| `jmeter/rank-service-test.jmx` | 改为直连 `8086/rank`，避免未登录 Gateway 鉴权影响 Rank 性能测试 |
| `docs/rankservice-测试说明.md` | 重写测试范围、非功能需求、测试步骤、实际结果 |
| `docs/rankservice-修改说明.md` | 重写服务定位、接口说明、业务设计、非功能处理 |

## 3. 新增接口说明

### 3.1 `GET /rank/health`

健康检查接口。

响应示例：

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "service": "rank-service",
    "status": "UP"
  }
}
```

### 3.2 `GET /rank/top10?type=daily`

获取当前榜单 Top10。`type` 支持：

| type | 含义 | 数据范围 |
|---|---|---|
| `daily` | 日榜 | 最近 1 天发布的正常笔记 |
| `weekly` | 周榜 | 最近 7 天发布的正常笔记 |
| `all` | 总榜 | 所有正常笔记 |

`type` 非法或为空时默认使用 `daily`。

### 3.3 `GET /rank/list?type=daily&page=1&size=20`

分页获取榜单。参数处理：

- `page` 最小为 1。
- `size` 最小为 1，最大为 50。
- Redis 缓存为空时自动触发重建。

返回字段：

| 字段 | 说明 |
|---|---|
| `type` | 榜单类型 |
| `page` | 当前页 |
| `size` | 每页数量 |
| `total` | 当前 Redis 榜单总数 |
| `list` | 榜单条目 |

榜单条目字段：

| 字段 | 说明 |
|---|---|
| `rankNo` | 排名 |
| `postId` | 笔记 ID |
| `authorId` | 作者 ID |
| `title` | 笔记标题 |
| `shopName` | 店铺名 |
| `likeCount` | 点赞数 |
| `collectCount` | 收藏数 |
| `commentCount` | 评论数 |
| `hotScore` | 热度分数 |
| `createdAt` | 发布时间 |

### 3.4 `POST /rank/rebuild?type=daily`

手动重建指定榜单缓存，主要用于初始化数据、测试和运维恢复。

响应示例：

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "rebuilt": true,
    "type": "daily"
  }
}
```

## 4. 前端业务功能设计

新增 `/rank` 页面后，用户登录进入主布局即可看到顶部“热榜”入口。

页面能力：

- 使用分段按钮切换 `daily/weekly/all`。
- 使用表格展示排名、笔记标题、店铺、点赞/收藏/评论数、热度分数和发布时间。
- 点击表格行或“查看”跳转 `/post/{postId}`，与 Post Detail 页面联动。
- 点击刷新按钮重新读取当前榜单。
- 点击“重建榜单”调用 `/api/rank/rebuild`，用于测试数据导入后的前端验证。
- 支持分页和 page size 切换。

前端通过 `frontend/src/api/request.js` 统一走 `/api` 前缀，因此生产链路为：

```text
RankView.vue -> /api/rank/list 或 /api/rank/rebuild -> Gateway -> rank-service
```

## 5. 后端业务功能设计

### 5.1 热度公式

当前热度计算公式：

```text
hotScore = likeCount * 3
         + collectCount * 5
         + commentCount * 4
         + qualityScore
         + timeBoost

qualityScore = (scoreTaste + scoreSmell + scoreColor) / 3
timeBoost = 24 / sqrt(hoursSinceCreated)
```

设计意图：

- 收藏权重最高，表示用户更强的长期兴趣。
- 评论权重高于点赞，体现讨论价值。
- 评分质量补充内容本身质量。
- 新鲜度加成避免老内容长期霸榜。

### 5.2 Redis 榜单结构

使用 Redis Sorted Set：

```text
key    = rank:hot:{type}
member = noteId
score  = hotScore
```

查询时使用 `reverseRangeWithScores` 按分数从高到低读取，适合 TopN 和分页场景。

### 5.3 事件驱动更新

Rank Service 消费以下事件：

| 来源 | Exchange | Routing Key | 处理 |
|---|---|---|---|
| Post Service | `biteblog.post` | `note.published` | 新笔记按初始热度加入榜单 |
| Post Service | `biteblog.post` | `note.deleted` | 从所有榜单移除笔记 |
| Post Service | `biteblog.interaction` | `interaction.like` | 热度 `+3` |
| Post Service | `biteblog.interaction` | `interaction.collect` | 热度 `+5` |
| Post Service | `biteblog.interaction` | `interaction.comment` | 热度 `+4` |

本次配置调整已开启 RabbitMQ listener，保证互动事件能被 Rank Service 消费；同时开启 Nacos 注册，保证 Gateway 能通过 `lb://rank-service` 路由到 Rank Service。

### 5.4 重建与定时刷新

`POST /rank/rebuild` 会从 MySQL 查询正常状态笔记，重新计算热度并写入 Redis。服务还配置了定时任务：

```java
@Scheduled(cron = "0 0/10 * * * ?")
```

每 10 分钟重建一次 `daily/weekly/all`，用于修正事件丢失或临时不一致。

## 6. 测试数据设计

本次测试数据统一由 `init-data.ps1` 管理基础用户：

- `13800000001` ~ `13800000003`：大 V，粉丝数超过 50。
- `13800000004` ~ `13800000060`：普通用户。

Rank 数据脚本 `init-rank-data.ps1` 不再注册 `139...` 测试用户，而是：

1. 登录 `13800000001` ~ `13800000060`。
2. 使用 `13800000001` 发布大 V 热榜样例。
3. 使用 `13800000004` 发布普通用户热榜样例。
4. 使用其余既有用户完成点赞、收藏、评论。
5. 调用 Rank rebuild 并输出 Top10。

这样可以同时验证：

- User Service 统一测试账号口径。
- Post Service 发布与互动能力。
- RabbitMQ 事件链路。
- Rank Service 热度计算和排序。
- 前端热榜到笔记详情的跳转。

## 7. 非功能需求处理

### 7.1 性能

榜单查询不直接做复杂 SQL 排序，而是读取 Redis ZSet。Top10 和分页查询的主要复杂度由 Redis 控制，适合高频访问。

### 7.2 容量控制

- 单次分页 `size` 最大 50。
- 每类榜单最多保留 200 条缓存。
- Redis 超出容量时裁剪低分段，避免无限增长。

### 7.3 可用性

当 Redis 缓存为空时，查询接口会自动触发 `rebuild(type)`，避免首次访问直接返回异常。

### 7.4 一致性

采用“事件实时更新 + 定时全量重建 + 手动重建”的组合：

- 实时事件保证用户互动后榜单尽快变化。
- 定时任务保证最终一致。
- 手动重建支持初始化、测试和运维恢复。

### 7.5 安全性

通过 Gateway 访问时，查询类 Top10 可公开访问；分页榜单和重建操作受 JWT 登录态保护。Rank 返回数据只包含公开笔记摘要和互动计数，不返回手机号、密码等敏感信息。

### 7.6 可维护性

榜单类型、缓存 key、最大缓存数量、热度公式和互动权重集中在 `RankService`，后续调整权重或新增榜单类型时影响范围较小。
