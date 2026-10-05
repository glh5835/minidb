# Studio 前端设计

## 技术栈（计划书 §31）

React 18 + TypeScript + Vite 5 + CodeMirror 6（@uiw/react-codemirror + @codemirror/lang-sql）+ SVG。
无重量级 UI 框架；路由为 100 行内的 hash 路由（`src/router.ts`）。

## 目录

```
frontend/src/
├─ api/client.ts        统一 API 客户端（三通道会话、错误码 → ApiFailure）
├─ stores/app.tsx       全局状态（会话/表/最近列表/确认框/消息条/空闲同步）
├─ components/ui.tsx    布局骨架、页面状态组件、模态、PlanTree、Pager、CSV、历史
├─ router.ts            hash 路由
├─ types.ts             与后端协议对应的类型
├─ styles.css           视觉规范（见下）
└─ features/
   ├─ database/  StartPage（快速开始/最近库/引导）· HelpPage
   ├─ table/     DatabasePage（表列表 + 数据/结构/索引三视图 + RID 编辑）
   ├─ sql/       SqlPage（CodeMirror + 结果/消息/计划/历史 + 事务条）
   ├─ index/     IndexPlanPage（索引管理 + B+ 树 SVG + 执行计划）
   └─ labs/      StorageLab · SqlPipelineLab · TxnLab · RecoveryLab · PerfLab · JdbcLab
```

## 视觉规范（计划书 §39）

背景 #F7FAF8 · 内容区 #FFFFFF · 主色 #23866B · 边框浅灰绿 #D8E5DF · 危险红 #C0392B ·
圆角 10px · 少阴影 · 中文优先 · 正文 14px · SQL/数字等宽（--mono）·
一个页面一个主动作 · 空状态必有引导（"暂无数据 + 添加第一条记录"）。

## 关键交互

- **键盘**（§40）：Ctrl+Enter 执行 SQL；Esc 关闭对话框。焦点态 outline、状态不只靠颜色
  （连接/事务状态为 圆点+文字）、表单有 label。
- **响应式**（§41）：Desktop First，≤1280px 左侧导航折叠为图标；表格横向滚动。
- **危险操作**（§38）：删除库/表/索引/记录、回滚、重置实验库全部走二次确认，
  按钮文案具体（"删除 student 表"）。
- **BIGINT**（§35）：JSON 字符串传输，编辑框提示"整数（可超 JS 精度）"；结果中
  ≥15 位数字自动等宽字体展示。
- **RID 编辑**（§7.2）：编辑/删除按 RID 定位；后端返回 `migrated` 时 toast 提示
  新旧 RID；RID_STALE 提示刷新。
- **SQL 历史**（§12）：localStorage 保存最近 100 条（SQL/时间/库/成败/耗时），
  一键放回编辑器。
- **浏览器刷新恢复**（§43）：启动时以 sessionId 查询 /session；过期自动重建并提示。

## 会话通道兼容

客户端把 sessionId 同时放进自定义头、Cookie 与查询参数（`client.ts call()`），
服务器任选其一——解决内嵌浏览器剥离自定义头导致的会话丢失。

## 构建与集成（§44/§45）

开发：`npm run dev`（Vite :5173，/api 代理 :8080）。
正式：`npm run build` → dist → `src/main/resources/webroot/` → Maven shade fat jar，
由 Java 服务器直接提供静态页。缓存策略：index.html no-store；assets/* 一年 immutable
（文件名带 hash）；其余 no-cache。
