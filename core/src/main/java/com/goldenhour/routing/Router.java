package com.goldenhour.routing;

/** A point-to-point shortest-path algorithm. Implementations are NOT thread-safe (they reuse buffers). */
public interface Router {
    Route route(int source, int target);

    String name();
}
