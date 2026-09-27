package minidb.storage;

/** 索引元数据：名字、所在列、B+ 树根页号（阶段2 使用）。 */
public record IndexMeta(String name, String column, int rootPage) {}
