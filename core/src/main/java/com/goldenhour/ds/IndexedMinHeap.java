package com.goldenhour.ds;

import java.util.Arrays;

/**
 * Binary min-heap over integer ids 0..capacity-1 with O(log n) decrease-key.
 *
 * <p>{@link java.util.PriorityQueue} has no decrease-key, so Dijkstra implementations usually push
 * duplicates ("lazy deletion"), which grows the heap to O(E). An indexed heap keeps exactly one entry
 * per node via a {@code pos[]} index, so the heap never exceeds O(V) and stale entries never exist.
 *
 * <p>{@link #reset()} is O(touched) rather than O(capacity): the same heap is reused across millions of
 * routing queries without reallocating or clearing large arrays.
 */
public final class IndexedMinHeap {
    private final int[] heap, pos;
    private final double[] key;
    private final int[] touched;
    private int size, touchedCount;

    public IndexedMinHeap(int capacity) {
        heap = new int[capacity];
        pos = new int[capacity];
        key = new double[capacity];
        touched = new int[capacity];
        Arrays.fill(pos, -1);
    }

    public boolean isEmpty() { return size == 0; }
    public int size() { return size; }
    public boolean contains(int id) { return pos[id] >= 0; }
    public double peekKey() { return key[heap[0]]; }

    /** Inserts id with key, or lowers its key if already present (no-op if the new key is not smaller). */
    public void pushOrDecrease(int id, double k) {
        if (pos[id] >= 0) {
            if (k < key[id]) { key[id] = k; siftUp(pos[id]); }
            return;
        }
        if (pos[id] == -1) touched[touchedCount++] = id;   // first time this id is seen since reset()
        key[id] = k;
        heap[size] = id;
        pos[id] = size;
        siftUp(size++);
    }

    public int pop() {
        int top = heap[0];
        int last = heap[--size];
        pos[top] = -2; // -2 = popped (distinguishes from never-seen during the same query)
        if (size > 0) { heap[0] = last; pos[last] = 0; siftDown(0); }
        return top;
    }

    public double key(int id) { return key[id]; }

    /** Clears only the entries touched since the last reset. */
    public void reset() {
        for (int i = 0; i < touchedCount; i++) pos[touched[i]] = -1;
        touchedCount = 0;
        size = 0;
    }

    private void siftUp(int i) {
        int id = heap[i];
        double k = key[id];
        while (i > 0) {
            int p = (i - 1) >>> 1;
            if (key[heap[p]] <= k) break;
            heap[i] = heap[p]; pos[heap[i]] = i; i = p;
        }
        heap[i] = id; pos[id] = i;
    }

    private void siftDown(int i) {
        int id = heap[i];
        double k = key[id];
        while (true) {
            int l = 2 * i + 1;
            if (l >= size) break;
            int c = (l + 1 < size && key[heap[l + 1]] < key[heap[l]]) ? l + 1 : l;
            if (key[heap[c]] >= k) break;
            heap[i] = heap[c]; pos[heap[i]] = i; i = c;
        }
        heap[i] = id; pos[id] = i;
    }
}
