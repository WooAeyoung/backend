package kr.co.wooaeyoung.ds;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Separate-chaining hash table used for deterministic catalog identity lookup. */
public final class HashTable<K, V> {
    private static final int DEFAULT_CAPACITY = 31;
    private final List<List<Entry<K, V>>> buckets;

    public HashTable() { this(DEFAULT_CAPACITY); }
    public HashTable(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        buckets = new ArrayList<>(capacity);
        for (int i = 0; i < capacity; i++) buckets.add(new ArrayList<>());
    }
    public void put(K key, V value) {
        List<Entry<K, V>> bucket = buckets.get(slot(key));
        for (int i = 0; i < bucket.size(); i++) {
            if (Objects.equals(bucket.get(i).key(), key)) { bucket.set(i, new Entry<>(key, value)); return; }
        }
        bucket.add(new Entry<>(key, value));
    }
    public V get(K key) {
        for (Entry<K, V> entry : buckets.get(slot(key))) if (Objects.equals(entry.key(), key)) return entry.value();
        return null;
    }
    public boolean containsKey(K key) { return get(key) != null; }
    private int slot(K key) { return Math.floorMod(Objects.hashCode(key), buckets.size()); }
    private record Entry<K, V>(K key, V value) {}
}
