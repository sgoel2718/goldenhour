package com.goldenhour.ds;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Consistent hashing with virtual nodes — how GoldenHour shards cities/regions across dispatch-core
 * instances. Each region is owned by exactly one instance (dispatch state must be single-writer), and
 * adding/removing an instance only moves ~1/N of the regions instead of reshuffling all of them.
 * Lookup is O(log V) via {@link TreeMap#ceilingKey}.
 */
public final class ConsistentHashRing {
    private final TreeMap<Long, String> ring = new TreeMap<>();
    private final int virtualNodes;

    public ConsistentHashRing(int virtualNodes) { this.virtualNodes = virtualNodes; }

    public void addNode(String node) { for (int i = 0; i < virtualNodes; i++) ring.put(hash(node + "#" + i), node); }

    public void removeNode(String node) { for (int i = 0; i < virtualNodes; i++) ring.remove(hash(node + "#" + i)); }

    public String nodeFor(String key) {
        if (ring.isEmpty()) throw new IllegalStateException("no nodes");
        SortedMap<Long, String> tail = ring.tailMap(hash(key));
        return tail.isEmpty() ? ring.firstEntry().getValue() : tail.get(tail.firstKey());
    }

    public Map<Long, String> view() { return java.util.Collections.unmodifiableMap(ring); }

    private static long hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            long h = 0;
            for (int i = 0; i < 8; i++) h = (h << 8) | (d[i] & 0xff);
            return h;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
