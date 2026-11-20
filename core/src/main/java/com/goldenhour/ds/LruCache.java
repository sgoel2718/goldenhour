package com.goldenhour.ds;

import java.util.HashMap;
import java.util.Map;

/**
 * Thread-safe LRU cache: hash map + doubly linked list, O(1) get/put/evict (LeetCode 146, production edition).
 * Caches hot routes such as "station 4 → city hospital" that are requested repeatedly.
 */
public final class LruCache<K, V> {
    private final class Node { K k; V v; Node prev, next; }

    private final int capacity;
    private final Map<K, Node> map = new HashMap<>();
    private final Node head = new Node(), tail = new Node();   // sentinels: head.next = most recent
    private long hits, misses;

    public LruCache(int capacity) {
        this.capacity = capacity;
        head.next = tail; tail.prev = head;
    }

    public synchronized V get(K k) {
        Node n = map.get(k);
        if (n == null) { misses++; return null; }
        hits++;
        unlink(n); addFront(n);
        return n.v;
    }

    public synchronized void put(K k, V v) {
        Node n = map.get(k);
        if (n != null) { n.v = v; unlink(n); addFront(n); return; }
        if (map.size() == capacity) { Node lru = tail.prev; unlink(lru); map.remove(lru.k); }
        n = new Node(); n.k = k; n.v = v;
        map.put(k, n); addFront(n);
    }

    public synchronized int size() { return map.size(); }
    public synchronized double hitRate() { return hits + misses == 0 ? 0 : (double) hits / (hits + misses); }

    private void unlink(Node n) { n.prev.next = n.next; n.next.prev = n.prev; }

    private void addFront(Node n) { n.next = head.next; n.prev = head; head.next.prev = n; head.next = n; }
}
