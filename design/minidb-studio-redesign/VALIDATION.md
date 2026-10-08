# MiniDB Studio 重设计验证记录

验证日期：2026-10-09（Asia/Shanghai）

本文件区分四类结论：仓库基线、当前产品运行、设计原型、正式产品回归。原型验证通过不等于正式 React 前端已完成改版或通过回归。

## 1. 仓库与隔离性

### 实际命令

```powershell
git fetch --prune origin
git remote show origin
git status --short --branch
git rev-list --left-right --count HEAD...origin/main
git rev-parse HEAD
git worktree add --detach 'D:\MiniDB-design-review' origin/main
```

### 结果

- 远端：`https://github.com/glh5835/minidb.git`
- 默认分支：`main`
- 基线：`c1cdb526ba2d0a8fdff615ad52c588aec9a88302`
- 本地 `main` 与 `origin/main`：ahead `0` / behind `0`
- 原仓库工作区：干净
- 设计 worktree：`D:\MiniDB-design-review`，detached HEAD
- 本轮文件仅位于 `design/minidb-studio-redesign/`（工具生成的 `target/` 和专用测试数据库不作为交付内容）
- 未执行 reset、clean、stash、merge、commit、push、PR 或部署

## 2. 当前产品运行与改版前截图

### 服务启动

使用已有包：

```powershell
& 'D:\MiniDB\tools\jdk-17.0.20.1+1\bin\java.exe' `
  '-Dfile.encoding=UTF-8' `
  -jar 'D:\MiniDB\target\minidb-0.1.0-SNAPSHOT-studio.jar' `
  --port 8080 `
  --data-dir 'D:\MiniDB-design-review\.review-runtime'
```

健康接口实际响应：

```json
{
  "success": true,
  "data": {
    "status": "ok",
    "service": "minidb-studio",
    "version": "0.1.0"
  }
}
```

因此没有把“8080 可连接”误写成启动成功。没有关闭其他占用端口的程序；本轮启动前 8080 未被占用。

注意：运行截图使用的是原仓库已存在的 Studio JAR（文件时间 2026-10-05），没有为设计任务重建正式前端。当前基线最新提交 `c1cdb52` 修改的是启动后健康验证脚本；本轮另外对该 SHA 的 Java 源码执行了完整 Maven 测试。运行截图与当前 React 源码的页面结构一致，但不把已有 JAR 的时间戳伪装成本轮构建产物。

### 专用测试数据

- 创建的示例库只用于本轮截图，位于隔离 worktree。
- 实际发现 `createSample('')` 把 `school.db` 解析到服务工作目录，而非 `--data-dir`；已记录在 `DESIGN_SPEC.md`，没有改代码。
- 截图结束后服务已停止，`school.db` / `.wal` 与 `.review-runtime` 已从隔离 worktree 移除，不交付数据库文件。

### 改版前截图

| 页面 | 视口 | 文件 |
| --- | --- | --- |
| 开始页 | 1440×900 | `screenshots/baseline/before-start-1440x900.png` |
| 数据库管理 / student | 1440×900 | `screenshots/baseline/before-database-1440x900.png` |
| SQL 工作台 / 未执行 | 1440×900 | `screenshots/baseline/before-sql-1440x900.png` |

机器报告：`screenshots/baseline/baseline-capture.json`。报告通过，记录了 URL、视口、页面、标题和健康响应。

## 3. 当前源码测试

在隔离 worktree 的当前 SHA 执行：

```powershell
$env:JAVA_HOME='D:\MiniDB\tools\jdk-17.0.20.1+1'
$env:Path='D:\MiniDB\tools\jdk-17.0.20.1+1\bin;D:\MiniDB\tools\apache-maven-3.9.16\bin;' + $env:Path
& 'D:\MiniDB\tools\apache-maven-3.9.16\bin\mvn.cmd' `
  -s 'D:\MiniDB\tools\mvn-settings.xml' test
```

实际输出：

```text
Tests run: 369, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 31.927 s
Finished at: 2026-10-09T00:17:01+08:00
```

这是当前 Java / Web service 源码测试，不是重设计后的正式前端回归；本轮没有修改正式代码。

## 4. 高保真原型浏览器验证

浏览器：Microsoft Edge `154.0.4258.62`，通过本地临时 HTTP server 加载，无外部网络资源。

最终命令：

```powershell
python 'design\minidb-studio-redesign\tools\validate_prototype.py' `
  --root 'design\minidb-studio-redesign' `
  --output-base 'design\minidb-studio-redesign\screenshots'
```

最终报告：`screenshots/qa-run-03/prototype-validation.json`

```text
passed: true
checks: 32
captures: 26
console_errors: 0
page_errors: 0
```

### 视口与缩放

- 1366×768：开始页
- 1440×900：开始、数据库、SQL、索引、六类实验、帮助、关键状态
- 1920×1080：开始页
- 1024×768：开始、数据库、SQL 的退化布局
- 125% CSS zoom：开始页主任务仍可达
- 150% CSS zoom：SQL 主执行按钮仍可达

CSS zoom 用于可重复模拟有效画布缩小；同时 1024px 视口约等价于 1440px 窗口在约 140% 浏览器缩放下的布局压力。没有声称完成所有浏览器 / 操作系统的无障碍认证。

### 交互通过项

- 主导航与 11 个 route view 均可达；任一时刻只有一个主视图 active。
- 实验分组可展开 / 折叠；帮助固定在主导航底部。
- 数据库对象列表选择可切换；数据 / 结构 / 索引页签可切换。
- 宽表在 `.data-grid-wrap` 内横向滚动，整页不横向溢出。
- 长表名 `semester_enrollment_archive_2026` 有截断策略。
- 表格包含 BIGINT `9223372036854775807`、`9007199254740993`，以等宽字符串展示。
- `NULL`、空字符串和长内容分别展示。
- 删除记录确认弹窗实际打开，并包含 `school.db`、`student`、`RID 3/0` 和影响范围。
- SQL 五状态均切换成功：未执行、执行中、成功、错误、结果为空。
- 多行 SQL 错误展示行列、原始片段和指示线。
- `Ctrl+Enter` 实际触发原型执行中状态，并转为成功状态。
- 开始事务后，SQL 工具栏与全局顶栏同步显示 ACTIVE；回滚后恢复自动提交。
- 存储与事务两个不同布局的完整实验页可达；另外四类实验均有真实内容和入口，不是空占位。
- 三个视觉方向均以同一 1440×900 视口检查开始页与 SQL 工作台。

### 关键改版后截图

| 场景 | 文件 |
| --- | --- |
| 开始页 1440×900 | `screenshots/qa-run-03/after-start-1440x900.png` |
| 数据库管理 1440×900 | `screenshots/qa-run-03/after-database-1440x900.png` |
| 数据库管理 1024×768 | `screenshots/qa-run-03/after-database-1024x768.png` |
| SQL 成功 | `screenshots/qa-run-03/after-sql-success-1440x900.png` |
| SQL 错误 | `screenshots/qa-run-03/after-sql-error-1440x900.png` |
| SQL 150% zoom | `screenshots/qa-run-03/after-sql-zoom-150-1440x900.png` |
| 索引与计划 | `screenshots/qa-run-03/after-index-1440x900.png` |
| 存储实验 | `screenshots/qa-run-03/after-storage-lab-1440x900.png` |
| 事务实验 | `screenshots/qa-run-03/after-txn-lab-1440x900.png` |
| A / B / C 开始页与 SQL | `screenshots/qa-run-03/style-*.png` |

`qa-run-01` 和 `qa-run-02` 被保留作为迭代证据；首轮唯一自动化问题是默认 favicon 404，第二轮修正；第三轮增加缩放检查并最终通过。

## 5. Frontend Slides 评审稿验证

最终命令：

```powershell
python 'C:\Users\LEGION\.agents\skills\frontend-slides\codex\scripts\slides.py' validate `
  'design\minidb-studio-redesign\review.html' `
  'design\minidb-studio-redesign\screenshots\review-qa-03' `
  --browser edge
```

最终报告：`screenshots/review-qa-03/validation.json`

```text
passed: true
slides: 11
captures: 33
viewports: 1920×1080, 1280×720, 390×844
page_errors: 0
blocked_external: 0
layout issues: 0
```

自动检查覆盖：固定舞台 16:9、元素溢出、图片加载、`data-panel` 重叠、外部资源和页面错误。最终接触表 `screenshots/review-qa-03/contact-sheet.png` 已人工检查；标题、截图、表格、边界说明和页码均可读。

`review-qa-01` 保留了首轮字体行盒溢出证据；通过增加标题底部行盒空间修正。`review-qa-02` 通过；`review-qa-03` 在评审稿切换为最终 `qa-run-03` 截图后再次通过。

## 6. 源码与交付边界审计

最终要求再次执行：

```powershell
git status --short
git diff --name-only
git diff --stat
```

最终实际结果：

```text
## HEAD (no branch)
?? design/
```

`git diff --name-only` 与 `git diff --stat` 均为空；没有已跟踪文件差异。额外过滤检查没有发现 `design/` 以外的未跟踪项；递归检查没有发现交付目录或 worktree 中遗留的 `.db` / `.db.wal`。因此正式业务代码、Java 内核、启动脚本、API、依赖文件和锁文件均未被修改。

## 7. 未验证 / 不应误读

- 未把原型集成进正式 React 应用；因此没有“重设计后的正式产品回归通过”结论。
- 未运行正式前端 `npm run build`：本轮没有修改正式前端，隔离 worktree 也没有安装 `node_modules`；没有为此新增或安装依赖。
- 未执行真实数据库写入、删除、SQL、索引创建、实验重置或 crash；原型只改变前端内存状态。
- 未在 Firefox / Chrome / Safari 做跨浏览器矩阵；本轮实际浏览器为本机 Edge。
- 未做屏幕阅读器、Windows 高对比度或正式 WCAG 审计；已检查键盘焦点、语义标签、非颜色状态和 reduced motion。
- 未部署、发布、commit、push 或创建 PR。

当前结论：**设计评审稿与本地高保真原型已通过本轮浏览器和幻灯片验证；正式前端尚未修改，等待审核。**
