package minidb.common;

import java.util.Objects;

/** 行标识：页号 + 槽号。页内槽号在记录存续期间保持稳定（更新可能改变页号）。 */
public record Rid(int pageId, int slot) implements Comparable<Rid> {
    @Override
    public int compareTo(Rid o) {
        int c = Integer.compare(pageId, o.pageId);
        return c != 0 ? c : Integer.compare(slot, o.slot);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Rid r)) return false;
        return pageId == r.pageId && slot == r.slot;
    }

    @Override
    public int hashCode() {
        return Objects.hash(pageId, slot);
    }

    @Override
    public String toString() {
        return "(" + pageId + "," + slot + ")";
    }
}
