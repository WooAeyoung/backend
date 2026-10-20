package kr.co.wooaeyoung.ds;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Prefix index equivalent to the Python catalog Trie. */
public final class TrieIndex {
    private final Node root = new Node();

    public void add(String value, String productId) {
        Node node = root;
        for (char character : normalize(value).toCharArray())
            node = node.children.computeIfAbsent(character, ignored -> new Node());
        node.productIds.add(productId);
    }

    public List<String> find(String prefix) {
        Node node = root;
        for (char character : normalize(prefix).toCharArray()) {
            node = node.children.get(character);
            if (node == null) return List.of();
        }
        Set<String> result = new LinkedHashSet<>();
        collect(node, result);
        return List.copyOf(result);
    }

    private static void collect(Node node, Set<String> result) {
        result.addAll(node.productIds);
        node.children.values().forEach(child -> collect(child, result));
    }

    private static String normalize(String value) { return value == null ? "" : value.replace(" ", "").toLowerCase(); }
    private static final class Node {
        private final java.util.Map<Character, Node> children = new java.util.HashMap<>();
        private final Set<String> productIds = new LinkedHashSet<>();
    }
}
