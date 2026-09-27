package minidb.exec;

import java.util.List;

/** 执行引擎算子接口（火山模型）。columns() 声明输出列名（形如 "t.col" 或别名）。 */
public interface ExecOp extends AutoCloseable {
    void open();

    /** 下一行（值为 Object[]）；无更多行返回 null。 */
    Object[] next();

    List<String> columns();

    @Override
    void close();

    /** 计划描述（EXPLAIN 用） */
    default String describe() {
        return getClass().getSimpleName();
    }

    /** 子算子（EXPLAIN 遍历用） */
    default List<ExecOp> children() {
        return List.of();
    }

    /** 单子算子（Filter/Project/Sort 等）；无则抛 IllegalStateException。 */
    default ExecOp child() {
        throw new IllegalStateException(getClass().getSimpleName() + " 无子算子");
    }

    static void closeQuietly(ExecOp op) {
        try {
            if (op != null) op.close();
        } catch (RuntimeException ignored) {
        }
    }
}

