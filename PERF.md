# MiniDB 性能报告（PERF.md）

环境：Windows 11 x64 / Temurin JDK 17.0.20.1 / 单线程
数据：`lineitem(id INT, k INT, val BIGINT)` 1,000,000 行（`k` 为 0..99999 随机值，无索引）
缓冲池：4096 页（16 MB）；B+ 树：变长键布局（8 字节键约 185 键/叶，键上限 255 字节）
压测程序：`minidb.perf.Stress1M`（100 万行）+ JMH（`minidb.bench.DbBenchmarks`，10 万行）

## 一、100 万行压测（真实耗时）

| 项目 | 耗时 | 吞吐 |
|------|------|------|
| 插入 1,000,000 行（Table API，经缓冲池） | **514 ms** | 194 万行/秒 |
| CREATE INDEX（10 万随机键 B+ 树构建） | **477 ms** | 树高 3，叶 7812，利用率 50.2% |
| Table API 全表扫描 1,000,000 行 | **34 ms** | 2941 万行/秒（校验和一致） |

## 二、走索引 vs 全表扫描（核心对比，SQL 层端到端耗时）

| 查询类型 | 走索引 | 全表扫描 | 加速比 |
|----------|--------|----------|--------|
| 点查 `WHERE id = ?` | **0.055 ms/次**（2000 次，110 ms） | **45 ms/次**（5 次平均，225 ms） | **≈ 818×** |
| 范围查 `WHERE id >= a AND id < a+1000` | **0.222 ms/次**（500 次，111 ms） | **42 ms/次**（5 次平均，209 ms） | **≈ 189×** |

结论：点查走 B+ 树（树高 3 → 3 次页访问 + 1 次取行）比全表扫描快约三个数量级；
范围查询合并上下界后只触碰约 5 个数据页。

> 附：实现范围谓词合并优化前，`id >= a AND id < a+1000` 只用下界走索引，
> 每次要扫约 33 万索引项（73 ms/次）。合并同列上下界后降至 0.222 ms/次（328×）。

## 三、JMH 微基准（avgt，µs/op，10 万行库）

| Benchmark | Score | Error |
|-----------|-------|-------|
| B+ 树等值查找（`bPlusTreePointSearch`） | **0.280 µs** | ±0.136 |
| B+ 树插入+删除（`bPlusTreeInsertDelete`） | **0.396 µs** | ±0.093 |
| 表层行插入+删除（`tableInsertDeleteRow`） | **1.020 µs** | ±1.489 |
| SQL 点查（解析→优化→索引扫描，`sqlPointQueryViaIndex`） | **1.551 µs** | ±0.417 |
| SQL 范围查 1000 行（`sqlRangeViaIndex`） | **49.5 µs** | ±11.0 |
| 全表扫描 1 万行（`tableFullScan10k`） | **314 µs** | ±246 |

观察：
- SQL 点查 1.55 µs 中约 0.28 µs 是 B+ 树查找，其余为 SQL 解析、计划构造与结果封装——对教学实现是合理开销。
- B+ 树节点 4KB、叶 255 键，10 万键树高仅 3，每次点查 3 页且几乎全部命中缓冲池。

## 四、增强轮实测（P1~P3：Hash Join / 变长键索引 / steal 协议）

| 项目 | 结果 | 口径 |
|------|------|------|
| 等值连接 2 万 × 5 千行（HashJoin） | **13.0 ms/次** | 3 次暖机后 5 次平均，fsync 关 |
| 同规模纯嵌套循环（谓词改写绕开哈希） | 25,231 ms/次 | 同口径 |
| **连接加速比** | **≈ 1900×** | 1 亿行组合 → 哈希探测 |
| 小事务提交吞吐（2 UPDATE + commit） | **≈ 8,700 事务/秒** | fsync 关 |
| 转账压测 20 线程 × 10 万次（总额守恒） | **9.9 s**（no-force 前 ≈110 s，**≈11×**） | @Timeout 120s 内 |
| 10 万随机键 SQL 逐行插入 + 建索引 | 1.52 s + 树高 3 | 变长键布局（8B 键约 185 键/叶） |

Hash Join 说明：ON 等值合取项自动走 `HashJoin`（EXPLAIN 可见），非等值 ON 回落 `NestedLoopJoin`；
上表 NLJ 对照用 `o.uid * 2 = u.id * 2`（两侧非裸列，规划器无法提取等值键）以保持同数据同语义。

steal/no-force 说明：commit 只 fsync 日志（no-force），未提交脏页可淘汰（steal，经 FlushHook
保证 WAL 先写）；事务新建页及其前驱页在提交时 mini-force。转账压测的提升主要来自
commit 不再逐页强制刷盘。

## 五、已知性能取舍

1. 无索引列的等值/范围查询只能全表扫描（每行 16 字节时约 2900 万行/秒，仍很快）。
2. JOIN 谓词不探测索引（WHERE 谓词才走索引）；非等值连接仍是 O(n·m) 嵌套循环。
3. 排序/去重/哈希聚合为内存物化，超大数据集需外部归并（未实现）。
4. fsync 开启时每笔提交 2 次 fsync（DML 日志 + COMMIT），持久性优先；压测/批量导入可关闭（进程崩溃仍安全）。

复现方式：
```bash
mvn -q test-compile
mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
java -Xmx2g -cp target/classes minidb.perf.Stress1M target/stress
java -cp "target/test-classes;target/classes;$(cat target/cp.txt)" org.openjdk.jmh.Main minidb.bench -f 1 -wi 2 -i 3 -r 1 -w 1
```
