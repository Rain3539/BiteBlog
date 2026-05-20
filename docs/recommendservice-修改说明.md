# Recommend Service 修改说明

## 1. 服务定位

Recommend Service 是 BiteBlog 的发现页个性化推荐服务，服务端口为 `8084`，对应前端顶部导航中的“发现”页面。

该服务负责根据用户行为、标签关键词、相似用户行为和近期热门内容，为用户返回一批可分页浏览的探店笔记。它不是单独存在的页面逻辑，而是连接 Gateway、User Service、Post Service、Redis、MySQL 和前端发现页的分布式推荐链路。

整体链路：

```text
用户登录
-> 前端 /discover
-> Gateway /api/recommend/discover
-> Recommend Service
-> 读取 Redis 曝光集合、Rank 日榜、MySQL 笔记和行为数据
-> 返回推荐列表
-> 前端渲染卡片
-> 前端上报曝光 /api/recommend/exposures
-> Redis exposure:{userId}
```

本次实现为：

- 标签召回优先使用 Elasticsearch `post_index`，ES 不可用或无结果时降级为 MySQL 关键词兜底。
- ItemCF 使用 Redis ZSet 保存预计算物品相似度，Redis 相似池无结果时再降级在线扫描 `user_behavior`。
- 曝光去重使用 Redis Lua 在推荐返回前原子筛选并预占，降低同一用户并发刷新时重复推荐概率。
- 冷启动优先复用 Rank Service 的 Redis ZSet `rank:daily:{yyyy-MM-dd}`，为空或 Redis 不可用时再降级到 MySQL 热门笔记。
- Redis 不缓存最终推荐结果，只保存 `exposure:{userId}` 曝光状态、`behavior:{userId}` 用户行为画像缓存和 Rank 日榜 ZSet。
- 预计算任务每 10 分钟刷新 Redis ItemCF 相似池，并补写本服务冷启动复用的 `rank:daily:{yyyy-MM-dd}`，在线请求只做曝光过滤、轻量重排和作者打散。
- 消费 RabbitMQ `note.published`、`note.deleted` 和 `interaction.*` 事件，准实时维护推荐侧 ES `post_index`、Redis ItemCF 相似池，并同步补写冷启动依赖的 Rank 日榜数据。

## 2. 新增和调整文件

| 文件 | 说明 |
|---|---|
| `biteblog-backend/biteblog-recommend/pom.xml` | 补充 Web、MyBatis-Plus、MySQL、Redis、Elasticsearch、RabbitMQ、OpenFeign 等依赖 |
| `RecommendApplication.java` | Recommend 服务启动类，启用服务注册、Mapper 扫描和公共配置扫描 |
| `entity/Note.java` | 映射 `note` 表，用于读取笔记基础信息和互动计数 |
| `entity/NoteImage.java` | 映射 `note_image` 表，用于补齐封面图 |
| `entity/UserBehavior.java` | 映射 `user_behavior` 表，用于用户行为和 ItemCF 计算 |
| `mapper/NoteMapper.java` | 笔记表 Mapper |
| `mapper/NoteImageMapper.java` | 笔记图片表 Mapper |
| `mapper/UserBehaviorMapper.java` | 用户行为表 Mapper |
| `dto/RecommendItemVO.java` | 推荐卡片返回对象 |
| `dto/RecommendResponse.java` | 推荐列表响应对象，包含 `list/cursor/hasMore` |
| `dto/ExposureRequest.java` | 曝光上报请求对象 |
| `service/RecommendDataService.java` | 数据访问层，集中封装 MySQL 查询 |
| `service/RecommendSearchService.java` | ES 召回层，从 `post_index` 查询标签/关键词候选 |
| `service/RecommendService.java` | 推荐核心逻辑，负责召回、排序、曝光过滤、冷启动和降级 |
| `service/RecommendPrecomputeService.java` | 轻量预计算任务，刷新 Redis ItemCF 相似池，并补写冷启动复用的 Rank 日榜数据 |
| `config/RecommendRabbitConfig.java` | Recommend MQ 队列、交换机和绑定配置 |
| `service/RecommendEventListener.java` | 消费发帖、删帖和互动事件，准实时刷新推荐侧数据 |
| `controller/RecommendController.java` | 推荐服务对外接口 |
| `frontend/src/api/recommend.js` | 前端推荐接口封装 |
| `frontend/src/views/DiscoverView.vue` | 发现页推荐流页面 |
| `sql/init-recommend-data.ps1` | 推荐测试数据脚本，生成 60 条推荐样例 |
| `sql/数据说明.md` | 推荐测试数据说明 |
| `jmeter/recommend-service-test.jmx` | 推荐服务 JMeter 测试脚本 |
| `docs/recommendservice-测试说明.md` | 推荐测试说明与截图要求 |

## 3. 新增接口说明

### 3.1 健康检查

```http
GET /recommend/health
```

用途：确认 Recommend Service 已启动。

响应示例：

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "service": "recommend-service",
    "status": "UP"
  }
}
```

通过 Gateway 访问：

```http
GET /api/recommend/health
```

### 3.2 发现页推荐

```http
GET /recommend/discover?cursor=0&size=20&tag=Hotpot&city=Guangzhou
X-User-Id: 10
```

通过 Gateway：

```http
GET /api/recommend/discover?cursor=0&size=20&tag=Hotpot&city=Guangzhou
Authorization: Bearer <token>
```

参数说明：

| 参数 | 类型 | 是否必填 | 说明 |
|---|---|---|---|
| `cursor` | Long | 否 | 分页游标，首次请求传 `0` |
| `size` | Integer | 否 | 每页数量，默认 20，最大 50 |
| `tag` | String | 否 | 标签或关键词过滤 |
| `city` | String | 否 | 城市或地址过滤 |
| `X-User-Id` | Header | 直连必填 | 当前用户 ID；通过 Gateway 时由 JWT 解析后透传 |

返回字段：

| 字段 | 说明 |
|---|---|
| `list` | 推荐笔记列表 |
| `cursor` | 下一页游标，没有更多时为 `null` |
| `hasMore` | 是否还有下一页 |

推荐项字段：

| 字段 | 说明 |
|---|---|
| `postId` | 笔记 ID |
| `authorId` | 作者 ID |
| `title` | 标题 |
| `coverUrl` | 封面图 |
| `shopName` | 店铺名称 |
| `likeCount` | 点赞数 |
| `collectCount` | 收藏数 |
| `commentCount` | 评论数 |
| `reason` | 推荐理由，如“近期热门”“兴趣标签相关”“相似用户喜欢” |
| `createdAt` | 发布时间 |

### 3.3 曝光上报

```http
POST /recommend/exposures
X-User-Id: 10
Content-Type: application/json

{
  "postIds": [101, 102, 103]
}
```

用途：记录本批推荐已经展示给用户，避免短时间内重复推荐。

服务写入 Redis：

```text
exposure:{userId}
```

特性：

- 使用 Redis Set，重复上报天然幂等。
- TTL 为 7 天，避免曝光集合无限增长。
- 单次最多处理前 100 个 `postId`，避免异常大请求。

### 3.4 预计算触发接口

```http
POST /recommend/internal/precompute
```

用途：手动触发轻量预计算，便于测试、演示和压测前预热。

返回示例：

```json
{
  "rankDailyCount": 60,
  "itemSimilarityCount": 12,
  "elapsedMs": 120
}
```

该接口会重建/补写：

- Redis `rank:daily:{yyyy-MM-dd}`：冷启动优先复用的 Rank 日榜数据，主职责仍属于 Rank Service。
- Redis ItemCF 相似池：ItemCF 相似笔记召回使用。

服务启动后也会通过 `@Scheduled` 每 10 分钟自动执行一次，保证推荐使用的是准实时预计算结果。

### 3.5 RabbitMQ 事件消费

Recommend Service 绑定项目已有的两个 Topic Exchange：

| Exchange | Routing Key | Queue | 处理逻辑 |
|----------|-------------|-------|----------|
| `biteblog.post` | `note.published` | `recommend.note.published.queue` | 读取 MySQL 笔记，写 ES `post_index`，补写 Rank 日榜冷启动数据 |
| `biteblog.post` | `note.deleted` | `recommend.note.deleted.queue` | 删除 ES `post_index` 文档，清理 Rank 日榜中的该笔记，并重建 ItemCF |
| `biteblog.interaction` | `interaction.*` | `recommend.interaction.queue` | 清理用户画像缓存，按最新互动计数补写 Rank 日榜分数，并重建 Redis ItemCF 相似池 |

事件来源是 Post Service：发布笔记后发送 `note.published`，删除笔记后发送 `note.deleted`，点赞/收藏/评论后发送 `interaction.like`、`interaction.collect`、`interaction.comment`。Recommend 消费事件后只刷新推荐侧读模型，MySQL 仍然是主数据源。

## 4. 业务功能设计

### 4.1 推荐主流程

```text
读取 userId
-> 读取 Redis exposure:{userId}
-> 统计用户历史行为数量
-> 判断是否冷启动
-> 多路召回候选内容
-> 过滤已曝光内容
-> 混合排序
-> 分页截取
-> 补齐封面图、店铺名、互动计数、推荐理由
-> 返回给前端
```

### 4.2 冷启动兜底

当用户行为不足，且没有主动输入标签时，服务走冷启动路径：

```text
优先读取 Redis ZSet rank:daily:{yyyy-MM-dd}
-> 为空时降级查询 MySQL 热门笔记
-> 根据分数倒序取热门笔记
-> 过滤 exposure:{userId}
-> 补齐笔记详情
-> 返回推荐列表
```

如果 Rank 日榜为空或 Redis 不可用：

```text
降级查询 MySQL note 表
-> 按 like_count、collect_count、comment_count、created_at 排序
-> 返回正常状态笔记
```

设计原因：新用户没有行为画像，不能依赖个性化模型，因此使用全局热门内容保证页面可用。

### 4.3 ES 标签召回

当用户传入 `tag` 或 `city` 时，服务使用关键词召回：

```text
tag/city
-> Elasticsearch post_index
-> multi_match(title^3, content, shopName^2, store_name^2, tags)
-> status=1 过滤
-> 返回 postId 列表
-> 回查 MySQL note 表补齐业务字段
```

如果 ES 查询失败，服务直接降级到 Rank 日榜冷启动结果，不报错；如果 ES 正常但没有命中候选，再通过热门候选补量保证接口可用。

### 4.4 预计算 ItemCF + 在线降级

ItemCF 相似度不在用户请求推荐页时全量计算，而是由预计算任务提前生成：

```text
最近 user_behavior
-> 按 userId 聚合交互过的 noteId
-> 同一用户交互过的笔记两两累计相似分
-> 每个 item 保留 Top20 similar item
-> 写入 Redis ItemCF 相似池
```

在线请求时只根据用户最近交互过的 `noteId` 查询 Redis 相似池，再把相似笔记加入候选池。

老用户推荐会读取当前用户近期行为：

```text
用户 A 交互过 note1、note2
-> 查询 Redis recommend:itemcf:similar:{noteId}
-> 得到离线/预计算的相似笔记
-> 作为 ItemCF 候选
```

近期行为会缓存到 Redis：

```text
behavior:{userId}
TTL = 5min
```

该缓存只保存用户行为画像中间数据，不缓存最终推荐结果，因此用户每次请求仍会经过曝光过滤、候选召回和排序。

如果 Redis 中没有预计算相似池，服务会降级为在线简化 ItemCF：

```text
找到也交互过 note1、note2 的其他用户
-> 读取这些相似用户交互过、但 A 没看过的笔记
-> 按行为权重合并为 ItemCF 候选
```

行为权重：

| 行为 | 权重 |
|---|---:|
| view | 1 |
| dwell | 3 |
| like | 5 |
| collect/favorite | 8 |
| comment | 10 |

如果 `user_behavior.weight` 有正数，则优先使用表内权重。

### 4.5 混合排序

当前排序分只在服务端内部使用，不返回给前端，避免用户误解为店铺评分或官方打分。

冷启动和补量不由 Recommend 维护独立热榜，而是优先复用 Rank Service 的日榜：

```text
rank:daily:{yyyy-MM-dd}
-> 为空或不可用时降级 MySQL 热门笔记
```

热度分计算口径沿用 Rank Service，不在 Recommend 内维护另一套 `recommend:hot:pool` 或独立热门分。

老用户综合排序：

```text
finalScore = tagScore * 0.6 + itemCfScore * 0.4
```

设计原因：

- 标签召回体现用户主动意图。
- ItemCF 体现相似用户兴趣。
- 热门内容只用于冷启动和候选不足时补量；个性化主排序只使用标签分和 ItemCF 分。

### 4.6 Lua 曝光预占与去重

推荐返回前会读取：

```text
exposure:{userId}
```

服务会把按分数排序后的候选 ID 交给 Redis Lua 脚本处理：

```text
for candidate in orderedCandidates:
  if SISMEMBER exposure:{userId} candidate == false:
    SADD exposure:{userId} candidate
    add candidate to selected
  until selected reaches pageSize
EXPIRE exposure:{userId} 7d
return selected
```

这样“判断是否曝光”和“写入曝光集合”在 Redis 内原子完成，比先查 Set 再异步上报更适合同一用户并发刷新。前端仍会在渲染后调用 `/recommend/exposures` 做一次幂等确认上报，重复写 Set 不会产生重复数据。

设计取舍：

- 曝光预占放在返回前完成，避免用户刷新后反复看到同一批内容。
- 用户行为画像更新可以异步或延迟，推荐系统允许最终一致。

### 4.7 前端发现页

`DiscoverView.vue` 已接入真实推荐流：

- 页面进入时健康检查和拉取推荐列表。
- 支持标签/城市筛选。
- 支持刷新推荐。
- 支持“加载更多”分页追加。
- 展示标题、店铺、推荐理由、点赞/收藏/评论数、发布时间。
- 点击卡片跳转 `/post/{postId}`。
- 页面渲染后自动上报曝光。

前端不展示内部推荐分，只展示推荐理由和公开互动数据，避免用户误解为店铺评分。

## 5. 非功能需求处理

本节按组长要求补充为：并发 -> 一致性 -> 可靠性 -> 安全性 -> 可维护性。括号中的 R 编号对应 `docs/recommendservice-测试说明.md` 中的测试编号。

### 5.1 并发

解决的问题：

- 发现页推荐是高频读接口，同一时间可能有大量用户刷新、翻页。
- 曝光上报和推荐读取会并发发生，如果曝光记录不是原子写入，容易出现重复推荐。
- 单用户连续刷新或多请求同时到达时，需要尽量避免同一批内容反复返回。

处理方案：

- 推荐服务本身不保存本地会话状态，用户身份由 Gateway 解析后透传 `X-User-Id`，多实例部署时可以横向扩展。
- 曝光集合、行为画像、Rank 日榜和 ItemCF 相似池都放在 Redis，多个 Recommend Service 实例共享同一份推荐状态。
- 曝光预占使用 Redis Lua + Set，在 Redis 内部完成“判断是否已曝光 + 写入曝光集合”，降低并发请求下重复推荐概率。
- 推荐列表不再逐条同步调用 Post Service 补全详情，直接使用 Recommend 本地 `note` 表字段和 `note_image` 批量封面，避免一页 20 条内容放大成 20 次 Post RPC。
- 作者昵称和头像通过 Redis `recommend:user:profile:{authorId}` 缓存，TTL 30 分钟；推荐列表按作者去重后批量 `multiGet` 读取缓存，缓存 miss 时才 Feign 调用 User Service，避免一页 20 条内容放大成 20 次 User RPC。
- 笔记卡片展示摘要通过 Redis `recommend:post:summary:{postId}` 缓存，预计算和 MQ 事件刷新时写入；冷启动/热门路径在线请求优先批量 `multiGet` 摘要，缺失时才回源 MySQL 并回填 Redis。
- JMeter 并发压测使用 200 个线程直连 Recommend Service，覆盖默认推荐、标签召回、翻页和曝光上报，验证并发下错误率。

对应测试：

- R-8：曝光 Lua 预占与幂等上报。
- R-11：200 线程 JMeter 并发压测。
- R-14：Redis 展示缓存与远程调用压缩。

当前结果：

- 200 线程、10,000 次混合请求错误率为 0%，说明并发稳定性通过。
- 优化前 100 线程并发平均响应时间约 2479ms，主要瓶颈是推荐列表逐条同步调用 Post/User Service，以及热门/冷启动路径仍需回源 MySQL 拼装展示字段。
- 当前已移除逐条 Post Feign，把 User Feign 改成 Redis 缓存回源，并将笔记展示摘要 Redis 化。优化后 200 线程、10,000 次混合压测平均响应时间约 236ms，吞吐量约 518.9 req/s；默认推荐、小分页、翻页和曝光上报延迟较低，标签 + 城市召回平均约 568ms，是后续继续优化的主要读路径。

### 5.2 一致性

解决的问题：

- Post Service 发布/删除笔记后，Recommend Service 的 ES `post_index` 需要准实时更新。
- 用户点赞、收藏、评论等互动后，Rank 日榜和用户画像需要最终一致。
- ItemCF 相似度不是实时强一致数据，适合预计算后写入 Redis，并允许短时间延迟。
- 曝光去重必须尽快写入，否则用户连续刷新会看到重复内容。

处理方案：

- Recommend Service 消费 RabbitMQ 里的发帖、删帖和互动事件。
- 发帖事件更新 ES `post_index`，保证标签召回能搜到新笔记。
- 删除事件同步删除 ES `post_index` 中的笔记，并清理相关推荐侧数据。
- 互动事件清理 `behavior:{userId}`，让下一轮推荐重新读取用户行为；同时按 Rank 口径补写 `rank:daily:{date}`，保证冷启动数据源在演示环境中可恢复。
- ItemCF 相似关系由预计算任务写入 Redis `recommend:itemcf:similar:{postId}`，在线请求只读取 TopN 相似候选。
- 笔记展示摘要由预计算和 MQ 事件写入 Redis `recommend:post:summary:{postId}`，发布、互动后可以准实时刷新推荐卡片内容。
- 曝光记录使用 Redis Set，重复上报天然幂等，并设置 TTL 避免长期堆积。

对应测试：

- R-1：健康检查与预计算。
- R-2：MQ 发帖与互动事件消费。
- R-6：游标分页与曝光去重。
- R-8：曝光 Lua 预占与幂等上报。
- R-9：Redis ItemCF 预计算数据。
- R-14：Redis 展示缓存验证。
- R-12：一致性验证汇总。

### 5.3 可靠性

解决的问题：

- 新用户没有行为数据时不能空白。
- ES、Redis 某个候选源为空或临时异常时，发现页不能直接 500。
- 推荐候选不足时，需要补量避免前端列表过短。

处理方案：

- 冷启动按 `rank:daily:{date}` -> MySQL 热门笔记逐级兜底。
- ES 标签召回失败时捕获异常，并使用热门候选补量。
- Redis ItemCF 相似池为空时，降级为扫描 MySQL `user_behavior` 做轻量在线相似补量；如果 ItemCF 仍不足，由外层推荐流程继续走 Rank 日榜 / MySQL 热门补量。
- Redis 曝光读取异常时记录 warn，并跳过曝光过滤，优先保证接口可返回。
- 推荐结果不足时从 Rank 日榜和 MySQL 候选补齐，再做作者打散，保证页面可用性。

对应测试：

- R-3：冷启动推荐响应时间。
- R-7：标签召回功能和候选补量。
- R-9：Redis ItemCF 预计算数据。
- R-10：边界参数。
- R-13：可靠性验证汇总。

补充破坏性验证：

- 清空 `rank:daily:{date}` 后请求冷启动推荐，验证 MySQL 热门笔记兜底。
- 删除 `recommend:itemcf:similar:*` 后请求个性化推荐，验证 MySQL 行为扫描补量。
- 停止 Elasticsearch 后请求标签推荐，验证 ES 失败时服务不报错。
- 停止 Redis 后请求推荐，验证曝光预占和 Rank 日榜异常时仍可尽量返回。
- 手动触发 `/recommend/internal/precompute`，验证 Rank 日榜和 Redis ItemCF 可以恢复。

### 5.4 安全性

解决的问题：

- 发现页接口必须识别当前用户，不能让用户伪造他人推荐流。
- 曝光上报不能一次提交过多 ID，避免异常请求拖垮 Redis。
- 推荐响应不能返回密码、手机号等敏感字段。

处理方案：

- 正常访问路径走 Gateway，Gateway 负责 JWT 鉴权并透传用户 ID。
- Recommend Service 直连仅用于本地测试，必须显式传 `X-User-Id`。
- 曝光上报限制单次最多处理 100 个 postId。
- 推荐响应只返回公开笔记摘要和展示字段，不返回用户敏感信息。

对应测试：

- R-10：边界参数。
- Gateway 鉴权由公共 Gateway/User Service 测试覆盖，Recommend 测试主要验证服务内边界保护。

### 5.5 可维护性

解决的问题：

- 推荐链路涉及 ES、Redis、MySQL、RabbitMQ，如果逻辑散落在 Controller 中会难以维护。
- 推荐算法和数据源后续还会调整，需要保持接口稳定。

处理方案：

- `RecommendController` 只负责接口入参和响应。
- `RecommendService` 负责编排召回、排序、去重、多样性和降级。
- `RecommendSearchService` 只负责 ES `post_index` 标签召回和索引维护。
- `RecommendPrecomputeService` 负责 Redis ItemCF 的预计算，并补写冷启动复用的 Rank 日榜数据。
- `RecommendEventListener` 负责 MQ 事件消费，隔离跨服务同步逻辑。
- 推荐列表展示字段优先来自本服务读模型，User Service 作者信息通过独立缓存方法封装，后续如果改成批量用户接口或前端懒加载，不影响推荐算法主流程。
- Redis 笔记摘要、作者缓存、Rank 日榜读取和 ItemCF 分别封装，后续可以独立调整 TTL 或迁移为批量接口，不影响 Controller 对外返回结构。
- 测试脚本、JMeter 脚本和测试说明统一放在 `测试脚本/`、`jmeter/`、`docs/`，便于复测和汇报截图。

对应测试：

- R-1：预计算入口。
- R-2：事件消费链路。
- R-11：JMeter 脚本可重复压测。
- R-14：Redis 展示缓存验证。

### 5.6 既有实现细节

#### 性能细节

处理方式：

- 标签召回优先使用 ES，避免复杂文本匹配压在 MySQL 上。
- ItemCF 相似度由预计算任务提前写入 Redis ZSet，在线请求只按用户交互过的 `item_id` 查询 TopN 相似笔记。
- 冷启动优先读取 Rank Service 的 Redis ZSet `rank:daily:{yyyy-MM-dd}`，为空时再降级 MySQL 热门笔记，避免每次都查数据库排序。
- 用户近期行为画像缓存到 Redis `behavior:{userId}`，TTL 5 分钟，减少高频请求时重复扫描 `user_behavior`。
- 曝光预占使用 Redis Lua + Set，查重和写入在 Redis 内完成。
- 分页 `size` 最大限制为 50，避免一次请求拉取过多数据。
- 数据访问集中在 `RecommendDataService`，减少 Controller 里散落查询逻辑。

测试方式：

- 使用 JMeter 并发请求 `/api/recommend/discover`。
- 使用 `size=999` 验证分页上限。
- 使用前端构建和接口请求验证常规路径。

#### 分布式并发细节

处理方式：

- Recommend Service 本身不保存本地会话状态，用户状态通过 JWT/Gateway 转成 `X-User-Id`。
- 曝光集合、行为画像缓存、Rank 日榜和 ItemCF 预计算相似关系都存储在 Redis 中，多实例部署时可以共享同一份推荐状态。
- 曝光预占使用 Redis Lua，同一内容并发竞争时由 Redis 单线程原子执行，降低重复推荐概率。
- 曝光上报使用 Redis Set，同一内容重复写入不会产生重复记录。
- 推荐读取和曝光写入分离，适合前端高频刷新和多用户并发访问。

测试方式：

- JMeter 多线程同时请求 `/api/recommend/discover` 和 `/api/recommend/exposures`。
- 重复上报同一批 `postIds`，检查 Redis Set 不重复。
- 多次刷新发现页，检查服务不返回 500，页面能稳定渲染。

#### 可用性细节

处理方式：

- Redis 日榜不可用时降级 MySQL 热门笔记。
- Redis 曝光读取失败时记录 warn 日志并跳过曝光过滤，保证推荐接口尽量可返回。
- ES 当前不是强依赖；ES 召回失败时会自动降级 MySQL 关键词兜底。
- 候选不足时自动使用热门笔记补齐。

测试方式：

- 清空 `rank:daily:{yyyy-MM-dd}` 后请求推荐。
- 不传标签的新用户请求冷启动推荐。
- 传标签但候选不足时检查是否有热门补齐。

#### 一致性细节

处理方式：

- 曝光数据使用 Redis Set，重复上报保持幂等。
- Redis Key 设置 7 天 TTL，避免曝光记录长期堆积。
- 行为画像缓存 `behavior:{userId}` 设置分钟级 TTL，互动事件消费后会清理画像缓存，让下一次推荐读取较新的行为数据。
- 用户行为推荐允许最终一致：点赞/收藏等行为进入 `user_behavior` 后，下一轮推荐生效。

测试方式：

- 连续两次调用 `/recommend/exposures` 上报同一批 ID。
- 检查 `SMEMBERS exposure:{userId}` 中没有重复 member。
- 检查 `TTL exposure:{userId}` 大于 0。

#### 安全性细节

处理方式：

- 通过 Gateway 访问时依赖 JWT 鉴权。
- Gateway 从 Token 解析当前用户，并透传 `X-User-Id`。
- 直连 Recommend Service 时必须显式传 `X-User-Id`。
- 返回值只包含公开笔记摘要，不返回手机号、密码哈希等敏感信息。
- 曝光上报限制最多 100 个 ID。

测试方式：

- 未登录访问 `/api/recommend/discover`，应被 Gateway 拦截。
- 登录后访问发现页，接口正常返回。
- 构造超长 `postIds`，服务只处理前 100 个。

#### 容量边界细节

处理方式：

- `size <= 0` 时使用默认 20。
- `size > 50` 时截断为 50。
- Redis 曝光集合设置 TTL。
- 初始化脚本生成 60 条推荐样例，支持前端分页和加载更多演示。

测试方式：

- 请求 `size=1`、`size=50`、`size=999`。
- 检查返回数量符合限制。
- 前端点击“加载更多”验证分页追加。

#### 可维护性细节

处理方式：

- `RecommendController` 只负责接口入参和响应。
- `RecommendService` 负责业务编排、召回、排序、去重和降级。
- `RecommendDataService` 负责数据库查询。
- DTO、Entity、Mapper 分层清晰。
- ES 标签召回、Redis ItemCF 召回、Redis/MySQL 兜底分层实现，后续调整召回源不影响 Controller 和前端接口结构。

测试方式：

- Maven 编译推荐模块。
- 前端 `npm run build`。
- JMeter XML 解析。

## 6. 测试数据设计

基础用户来自：

```powershell
.\sql\init-data.ps1
```

推荐数据来自：

```powershell
.\sql\init-recommend-data.ps1
```

推荐脚本特点：

- 不创建额外用户。
- 复用 `13800000001` ~ `13800000060`。
- `13800000001` ~ `13800000003` 是大 V，粉丝超过 50。
- 推荐笔记集中发布在 `13800000001` 和 `13800000004` 上。
- 共生成 60 条 `Recommend Test` 笔记，用于分页和加载更多。
- 写入 `rank:daily:{yyyy-MM-dd}`，用于冷启动。
- 写入 `recommend:itemcf:similar:{postId}`，作为 ItemCF 的预计算相似池。
- 写入少量 `user_behavior`，用于个性化和在线 ItemCF 降级。
- 尝试同步样例笔记到 ES `post_index`；ItemCF 相似关系写入 Redis，ES 不可用时不影响 MySQL/Redis 数据初始化。
- 写入 `exposure:{13800000005的userId}`，用于曝光去重样例。

## 7. 已实现增强与仍可优化点

本次已把三个生产级关注点做进当前版本：

| 方向 | 当前实现 | 说明 |
|---|---|---|
| 标签召回 | ES `post_index` 优先，MySQL `LIKE` 降级 | 兼顾全文检索能力和服务可用性 |
| ItemCF | Redis 相似池优先，在线 `user_behavior` 降级 | 相似度是 TopN 预计算数据，放 Redis 查询更轻 |
| 曝光一致性 | Redis Lua 返回前原子预占 | 降低并发刷新重复推荐概率 |

仍可继续优化：

| 方向 | 当前实现 | 后续优化 |
|---|---|---|
| 推荐解释 | 简单 reason | 根据召回源、标签和相似行为生成更细解释 |
| 图片数据 | 当前推荐样例可无封面 | 后续结合 Post/MinIO 上传真实图片 |
| 相似度生成 | 定时任务、手动接口和 MQ 互动事件重建 Redis ItemCF 相似池 | 当前为轻量级全量重建，后续可改成真正增量更新 |

## 8. 汇报重点

答辩时建议重点讲：

1. 我负责的是发现页推荐，不是热榜/附近/关注。
2. 推荐服务通过 Gateway 暴露给前端，用户身份由 JWT 解析后透传。
3. 冷启动用户优先走 Rank 日榜，日榜为空时降级 MySQL 热门笔记，保证新用户也能看到内容。
4. 老用户结合标签召回和简化 ItemCF，体现个性化。
5. 曝光记录使用 Redis Set，解决重复推荐问题。
6. Redis 作为共享状态，体现分布式场景下多实例可共享曝光、Rank 日榜读取结果和 ItemCF 相似池。
7. JMeter 并发测试用于验证多用户同时访问时接口稳定性和响应时间。
