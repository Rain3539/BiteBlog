# Rank Service 测试说明

## 1. 测试范围

本文件记录 Rank Service 的命令行测试方法。JMeter 压测脚本单独保存在：

```text
jmeter/rank-service-test.jmx
```

测试目标包括：

1. 服务是否可以正常启动；
2. `/rank/health` 是否返回正常；
3. `/rank/rebuild` 是否可以从 MySQL 重建 Redis 榜单；
4. `/rank/top10` 是否可以返回排行榜；
5. `/rank/list` 是否支持分页查询；
6. 点赞、收藏、评论事件发生后，排行榜分数是否会上升；
7. 删除笔记后，该笔记是否从排行榜中移除。

## 2. 启动前准备

需要先启动基础服务：MySQL、Redis、RabbitMQ、Nacos。然后启动以下微服务：

```bash
cd biteblog-backend

mvn -pl biteblog-user -am spring-boot:run
mvn -pl biteblog-post -am spring-boot:run
mvn -pl biteblog-rank -am spring-boot:run
```

如果通过网关测试，还需要启动：

```bash
mvn -pl biteblog-gateway -am spring-boot:run
```

## 3. 初始化测试数据

在项目根目录执行：

```powershell
cd sql
.\init-rank-data.ps1
```

该脚本会创建测试用户、发布多条测试笔记、制造点赞/收藏/评论行为，并调用 Rank 服务重建排行榜。

## 4. 命令行接口测试

### 4.1 健康检查

直接访问 Rank 服务：

```bash
curl -s http://localhost:8086/rank/health
```

通过 Gateway 访问：

```bash
curl -s http://localhost:8080/api/rank/health
```

预期结果：`code=200`，`data.status=UP`。

### 4.2 手动重建日榜

```bash
curl -X POST "http://localhost:8086/rank/rebuild?type=daily"
```

预期结果：

```json
{"code":200,"msg":"success","data":{"rebuilt":true,"type":"daily"}}
```

### 4.3 查询 Top10

```bash
curl -s "http://localhost:8086/rank/top10?type=daily"
```

预期结果：返回 `list` 数组，数组内包含 `rankNo`、`postId`、`title`、`hotScore` 等字段。`hotScore` 较高的笔记排在前面。

### 4.4 查询分页榜单

```bash
curl -s "http://localhost:8086/rank/list?type=weekly&page=1&size=5"
```

预期结果：返回前 5 条周榜数据，`page=1`，`size=5`。

### 4.5 Redis 数据检查

```bash
redis-cli -a redis123456 ZREVRANGE rank:hot:daily 0 9 WITHSCORES
redis-cli -a redis123456 ZREVRANGE rank:hot:weekly 0 9 WITHSCORES
redis-cli -a redis123456 ZREVRANGE rank:hot:all 0 9 WITHSCORES
```

预期结果：Redis 中可以看到笔记 ID 与对应热度分数。

## 5. JMeter 测试

JMeter 脚本路径：

```text
jmeter/rank-service-test.jmx
```

建议执行方式：

```bash
jmeter -n -t jmeter/rank-service-test.jmx -l jmeter/rank-service-result.jtl -e -o jmeter/rankservice-report
```

测试内容包括：

1. `GET /api/rank/health`；
2. `POST /api/rank/rebuild?type=daily`；
3. `GET /api/rank/top10?type=daily`；
4. `GET /api/rank/list?type=weekly&page=1&size=10`。

非功能目标建议：平均响应时间低于 300ms，错误率为 0%。因为排行榜查询主要访问 Redis，所以在测试数据量不大的情况下，响应时间应明显低于直接 SQL 排序。

