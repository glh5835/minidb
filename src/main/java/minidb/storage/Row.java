package minidb.storage;

import minidb.common.Rid;

/** 一行数据：RID + 值数组。 */
public record Row(Rid rid, Object[] values) {}
