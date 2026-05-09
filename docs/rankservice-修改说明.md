# Rank Service 修改说明

## 1. 服务定位

本次完成的是组员 5 负责的 `Rank Service`，服务端口为 `8086`。该服务负责 BiteBlog 平台的热度排行能力，核心目标是把笔记的点赞、收藏、评论、发布时间等因素转化为热度分数，并使用 Redis Sorted Set 维护日榜、周榜和总榜。

## 2. 本次新增/修改文件

| 文件 | 说明 |
|---|---|
| `biteblog-backend/biteblog-rank/pom.xml` | 补充 Web 与 MyBatis-Plus 依赖，使服务可以提供 HTTP 接口并访问 MySQL。 |
| `RankApplication.java` | Rank 服务启动类，启用 Nacos 服务发现、定时任务和 Mapper 扫描。 |
| `config/RankRabbitConfig.java` | 声明 RabbitMQ 交换机、队列和绑定关系，消费笔记发布、删除和互动事件。 |
| `entity/Note.java` | 映射数据库 `note` 表，供 Rank 服务读取笔记基础信息与互动计数。 |
| `mapper/NoteMapper.java` | MyBatis-Plus Mapper，用于查询笔记数据。 |
| `dto/RankItemVO.java` | 排行榜列表返回对象。 |
| `service/RankService.java` | 排行榜核心业务逻辑，包括热度计算、Redis ZSet 缓存、事件加分、定时刷新。 |
| `service/RankEventListener.java` | 监听 RabbitMQ 事件，完成发布入榜、删除移除、点赞/收藏/评论加分。 |
| `controller/RankController.java` | 暴露 `/rank/top10`、`/rank/list`、`/rank/rebuild`、`/rank/health` 接口。 |
| `sql/init-rank-data.ps1` | Rank 服务测试数据初始化脚本。 |
| `jmeter/rank-service-test.jmx` | Rank 服务 JMeter 压测脚本。 |

## 3. 接口设计

### 3.1 健康检查

```http
GET /rank/health
```

用于确认 Rank 服务是否已经启动成功。

### 3.2 获取 Top10 热榜

```http
GET /rank/top10?type=daily
```

参数 `type` 支持：

- `daily`：日榜，统计最近 1 天内发布的正常笔记；
- `weekly`：周榜，统计最近 7 天内发布的正常笔记；
- `all`：总榜，统计所有正常笔记。

返回结果包含 `type`、`page`、`size`、`total` 和 `list`。其中 `list` 中每一项包含排名、笔记 ID、作者 ID、标题、店铺名、点赞数、收藏数、评论数、热度分数和创建时间。

### 3.3 分页查询热榜

```http
GET /rank/list?type=daily&page=1&size=20
```

用于排行榜分页展示。`size` 最大限制为 50，避免单次请求过大影响服务稳定性。

### 3.4 手动重建排行榜缓存

```http
POST /rank/rebuild?type=daily
```

用于测试或运维场景。当 Redis 中排行榜为空，或者测试数据刚导入后，可以调用该接口从 MySQL 重新计算热度并写入 Redis。

## 4. 核心业务逻辑

Rank 服务使用 Redis Sorted Set 保存排行榜。key 设计如下：

```text
rank:hot:daily
rank:hot:weekly
rank:hot:all
```

ZSet 的 member 为 `noteId`，score 为热度分数。查询排行榜时使用 `reverseRangeWithScores` 按分数从高到低读取。

热度分数公式如下：

```text
hotScore = likeCount * 3 + collectCount * 5 + commentCount * 4 + qualityScore + timeBoost
qualityScore = (scoreTaste + scoreSmell + scoreColor) / 3
timeBoost = 24 / sqrt(hoursSinceCreated)
```

该公式体现了三个考虑：第一，收藏通常比点赞代表更强的兴趣，所以权重更高；第二，评论体现互动讨论价值，权重大于点赞；第三，发布时间越近的内容获得一定的新鲜度加成，避免老内容长期垄断榜单。

## 5. RabbitMQ 事件加分

Rank 服务监听以下事件：

| 事件来源 | 交换机 | 路由键 | 处理逻辑 |
|---|---|---|---|
| 笔记发布 | `biteblog.post` | `note.published` | 将新笔记按初始热度加入对应榜单。 |
| 笔记删除 | `biteblog.post` | `note.deleted` | 从所有榜单中移除该笔记。 |
| 点赞 | `biteblog.interaction` | `interaction.like` | 对该笔记热度 `+3`。 |
| 收藏 | `biteblog.interaction` | `interaction.collect` | 对该笔记热度 `+5`。 |
| 评论 | `biteblog.interaction` | `interaction.comment` | 对该笔记热度 `+4`。 |

为了防止排行榜缓存无限增长，服务只保留每个榜单前 200 条数据。

## 6. 定时刷新机制

服务使用 Spring Scheduling，每 10 分钟执行一次排行榜重建：

```java
@Scheduled(cron = "0 0/10 * * * ?")
```

这样即使 RabbitMQ 消息短暂丢失，排行榜也可以通过定时任务从 MySQL 中恢复到一致状态。

## 7. 非功能需求处理

### 7.1 性能

排行榜读请求不直接进行复杂 SQL 排序，而是优先读取 Redis ZSet。Top10 和分页查询都是基于 Redis 完成，时间复杂度较低，适合高频访问。

### 7.2 可用性

如果 Redis 中没有对应榜单缓存，接口会自动调用 `rebuild(type)` 从 MySQL 重建数据，避免首次访问返回空结果。

### 7.3 一致性

服务采用“事件实时加分 + 定时全量重建”的方式。事件加分保证热榜实时性，定时重建保证最终一致性。

### 7.4 安全性

Rank 查询接口只提供公开排行榜数据，不涉及用户密码、手机号等敏感信息。分页参数做了边界控制，避免异常大分页导致资源消耗过高。

### 7.5 可维护性

排行榜 key、榜单类型、最大缓存数量和加分权重均集中在 `RankService` 中，后续可以方便调整。

## 8. 本地测试环境说明

本地测试时，为避免 Nacos 和 RabbitMQ 未启动导致服务启动失败，`application.yml` 中临时关闭了 Nacos 服务注册和 RabbitMQ Listener 自动启动。MySQL 使用本机 MySQL 8.0，数据库名为 `biteblog`，通过 `sql/init.sql` 初始化表结构。

本地测试配置与完整微服务联调配置有所区别。完整联调时需要启动 Nacos、RabbitMQ，并重新开启 RabbitMQ Listener。

## 9. 本地接口验证结果

已完成本地接口验证。测试前先导入 `sql/init.sql`，再插入 3 条排行榜测试笔记数据。调用 `/rank/rebuild?type=daily` 后，Redis 中成功生成排行榜缓存。

测试结果如下：

| 接口                                        | 测试结果 | 说明                  |
| ------------------------------------------- | -------- | --------------------- |
| GET `/rank/health`                          | 通过     | 返回 `status=UP`      |
| POST `/rank/rebuild?type=daily`             | 通过     | 返回 `rebuilt=true`   |
| GET `/rank/top10?type=daily`                | 通过     | 返回 3 条热榜数据     |
| GET `/rank/list?type=weekly&page=1&size=10` | 通过     | 返回 3 条分页排行数据 |

测试数据中，`热榜测试笔记3` 因点赞、收藏、评论数最高，最终排名第一，符合热度分排序预期。
