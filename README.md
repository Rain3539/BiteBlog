# BiteBlog - 分布式美食探店社区平台

武汉大学计算机学院 2025-2026 学年第二学期《分布式系统》课程设计

## 项目简介

BiteBlog 是一个基于微服务架构的美食探店社区平台，支持探店笔记发布、关注 Feed 流、个性化推荐、附近探店、热度排行、实时通知等功能。

**项目文档**：

[软件需求说明书](docs/BiteBlog软件需求说明书.docx) · [概要设计说明书](docs/BiteBlog概要设计说明书.docx) · [测试分析报告](docs/BiteBlog测试分析报告.docx) · [答辩汇报 PPT](docs/BiteBlog汇报ppt.pptx)

## 技术栈

- **前端**: Vue 3 + Vite + Element Plus + Pinia
- **后端**: Spring Boot 3.2.5 + Spring Cloud 2023.0.1 + Spring Cloud Alibaba
- **存储**: MySQL 8.0 + Elasticsearch 8.12 + Redis 7
- **消息队列**: RabbitMQ 3.12
- **对象存储**: MinIO
- **服务注册**: Nacos 2.3
- **部署**: Docker + Docker Compose

##  快速启动

###  前置检查

启动前确认环境就绪：

```bash
# 检查 JDK 版本（需要 17+）
java -version

# 检查 Maven 版本（需要 3.8+）
mvn -v

# 检查 Node.js 版本（需要 18+）
node -v
npm -v

# 检查 Docker 和 Docker Compose
docker -v
docker compose version

# 克隆项目（如果是第一次）
git clone <仓库地址>
cd BiteBlog
```

###  启动中间件（Docker Compose）

```bash
# 在项目根目录启动全部中间件
docker compose up -d

# 查看容器启动状态（等待所有容器 health 状态变为 healthy）
docker compose ps

# 如果有容器启动失败，查看日志排查
docker compose logs <容器名>       # 例如: docker compose logs mysql
docker compose logs -f             # 实时跟踪所有日志
```

首次启动需要拉取镜像，可能需要几分钟。确认所有容器状态为 `healthy`：

```
NAME                  STATUS
biteblog-mysql        Up (healthy)
biteblog-redis        Up (healthy)
biteblog-rabbitmq     Up (healthy)
biteblog-elasticsearch Up (healthy)
biteblog-minio        Up (healthy)
biteblog-nacos        Up (healthy)
```

启动后各服务管理界面：

| 服务 | 地址 | 账号密码 | 用途 |
|------|------|----------|------|
| Nacos | http://localhost:8848/nacos | nacos/nacos | 服务注册发现、配置管理 |
| RabbitMQ 管理台 | http://localhost:15672 | guest/guest | 消息队列监控 |
| MinIO 控制台 | http://localhost:9001 | minioadmin/minioadmin | 对象存储管理 |
| Elasticsearch | http://localhost:9200 | 无 | 搜索引擎，直接浏览器访问 |

> **MySQL** 端口 3306，账号 root/root123456，可用 DBeaver 等工具连接。
> **Redis** 端口 6379，密码 redis123456，可用 RedisInsight 连接。

**如果需要停止/清理中间件：**

```bash
docker compose stop            # 停止容器（保留数据）
docker compose down            # 停止并移除容器
docker compose down -v         # 停止并移除容器 + 删除数据卷（清空所有数据）
```

###  初始化 ES 索引

```bash
# 回到项目根目录
cd elasticsearch

# 执行索引初始化脚本（会创建 9 个 ES 索引）
bash init-indices.sh
```

预期输出：每个索引显示 `"acknowledged": true`，最后打印 `全部索引创建完成`。

**验证索引是否创建成功：**

```bash
# 查看所有索引
curl http://localhost:9200/_cat/indices?v

# 应能看到 post_index、user_index、follow_index 等 9 个索引
```

###  编译后端项目

```bash
# 进入后端根目录
cd biteblog-backend

# 首次编译（跳过测试，安装 common 模块到本地仓库）
mvn clean install -DskipTests
```

编译成功后会看到 `BUILD SUCCESS`。如果依赖下载慢，参考下方 FAQ 配置阿里云镜像。

**常见编译问题：**

```bash
# 如果报 "Non-resolvable parent POM" 错误，先安装父 POM
mvn install -N

# 如果某个模块编译失败，可以单独编译该模块排查
cd biteblog-common
mvn clean install -DskipTests
```

### 启动后端服务

**方式一：一键启动（推荐）**

```powershell
# 编译所有模块并启动 8 个微服务（每个服务自动打开独立终端窗口）
.\start-all.ps1

# 仅编译不启动
.\start-all.ps1 compile
```

脚本会按顺序启动：gateway → user → post → feed → recommend → location → rank → notify，端口 8080~8087。

**方式二：命令行手动启动（调试用）**

```bash
# 终端 1 - 启动网关（端口 8080）
cd biteblog-backend/biteblog-gateway
mvn spring-boot:run

# 终端 2 - 启动用户服务（端口 8081）
cd biteblog-backend/biteblog-user
mvn spring-boot:run

# ... 其他服务同理，参见 start-all.ps1 中的服务列表
```

**方式三：IntelliJ IDEA 启动**

1. 用 IDEA 打开 `biteblog-backend/` 目录（作为 Maven 项目导入）
2. 等待 Maven 依赖下载完成
3. 打开每个服务的 `XxxApplication.java`，点击绿色 ▶ 运行按钮
4. 在 Run Configuration 中为每个服务设置不同的端口（已在 application.yml 中配置）

**验证服务注册成功：**

打开 Nacos 控制台 http://localhost:8848/nacos → 服务管理 → 服务列表，应能看到已启动的服务。

也可以用命令行检查：

```bash
# 检查某个服务是否能响应（以 user-service 为例）
curl http://localhost:8081/user/1
# 预期返回: {"code":200,"msg":"success","data":{...}}
```

###  启动前端

```bash
# 进入前端目录
cd frontend

# 安装依赖（首次需要）
npm install

# 如果 npm install 慢，可切换淘宝源
npm install --registry=https://registry.npmmirror.com

# 启动开发服务器
npm run dev
```

启动成功后终端会显示：

```
  VITE v5.x.x  ready in xxx ms

  ➜  Local:   http://localhost:3000/
  ➜  Network: http://192.168.x.x:3000/
```

浏览器访问 http://localhost:3000 ，应能看到 BiteBlog 登录页面。

**前端开发常用命令：**

```bash
npm run dev          # 启动开发服务器（热更新）
npm run build        # 构建生产包（输出到 dist/ 目录）
npm run preview      # 预览生产构建结果
```

###  验证全链路

全部启动后，按以下步骤验证系统是否正常：

```bash
# 1. 测试用户注册
curl -X POST http://localhost:8080/api/user/register \
  -H "Content-Type: application/json" \
  -d '{"phone":"13800000001","password":"12345678","username":"测试用户"}'

# 2. 测试用户登录（获取 Token）
curl -X POST http://localhost:8080/api/user/login \
  -H "Content-Type: application/json" \
  -d '{"phone":"13800000001","password":"12345678"}'

# 3. 用返回的 Token 测试鉴权接口（将 <token> 替换为实际值）
curl http://localhost:8080/api/user/1 \
  -H "Authorization: Bearer <token>"

# 4. 测试热度榜（无需 Token，白名单接口）
curl http://localhost:8080/api/rank/top10
```

> 上述命令请求经过 Gateway（8080 端口）转发，验证了路由 + 鉴权全链路。
> 如果直接访问后端服务端口（如 8081）能通但经过 Gateway 不通，检查 Gateway 日志。

### 初始化测试数据

后端服务启动后，按模块需要执行对应的数据初始化脚本：

```powershell
# 基础测试数据（10 个用户 + 关注关系），所有模块通用
.\sql\init-data.ps1

# 各模块专用测试数据（根据需要执行）
.\sql\init-feed-data.ps1        # Feed 服务测试数据
.\sql\init-rank-data.ps1        # Rank 排行服务测试数据
.\sql\init-location-data.ps1    # Location 位置服务测试数据
.\sql\init-notify-data.ps1      # Notify 通知服务测试数据
.\sql\init-recommend-data.ps1   # Recommend 推荐服务测试数据
```

基础测试账号：`13800000001` ~ `13800000010`，密码统一 `12345678`。

### 启动顺序总结

```
1. docker compose up -d                       （等所有容器 healthy）
2. bash elasticsearch/init-indices.sh         （创建 ES 索引）
3. .\start-all.ps1                            （编译 + 一键启动 8 个微服务）
4. .\sql\init-data.ps1                        （初始化基础测试数据）
5. .\sql\init-feed-data.ps1                   （按需执行各模块测试数据）
6. cd frontend && npm install && npm run dev  （启动前端）
7. 浏览器打开 http://localhost:3000
```

## 项目结构

```
├── docker-compose.yml          # 中间件编排
├── sql/init.sql                # MySQL 初始化
├── elasticsearch/              # ES 索引初始化
├── docs/                       # 项目文档
├── biteblog-backend/           # 后端 Maven 工程
│   ├── biteblog-common/        # 公共模块
│   ├── biteblog-gateway/       # 网关服务
│   ├── biteblog-user/          # 用户服务
│   ├── biteblog-post/          # 笔记服务
│   ├── biteblog-feed/          # Feed 服务
│   ├── biteblog-recommend/     # 推荐服务
│   ├── biteblog-location/      # 位置服务
│   ├── biteblog-rank/          # 排行服务
│   └── biteblog-notify/        # 通知服务
└── frontend/                   # 前端项目
```


