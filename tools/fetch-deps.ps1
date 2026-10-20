# 下载 Create 及其内嵌依赖到 libs/
#
# 为什么需要这个脚本：
#   Create 6.x 把 Flywheel / Ponder / Registrate 以 jar-in-jar 形式内嵌在
#   META-INF/jarjar/ 里。编译时需要把它们拆出来放到 classpath 上，否则
#   引用 Create API 时会缺少类型。
#
#   libs/*.jar 是二进制依赖，不进版本控制（见 .gitignore），
#   新环境 clone 后跑一次这个脚本即可。
#
# 用法：
#   .\tools\fetch-deps.ps1
#   .\tools\fetch-deps.ps1 -CreateVersion 6.0.10

param(
    [string]$CreateVersion = '6.0.10',
    [string]$MinecraftVersion = '1.21.1'
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$libs = Join-Path $root 'libs'
New-Item -ItemType Directory -Force -Path $libs | Out-Null

# Modrinth 用 game_versions + loaders 过滤，拿到与目标版本匹配的下载项。
$query = "https://api.modrinth.com/v2/project/create/version" +
         "?game_versions=%5B%22$MinecraftVersion%22%5D&loaders=%5B%22neoforge%22%5D"
$versions = Invoke-RestMethod -Uri $query -TimeoutSec 60

# version_number 的格式在不同版本间不一致（6.0.10+mc1.21.1 / mc1.21.1-6.0.9），
# 所以用通配匹配而不是精确比较。
$target = $versions | Where-Object { $_.version_number -like "*$CreateVersion*" } | Select-Object -First 1
if (-not $target) {
    throw "找不到 Create $CreateVersion（MC $MinecraftVersion / NeoForge）。请检查 gradle.properties 里的 create_version。"
}

$fileName = "create-$MinecraftVersion-$CreateVersion.jar"
$dest = Join-Path $libs $fileName
Write-Host "下载 $($target.version_number) -> $fileName"
Invoke-WebRequest -Uri $target.files[0].url -OutFile $dest -TimeoutSec 300

# 拆出 jar-in-jar 的内嵌依赖。用 java 自带的 jar 工具，不依赖第三方解压软件。
$javaHome = $env:JAVA_HOME
if (-not $javaHome) { $javaHome = 'C:\Program Files\Java\jdk-21' }
$jarExe = Join-Path $javaHome 'bin\jar.exe'

$tmp = Join-Path $env:TEMP "tbh_create_$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
Push-Location $tmp
try {
    & $jarExe xf $dest 'META-INF/jarjar'
    Get-ChildItem (Join-Path $tmp 'META-INF\jarjar') -Filter *.jar | ForEach-Object {
        Copy-Item $_.FullName $libs -Force
        Write-Host "  拆出 $($_.Name)"
    }
} finally {
    Pop-Location
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host "完成。libs/ 内容："
Get-ChildItem $libs -Filter *.jar | ForEach-Object {
    Write-Host ("  {0}  ({1:N1} MB)" -f $_.Name, ($_.Length / 1MB))
}
