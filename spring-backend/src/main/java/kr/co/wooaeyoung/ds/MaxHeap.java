package kr.co.wooaeyoung.ds;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Binary max heap for recommendation candidate ranking. */
public final class MaxHeap<T> {
    private final List<T> values = new ArrayList<>();
    private final Comparator<T> comparator;
    public MaxHeap(Comparator<T> comparator) { this.comparator = comparator; }
    public boolean isEmpty() { return values.isEmpty(); }
    public int size() { return values.size(); }
    public void push(T value) {
        values.add(value); int index = values.size() - 1;
        while (index > 0) {
            int parent = (index - 1) / 2;
            if (comparator.compare(values.get(parent), value) >= 0) break;
            values.set(index, values.get(parent)); index = parent;
        }
        values.set(index, value);
    }
    public T pop() {
        if (values.isEmpty()) return null;
        T top = values.get(0), last = values.remove(values.size() - 1);
        if (!values.isEmpty()) {
            int index = 0;
            while (true) {
                int left = index * 2 + 1, right = left + 1;
                if (left >= values.size()) break;
                int best = right < values.size() && comparator.compare(values.get(right), values.get(left)) > 0 ? right : left;
                if (comparator.compare(last, values.get(best)) >= 0) break;
                values.set(index, values.get(best)); index = best;
            }
            values.set(index, last);
        }
        return top;
    }
}
