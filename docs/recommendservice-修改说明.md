# Recommend Service 修改说明

## 1. 服务定位

本次完成的是组员 3 负责的 `Recommend Service`，服务端口为 `8084`。该服务负责 BiteBlog 平台发现页推荐能力，核心目标是根据用户历史行为、标签兴趣、相似用户行为和内容热度返回个性化笔记列表，并提供曝光去重与冷启动兜底。

当前实现是一个可联调版本：标签召回暂时使用 MySQL 关键词兜底，ItemCF 使用 `user_behavior` 做简化协同过滤。后续如果 ES `post_index` 和 `item_sim_index` 数据稳定，可以把召回源替换为 ES，Controller 和返回结构不需要调整。

## 2. 本次新增/修改文件

| 文件 | 说明 |
|---|---|
| `biteblog-backend/biteblog-recommend/pom.xml` | 补充 Web、MyBatis-Plus、MySQL、Redis、Elasticsearch、RabbitMQ、OpenFeign 等依赖。 |
| `RecommendApplication.java` | Recommend 服务启动类，启用 Nacos 服务发现、Mapper 扫描，并扫描 `com.biteblog.common` 以加载公共 Redis 配置。 |
| `entity/Note.java` | 映射数据库 `note` 表，用于读取笔记基础信息、热度计数和状态。 |
| `entity/NoteImage.java` | 映射数据库 `note_image` 表，用于读取笔记封面图。 |
| `entity/UserBehavior.java` | 映射数据库 `user_behavior` 表，用于判断用户行为数量和简化 ItemCF。 |
| `mapper/NoteMapper.java` | MyBatis-Plus Mapper，用于查询笔记数据。 |
| `mapper/NoteImageMapper.java` | MyBatis-Plus Mapper，用于查询封面图数据。 |
| `mapper/UserBehaviorMapper.java` | MyBatis-Plus Mapper，用于查询用户行为数据。 |
| `dto/RecommendItemVO.java` | 推荐列表单项返回对象。 |
| `dto/RecommendResponse.java` | 推荐列表响应对象，包含 `list`、`cursor`、`hasMore`。 |
| `dto/ExposureRequest.java` | 曝光上报请求对象，接收 `postIds`。 |
| `service/RecommendDataService.java` | 推荐服务数据访问封装，包括热门笔记、标签搜索、行为查询、封面补齐等。 |
| `service/RecommendService.java` | 推荐核心业务逻辑，包括冷启动、曝光过滤、标签召回、简化 ItemCF、混合排序和降级。 |
| `controller/RecommendController.java` | 暴露 `/recommend/discover`、`/recommend/exposures`、`/recommend/health` 接口。 |
| `sql/init-recommend-data.ps1` | Recommend 服务测试数据初始化脚本。 |
| `jmeter/recommend-service-test.jmx` | Recommend 服务 JMeter 压测脚本。 |

## 3. 接口设计

### 3.1 健康检查

```http
GET /recommend/health
```

用于确认 Recommend 服务是否已经启动成功。

### 3.2 发现页推荐

```http
GET /recommend/discover?cursor=0&size=20&tag=Hotpot&city=Guangzhou
X-User-Id: 1001
```

参数说明：

- `cursor`：分页游标，首次请求可传 `0` 或不传；
- `size`：每页条数，最大限制为 `50`；
- `tag`：可选标签关键词；
- `city`：可选城市关键词；
- `X-User-Id`：当前用户 ID，直连服务时必须传入；通过网关访问时由网关透传。

返回结果包含 `list`、`cursor` 和 `hasMore`。其中 `list` 中每一项包含笔记 ID、作者 ID、标题、封面图、店铺名、互动计数、推荐分、推荐原因和发布时间。

### 3.3 曝光上报

```http
POST /recommend/exposures
X-User-Id: 1001
Content-Type: application/json

{
  "postIds": [101, 102, 103]
}
```

用于记录用户已经看过的笔记。服务会写入 Redis Set：

```text
exposure:{userId}
```

TTL 为 7 天，重复上报不报错。

## 4. 核心业务逻辑

Recommend 服务当前采用“行为判断 + 多路召回 + 混合排序 + 热度兜底”的流程：

```text
读取用户行为数量和曝光集合
-> 行为不足且没有标签过滤：走冷启动
-> 行为充足：标签召回 + 简化 ItemCF 召回
-> 候选不足：用热门正常笔记补齐
-> 计算 finalScore 并排序
-> 补齐封面图和推荐原因
-> 返回分页结果
```

冷启动优先读取 Redis ZSet：

```text
recommend:hot:pool
```

如果 Redis 热度池为空或不可用，则降级查询 MySQL `note` 表中的正常笔记。

## 5. 推荐分数与混合排序

冷启动热度分数：

```text
hotScore = likeCount * 3 + collectCount * 5 + commentCount * 4 + qualityScore + freshBoost
qualityScore = (scoreTaste + scoreSmell + scoreColor) / 3
freshBoost = 24 / sqrt(hoursSinceCreated)
```

老用户混合排序：

```text
finalScore = tagScore * 0.6 + itemCfScore * 0.4 + hotScore
```

ItemCF 行为权重：

| 行为 | 权重 |
|---|---:|
| view | 1 |
| dwell | 3 |
| like | 5 |
| collect / favorite | 8 |
| comment | 10 |

如果 `user_behavior.weight` 有正数，则优先使用表内权重。

## 6. 曝光去重与降级

推荐返回前会读取 Redis Set：

```text
exposure:{userId}
```

已曝光笔记会从候选集中排除。Redis 不可用时，服务记录 warn 日志并跳过曝光过滤，保证推荐接口不因为 Redis 异常直接失败。

当前版本没有强依赖 ES：标签召回先使用 MySQL `title/content/shopName/address` 做兜底，因此 ES 不可用时推荐接口仍可返回冷启动或热门补齐结果。

## 7. 非功能需求处理

### 7.1 性能

分页大小最大限制为 50，避免单次请求过大。冷启动优先读取 Redis ZSet，曝光过滤使用 Redis Set，常见路径不需要复杂全表扫描。

### 7.2 可用性

Redis 热度池不可用时自动降级 MySQL 热门笔记；ES 当前不是强依赖；候选不足时自动使用热门正常笔记补齐。

### 7.3 安全性

通过网关访问时依赖 JWT 鉴权，服务内接口通过 `X-User-Id` 获取当前用户。分页参数做了边界控制，曝光上报最多处理前 100 个 `postId`。

### 7.4 一致性

曝光记录使用 Redis Set，重复上报保持幂等；TTL 为 7 天，避免曝光集合无限增长。

### 7.5 可维护性

推荐入口集中在 `RecommendService`，数据库读取集中在 `RecommendDataService`。后续接入 ES 时，可以替换标签召回和 ItemCF 召回来源，不影响 Controller 和 DTO。

## 8. 本地测试环境说明

本地测试依赖 MySQL、Redis、RabbitMQ、Nacos、Elasticsearch。Recommend 服务使用 `localhost:3306` 连接数据库 `biteblog`，MySQL 密码为 `root123456`，Redis 密码为 `redis123456`。

测试数据通过以下脚本初始化：

```powershell
.\sql\init-recommend-data.ps1
```

该脚本会创建推荐演示用户、推荐候选笔记、用户行为数据、Redis 热度池和曝光集合。

## 9. 本地接口验证结果

已完成本地接口验证。测试前启动 Docker 中间件并执行 `sql/init-recommend-data.ps1`。

测试结果如下：

| 接口 | 测试结果 | 说明 |
|---|---|---|
| GET `/recommend/health` | 通过 | 返回 `status=UP` |
| GET `/recommend/discover?cursor=0&size=3` | 通过 | 新用户返回 3 条冷启动推荐数据 |
| GET `/recommend/discover?cursor=0&size=3&tag=Hotpot&city=Guangzhou` | 通过 | 返回推荐列表，支持标签和城市参数 |
| POST `/recommend/exposures` | 通过 | 返回 `saved=true`，Redis 写入 `exposure:1` |
| Redis `SMEMBERS exposure:1` | 通过 | 可看到曝光笔记 ID |
| Redis `TTL exposure:1` | 通过 | TTL 大于 0，接近 7 天 |

另外，集成测试发现并修复了两个启动问题：

1. Recommend 服务需要扫描 `com.biteblog.common`，否则启动时找不到公共 `RedisTemplate`；
2. Rank 服务 MySQL 密码需要与 `docker-compose.yml` 保持一致，统一为 `root123456`。
