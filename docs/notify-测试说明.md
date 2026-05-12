# Notify Service 测试说明

## 1. 测试范围

1. 服务启动与健康检查  
2. HTTP：通知列表、未读数、单条已读、全部已读（经网关 **8080** 或直连 **8087**）  
3. RabbitMQ：队列 `notify.interaction.queue` 消费点赞/收藏/评论  
4. WebSocket：SockJS + STOMP，订阅 `/user/queue/notify`  
5. JMeter 压测脚本：`jmeter/notify-service-test.jmx`  
6. 初始化脚本：`sql/init-notify-data.ps1`

## 2. 启动前准备

- Docker：**MySQL、RabbitMQ、Nacos** 已就绪，`sql/init.sql` 已执行  
- 启动：`gateway`（8080）、`user-service`（8081）、`post-service`（8082）、`notify-service`（8087）  
- `notify-service` 中 `spring.rabbitmq.listener.simple.auto-startup` 为 **true**（默认），否则不消费 MQ  

## 3. 初始化测试数据

在仓库 `BiteBlog/sql` 目录执行：

```powershell
.\init-notify-data.ps1
```

将创建/登录 **13900004001**（作者）、**13900004002**（互动方），并产生至少 3 条通知（赞、藏、评）。密码均为 **12345678**。

## 4. 命令行快速验证（经网关）

### 4.1 PowerShell（推荐）

**登录请求体必须是合法 JSON**：用下面「写法 A」或「写法 B」。**错误写法**：把已是 JSON 的字符串再 `| ConvertTo-Json`，会得到转义后的错误 body，登录失败，后续接口 **401**。

写法 A（原始 JSON 字符串，不要再套一层 ConvertTo-Json）：

```powershell
$body = '{"phone":"13900004001","password":"12345678"}'
$r = Invoke-RestMethod -Uri "http://localhost:8080/api/user/login" -Method POST -Body $body -ContentType "application/json; charset=utf-8"
$token = $r.data.token

Invoke-RestMethod -Uri "http://localhost:8080/api/notify/list?page=1&size=20" -Headers @{ Authorization = "Bearer $token" }
Invoke-RestMethod -Uri "http://localhost:8080/api/notify/unread-count" -Headers @{ Authorization = "Bearer $token" }
```

写法 B（哈希表再转 JSON，正确）：

```powershell
$body = @{ phone = "13900004001"; password = "12345678" } | ConvertTo-Json -Compress
$r = Invoke-RestMethod -Uri "http://localhost:8080/api/user/login" -Method POST -Body $body -ContentType "application/json; charset=utf-8"
$token = $r.data.token
```

**关于 `code=200` 但 `total=0`**：说明网关与 JWT 已打通，但 **notification 表里没有数据**。请先执行 `.\init-notify-data.ps1`，并确认 **RabbitMQ** 已启动、**notify-service** 已启动且队列 **`notify.interaction.queue`** 有消费者、Post 与 Notify 连接**同一套** RabbitMQ。

### 4.2 curl（将 `<TOKEN>` 换为登录返回的 JWT）

```bash
curl -s "http://localhost:8080/api/notify/list?page=1&size=20" -H "Authorization: Bearer <TOKEN>"
curl -s "http://localhost:8080/api/notify/unread-count" -H "Authorization: Bearer <TOKEN>"
```

### 4.3 直连 notify（调试用，需 `X-User-Id`）

```powershell
Invoke-RestMethod -Uri "http://localhost:8087/notify/list?page=1&size=20" -Headers @{ "X-User-Id" = "11" }
```

将 `11` 换成作者用户的实际 `userId`。

## 5. JMeter 压测

测试计划文件：

```text
jmeter/notify-service-test.jmx
```

默认变量：**host=localhost**，**port=8080**，登录手机 **13900004001** / 密码 **12345678**（与 `init-notify-data.ps1` 作者一致）。每个线程迭代内先 **登录** 再压 **health、list、unread-count**。

**非 GUI 执行示例**（在项目 **BiteBlog** 根目录，与 `rankservice-report` 同级输出到 `notifyservice-report`）：

```powershell
cd e:\WHU\DistributedGroupWork\BiteBlog
& "D:\from_browser\apache-jmeter-5.6.3\bin\jmeter.bat" -n -t jmeter\notify-service-test.jmx -l jmeter\notify-service-result.jtl -e -o jmeter\notifyservice-report
```

将本机 JMeter 安装路径替换为实际路径。期望：**错误率 0%**；列表与未读接口平均响应时间在本地数据量下应较低（可参考课程对列表查询的响应时间要求）。

结果文件：`jmeter/notify-service-result.jtl`；HTML 报告目录：`jmeter/notifyservice-report/`。

## 6. 前端联调

1. `npm install`（引入 `@stomp/stompjs`、`sockjs-client`）  
2. `npm run dev`，登录有通知的账号，打开 **通知中心** 路由  
3. 默认 WebSocket 连接 **`http://localhost:8087`**；若 notify 部署在其他地址，在项目根或 `frontend` 下增加 `.env.development`：  

```env
VITE_NOTIFY_WS_ORIGIN=http://你的主机:8087
```

4. 用另一账号对当前用户笔记点赞，当前页应出现实时条目或刷新后列表更新  

## 7. RabbitMQ 检查

- 队列 **`notify.interaction.queue`** 存在且有 **consumer**  
- 点赞后 **Ready** 消息被消费，无异常堆积  

## 8. 常见问题

| 现象 | 处理 |
|------|------|
| **Rabbit 上 `notify.interaction.queue`：Unacked 很多、deliver≈redelivered、ack=0** | 消费在 ack 前抛错导致无限重投。已修复：WS 载荷 `createdAt` 改为 ISO 字符串、监听器捕获异常后仍 ack；请 **重新编译启动 notify**，在管理台 **Purge** 该队列后再跑 `init-notify-data.ps1`。 |
| 网关 notify 返回 **401** | 检查登录 body：勿对 JSON 字符串再 `ConvertTo-Json`；见上文 4.1 |
| **code=200** 但 **total=0**，列表有数据、`unreadCount>0` | `MybatisPlusConfig` 未加载（分页拦截器缺失，`Page.getTotal()` 恒为 0）；确认 `biteblog-notify/config/MybatisPlusConfig.java` 存在并已重新编译启动 |
| **code=200** 但 **total=0**，列表也为空 | 鉴权正常但库中无数据；查 RabbitMQ、`notify-service` 消费、Post 与 Notify 是否同一 broker |
| 列表始终为空 | 确认 RabbitMQ、Post 与 Notify 连同一 broker；看 notify 日志是否消费异常 |
| JMeter 登录失败 | 先执行 `init-notify-data.ps1` 或改 JMeter 用户变量 `PHONE`/`PASSWORD` |
| 前端「实时未连接」 | 确认 8087 可访问、防火墙放行；检查 `VITE_NOTIFY_WS_ORIGIN` 与浏览器控制台 |
