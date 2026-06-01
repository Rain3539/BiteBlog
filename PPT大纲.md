# BiteBlog 分布式美食探店社区平台 — PPT 大纲

---

## 第一部分：软件功能（约 6-8 页）

### 1.1 项目概述 (1页)

- **项目定位**：分布式美食探店社区平台，支持用户发布探店笔记、发现热门内容、搜索附近店铺
- **技术栈**：Spring Cloud Alibaba + Vue 3 + Docker Compose
  - 后端：Spring Boot 3.x + Spring Cloud Gateway + Nacos + MyBatis-Plus
  - 中间件：MySQL 8.0 / Redis 7 / RabbitMQ 3.12 / Elasticsearch / MinIO
  - 前端：Vue 3 + Vite + Element Plus
- **架构规模**：1 网关 + 7 微服务 + 6 中间件 + SPA 前端

### 1.2 系统架构总览 (1页)

- 架构图：Gateway(8080) → 7 个微服务(8081-8087) → MySQL/Redis/ES/RabbitMQ/MinIO
- 服务注册发现：Nacos
- API 网关统一鉴权（JWT）+ 路由转发
- 异步消息总线：RabbitMQ（Topic Exchange，5 类事件）
- 缓存层：Redis 多数据结构（String/ZSet/Set/GEO/Hash）
- 搜索层：Elasticsearch（IK 分词器，9 个索引）

### 1.3 核心功能展示 (3-4页)

#### 1.3.1 用户与社交
- 注册/登录（BCrypt + JWT，Redis Session 24h TTL）
- 用户主页（Cache-Aside，2h TTL）
- 关注/取关（幂等切换 + 原子计数器 + Redis Set 同步）
- 粉丝列表 / 关注列表（MyBatis-Plus 分页）

#### 1.3.2 笔记发布与浏览（Post Service）
- **笔记发布**：图片上传 MinIO → 表单验证 → `@Transactional` 写入 note + note_image → afterCommit 发 MQ `note.published`
- **笔记详情**：Cache-Aside 模式（Redis TTL 30min），冷路径 → MySQL，热路径 → Redis
- **全文搜索**：ES `multiMatch`（title/content/shopName，IK 分词），降级返回空列表
- **互动系统**：点赞/收藏（UNIQUE KEY + 原子 `setSql` 计数）、评论（一层回复 + 分页）
- **逻辑删除**：`@TableLogic` + ES `filter(term("status", 1))` 双重过滤

#### 1.3.3 Feed 流（Feed Service）
- **推拉结合策略**：普通用户 fanout 到粉丝 inbox（Redis ZSet），大 V（粉丝≥50）读时实时拉取
- **游标分页**：`ZREVRANGEBYSCORE` + 时间戳 cursor，不丢不重
- **冷启动兜底**：inbox 为空 → 降级 MySQL 查询 → 回填 Redis

#### 1.3.4 热度排行榜（Rank Service）
- **三类榜单**：日榜/周榜/总榜，Redis Sorted Set 存储
- **热度公式**：likes×3 + collects×5 + comments×4 + qualityScore + 24/√hours
- **实时更新**：MQ 消费 `note.published`/`interaction.*` 事件，增量更新分数
- **定时重建**：`@Scheduled` 每 10 分钟全量重建，修正漂移

#### 1.3.5 附近探店（Location Service）
- **Redis GEO**：`GEORADIUS` 纯内存空间查询，O(N+logM)，返回距离+笔记详情
- **POI 搜索**：高德 API 代理 + Redis 缓存（TTL 1h，加速 49x）
- **坐标异步写入**：MQ `note.published` → GEOADD，失败重试 5 次

#### 1.3.6 个性化推荐（Recommend Service）
- **多路召回**：标签召回（ES `post_index`）+ ItemCF（ES `item_sim_index`）+ 热门补量
- **混合排序**：tagScore×0.6 + itemCfScore×0.4 + hotScore
- **曝光去重**：Redis Lua 原子预占 `exposure:{userId}`，TTL 7d
- **三级降级**：`rank:daily` → `recommend:hot:pool` → MySQL 热门笔记

#### 1.3.7 实时通知（Notify Service）
- **STOMP over WebSocket**：在线实时推送
- **通知持久化**：MySQL `notification` 表 + Redis 未读计数（Cache-Aside）
- **幂等去重**：5 分钟窗口 `(receiver, sender, type, bizId)` 四字段去重
- **冷热分离**：30 天已读通知归档到 `notification_archive`

### 1.4 前端交互亮点 (1页)

- **发布页**：图片上传与发布分离（可边写边传图）、POI 搜索自动补全、三维度评分
- **详情页**：图片轮播、互动 toggle 动画、评论回复分页、路由切换 `watch(postId, immediate: true)`
- **搜索浮层**：ES 实时检索、结果列表跳转
- **发现页**：推荐流无限滚动、标签筛选、曝光上报
- **通知中心**：WebSocket 实时推送、类型筛选、未读角标

---

## 第二部分：问题分析（约 4-5 页）

### 2.1 性能挑战

| 挑战 | 具体表现 | 影响范围 |
|------|----------|----------|
| **高并发读** | 笔记详情、热榜 Top10、Feed 流为高频读入口，直查 MySQL 无法支撑 | Post / Rank / Feed |
| **全文搜索延迟** | 中文分词 + 多字段匹配，MySQL LIKE 效率低 | Post（搜索） |
| **Feed 写入放大** | 大 V 发布笔记，fanout 到数万粉丝 inbox，写扩散严重 | Feed |
| **推荐实时计算** | ItemCF 需扫描全量 `user_behavior` 表，在线计算不可行 | Recommend |
| **空间查询** | 附近探店需计算所有笔记与用户的球面距离，MySQL 无法高效处理 | Location |
| **未读数高频轮询** | 导航栏每次路由切换都查未读数，频繁 `COUNT(*)` 压 DB | Notify |

### 2.2 可用性挑战

| 挑战 | 具体表现 | 影响范围 |
|------|----------|----------|
| **ES 单点风险** | 搜索和推荐强依赖 ES，ES 宕机导致搜索报 500、推荐不可用 | Post / Recommend |
| **Redis 缓存雪崩** | 大量缓存同时过期，请求穿透到 MySQL | Post / Feed / Rank / Notify |
| **RabbitMQ 消息丢失** | Broker 重启或消费异常导致事件丢失，下游数据不一致 | 全部服务 |
| **Feign 调用无断路器** | Post/User 不可用时，Recommend 响应被拖慢甚至超时 | Recommend |
| **WebSocket 连接不稳定** | 网络波动导致通知推送断开 | Notify |

### 2.3 可靠性挑战

| 挑战 | 具体表现 | 影响范围 |
|------|----------|----------|
| **事务边界与消息** | 笔记发布后若 MQ 先发送而 MySQL 回滚，下游收到脏事件 | Post |
| **跨服务时序差** | Post 事务刚提交，Location/Recommend 已消费 MQ 却查不到 note | Location / Recommend |
| **消息重复投递** | MQ 重试导致同一互动产生多条重复通知 | Notify |
| **并发写冲突** | 点赞/收藏高并发下 SELECT-UPDATE 导致 lost update | Post |
| **缓存与 DB 不一致** | 榜单 Redis 分数因事件丢失与 MySQL 真实值偏离 | Rank |

### 2.4 一致性挑战

| 挑战 | 具体表现 | 涉及存储 |
|------|----------|----------|
| **MySQL ↔ Redis ↔ ES 三副本** | 发布/删除/互动后三种存储存在同步延迟 | Post / Rank / Recommend |
| **逻辑删除传播** | MySQL status=0 后，Redis 缓存、ES 索引、GEO 集合均需清理 | Post / Feed / Rank / Location |
| **Feed inbox 不一致** | fanout 中途 Redis 断连导致部分粉丝收不到 | Feed |
| **曝光状态同步** | 用户刷新推荐时曝光集合需实时反映，否则重复推荐 | Recommend |

### 2.5 安全性挑战

| 挑战 | 具体表现 |
|------|----------|
| **越权操作** | 用户删除他人笔记、标记他人通知已读 |
| **未登录访问** | 敏感接口绕过 Gateway 鉴权 |
| **WebSocket 鉴权** | WS 握手阶段无 HTTP Header，Token 传递方式需特殊处理 |

---

## 第三部分：技术方案（约 8-10 页）

### 3.1 性能优化方案

#### 3.1.1 多级缓存体系
我们利用 Redis 的多种数据结构为不同场景构建了专门的缓存层。笔记详情使用 String 类型配合 Cache-Aside 模式，设置 30 分钟过期时间，命中缓存直接返回，未命中则回源 MySQL 并回填。榜单排行和 Feed 收件箱采用 ZSet 有序集合存储，附近探店使用 GEO 类型进行空间计算，曝光去重和惰性过滤则通过 Set 集合实现。高频 POI 搜索结果也做了 Redis 缓存，二次查询相比首次调用外部 API 加速了 49 倍。

#### 3.1.2 读写分离与异步化
为了平衡读写压力，Feed 流采用了推拉结合策略——普通用户的笔记会直接推送到粉丝的收件箱中，而粉丝数超过 50 的大 V 发布时则改为粉丝阅读时实时拉取，这样既保证了普通用户体验，又避免了大 V 带来的写扩散问题。全文搜索由独立的 Elasticsearch 搜索引擎承担，与 MySQL 写入路径完全解耦。推荐模块的 ItemCF 相似度矩阵每 10 分钟离线预计算一次，在线请求只需查询已经算好的 TopN 结果即可。此外，发布、删除、互动等操作全部通过消息队列异步通知下游服务，不阻塞用户的主流程响应。

#### 3.1.3 数据库优化
在数据库层面，我们在笔记表中冗余存储了点赞数、收藏数和评论数，避免每次展示时都要执行 COUNT 聚合查询。点赞、收藏和关注表都添加了用户与笔记的联合唯一键约束，既保证了幂等性，防止用户重复操作产生脏数据，又加速了去重查询。计数更新统一使用 SQL 的原子增减操作，避免了传统 SELECT 后再 UPDATE 模式下的并发丢失更新问题。笔记删除采用 MyBatis-Plus 的逻辑删除功能，自动在每条查询后追加状态过滤条件。通知模块还将超过 30 天的已读通知定期归档到历史表中，保持热表数据量精简，维持查询性能。

#### 3.1.4 前端性能
前端方面，图片上传与笔记发布流程被拆分为两个独立的操作，用户可以一边撰写内容一边后台上传图片，不必等待上传完成再开始编辑。Vue 路由切换时通过监听路由参数变化来复用已有组件实例，避免重复创建和销毁带来的渲染开销。推荐信息流采用游标分页而非传统页码翻页，加载更多内容时定位更高效。通知中心则使用 WebSocket 长连接实时推送新消息，彻底替代了客户端定时轮询，大幅减少了无效的网络请求。

### 3.2 可用性方案

#### 3.2.1 多级降级链

| 服务 | 降级链路 | 兜底 |
|------|----------|------|
| **Post** | ES 搜索异常 → `Page.empty()`，不报 500 | 空列表 |
| **Feed** | Redis inbox 空 → MySQL 查询 → 回填 inbox | 冷启动兜底 |
| **Recommend** | `rank:daily` → `hot:pool` → MySQL | 三级 Fallback |
| **Recommend ItemCF** | ES `item_sim_index` → Redis `itemcf:similar` → MySQL 在线扫描 | 三级 Fallback |
| **Rank** | Redis ZSet 空 → `ensureCache()` 自动 rebuild | 缓存自愈 |
| **Notify** | Redis 未读数 miss → MySQL `COUNT(*)` | Cache-Aside 兜底 |

#### 3.2.2 缓存自愈与定时重建
- **Rank**：`@Scheduled(cron = "0 0/10 * * * ?")` 全量重建日榜/周榜/总榜
- **Recommend**：`@Scheduled(initialDelay=60s, fixedDelay=10min)` 重建热门池 + ES ItemCF
- **Notify**：`@Scheduled(cron = "0 0 3 * * ?")` 每日凌晨归档 30 天已读通知

#### 3.2.3 跨服务时序补偿
- **Location 重试**：消费 `note.published` 时查 note 失败 → 5 次重试（间隔 200ms）
- **Feed 冷启动**：inbox 为空 → 直接查 MySQL 补充 → 回填 inbox

### 3.3 可靠性方案

#### 3.3.1 事务边界保障
- **afterCommit MQ 发送**：`TransactionSynchronization.afterCommit()` 回调，MySQL 回滚 → MQ 不发出，杜绝脏事件
- **消息持久化**：Exchange/Queue `durable` + 投递模式 `PERSISTENT`

#### 3.3.2 消息消费可靠性
- **手动 Ack + DLQ（Notify）**：`ackMode="MANUAL"`，成功 `basicAck`，失败 `basicNack(requeue=false)` → 死信队列 `notify.dead.queue`
- **幂等去重（Notify）**：5 分钟窗口内查重 `(receiver, sender, type, bizId)` 防 MQ 重投

#### 3.3.3 并发安全保障
- **点赞/收藏幂等**：`UNIQUE KEY` + DuplicateKeyException → 取消操作
- **原子计数器**：`setSql("col = col +/- 1")` 避免 lost update
- **曝光原子预占（Recommend）**：Redis Lua 脚本 `SISMEMBER + SADD`，单线程原子执行
- **自操作过滤（Notify）**：`authorId == userId` 跳过通知写入

### 3.4 一致性方案

#### 3.4.1 最终一致性模型
- **事件驱动同步**：Post（事件源） → MQ → Feed/Rank/Location/Recommend/Notify（消费者）
- **事件路由**：

```
note.published  → Feed(fanout) + Rank(入榜) + Location(GEO) + Recommend(ES+热池)
note.deleted    → Feed(过滤) + Rank(下榜) + Location(清理) + Recommend(清理)
interaction.*   → Rank(加分) + Notify(通知) + Recommend(热分刷新)
```

#### 3.4.2 多副本一致性保障
- **MySQL → Redis**：Cache-Aside 模式，写操作后主动 DEL key
- **MySQL → ES**：MQ `note.published/deleted` 增量同步，定时 precompute 全量刷新
- **MySQL → Redis ZSet**：MQ 事件增量更新 + 10 分钟定时全量重建
- **逻辑删除传播**：`@TableLogic`(MySQL) + ES `filter(term("status", 1))` + GEO 查询后 status 过滤 + Feed `feed:deleted` Set

### 3.5 安全性方案

- **Gateway 统一 JWT 鉴权**：`JwtAuthFilter` 拦截 `/api/**`，白名单仅限 login/register/top10
- **X-User-Id 透传**：Gateway 解析 JWT 后注入 Header，下游信任网关
- **越权校验**：删除笔记检查 `authorId`，标记已读检查 `receiverId`
- **WebSocket 鉴权**：握手拦截器解析 Query 参数 `token`
- **直连保护**：前端统一走 Gateway `/api/*`，不暴露服务端口

### 3.6 可维护性方案

- **服务分层**：Controller → Service → Mapper，Entity/DTO/VO 分离
- **配置集中**：Nacos 服务发现 + 统一配置管理
- **幂等模式复用**：LikeService/FavoriteService 相同 UNIQUE KEY + `setSql` 模式
- **公式集中**：Rank `calculateScore` / Recommend 混合排序权重集中管理
- **常量管理**：榜单类型 `TYPES`、Redis Key 前缀、MQ Exchange/RoutingKey 集中定义
- **统一响应**：`Result<T>` + `ErrorCode` 枚举 + `GlobalExceptionHandler`
- **测试脚本独立可运行**：PowerShell 5.1+，不依赖 IDE

---

## 第四部分：测试评估（约 6-8 页）

### 4.1 测试体系总览 (1页)

- **测试分层**：
  - 冒烟测试：服务可达性（直连 + Gateway）
  - 单服务功能测试：PowerShell 验证脚本（15-27 项/服务）
  - 一致性测试：跨存储（MySQL/Redis/ES）数据一致性
  - 可靠性测试：DLQ、缓存自愈、降级、幂等
  - 性能测试：curl 采样 + JMeter 并发压测
- **测试编号体系**：P-(Post)、F-(Feed/Rank)、L-(Location)、N-(Notify)、R-(Recommend)、E2E-(端到端)

### 4.2 性能测试结果 (2页)

#### 4.2.1 单接口响应时间（curl 采样基线）

| 服务 | 接口 | P95 | 目标 | 达成率 |
|------|------|-----|------|--------|
| Post | 笔记详情(GET /post/{id}) | **33ms** | <300ms | 11% |
| Post | ES 搜索(GET /post/search) | **67ms** | <800ms | 8% |
| Post | 点赞切换(POST /post/{id}/like) | **45ms** | <300ms | 15% |
| Feed | Feed 流(GET /feed/timeline) | avg **47ms** | <300ms | 16% |
| Rank | Top10(GET /rank/top10) | avg **85ms** | <100ms | 85% |
| Location | 附近查询(GET /location/nearby) | avg **14ms** | <300ms | 5% |
| Location | POI 搜索(缓存命中) | **3ms** | — | 49x 加速 |
| Notify | 通知列表(GET /notify/list) | **32ms** | <300ms | 11% |
| Notify | 未读数(GET /notify/unread-count) | **10ms** | <100ms | 10% |
| Recommend | 冷启动推荐 | avg **190ms** | <600ms | 32% |
| Recommend | 个性化推荐 | avg **208ms** | <600ms | 35% |
| Recommend | 标签召回 | avg **208ms** | <600ms | 35% |

#### 4.2.2 JMeter 并发压测结果

| 服务 | 线程配置 | 总样本 | 错误率 | 吞吐量 | 关键指标 |
|------|----------|--------|--------|--------|----------|
| **Post** | 200×4组(峰值800) | 17,401 | **0.02%** | 315/s | ES搜索P50=863ms最稳定 |
| **Feed** | 50×200 | 10,000 | **0%** | **838/s** | P99=22ms |
| **Rank** | 100×5 | 1,000 | **0%** | 128/s | P95=837ms |
| **Location** | 20×50 | 5,000 | **0%** | **910/s** | 平均2ms |
| **Notify** | 10×20 | 600 | **0%** | — | P95<10ms |
| **Recommend** | 已准备 | — | — | — | 脚本就绪 |

### 4.3 一致性测试结果 (1页)

| 编号 | 测试项 | 结果 |
|------|--------|------|
| PC-1 | 发布事务原子性（note+note_image 同事务） | **通过** |
| PC-2 | Cache-Aside 缓存一致性（冷39ms→热29ms） | **通过** |
| PC-3 | ES 搜索一致性（MQ 同步延迟<1.5s） | **通过** |
| PC-4 | ES 降级（不可用时返回空列表） | **通过** |
| PC-5 | 逻辑删除过滤（status=0 不可见） | **通过** |
| FC-1 | Fanout 写入一致性（粉丝 inbox 含新笔记） | **通过** |
| RC-1 | 发布入榜一致性（note.published→rank ZSet） | **通过** |
| RC-2 | 互动刷新一致性（点赞+3/收藏+5/评论+4） | **通过** |
| LC-1 | GEO 写入一致性（发布→MQ→GEO→附近查到） | **通过** |
| LC-2 | GEO 清理一致性（删除→ZREM） | **通过** |
| NC-1 | 通知写入一致性（互动→notification 记录） | **通过** |
| NC-5 | 幂等去重（5 分钟窗口） | **通过** |
| E2E-1 | 发布→Feed 全链路 | **通过** |
| E2E-11 | 点赞→榜单+通知+推荐三服务联动 | **通过** |

### 4.4 可靠性测试结果 (1页)

| 编号 | 测试项 | 结果 |
|------|--------|------|
| P-8 | ES 降级（搜索不可用返空列表，不报500） | **通过** |
| P-11 | 点赞幂等（5 次 toggle，delta=1） | **通过** |
| PC-4 | afterCommit 事务回滚不发脏事件 | **通过** |
| F-6 | Feed Redis 不可用降级 MySQL | **通过** |
| F-7 | Rank 缓存为空自动重建（ensureCache） | **通过** |
| F-8 | Rank 定时重建 + 队列消费无积压 | **通过** |
| N-5 | Notify 手动 Ack + DLQ（异常消息进死信） | **通过** |
| N-6 | Notify 幂等去重（5 分钟窗口） | **通过** |
| L-9 | Location 5 次重试等 Post 事务提交 | **通过** |
| R-10 | Recommend 三级降级链（rank→hot→MySQL） | **通过** |

### 4.5 测试亮点总结 (1页)

- **800 并发零崩溃**：Post Service 200线程×4组，17401样本仅4次错误(0.02%)，系统稳定
- **全链路延迟 < 500ms**：Post 发布 → MQ → Feed/Rank/Location/Notify 消费，首次轮询即命中
- **缓存加速效果显著**：Post 详情 P95=33ms（目标 11%），Location 14ms（目标 5%），Notify 10ms（目标 10%）
- **降级全覆盖**：每个服务的每个外部依赖（ES/Redis/MySQL）均有 fallback 路径
- **幂等零脏数据**：点赞 UNIQUE KEY + Notify 5分钟去重窗口 + Recommend Lua 原子预占
- **22 项全通过**：Post Service 全量验证脚本 22 项检查 0 失败

### 4.6 已知不足与改进方向 (0.5页)

| 问题 | 涉及服务 | 改进方向 |
|------|----------|----------|
| Feed/Rank 消费端无 DLQ（仅 Notify 有） | Feed、Rank | 补充死信队列 |
| Feed fanout 逐粉丝 ZADD 非事务 | Feed | Pipeline 批量 + 异常补偿 |
| Feign 调用无断路器 | Recommend | 引入 Sentinel/CircuitBreaker |
| JMeter 压测结果与单请求基线差距大 | Post | MySQL 连接池调优 |

---

## 附录：建议的 PPT 页数分配

| 部分 | 页数 | 说明 |
|------|------|------|
| 封面 + 目录 | 2 | 项目名称、成员、目录 |
| 软件功能 | 6-8 | 架构图 1p + 核心功能 4p + 前端 1p |
| 问题分析 | 4-5 | 每类挑战 1p，总览 1p |
| 技术方案 | 8-10 | 性能 2p + 可用性 2p + 可靠性 2p + 一致性 1p + 安全+可维护 1p |
| 测试评估 | 6-8 | 测试体系 1p + 性能 2p + 一致性 1p + 可靠性 1p + 亮点总结 1p |
| 总结与展望 | 1-2 | 项目收获、改进方向 |
| **合计** | **28-35** | 可根据答辩时间弹性调整 |
