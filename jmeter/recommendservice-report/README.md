# Recommend Service JMeter Report

This directory is reserved for Recommend Service JMeter HTML report output.

Current reference script covers:

- `GET /api/recommend/discover?cursor=0&size=20`
- `GET /api/recommend/discover?cursor=0&size=5`
- `GET /api/recommend/discover?cursor=0&size=20&tag=火锅`
- `POST /api/recommend/exposures`
- `GET /api/recommend/health`

Suggested command:

```bash
jmeter -n -t jmeter/recommend-service-test.jmx \
  -Jhost=localhost \
  -Jport=8080 \
  -Jtoken=<token> \
  -JuserId=1001 \
  -l jmeter/recommend-service-result.jtl \
  -e -o jmeter/recommendservice-report
```

If the directory already contains an old report, clean or move the old generated files before running JMeter again.

Last code verification:

```text
2026-05-11 mvn -pl biteblog-recommend -am compile -DskipTests: PASS
```
