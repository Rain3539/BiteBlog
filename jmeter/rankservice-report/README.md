# Rank Service JMeter Report

运行以下命令后会在本目录生成 HTML 报告：

```bash
jmeter -n -t jmeter/rank-service-test.jmx -l jmeter/rank-service-result.jtl -e -o jmeter/rankservice-report
```

如果目录非空，先删除旧报告：

```bash
rm -rf jmeter/rankservice-report/*
```
