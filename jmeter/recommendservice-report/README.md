# Recommend Service JMeter Report

该目录用于保存 Recommend Service 的 JMeter HTML 压测报告。

当前压测脚本：`jmeter/recommend-service-test.jmx`

压测覆盖接口：

- `GET /recommend/discover?cursor=0&size=20`
- `GET /recommend/discover?cursor=0&size=5`
- `GET /recommend/discover?cursor=0&size=20&tag=Hotpot&city=Guangzhou`
- `POST /recommend/exposures`
- `GET /recommend/discover?cursor=20&size=20`

压测规模：

- 10 个并发用户
- 每线程 200 轮
- 每轮 5 个请求
- 总请求数约 10,000

运行命令：

```powershell
jmeter -n -f -t jmeter/recommend-service-test.jmx `
  -Jhost=localhost `
  -Jport=8084 `
  -l jmeter/recommend-service-result.jtl `
  -e -o jmeter/recommendservice-report
```
