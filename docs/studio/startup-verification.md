# MiniDB Studio 启动流程验证

以下命令均在仓库根目录的 PowerShell 中执行。验证前请先确认没有需要保留的服务正在使用
8080 端口；测试结束后，在任务栏关闭 `MiniDB-Studio-Server` 窗口即可停止服务。

## 1. 正常启动

```powershell
cmd /c 'set MINIDB_STUDIO_NO_BROWSER=1&&set MINIDB_STUDIO_NO_PAUSE=1&&"启动 MiniDB Studio.bat"'
$health = Invoke-RestMethod http://127.0.0.1:8080/api/v1/health
$health.success -eq $true -and
  $health.data.status -eq 'ok' -and
  $health.data.service -eq 'minidb-studio'
```

预期：启动脚本退出码为 `0`，显示“已确认 MiniDB Studio 健康接口”，最后一个表达式输出
`True`。去掉 `MINIDB_STUDIO_NO_BROWSER=1` 后双击启动时应自动打开 Studio 首页。

## 2. 8080 端口被占用

先保持上一步的 MiniDB Studio 服务运行，再从另一个 PowerShell 窗口执行：

```powershell
cmd /c 'set MINIDB_STUDIO_NO_PAUSE=1&&"启动 MiniDB Studio.bat"'
$LASTEXITCODE
```

预期：脚本在启动新的 Java 服务前即报告“端口 8080 已被占用”，不打开浏览器，退出码为
`1`。也可用任意监听 8080 的程序代替第一份 MiniDB Studio 服务。

## 3. 服务启动失败或超时

通过 Java 自带的 `JAVA_TOOL_OPTIONS` 注入一个无效虚拟机参数，可以安全触发启动失败，而不改动
正式构建产物：

```powershell
cmd /c 'set JAVA_TOOL_OPTIONS=-XX:DefinitelyNotAMiniDbOption&&set MINIDB_STUDIO_STARTUP_TIMEOUT=3&&set MINIDB_STUDIO_NO_BROWSER=1&&set MINIDB_STUDIO_NO_PAUSE=1&&"启动 MiniDB Studio.bat"'
$LASTEXITCODE
```

预期：Java 启动失败；健康检查等待 3 秒后报告“未能在限定时间内通过健康检查”，不打开
浏览器，退出码为 `1`。常规双击启动仍使用 30 秒超时；测试变量只用于缩短验证时间。
