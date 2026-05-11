# Recommend Service JMeter Report

This directory is reserved for Recommend Service JMeter HTML report output.

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

