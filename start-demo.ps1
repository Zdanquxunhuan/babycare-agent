param([int]$Port = 18136, [string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$javaCommand = if ($JavaHome) { Join-Path $JavaHome 'bin/java.exe' } else { 'java' }
$jar = Join-Path $PSScriptRoot 'target/baby-care-agent-0.0.1-SNAPSHOT.jar'
if (!(Test-Path -LiteralPath $jar)) { throw '请先使用 JDK 21 执行 .\mvnw.cmd package' }
$initialKey = $env:DASHSCOPE_API_KEY
Push-Location $PSScriptRoot
try {
    if (!$initialKey) {
        $secret = Read-Host '输入百炼 API Key（仅用于当前进程）' -AsSecureString
        $env:DASHSCOPE_API_KEY = [Net.NetworkCredential]::new('', $secret).Password
    }
    & $javaCommand -jar $jar "--server.port=$Port" "--babycare.stock-fault-file=target/stock-offline.flag"
    if ($LASTEXITCODE -ne 0) { throw "Java 服务退出，状态码 $LASTEXITCODE" }
} finally {
    $env:DASHSCOPE_API_KEY = $initialKey
    Pop-Location
}
