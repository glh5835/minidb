# MiniDB Studio 前端美术与交互重设计

本目录是设计评审交付物，不会替换正式前端。建议先打开：

1. [`review.html`](review.html) — 11 页 Frontend Slides 设计评审稿；方向键、空格、滚轮或触摸翻页。
2. [`prototype/index.html`](prototype/index.html) — 推荐方向 A「玉白·松绿」的可点击高保真工作台原型。
3. [`style-previews/index.html`](style-previews/index.html) — A / B / C 三种方向，均可切换开始页和 SQL 工作台。
4. [`DESIGN_SPEC.md`](DESIGN_SPEC.md) — 完整功能映射、设计变量、组件规范与实施顺序。
5. [`VALIDATION.md`](VALIDATION.md) — 实际命令、浏览器验证、截图索引与未验证项。

Windows 预览命令：

```powershell
Start-Process -FilePath 'D:\MiniDB-design-review\design\minidb-studio-redesign\review.html'
Start-Process -FilePath 'D:\MiniDB-design-review\design\minidb-studio-redesign\prototype\index.html'
```

所有资源均为本地相对路径；没有 CDN、在线字体、云端接口或公开部署。原型顶部持续显示“设计原型 / 示例数据 / 不会执行数据库命令”。

## 仓库基线

| 项目 | 实际值 |
| --- | --- |
| 原仓库 | `D:\MiniDB` |
| 隔离 worktree | `D:\MiniDB-design-review` |
| 远端 | `https://github.com/glh5835/minidb.git` |
| 默认 / 设计基线分支 | `origin/main`（隔离 worktree 为 detached HEAD） |
| 完整 commit SHA | `c1cdb526ba2d0a8fdff615ad52c588aec9a88302` |
| fetch 后 ahead / behind | `0 / 0` |
| 基线工作区 | 原仓库干净；无未推送提交 |
| `AGENTS.md` | 仓库内未找到 |

本轮执行时已成功 `git fetch --prune origin` 并核对 `origin/HEAD -> origin/main`；因此以上 SHA 是本轮可确认的最新远端基线，而不是历史快照猜测。

## Frontend Slides

| 项目 | 实际值 |
| --- | --- |
| 官方来源 | `https://github.com/zarazhangrui/frontend-slides` |
| 安装路径 | `C:\Users\LEGION\.agents\skills\frontend-slides` |
| 固定快照 | `9906a34d640d2111f724544cbc50f7f130569ae1` |
| 许可证 | MIT |
| 本轮采用 | fixed 1920×1080 stage、三种可视方向、Emerald Editorial 的纸白 / 绿 / 深蓝 / 规则线原则、克制揭示动效、三视口验证 |

评审稿只借鉴 Emerald Editorial 的编辑排版语法；应用原型仍遵守正常工作台布局、滚动和键盘规则，没有套用幻灯片的固定画布或翻页快捷键。未修改全局 Skill、Codex 配置或依赖。

## 交付边界

- 未修改正式 React 页面、Java 内核、API、SQL 语义、REPL、JDBC、数据库文件格式、启动脚本、`package.json`、锁文件或 `pom.xml`。
- 未安装或新增第三方依赖；原型只使用 HTML / CSS / JavaScript 与本地 SVG。
- 未 commit、push、创建 PR、部署或发布版本。
- 原型中的表数据、耗时、计划和实验输出均明确为示例；不会向 MiniDB 服务发送读写、删除、重置或 SQL 请求。
- 当前状态：**设计方案与原型待审核**。未经明确批准，不进入正式前端替换阶段。

## 目录

```text
design/minidb-studio-redesign/
├─ README.md
├─ review.html
├─ DESIGN_SPEC.md
├─ VALIDATION.md
├─ SHA256SUMS.txt
├─ prototype/
│  ├─ index.html
│  ├─ styles.css
│  └─ app.js
├─ style-previews/
│  ├─ index.html
│  ├─ a-jade.html
│  ├─ b-academy.html
│  ├─ c-inklab.html
│  ├─ preview.css
│  └─ preview.js
├─ screenshots/
│  ├─ baseline/
│  ├─ qa-run-01/       # 保留的首轮迭代证据
│  ├─ qa-run-02/       # 修正 favicon 后的中间验证
│  ├─ qa-run-03/       # 最终原型浏览器验证（含缩放）
│  ├─ review-qa-01/    # 保留的首轮幻灯片验证
│  ├─ review-qa-02/    # 通过的中间 Frontend Slides 验证
│  └─ review-qa-03/    # 最终 Frontend Slides 验证
└─ tools/
   ├─ capture_baseline.py
   └─ validate_prototype.py
```
