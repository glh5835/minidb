package minidb.exec;

import minidb.storage.Row;

/**
 * 火山模型算子接口（阶段1 引入雏形，阶段3 全面展开）：
 * open() 初始化 → 反复 next() 拉取下一行（null 结束）→ close() 释放资源。
 */
public interface Op extends AutoCloseable {
    void open();

    /** 返回下一行；没有更多行时返回 null。 */
    Row next();

    @Override
    void close();
}
