# BiteBlog JMeter 并发压测结果汇总

> 测试环境：Docker Desktop 单节点中间件 + 7 个微服务同机运行，全部使用 Spring Boot 默认连接池（Tomcat=200, HikariCP=10, Lettuce=8）。

---

## 一、Post Service — 笔记服务

**配置**：4 组 ThreadGroup 并发，每组 200 线程，ramp-up 15s，峰值 800 并发。经 Gateway（localhost:8080），需 JWT 鉴权。

| 接口 | 接口功能 | 样本 | avg | P50 | P95 | P99 | Min | Max | 错误 |
|------|------|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| `GET /api/post/1` | 笔记详情：Redis Cache-Aside → MySQL | 5,000 | 2420ms | 2726ms | 3326ms | 3517ms | 23ms | 3890ms | 0 |
| `GET /api/post/search?keyword=烧烤` | ES 全文搜索：multiMatch 三字段 IK 分词 | 5,000 | 718ms | 863ms | 1599ms | 1694ms | 8ms | 1813ms | 0 |
| `POST /api/post/1/like` | 点赞切换：UNIQUE KEY + 原子计数 | 2,400 | 1087ms | 1194ms | 1986ms | 2091ms | 24ms | 2338ms | 4 (0.17%) |
| `GET /api/post/1/comments` | 评论分页：MyBatis-Plus 分页 | 5,000 | 1962ms | 2148ms | 2623ms | 2767ms | 22ms | 3252ms | 0 |

**分析**：笔记详情最慢（P50=2726ms），因为 Cache-Aside 冷热交替下频繁查 MySQL。ES 搜索不查 MySQL，绕开了 HikariCP 竞争，P50=863ms 相对较好。4 次点赞错误是并发 toggle 下的正常幂等冲突（DuplicateKeyException）。

---

## 二、Feed Service — Feed 流服务

**配置**：1000 线程 × 5 循环，ramp-up 5s，直连 localhost:8083。使用固定 `X-User-Id: 7`。

| 接口 | 接口功能 | 样本 | avg | P50 | P95 | P99 | Min | Max | 错误 |
|------|------|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| `GET /feed/timeline` | 关注 Feed 流：Redis ZSet inbox → MySQL 批量补笔记详情 | 5,000 | 184ms | 151ms | 947ms | 1904ms | 4ms | 2014ms | 0 |

**分析**：Min=4ms 证明查询本身只要 4ms。P50=151ms 是 1000 线程抢 HikariCP=10 + Lettuce=8 的排队代价。P99=1904ms 说明 1% 的请求等连接等了近 2 秒。吞吐量 1023 QPS 为六服务最高。实际峰值线程 553（请求太快，ramp-up 5s 无法让 1000 线程全部聚齐）。

---

## 三、Rank Service — 热度排行榜服务

**配置**：500 线程 × 25 循环，ramp-up 10s，直连 localhost:8086。health 和 rebuild 接口已禁用。

| 接口 | 接口功能 | 样本 | avg | P50 | P95 | P99 | Min | Max | 错误 |
|------|------|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| `GET /rank/top10?type=daily` | 日榜 Top10：zCard → ZREVRANGE WITHSCORES → MySQL 补详情 | 12,500 | 843ms | 938ms | 1051ms | 1070ms | 17ms | 1097ms | 0 |
| `GET /rank/list?type=weekly&page=1&size=10` | 周榜分页：同上流程 | 12,500 | 841ms | 934ms | 1051ms | 1070ms | 39ms | 1099ms | 0 |

**分析**：两个接口延迟几乎相同，瓶颈一致。每请求需要 2 次 Redis 操作（zCard + ZREVRANGE WITHSCORES）+ 1 次 MySQL。500 线程全部聚齐（峰值=500），500×2=1000 次 Redis 操作在单线程队列里串行排队，叠加 HikariCP=10 的 MySQL 连接竞争。Min=17ms 证明查询本身不慢。

---

## 四、Location Service — 位置服务

**配置**：200 线程 × 25 循环，ramp-up 5s，直连 localhost:8085。5 个 Sampler 串联执行。

| 接口 | 接口功能 | 样本 | avg | P50 | P95 | P99 | Min | Max | 错误 |
|------|------|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| `GET /location/health` | 健康检查 | 5,000 | 1ms | 1ms | 2ms | 4ms | 0ms | 55ms | 0 |
| `GET /location/nearby/markers?radius=1` | 附近笔记 1km：Redis GEORADIUS → MySQL 查 note → MySQL 查 note_image | 5,000 | 468ms | 509ms | 685ms | 799ms | 8ms | 1148ms | 0 |
| `GET /location/nearby/markers?radius=3` | 附近笔记 3km | 5,000 | 466ms | 510ms | 646ms | 791ms | 8ms | 1207ms | 0 |
| `GET /location/poi/search?keyword=星巴克` | POI 搜索：Redis 缓存命中 | 5,000 | 10ms | 2ms | 4ms | 189ms | 1ms | 21077ms | 0 |
| `GET /location/poi/search?keyword=火锅&city=武汉` | POI 搜索：部分缓存 miss → 高德 API | 5,000 | 398ms | 2ms | 6ms | 10157ms | 1ms | 10606ms | 0 |

**分析**：核心接口 nearby P50=509ms，Min=8ms 证明查询本身不慢——每次 nearby 要查询 2 次 MySQL（note 表 + note_image 表），且 5 个 Sampler 共享 HikariCP=10。POI 星巴克 P50=2ms 体现了 Redis 缓存（TTL 1h）的威力。POI 火锅 max=10606ms 是因为个别请求缓存过期穿透到高德 API，外部 HTTP 调用超时接近 10 秒。

---

## 五、Notify Service — 消息通知服务

**配置**：200 线程 × 25 循环，ramp-up 10s，经 Gateway（localhost:8080），需 JWT 鉴权。health 接口已禁用。

| 接口 | 接口功能 | 样本 | avg | P50 | P95 | P99 | Min | Max | 错误 |
|------|------|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| `GET /api/notify/list` | 通知列表：MyBatis-Plus 分页查 MySQL + Feign 调 User Service 补昵称 | 5,000 | 199ms | 210ms | 341ms | 385ms | 15ms | 719ms | 0 |
| `GET /api/notify/unread-count` | 未读数：Redis GET `notify:unread:{userId}` | 5,000 | 4ms | 4ms | 6ms | 9ms | 2ms | 48ms | 0 |

**分析**：同一 Gateway、同一 JWT，unread-count 只要 4ms（纯 Redis），list 要 199ms（MySQL + Feign）。差距 50 倍，清楚证明了瓶颈在业务链路复杂度（MySQL 分页 + 远程调用），不在框架或网络。实际峰值线程 117。

---

## 六、Recommend Service — 个性化推荐服务

**配置**：200 线程 × 10 循环，ramp-up 10s，直连 localhost:8084。5 个 Sampler 串联执行。

| 接口 | 接口功能 | 样本 | avg | P50 | P95 | P99 | Min | Max | 错误 |
|------|------|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| `GET /recommend/discover?size=20` | 默认推荐流：冷启动走 Rank 日榜 → MySQL 热门补量 → 曝光过滤 → 作者打散 | 2,000 | 202ms | 181ms | 392ms | 660ms | 17ms | 1009ms | 0 |
| `GET /recommend/discover?size=5` | 小分页推荐 | 2,000 | 189ms | 173ms | 331ms | 476ms | 13ms | 676ms | 0 |
| `GET /recommend/discover?cursor=20&size=20` | 翻页推荐 | 2,000 | 205ms | 179ms | 449ms | 601ms | 13ms | 767ms | 0 |
| `GET /recommend/discover?tag=Hotpot&city=Guangzhou` | 标签召回：ES multiMatch 搜索 → 补量 → MySQL 补详情 | 2,000 | 568ms | 567ms | 871ms | 1201ms | 47ms | 1285ms | 0 |
| `POST /recommend/exposures` | 曝光上报：Redis SADD `exposure:{userId}` | 2,000 | 15ms | 12ms | 37ms | 60ms | 3ms | 93ms | 0 |

**分析**：标签召回最重——多了一次 ES 全文检索，P50=567ms。默认/小分页/翻页 P50 均在 170~180ms，表现一致。exposures 纯 Redis SADD → 12ms，再次验证纯 Redis 操作的极低延迟。

---

## 总览对比

| 服务 | 配置 | 最快 Min | 核心 P50 | 数据源 | 瓶颈 |
|------|------|:---:|:---:|------|------|
| Feed | 1000线程×5 | 4ms | 151ms | 1 Redis + 1 MySQL | HikariCP 排队 |
| Rank | 500线程×25 | 17ms | 938ms | 2 Redis + 1 MySQL | Redis单线程 + HikariCP |
| Post 详情 | 4×200线程 | 23ms | 2726ms | Redis → MySQL | Gateway + 4组并发 + HikariCP |
| Post ES搜索 | 4×200线程 | 8ms | 863ms | ES | ES单节点排队 |
| Location nearby | 200线程×25 | 8ms | 509ms | 1 Redis + 2 MySQL | 两次MySQL + 多Sampler共享池 |
| Location POI缓存 | 200线程×25 | 1ms | 2ms | 纯 Redis | 无 |
| Notify list | 200线程×25 | 15ms | 210ms | MySQL + Feign | MySQL分页 + 远程调用 |
| Notify unread | 200线程×25 | 2ms | 4ms | 纯 Redis | 无 |
| Recommend 推荐 | 200线程×10 | 17ms | 181ms | Redis+ES+MySQL | 多路召回 |
| Recommend 标签 | 200线程×10 | 47ms | 567ms | Redis+ES+MySQL | ES检索最重 |
| Recommend 曝光 | 200线程×10 | 3ms | 12ms | 纯 Redis | 无 |

---

## 三条核心结论

1. **纯 Redis 接口全部 < 15ms**：health=1ms、unread-count=4ms、exposures=12ms、POI缓存=2ms。没有例外。

2. **Min 值揭示了真实查询能力**：所有服务的查询本身在 4~50ms 内就能完成。P50 和 Min 之间的差距就是连接池排队时间。

3. **HikariCP=10（默认）是全系统瓶颈**：每个请求需要 MySQL 的次数决定了它在高并发下的排队程度——Feed 1 次 MySQL → P50=151ms，Rank 1 次 MySQL + 多次 Redis → P50=938ms，Location 2 次 MySQL → P50=509ms。将 HikariCP 扩容到 200、Lettuce 扩容到 200 可消除大部分排队延迟。

---

> 测试时间：2026 年 5-6 月 | JMeter 5.6.3 | 全部结果详见 `jmeter/*-service-result.jtl`
