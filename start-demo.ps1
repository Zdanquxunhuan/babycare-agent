param([int]$Port = 18136, [string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$javaCommand = if ($JavaHome) { Join-Path $JavaHome 'bin/java.exe' } else { 'java' }
[xml]$project = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'pom.xml') -Raw
$jar = Join-Path $PSScriptRoot ("target/{0}-{1}.jar" -f $project.project.artifactId, $project.project.version)
if (!(Test-Path -LiteralPath $jar)) { throw '请先使用 JDK 21 执行 .\mvnw.cmd package' }
$initialKey = $env:DASHSCOPE_API_KEY
$keyFile = Join-Path $PSScriptRoot 'start-demo.key'
Push-Location $PSScriptRoot
try {
    if (!$initialKey) {
        if (Test-Path -LiteralPath $keyFile) {
            $env:DASHSCOPE_API_KEY = (Get-Content -LiteralPath $keyFile -Raw).Trim()
        } else {
            $secret = Read-Host '输入百炼 API Key（仅用于当前进程）' -AsSecureString
            $env:DASHSCOPE_API_KEY = [Net.NetworkCredential]::new('', $secret).Password
        }
    }
    & $javaCommand -jar $jar "--server.port=$Port" "--babycare.stock-fault-file=target/stock-offline.flag" "--babycare.promotion-fault-file=target/promotion-offline.flag"
    if ($LASTEXITCODE -ne 0) { throw "Java 服务退出，状态码 $LASTEXITCODE" }
} finally {
    $env:DASHSCOPE_API_KEY = $initialKey
    Pop-Location
}
