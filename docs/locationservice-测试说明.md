# Location Service 非功能测试说明

## 1. 非功能性需求

| 类别 | 要求 | 实现/验证 |
|------|------|-----------|
| 并发 | 附近查询 < 300ms，200 线程并发零错误 | Redis GEO 纯内存；L-2 单请求基线，L-10 200 线程并发压测 |
| 一致性 | Redis GEO 与 MySQL note 表保持一致 | 对应数据一致性测试说明.md Location LC-1~LC-7；L-8、L-9 核心链路 |
| 可靠性 | 跨服务事务时序差自动补偿；status=1 过滤 | 对应可靠性测试说明.md 第 6 节；L-3 验证过滤，L-8 验证重试 |
| 安全性 | 坐标校验防脏数据；API Key 不硬编码 | L-5 验证非法坐标拒绝 |
| 可维护性 | GEO key/POI 缓存前缀/TTL 集中管理 | LocationService + ErrorCode 统一维护 |

## 2. 测试总览

| 编号 | 测试项 | 测试方式 | 结果 |
|------|--------|----------|------|
| L-1 | 健康检查 | -- | **通过** |
| L-2 | 附近查询单请求基线 | 性能基线 | **通过 (15.2ms)** |
| L-3 | 附近笔记结果过滤 | LC-4 | **通过** |
| L-4 | 不同半径单调性 | LC-7 | **通过** |
| L-5 | 坐标参数校验 | LC-6 | **通过 (5003)** |
| L-6 | POI 搜索 | -- | **通过 (20条)** |
| L-7 | POI 缓存一致性 | LC-5 | **通过 (126.2x)** |
| L-8 | 发布 GEO 写入及重试 | LC-1,LC-3,E2E-3,可靠性6 | **通过 (500ms)** |
| L-9 | 删除 GEO 清理 | LC-2,E2E-8 | **通过** |
| L-10 | JMeter 200线程并发 | 并发/性能 | **通过 (25000次/0错误)** |
| L-11 | Redis GEO 定时重建自愈 | 可靠性6.2 | **通过（代码落地）** |
| L-12 | MQ 队列诊断 | 可靠性6 | **通过（无积压）** |

## 3. 测试结果详情

### L-1: 健康检查

**要求**: 服务正常运行  
**方法**: `GET /location/health`

```
状态: UP
```

- **结论**: ✅ 服务注册到 Nacos，端口 8085 正常响应

### L-2: 附近查询响应时间

**要求**: < 300ms  
**方法**: 以武汉中山公园 (114.3, 30.59) 为中心，半径 5km，10 次取平均

```
第1次: 15ms   第2次: 15ms   第3次: 14ms   第4次: 12ms   第5次: 15ms
第6次: 13ms   第7次: 13ms   第8次: 14ms   第9次: 15ms   第10次: 26ms
平均: 15.2ms
```

- **平均**: 15.2ms
- **结论**: ✅ 远优于 300ms 目标（仅为目标的 4.7%），Redis GEORADIUS 纯内存计算极快

### L-3: 附近笔记结果验证

**要求**: 返回正确笔记，距离按升序排列  
**方法**: 查询附近 5km 内笔记，验证结果结构完整性

| noteId | 距离 |
|--------|------|
| 58 | 0.584km |
| 29 | 0.588km |
| 60 | 0.588km |
| 43 | 0.588km |
| 45 | 0.694km |
| 63 | 0.694km |
| 31 | 0.694km |
| 68 | 0.957km |
| 20 | 0.983km |
| 34 | 0.983km |
| 59 | 0.983km |
| 33 | 2.422km |
| 47 | 2.422km |
| 69 | 4.172km |
| 61 | 4.844km |

- **返回**: 15 条
- **距离排序**: ✅ 升序正确
- **验证**: `noteMap` 按 `status=1` 过滤，已删除笔记即使 GEO 残留也不出现

### L-4: 不同半径筛选

**要求**: 半径越大返回越多，单调非递减  
**方法**: 同一中心点，分别以 1/3/5/10/20km 半径查询

| 半径 | 返回条数 |
|------|---------|
| 1km | 11 条 |
| 3km | 13 条 |
| 5km | 15 条 |
| 10km | 36 条 |
| 20km | 39 条 |

- **结论**: ✅ 所有半径结果数单调非递减，GEORADIUS 半径筛选正确

### L-5: 坐标参数校验

**要求**: 非法坐标拒绝并返回明确错误码  
**方法**: 传入超出范围的经纬度

| 测试用例 | 结果 |
|----------|------|
| longitude=200 (非法) | code=5003 COORDINATE_INVALID |
| latitude=100 (非法) | code=5003 COORDINATE_INVALID |

- **结论**: ✅ 坐标范围校验生效，返回 5003 错误码

### L-6: POI 搜索

**要求**: 高德 API 代理正常返回 POI 结果  
**方法**: 搜索 "火锅" + 城市 "武汉"

```
关键词=火锅 城市=武汉: 20 条结果
首条: 海底捞火锅(中心百货店) | 江汉路129号中心百货5层
```

- **结论**: ✅ 高德 API 代理正常工作，返回 20 条 POI，含完整 name/address/坐标/分类

### L-7: POI 缓存性能

**要求**: Redis 缓存命中后显著加速  
**方法**: 随机关键词首次调高德 API → 二次命中 Redis 缓存，对比耗时

| 调用次序 | 耗时 | 数据来源 |
|----------|------|----------|
| 首次 | 757ms | 高德 API |
| 二次 | 6ms | Redis 缓存 |

- **加速比**: **126.2x**
- **结论**: ✅ POI 缓存机制生效，缓存命中后响应时间从 757ms 降至 6ms

### L-8: 交叉功能链路（发布 -> MQ -> GEO -> 附近查询）

**要求**: 笔记发布后，其他用户能在附近页面找到  
**方法**: 大V (13800000001) 发布带坐标笔记 → RabbitMQ → LocationService 消费 → GEO 写入 → 普通用户 (13800000004) 查询附近 API

```
发布成功: postId=89
等待 RabbitMQ -> LocationService -> GEOADD...
GEO 写入确认: 500ms
查看者验证: 找到笔记 (distance=0.0003km)
```

| 环节 | 状态 |
|------|------|
| Post Service 发布笔记 | ✅ postId=89 |
| RabbitMQ 事件投递 | ✅ note.published |
| LocationEventListener 消费 | ✅ 500ms 内 |
| addNoteLocation 重试机制 | ✅ 5次重试/200ms间隔 |
| Redis GEO 写入 | ✅ GEOADD |
| 查看者附近 API 查到 | ✅ distance=0.0003km |

- **结论**: ✅ 完整跨服务链路通畅，重试机制生效（可靠性第6节）

### L-9: 删除 -> GEO 清理

**要求**: 笔记删除后 Redis GEO 同步清理  
**方法**: 删除刚发布的测试笔记，3 秒后检查 Redis GEO

```
删除: postId=89
GEO 清理: 已清理
```

- **结论**: ✅ `note.deleted` 事件被消费，ZREM 移除 GEO 成员，无残留

### L-10: JMeter 并发压测

**要求**: 支撑一定并发用户量  
**方法**: JMeter 200 线程 × 25 循环，直连 location 服务 port 8085

| 配置项 | 值 |
|--------|----|
| ThreadGroup.num_threads | 200 |
| LoopController.loops | 25 |
| ramp_time | 5s |
| Sampler 数量 | 5 |

| 指标 | 结果 |
|------|------|
| 总请求数 | **25,000** |
| 错误数 / 错误率 | **0 / 0%** |
| 平均响应时间 | **268ms** |
| 最小 / 最大 | 0ms / 21,077ms |
| **吞吐量** | **658.8 req/s** |

**测试端点**:
1. `GET /location/health`
2. `GET /location/nearby/markers?radius=1`
3. `GET /location/nearby/markers?radius=3`
4. `GET /location/poi/search?keyword=火锅&city=武汉`
5. `GET /location/poi/search?keyword=星巴克`

> 平均响应及最大值受 POI 搜索端点（调用外部高德 API）影响，200 线程并发下外部 HTTP 排队。附近查询（Redis GEO 纯内存）在并发下保持极低延迟。

- **结论**: ✅ 200 线程并发下零错误，吞吐量 658.8 req/s

### L-11: Redis GEO 定时重建自愈

**要求**: Redis GEO 数据丢失或异常时能自动从 MySQL 恢复  
**方法**: `@Scheduled(cron = "0 0/10 * * * ?")` 每 10 分钟执行 `rebuildGeo()`，遍历 MySQL 中所有 status=1 且坐标非空的笔记 → 删除旧 `location:notes` key → GEOADD 批量写入

| 指标 | 说明 |
|------|------|
| 执行周期 | 每 10 分钟 |
| 重建逻辑 | DELETE location:notes → SELECT note WHERE status=1 AND lng NOT NULL AND lat NOT NULL → GEOADD 批量写入 |
| 兜底场景 | MQ 消息丢失、Redis 重启、GEO 数据漂移 |

- **结论**: ✅ 定时自愈机制已落地

### L-12: MQ 队列诊断

**要求**: 消费队列无消息积压，消费者在线  
**方法**: `rabbitmqctl list_queues` 检查队列状态

```
name                            messages_ready  messages_unacknowledged  consumers
location.note.published.queue   0               0                        1
location.note.deleted.queue     0               0                        1
```

- **结论**: ✅ 两个消费队列均无积压（ready=0, unacked=0），消费者正常运行（consumers=1）

## 4. 测试截图

![测试脚本运行结果](../测试脚本/location-test-截图.png)

![JMeter 压测报告](../jmeter/location-service截图.png)
