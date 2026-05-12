# Location Service 测试说明

## 1. 测试范围

本文件记录 Location Service 的命令行测试方法。JMeter 压测脚本单独保存在 jmeter/location-service-test.jmx。

测试目标：

1. 附近笔记查询响应时间是否 < 300ms
2. 不同半径下的结果数量是否正确
3. 距离排序是否准确
4. 坐标参数校验是否生效
5. POI 搜索是否可以正常返回结果
6. POI 缓存是否命中

## 2. 启动前准备

Docker 中间件：

```bash
docker compose up -d
docker compose ps
```

启动 Location Service：

```bash
cd biteblog-backend
mvn -pl biteblog-location -am spring-boot:run
```

## 3. 初始化测试数据

方式一：PowerShell 脚本（需 user-service 和 post-service 已启动）

```powershell
cd sql
.\init-location-data.ps1
```

方式二：手动插入 Redis GEO

```bash
docker exec biteblog-redis redis-cli -a redis123456 GEOADD location:notes 114.305 30.593 1 114.310 30.588 2
```

## 4. 命令行接口测试

### 4.1 附近查询响应时间

预期：平均 < 300ms，实测 15-35ms。

### 4.2 不同半径测试

预期：1km=3条, 3km=3条, 5km=4条, 10km=4条, 50km=5条。

### 4.3 坐标参数校验

非法经度 -> code=5003，非法纬度 -> code=5003，缺参数 -> code=500。

### 4.4 POI 搜索

返回 POI 列表，含 name, address, longitude, latitude, type 字段。

### 4.5 POI 缓存性能

首次 ~566ms（高德API），缓存命中 15-22ms（Redis）。

### 4.6 Redis GEO 验证

docker exec biteblog-redis redis-cli -a redis123456 ZRANGE location:notes 0 -1 WITHCOORDS
docker exec biteblog-redis redis-cli -a redis123456 ZCARD location:notes

## 5. JMeter 测试

JMeter 脚本：jmeter/location-service-test.jmx

执行命令：
  jmeter -n -t jmeter/location-service-test.jmx -l jmeter/location-service-result.jtl -e -o jmeter/locationservice-report

测试内容：
1. GET /api/location/nearby/markers?longitude=114.3&latitude=30.5&radius=5
2. GET /api/location/poi/search?keyword=火锅&city=北京

非功能目标：

| 指标 | 目标 | 实测 |
|------|------|------|
| 附近查询响应 | < 300ms | 14-35ms |
| 错误率 | 0% | 0% |
| POI 首次 | 正常 | ~566ms |
| POI 缓存 | 提速 | 15-22ms |
| 坐标校验 | 非法拒绝 | code=5003 |

## 6. 容错降级验证

停 Redis 后请求附近查询，应返回 500 而非服务崩溃。
Redis 恢复后服务自动恢复。
