# Recommend Service 测试说明

## 1. 测试范围

本文件记录 Recommend Service 的命令行测试方法。JMeter 压测脚本单独保存在：

```text
jmeter/recommend-service-test.jmx
```

测试目标包括：

1. 服务是否可以正常启动；
2. `/recommend/health` 是否返回正常；
3. `/recommend/discover` 是否可以返回推荐列表；
4. 新用户或行为不足用户是否可以走冷启动兜底；
5. 标签参数和城市参数是否可以参与推荐；
6. `/recommend/exposures` 是否可以写入曝光记录；
7. Redis 中 `exposure:{userId}` 是否有 TTL；
8. Redis 热度池或 ES 不可用时，服务是否可以降级。

## 2. 启动前准备

需要先启动基础服务：MySQL、Redis、RabbitMQ、Nacos、Elasticsearch。

```powershell
docker compose up -d
docker compose ps
```

然后启动 Recommend 服务：

```bash
cd biteblog-backend
mvn -pl biteblog-recommend -am spring-boot:run
```

如果通过网关测试，还需要启动：

```bash
mvn -pl biteblog-gateway -am spring-boot:run
```

## 3. 初始化测试数据

在项目根目录执行：

```powershell
.\sql\init-recommend-data.ps1
```

该脚本会创建测试用户、测试笔记、用户行为数据，并写入 Redis 热度池 `recommend:hot:pool` 和曝光集合 `exposure:{userId}`。

脚本输出中会显示推荐测试用户 ID。当前本地验证中，主要用户如下：

| 用户 | 用途 |
|---|---|
| `recommend_user_foodie` | 有历史行为的推荐用户 |
| `recommend_user_tea` | 另一个兴趣组用户 |
| `recommend_user_new` | 冷启动用户 |
| `recommend_user_neighbor` | 简化 ItemCF 相似用户 |

## 4. 命令行接口测试

### 4.1 健康检查

直接访问 Recommend 服务：

```bash
curl -s http://localhost:8084/recommend/health
```

通过 Gateway 访问：

```bash
curl -s http://localhost:8080/api/recommend/health
```

预期结果：`code=200`，`data.status=UP`。

### 4.2 查询发现页推荐

```bash
curl -s "http://localhost:8084/recommend/discover?cursor=0&size=20" \
  -H "X-User-Id: 1001"
```

预期结果：返回 `list` 数组，数组内包含 `postId`、`title`、`shopName`、`score`、`reason` 等字段。

### 4.3 冷启动推荐

使用无行为或行为不足的新用户请求：

```bash
curl -s "http://localhost:8084/recommend/discover?cursor=0&size=3" \
  -H "X-User-Id: 3"
```

预期结果：返回近期热门笔记，`reason` 为“近期热门”，且不返回 500。

### 4.4 标签和城市参数

```bash
curl -s "http://localhost:8084/recommend/discover?cursor=0&size=20&tag=Hotpot&city=Guangzhou" \
  -H "X-User-Id: 1"
```

预期结果：接口返回 `code=200`，推荐列表优先包含与标签或城市相关的正常笔记。当前版本使用 MySQL 关键词兜底，后续可替换为 ES 召回。

### 4.5 曝光上报

```bash
curl -s -X POST "http://localhost:8084/recommend/exposures" \
  -H "X-User-Id: 1" \
  -H "Content-Type: application/json" \
  -d '{"postIds":[2,3]}'
```

预期结果：

```json
{"code":200,"msg":"success","data":{"saved":true,"count":2}}
```

### 4.6 Redis 曝光数据检查

```bash
docker exec biteblog-redis redis-cli -a redis123456 SMEMBERS exposure:1
docker exec biteblog-redis redis-cli -a redis123456 TTL exposure:1
```

预期结果：Redis 中可以看到已曝光的笔记 ID，TTL 大于 0，接近 7 天。

### 4.7 分页边界测试

```bash
curl -s "http://localhost:8084/recommend/discover?cursor=0&size=1" \
  -H "X-User-Id: 1"

curl -s "http://localhost:8084/recommend/discover?cursor=0&size=50" \
  -H "X-User-Id: 1"

curl -s "http://localhost:8084/recommend/discover?cursor=0&size=999" \
  -H "X-User-Id: 1"
```

预期结果：`size=1` 最多返回 1 条；`size=50` 最多返回 50 条；`size=999` 会被限制到最大页大小 50。

## 5. JMeter 测试

JMeter 脚本路径：

```text
jmeter/recommend-service-test.jmx
```

建议执行方式：

```bash
jmeter -n -t jmeter/recommend-service-test.jmx \
  -Jhost=localhost \
  -Jport=8080 \
  -Jtoken=<token> \
  -JuserId=1001 \
  -l jmeter/recommend-service-result.jtl \
  -e -o jmeter/recommendservice-report
```

测试内容包括：

1. `GET /api/recommend/discover?cursor=0&size=20`；
2. `GET /api/recommend/discover?cursor=0&size=5`；
3. `GET /api/recommend/discover?cursor=0&size=20&tag=火锅`；
4. `POST /api/recommend/exposures`；
5. `GET /api/recommend/health`。

非功能目标建议：每页 20 条推荐平均响应时间低于 600ms，错误率为 0%。冷启动优先访问 Redis 热度池，曝光过滤使用 Redis Set，因此在测试数据量不大的情况下应保持较低响应时间。

## 6. 本地验证记录

当前已完成以下本地验证：

| 场景 | 结果 | 说明 |
|---|---|---|
| Docker 中间件状态 | 通过 | MySQL、Redis、RabbitMQ、Nacos、Elasticsearch 均为 healthy |
| 初始化推荐数据 | 通过 | `sql/init-recommend-data.ps1` 成功写入 MySQL 和 Redis |
| 后端打包 | 通过 | `mvn -pl biteblog-rank,biteblog-recommend -am package -DskipTests` 成功 |
| Recommend 健康检查 | 通过 | `/recommend/health` 返回 `status=UP` |
| 冷启动推荐 | 通过 | 新用户请求返回 3 条推荐数据 |
| 曝光上报 | 通过 | `/recommend/exposures` 返回 `saved=true` |
| Redis 曝光检查 | 通过 | `SMEMBERS exposure:1` 可看到笔记 ID，TTL 正常 |
| JMeter XML 解析 | 通过 | `jmeter/recommend-service-test.jmx` 可正常解析 |

## 7. 注意事项

1. 直连 Recommend 服务时必须传 `X-User-Id`；
2. 通过 Gateway 测试时需要先登录获取 Token；
3. 当前标签召回是 MySQL 兜底版，ES 索引稳定后可替换为 ES 查询；
4. PowerShell 输出中文推荐原因时可能出现编码显示问题，接口实际返回字段不影响业务判断；
5. Redis 密码为 `redis123456`，MySQL root 密码为 `root123456`。
