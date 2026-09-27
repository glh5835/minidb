# MiniDB —— 从零手写的关系型数据库

纯 Java（JDK 17）+ Maven + JUnit 5 实现的教学向关系型数据库内核，严格按照《任务书》自底向上分阶段开发。**每个阶段结束时都能在 REPL 里真跑通**，全部代码不依赖任何数据库库。

## 当前进度

| 阶段 | 内容 | 状态 |
|------|------|------|
| 1 | 存储层：页式文件 / BufferPool / 定长+变长表 / REPL | ✅ 完成（127 项测试） |
| 2 | B+ 树索引：分裂 / 借位合并 / 范围扫描 / 统计接口 | ✅ 完成（累计 163 项测试） |
| 3 | SQL 解析器 + 火山模型执行引擎 + 代价优化器 | ⏳ 待开始 |
| 4 | 200 条端到端 SQL 测试 + 100 万行压测 + JMH | ⏳ 待开始 |
| 5 | WAL + 2PL + 死锁检测 + 崩溃恢复 | ⏳ 待开始 |
| 6 | JDBC 驱动 | ⏳ 待开始 |

## 快速开始

```bash
# 构建 + 全量测试（163 项）
mvn test

# 启动 REPL（阶段1手工计划版）
mvn -q exec:java -Dexec.mainClass=minidb.repl.Repl   # 或直接 java -cp target/classes minidb.repl.Repl
```

REPL 命令（手工构造查询计划，无 SQL 解析——解析器在阶段 3 引入）：

```
.open mydb.db                      打开/创建数据库
.create t id:int,name:varchar(20)  建表
.insert t 1, 'alice'               插入
.scan t                            全表扫描（SeqScan 计划）
.get t 3 0                         按 RID=(页3,槽0) 读
.delete t 3 0                      按 RID 删
.update t 3 0 2, 'bob'             按 RID 改
.count t / .pages t / .stats       统计
```

## 阶段 1：存储层（已完成）

- **Page**：固定 4KB，带 dirty/pinCount/pageLsn（pageLsn 供阶段 5 WAL 使用）。
- **DiskManager**：页粒度随机读写，文件即页的线性序列。
- **StorageEngine**：自管理页分配与回收。页 0 元数据 + 页 1 目录 + 链式**空闲空间位图页**（每页覆盖 32736 个页号，自举分配）。回收页磁盘清零，防止旧数据泄漏。
- **BufferPool**：LRU 淘汰（LinkedHashMap accessOrder）+ 脏页写回 + **引用计数**（pin>0 不可淘汰；全部 pin 时抛 BUFFER_FULL，作为故障注入点）。带命中/淘汰/写回统计与 FlushHook（阶段 5 的"先写日志"挂点）。
- **Table 抽象**：
  - `FixedTable` 定长记录：定长槽 + 页尾占用位图，原地更新，**RID 永不变化**。
  - `VarTable` 变长记录（VARCHAR）：slotted page（槽位数组向上、记录向下），空槽空闲链 + 空洞统计，放不下时页内**压缩**（槽号不变），UPDATE 放不下原页时迁移并返回新 RID。
  - 同表数据页用双向链表串联，全空页自动归还存储引擎。
- **Catalog**：目录页链持久化表 schema / firstPage / 索引元数据，支持多页溢出。
- **REPL**：读一行执行一行，手工构造 SeqScan 火山算子执行。
- **验证**：定长/变长表各插入 **10 万条记录**再全部读回，逐字段比对一致（`Phase1LargeDataTest`）。

## 阶段 2：B+ 树索引（已完成）

- **BPlusTree**（`minidb.btree`）：key = long，value = 行 RID。
  - 节点即 4KB 页（内部节点 fanout 338 / 叶 255），**全部读写通过 BufferPool**，绝不直接操作文件。
  - 插入：节点满即分裂（叶 push-right 保留分隔键在右节点；内部节点中间键上推），根分裂增高。
  - 删除：低于最小键数先向左右兄弟**借位**（旋转，父分隔键同步更新），借不到则**合并**（分隔键下坠），父递归处理，空内部根自动降级。
  - 叶节点双向链表 → 等值查找 / 有序全扫描 / **开闭边界可选的范围扫描**。
  - 统计接口：树高、内部/叶节点数、叶平均利用率、总键数；另有 `validate()` 结构不变量校验（键有序、子树边界、叶链双向一致）。
- **Database.createIndex / dropIndex**：对现有数据全表扫描建唯一索引（INT/BIGINT 列），索引根页登记进 Catalog 持久化；dropIndex/dropTable 释放整棵树节点页。
- **验证**：**随机插入 10 万 key 与 java.util.TreeMap 逐条比对**（等值查找、全序扫描、20 组随机范围扫描与 subMap 完全一致）；**随机删除 5 万 key 后再比对一次**；重开文件持久化验证；8 页小缓冲池下 3000 键压力正确性；坏页类型故障注入。

## 测试

```bash
mvn test    # 163 项测试全绿
```

覆盖：字节编解码边界、磁盘 IO 故障、缓冲池 LRU/pin/脏页、位图链跨页、定长/变长表边界（VARCHAR 超长、空洞压缩、页回收）、目录溢出、REPL 全命令、B+ 树分裂/借位/合并/降级/持久化/故障注入，以及两份 10 万级数据验证。

## 文件布局

```
src/main/java/minidb/
├── common/    Rid、Bytes(小端编解码)、统一异常
├── storage/   Page/DiskManager/StorageEngine/BufferPool + Table 抽象 + Catalog/Database
├── btree/     BPlusTree/BTreeNode（阶段 2）
├── exec/      火山算子（阶段 1 雏形：Op/SeqScanOp，阶段 3 全面展开）
└── repl/      REPL
```
