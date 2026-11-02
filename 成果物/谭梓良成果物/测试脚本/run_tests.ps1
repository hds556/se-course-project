<#
.SYNOPSIS
在独立副本中运行第 3 次评审测试，不改动用户提供的项目目录。

.DESCRIPTION
只复制 pom.xml、mvnw、mvnw.cmd、.mvn、src、public 到本脚本旁的
.runs/时间-模式-唯一编号/project。不会复制 .git、target、运行数据库或上传目录。
每次运行使用新目录，保留 run-info.json、run.log 和 Maven Surefire XML。

baseline 测试原版本；fixed 仅在副本中应用“修复文件”内的补丁。
fixed 会逐个核验 manifest.json 中的 beforeSha256/afterSha256；原文件不匹配则终止。
manifest.json 默认位于“修复文件”目录，也兼容本脚本旁的同名清单。

.PARAMETER ProjectPath
待测 Java 项目的根目录，必须包含 pom.xml。不要传本测试成果目录。

.PARAMETER JavaHome
可选 JDK 根目录，至少 Java 17。例如 D:\jdk。省略时优先使用现有 JAVA_HOME，
否则从 PATH 查找 java.exe。脚本结束后恢复原进程的 JAVA_HOME 和 PATH。

.PARAMETER Mode
baseline（默认）或 fixed。不会直接修补 ProjectPath。

.PARAMETER CaseId
可选单用例编号，例如 R3-A001、R3-P001、R3-T001、R3-X001。
省略时运行四个新增测试类及项目原有 CatalogIntegrationTest。

.PARAMETER PrepareOnly
只复制、检查 Java 版本、核验及应用修复（fixed 模式），不运行 Maven。

.EXAMPLE
powershell -NoProfile -ExecutionPolicy Bypass -File .\run_tests.ps1 -ProjectPath 'D:\项目\se-course-project' -JavaHome 'D:\jdk'

.EXAMPLE
.\run_tests.ps1 -ProjectPath 'D:\项目\se-course-project' -JavaHome 'D:\jdk' -Mode fixed

.EXAMPLE
.\run_tests.ps1 -ProjectPath 'D:\项目\se-course-project' -JavaHome 'D:\jdk' -CaseId R3-X001

.EXAMPLE
.\run_tests.ps1 -ProjectPath 'D:\项目\se-course-project' -Mode fixed -PrepareOnly

.NOTES
Windows PowerShell 5.1 / PowerShell 7 均可运行。首次使用 Maven 包装器可能下载依赖。
原版本中用于复现缺陷的测试可能失败；失败会返回非零退出码，不会伪装为通过。
单用例只验证该场景，不能代替全套回归。请按 run-info.json 识别本次模式和选择。
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$ProjectPath,

    [string]$JavaHome,

    [ValidateSet('baseline', 'fixed')]
    [string]$Mode = 'baseline',

    [ValidatePattern('^R3-[APTX][0-9]{3}$')]
    [string]$CaseId,

    [switch]$PrepareOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$oldJavaHome = $env:JAVA_HOME
$oldPath = $env:PATH
$oldLocation = Get-Location
$runDirectory = $null
$logFile = $null
$runInfo = $null
$exitCode = 1

function Write-RunLog {
    param([string]$Message)
    Write-Host $Message
    if ($null -ne $script:logFile) {
        [System.IO.File]::AppendAllText($script:logFile, $Message + [Environment]::NewLine, $script:utf8)
    }
}

function Save-RunInfo {
    if ($null -ne $script:runInfo -and $null -ne $script:runDirectory) {
        $json = ConvertTo-Json -InputObject $script:runInfo -Depth 8
        [System.IO.File]::WriteAllText((Join-Path $script:runDirectory 'run-info.json'), $json, $script:utf8)
    }
}

function Get-ContainedPath {
    param([string]$Root, [string]$RelativePath)
    $rootFull = [System.IO.Path]::GetFullPath($Root).TrimEnd([char[]]'\/')
    $full = [System.IO.Path]::GetFullPath((Join-Path $rootFull $RelativePath))
    $prefix = $rootFull + [System.IO.Path]::DirectorySeparatorChar
    if (-not $full.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "清单路径超出允许目录：$RelativePath"
    }
    return $full
}

try {
    if (-not (Test-Path -LiteralPath $ProjectPath -PathType Container)) {
        throw "项目目录不存在：$ProjectPath"
    }
    $source = (Resolve-Path -LiteralPath $ProjectPath).ProviderPath
    if (-not (Test-Path -LiteralPath (Join-Path $source 'pom.xml') -PathType Leaf)) {
        throw 'ProjectPath 必须指向包含 pom.xml 的 Java 项目根目录。'
    }
    $testSource = Join-Path $PSScriptRoot 'src/test/java'
    if (-not (Test-Path -LiteralPath $testSource -PathType Container)) {
        throw "测试源码目录不存在，请完整解压测试成果：$testSource"
    }

    $selectedJavaHome = $JavaHome
    if ([string]::IsNullOrWhiteSpace($selectedJavaHome) -and
        -not [string]::IsNullOrWhiteSpace($env:JAVA_HOME) -and
        (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe') -PathType Leaf)) {
        $selectedJavaHome = $env:JAVA_HOME
    }
    if (-not [string]::IsNullOrWhiteSpace($selectedJavaHome)) {
        if (-not (Test-Path -LiteralPath $selectedJavaHome -PathType Container)) {
            throw "JDK 目录不存在：$selectedJavaHome"
        }
        $selectedJavaHome = (Resolve-Path -LiteralPath $selectedJavaHome).ProviderPath
        $java = Join-Path $selectedJavaHome 'bin/java.exe'
        if (-not (Test-Path -LiteralPath $java -PathType Leaf)) {
            throw "JavaHome 中没有 bin/java.exe：$selectedJavaHome"
        }
        $env:JAVA_HOME = $selectedJavaHome
        $env:PATH = (Join-Path $selectedJavaHome 'bin') + [System.IO.Path]::PathSeparator + $env:PATH
    } else {
        $javaCommand = Get-Command java.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($null -eq $javaCommand) {
            throw '找不到 java.exe。请安装 JDK 17 或更高版本，并通过 -JavaHome 指定目录。'
        }
        $java = $javaCommand.Source
    }

    $javaPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $javaLines = @(& $java -version 2>&1 | ForEach-Object { $_.ToString() })
        $javaExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $javaPreference
    }
    if ($javaExit -ne 0) { throw "无法运行 Java：$java" }
    $javaText = $javaLines -join [Environment]::NewLine
    $versionMatch = [regex]::Match($javaText, '(?:openjdk|java)\s+version\s+"(?<version>[^"]+)"')
    if (-not $versionMatch.Success) {
        $versionMatch = [regex]::Match($javaText, '(?:openjdk|java)\s+(?<version>[0-9]+(?:\.[0-9]+)*)')
    }
    if (-not $versionMatch.Success) { throw "无法识别 Java 版本：$javaText" }
    $version = $versionMatch.Groups['version'].Value
    $versionNumbers = $version.Split('.')
    $javaMajor = [int]$versionNumbers[0]
    if ($javaMajor -eq 1 -and $versionNumbers.Length -gt 1) { $javaMajor = [int]$versionNumbers[1] }
    if ($javaMajor -lt 17) {
        throw "当前 Java 为 $version，至少需要 JDK 17。请通过 -JavaHome 指定合适的 JDK。"
    }

    $classes = @{
        'A' = 'AccountAcceptanceTest'
        'P' = 'CatalogAcceptanceTest'
        'T' = 'TradeAcceptanceTest'
        'X' = 'BoundaryRegressionTest'
    }
    $testSelection = 'AccountAcceptanceTest,CatalogAcceptanceTest,TradeAcceptanceTest,BoundaryRegressionTest,CatalogIntegrationTest'
    if (-not [string]::IsNullOrWhiteSpace($CaseId)) {
        $module = $CaseId.Substring(3, 1)
        $method = $module.ToLowerInvariant() + $CaseId.Substring(4, 3)
        $className = $classes[$module]
        $classFile = Join-Path $testSource ('com/coursewise/shopping/review3/' + $className + '.java')
        if (-not (Test-Path -LiteralPath $classFile -PathType Leaf)) { throw "缺少测试类：$className" }
        if (-not (Select-String -LiteralPath $classFile -Pattern ('\bvoid\s+' + $method + '\s*\(') -Quiet)) {
            throw "用例编号不存在：$CaseId"
        }
        $testSelection = $className + '#' + $method
    }

    $stamp = [DateTimeOffset]::Now.ToString('yyyyMMdd-HHmmss-fff')
    $suffix = [guid]::NewGuid().ToString('N').Substring(0, 8)
    $runsRoot = Join-Path $PSScriptRoot '.runs'
    $runDirectory = Join-Path $runsRoot ($stamp + '-' + $Mode + '-' + $suffix)
    $project = Join-Path $runDirectory 'project'
    if (Test-Path -LiteralPath $runDirectory) { throw "运行目录已存在，拒绝覆盖：$runDirectory" }
    New-Item -ItemType Directory -Path $project -Force | Out-Null
    $logFile = Join-Path $runDirectory 'run.log'
    [System.IO.File]::WriteAllText($logFile, '', $utf8)
    $runInfo = [ordered]@{
        mode = $Mode
        startedAt = [DateTimeOffset]::Now.ToString('o')
        sourceProject = $source
        isolatedProject = $project
        javaExecutable = $java
        javaVersion = $version
        javaVersionOutput = $javaText
        testSelection = $testSelection
        caseId = $CaseId
        prepareOnly = [bool]$PrepareOnly
        appliedFixes = @()
        status = 'preparing'
        surefireDirectory = (Join-Path $project 'target/surefire-reports')
    }
    Save-RunInfo
    Write-RunLog "模式：$Mode；Java：$version；测试选择：$testSelection"
    Write-RunLog "原项目：$source"
    Write-RunLog "独立副本：$project"

    foreach ($name in @('pom.xml', 'mvnw', 'mvnw.cmd', '.mvn', 'src', 'public')) {
        $original = Join-Path $source $name
        if (Test-Path -LiteralPath $original) {
            # 拒绝链接，防止测试源码覆盖时经符号链接写回用户项目。
            $itemsToCheck = @((Get-Item -LiteralPath $original -Force))
            if (Test-Path -LiteralPath $original -PathType Container) {
                $itemsToCheck += @(Get-ChildItem -LiteralPath $original -Recurse -Force)
            }
            foreach ($sourceItem in $itemsToCheck) {
                if (($sourceItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
                    throw "项目包含链接，请先提供普通文件副本：$($sourceItem.FullName)"
                }
            }
            Copy-Item -LiteralPath $original -Destination $project -Recurse -Force
        }
    }
    $testDestination = Join-Path $project 'src/test/java'
    New-Item -ItemType Directory -Path $testDestination -Force | Out-Null
    foreach ($item in @(Get-ChildItem -LiteralPath $testSource -Force)) {
        Copy-Item -LiteralPath $item.FullName -Destination $testDestination -Recurse -Force
    }

    if ($Mode -eq 'fixed') {
        $fixRoot = Join-Path $PSScriptRoot '修复文件'
        $manifestPath = Join-Path $fixRoot 'manifest.json'
        if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
            $manifestPath = Join-Path $PSScriptRoot 'manifest.json'
        }
        if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
            throw 'fixed 模式缺少修复 manifest.json，请完整解压交付包。'
        }
        $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
        if (@($manifest.files).Count -eq 0) { throw '修复清单 files 为空。' }
        $changes = @()
        foreach ($entry in @($manifest.files)) {
            $relative = ([string]$entry.path).Replace('\', '/')
            if (-not $relative.StartsWith('src/main/java/', [System.StringComparison]::Ordinal)) {
                throw "修复清单只允许 src/main/java 内的文件：$relative"
            }
            if ([string]$entry.beforeSha256 -notmatch '^[a-fA-F0-9]{64}$' -or
                [string]$entry.afterSha256 -notmatch '^[a-fA-F0-9]{64}$') {
                throw "修复清单中的 SHA-256 无效：$relative"
            }
            $target = Get-ContainedPath -Root $project -RelativePath $relative
            $replacement = Get-ContainedPath -Root $fixRoot -RelativePath $relative
            $allowedJavaRoot = [System.IO.Path]::GetFullPath((Join-Path $project 'src/main/java'))
            $targetPrefix = $allowedJavaRoot.TrimEnd([char[]]'\/') + [System.IO.Path]::DirectorySeparatorChar
            if (-not $target.StartsWith($targetPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
                throw "修复路径超出 Java 源目录：$relative"
            }
            if (-not (Test-Path -LiteralPath $target -PathType Leaf) -or
                -not (Test-Path -LiteralPath $replacement -PathType Leaf)) {
                throw "修复目标或替换文件缺失：$relative"
            }
            $replacementHash = (Get-FileHash -LiteralPath $replacement -Algorithm SHA256).Hash
            if ($replacementHash -ine [string]$entry.afterSha256) {
                throw "交付修复文件的哈希不匹配：$relative"
            }
            $currentHash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash
            if ($currentHash -ieq [string]$entry.afterSha256) {
                $state = 'already-fixed'
                Write-RunLog "已是修复内容，保留副本文件：$relative"
            } elseif ($currentHash -ieq [string]$entry.beforeSha256) {
                Copy-Item -LiteralPath $replacement -Destination $target -Force
                if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ine [string]$entry.afterSha256) {
                    throw "副本替换后的哈希不匹配：$relative"
                }
                $state = 'applied'
                Write-RunLog "已在副本应用修复：$relative"
            } else {
                throw "原文件版本不匹配，已停止；不会覆盖未知修改：$relative；实际 SHA-256=$currentHash"
            }
            $changes += [ordered]@{ path = $relative; state = $state; beforeSha256 = $entry.beforeSha256; afterSha256 = $entry.afterSha256 }
        }
        $runInfo.appliedFixes = $changes
        $runInfo.manifest = $manifestPath
        Save-RunInfo
    }

    if ($PrepareOnly) {
        Write-RunLog '准备与校验完成；PrepareOnly 模式没有运行 Maven，也没有生成测试结果。'
        $runInfo.status = 'prepared'
        $exitCode = 0
    } else {
        Set-Location -LiteralPath $project
        $wrapper = Join-Path $project 'mvnw.cmd'
        if (Test-Path -LiteralPath $wrapper -PathType Leaf) {
            $maven = $wrapper
        } else {
            $mavenCommand = Get-Command mvn.cmd -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($null -eq $mavenCommand) { throw '副本没有 mvnw.cmd，PATH 中也找不到 mvn.cmd。' }
            $maven = $mavenCommand.Source
        }
        $mavenArguments = @('-B', '-Dstyle.color=never', '-Dfile.encoding=UTF-8', ('-Dtest=' + $testSelection), 'test')
        $runInfo.status = 'running'
        $runInfo.mavenExecutable = $maven
        $runInfo.mavenArguments = $mavenArguments
        Save-RunInfo
        Write-RunLog ('运行 Maven：' + $maven + ' ' + ($mavenArguments -join ' '))
        $nativePreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            & $maven @mavenArguments 2>&1 | ForEach-Object { Write-RunLog $_.ToString() }
            $exitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $nativePreference
        }
        $runInfo.mavenExitCode = $exitCode
        if ($exitCode -eq 0) { $runInfo.status = 'passed' } else { $runInfo.status = 'failed' }
        Write-RunLog "Maven 退出码：$exitCode"
        Write-RunLog ('逐项 XML：' + $runInfo.surefireDirectory)
    }
} catch {
    $exitCode = 1
    $message = $_.Exception.Message
    if ($null -ne $runInfo) { $runInfo.status = 'error'; $runInfo.error = $message }
    Write-RunLog ('已停止：' + $message)
    Write-Error -Message $message -ErrorAction Continue
} finally {
    if ($null -ne $runInfo) {
        $runInfo.finishedAt = [DateTimeOffset]::Now.ToString('o')
        $runInfo.exitCode = $exitCode
        Save-RunInfo
    }
    Set-Location -LiteralPath $oldLocation.Path
    $env:JAVA_HOME = $oldJavaHome
    $env:PATH = $oldPath
}
exit $exitCode
