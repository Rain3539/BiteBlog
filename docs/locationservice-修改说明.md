# Location Service 修改说明

## 1. 服务定位

本次完成的是成员 4 负责的 Location Service，服务端口为 8085。核心功能：

- 附近探店笔记查询：Redis GEO GEORADIUS 按距离排序
- POI 搜索：高德地图 API 代理 + Redis 缓存

## 2. 新增/修改文件

| 文件 | 说明 |
|---|---|
| pom.xml | 补充 web、mybatis-plus、mysql、amqp、validation 依赖 |
| LocationApplication.java | scanBasePackages + MapperScan |
| controller/LocationController.java | 附近调查 + POI 搜索 |
| entity/Note.java | 映射 note 表，含 longitude/latitude |
| mapper/NoteMapper.java | MyBatis-Plus Mapper |
| config/LocationRabbitConfig.java | RabbitMQ 交换机/队列/绑定 |
| dto/NearbyMarkerVO.java | 附近笔记返回对象 |
| dto/PoiItemVO.java | POI 搜索结果对象 |
| service/LocationService.java | GEORADIUS / POI搜索+缓存 / GEO写入 |
| service/LocationEventListener.java | 消费 note.published -> GEOADD |
| ErrorCode.java | POI_SEARCH_FAIL(5002) + COORDINATE_INVALID(5003) |
| sql/init-location-data.ps1 | 测试数据初始化脚本 |
| sql/init-location-geo.sql | GEO 测试数据 |
| jmeter/location-service-test.jmx | JMeter 压测脚本 |

## 3. 接口设计

### 3.1 附近探店笔记

GET /location/nearby/markers?longitude=114.3&latitude=30.59&radius=5

- longitude: 经度 [-180,180]，必填
- latitude: 纬度 [-90,90]，必填
- radius: 半径(km)，默认3

返回 markers 数组：noteId, title, shopName, longitude, latitude, distance(km)

### 3.2 POI 搜索

GET /location/poi/search?keyword=火锅&city=武汉

- keyword: 关键词，必填
- city: 城市，可选

返回 list 数组：id, name, address, longitude, latitude, type

## 4. 核心逻辑

### Redis GEO

- Key: location:notes
- 写入: GEOADD location:notes lng lat noteId
- 查询: GEORADIUS + 批量 MySQL 查详情

### POI 缓存

- 首次: WebClient -> 高德 /v3/place/text -> 解析 JSON
- 缓存: Redis key=location:poi:{keyword}:{city}, TTL=1h

### 坐标异步写入

RabbitMQ note.published -> 查 MySQL Note -> 坐标非空则 GEOADD

## 5. 非功能需求

| 指标 | 目标 | 实测 |
|------|------|------|
| 附近查询 | < 300ms | 14-35ms |
| POI 首次 | 正常 | ~566ms |
| POI 缓存 | 提速 | 15-22ms |
| 坐标校验 | 非法拒绝 | COORDINATE_INVALID(5003) |
| 位置降级 | 无坐标跳过 | 日志记录，不阻塞 |

## 6. 本地验证结果

| 测试项 | 结果 |
|---|---|
| 附近查询(5km) | 返回4条，0.58-4.84km |
| 不同半径(1/3/5/10/50km) | 3/3/4/4/5条 |
| 非法经度(200) | code=5003 |
| POI搜索(火锅/武汉) | 返回20条 |
| POI缓存命中 | 15-22ms |
