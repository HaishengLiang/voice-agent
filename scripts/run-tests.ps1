$env:JAVA_HOME = 'I:\voice-agent\.toolchain\jdk17'
$env:ANDROID_HOME = 'I:\voice-agent\.toolchain\android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:JAVA_TOOL_OPTIONS = '-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890'

& 'I:\voice-agent\.toolchain\gradle-8.11.1\bin\gradle.bat' -p 'I:\voice-agent\xiaomiao-voice-agent' testDebugUnitTest jacocoTestReport --no-daemon
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "`n=== 覆盖率报告 ==="
Write-Host 'HTML: I:\voice-agent\xiaomiao-voice-agent\app\build\reports\jacoco\jacocoTestReport\html\index.html'
Write-Host 'XML : I:\voice-agent\xiaomiao-voice-agent\app\build\reports\jacoco\jacocoTestReport\jacocoTestReport.xml'
