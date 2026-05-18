# Location Service 修改说明

## 1. 服务定位

Location Service（端口 8085）是 BiteBlog 探店平台的位置服务模块，由成员三负责。核心能力：

- **附近探店**：基于 Redis GEO 空间索引，按距离查询附近笔记，支持不同半径筛选
- **POI 搜索**：代理高德地图 API，支持关键词+城市搜索地点，Redis 缓存加速
- **坐标异步写入**：监听 RabbitMQ 笔记发布事件，异步写入 Redis GEO
- **坐标清理**：监听笔记删除事件，同步清理 GEO 僵尸数据

## 2. 新增接口说明

| 接口 | 方法 | 路径 | 认证 | 说明 |
|------|------|------|------|------|
| 健康检查 | GET | `/location/health` | 否 | 返回 service 名和 status |
| 附近探店 | GET | `/location/nearby/markers` | 是 | 按坐标+半径返回附近笔记 |
| POI 搜索 | GET | `/location/poi/search` | 是 | 高德 POI 搜索代理 |

### 2.1 健康检查

```
GET /location/health
→ {"code":200,"data":{"service":"location-service","status":"UP"}}
```

### 2.2 附近探店笔记

```
GET /location/nearby/markers?longitude=114.3&latitude=30.59&radius=5
```

参数：

| 参数 | 类型 | 必填 | 范围 | 默认值 |
|------|------|------|------|--------|
| longitude | Double | 是 | [-180, 180] | — |
| latitude | Double | 是 | [-90, 90] | — |
| radius | int | 否 | — | 3 (km) |

返回：`markers` 数组，每项含 `noteId`, `authorId`, `title`, `shopName`, `longitude`, `latitude`, `distance`(km)。最多 50 条，按距离升序。

### 2.3 POI 搜索

```
GET /location/poi/search?keyword=火锅&city=武汉
```

参数：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| keyword | String | 是 | 搜索关键词 |
| city | String | 否 | 城市名，不填则全国范围 |

返回：`list` 数组，每项含 `id`, `name`, `address`, `longitude`, `latitude`, `type`。

### 2.4 错误码

| 错误码 | 含义 | 触发条件 |
|--------|------|----------|
| 5001 | LOCATION_ERROR | 地理位置获取失败 |
| 5002 | POI_SEARCH_FAIL | 高德 API 调用失败或返回异常 |
| 5003 | COORDINATE_INVALID | 经度或纬度超出合法范围 |

## 3. 业务功能设计

### 3.1 附近探店查询

```
Redis GEO (location:notes)
  │ GEORADIUS 查询
  ▼
noteId 列表 + 距离
  │ selectBatchIds
  ▼
MySQL note 表（标题、店铺名、坐标）
  │ 过滤 status != 1
  ▼
组装 NearbyMarkerVO 返回
```

- Redis GEO key: `location:notes`，成员为 noteId 字符串，坐标为 WGS84 Point
- GEORADIUS 纯内存计算，O(N+logM) 复杂度
- 返回后批量查 MySQL 补充标题/店铺名等详情
- 已删除笔记（status≠1）自动过滤

### 3.2 POI 搜索代理

```
请求 → Redis 缓存查 (key=location:poi:{keyword}:{city})
  │ 未命中
  ▼
WebClient → 高德 /v3/place/text API
  │ 解析 JSON pois 数组
  ▼
写 Redis 缓存 (TTL=1h) → 返回
```

- 缓存命中直接返回，避免重复调用高德 API（实测 25x 加速）
- 高德 API Key 配置在 application.yml，不硬编码
- 使用 UriComponentsBuilder 自动 URL 编码中文参数

### 3.3 坐标异步写入

```
Post Service 发布笔记（含坐标）
  → MySQL 写入 note.longitude, note.latitude
  → RabbitMQ 发送 "note.published" {noteId, authorId, timestamp}
    → LocationEventListener.onNotePublished()
      → LocationService.addNoteLocation()
        → MySQL 查询 Note by noteId
        → 坐标非空: GEOADD location:notes
        → 坐标为空: 跳过（用户拒绝位置授权）
```

### 3.4 笔记删除清理

```
Post Service 删除笔记
  → MySQL 软删除 note (status=0)
  → RabbitMQ 发送 "note.deleted" {noteId, timestamp}
    → LocationEventListener.onNoteDeleted()
      → LocationService.removeNoteLocation()
        → ZREM location:notes noteId
```

### 3.5 RabbitMQ 架构

| 组件 | 配置 |
|------|------|
| 交换机 | `biteblog.post` (Topic, durable) |
| 队列 | `location.note.published.queue`, `location.note.deleted.queue` (durable) |
| 路由键 | `note.published`, `note.deleted` |

多个服务（Feed、Rank、Location、Notify）共享同一交换机，通过独立队列和绑定消费同一事件，互不影响。

## 4. 非功能需求处理

### 4.1 性能

| 指标 | 目标 | 实测 | 实现方式 |
|------|------|------|----------|
| 附近查询响应 | < 300ms | **14-35ms** | Redis GEORADIUS 纯内存 |
| POI 首次查询 | — | ~566ms | WebClient + 高德 API |
| POI 缓存命中 | — | **15-22ms** | Redis key-value，TTL 1h |
| 附近查询并发 | 20 线程 | JMeter 400次 0错误 | 详见 JMeter 报告 |

### 4.2 可用性

- **坐标降级**：用户拒绝位置授权时，笔记坐标为空，消费端跳过 GEO 写入并记录日志，不影响发布主流程
- **高德 API 降级**：API 调用失败时返回 `POI_SEARCH_FAIL`，不抛出 5xx 导致网关熔断
- **Redis 不可用**：GEORADIUS 失败时由 GlobalExceptionHandler 统一兜底，返回 500 而非服务崩溃
- **POI 缓存保护**：缓存读取异常时捕获日志，继续调高德 API，不中断业务

### 4.3 一致性

- **最终一致性**：坐标写入通过 RabbitMQ 异步完成，延迟秒级。GEOADD 对同一 noteId 幂等，重复消费不会产生脏数据
- **删除一致性**：笔记删除后 GEO 异步清理。若消息丢失，GEO 中残留已删除笔记的坐标，但 nearbyMarkers 查询会通过 status 过滤掉
- **定时重建兜底**（可扩展）：可添加定时任务从 MySQL 全量重建 `location:notes`，消除长期积累的不一致

### 4.4 安全性

- 坐标参数做范围校验，非法值返回 `COORDINATE_INVALID` 而非 500
- 高德 API Key 配置在 application.yml，通过 `@Value` 注入，不硬编码
- 所有接口通过 Gateway JWT 鉴权（/health 除外）

### 4.5 可维护性

- GEO key、POI 缓存前缀、TTL 等常量集中在 LocationService 顶部
- RabbitMQ 配置独立在 LocationRabbitConfig，队列/交换机名用 public static final 常量
- 错误码统一在 common 模块 ErrorCode 枚举中管理
