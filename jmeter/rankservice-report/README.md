# Rank Service JMeter Report

JMeter requires the `-o` report directory to be empty or absent.

PowerShell:

```powershell
if (Test-Path jmeter/rankservice-report) {
  Remove-Item -Recurse -Force jmeter/rankservice-report
}

jmeter -n -t jmeter/rank-service-test.jmx `
  -l jmeter/rank-service-result.jtl `
  -e -o jmeter/rankservice-report
```

After the command finishes, open:

```text
jmeter/rankservice-report/index.html
```

Take a screenshot of the dashboard page and save it as a Rank Service test screenshot.
