package minidb.storage;

import minidb.common.Rid;

import java.util.Iterator;

/**
 * 表抽象：支持增删改查与全表扫描。
 * 定长记录表（FixedTable）与变长记录表（VarTable）都实现本接口；
 * RID 在记录存续期间稳定（变长表 UPDATE 放不下原页时会返回新 RID）。
 */
public interface Table {
    Schema schema();

    /** 插入一行，返回 RID。 */
    Rid insert(Object[] row);

    /** 删除指定行；RID 不存在抛 RECORD 异常。 */
    void delete(Rid rid);

    /** 更新指定行为 newRow；可能移动到别的页，返回（新）RID。 */
    Rid update(Rid rid, Object[] newRow);

    /** 读取指定行；RID 不存在抛 RECORD 异常。 */
    Object[] get(Rid rid);

    /** 全表扫描（逻辑顺序 = 页链 + 槽序）。 */
    Iterator<Row> scan();

    int rowCount();

    /** 供上层调试/统计 */
    int pageCount();

    /** 页内空间统计接口（阶段2+ 调试用） */
    long totalFreeBytes();
}
