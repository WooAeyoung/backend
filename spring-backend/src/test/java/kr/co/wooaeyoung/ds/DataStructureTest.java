package kr.co.wooaeyoung.ds;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DataStructureTest {
    @Test void hashTableSupportsCollisionAndReplacement() {
        HashTable<String, Integer> table = new HashTable<>(1);
        table.put("a", 1); table.put("b", 2); table.put("a", 3);
        assertEquals(3, table.get("a")); assertEquals(2, table.get("b")); assertTrue(table.containsKey("b"));
    }
    @Test void maxHeapReturnsDescendingPriority() {
        MaxHeap<Integer> heap = new MaxHeap<>(Integer::compareTo);
        heap.push(2); heap.push(9); heap.push(4);
        assertEquals(9, heap.pop()); assertEquals(4, heap.pop()); assertEquals(2, heap.pop()); assertTrue(heap.isEmpty());
    }

    @Test void trieFindsProductIdsByPrefix() {
        TrieIndex trie = new TrieIndex();
        trie.add("데일리 밸런스 독", "feed-dog");
        trie.add("데일리 멀티", "supp-multi");
        assertEquals(List.of("feed-dog", "supp-multi"), trie.find("데일리"));
    }
}
