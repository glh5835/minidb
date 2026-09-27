# MiniDB 性能报告（PERF.md）

环境：Windows 11 x64 / Temurin JDK 17.0.20.1 / 单线程
数据：`lineitem(id INT, k INT, val BIGINT)` 1,000,000 行（`k` 为 0..99999 随机值，无索引）
缓冲池：4096 页（16 MB）；B+ 树：叶 255 键 / 内部 338 键
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

## 四、已知性能取舍

1. 无索引列的等值/范围查询只能全表扫描（每行 16 字节时约 2900 万行/秒，仍很快）。
2. 连接采用嵌套循环；JOIN 谓词不探测索引（WHERE 谓词才走索引），大表对大表连接是 O(n·m)。
3. 排序/去重为内存物化，超大数据集需外部归并（未实现）。
4. 未建 WAL 前，每次提交即整池刷盘由 `.flush`/close 触发；插入路径只写缓冲池（阶段 5 会引入写日志开销）。

复现方式：
```bash
mvn -q test-compile
mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
java -Xmx2g -cp target/classes minidb.perf.Stress1M target/stress
java -cp "target/test-classes;target/classes;$(cat target/cp.txt)" org.openjdk.jmh.Main minidb.bench -f 1 -wi 2 -i 3 -r 1 -w 1
```
