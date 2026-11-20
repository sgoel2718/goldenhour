package com.goldenhour.graph;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Immutable directed road graph in Compressed Sparse Row (CSR) form.
 *
 * <p>Why CSR instead of {@code List<List<Edge>>}: a city graph has ~10^5–10^6 nodes. CSR stores all
 * edges in three primitive arrays, so iterating a node's neighbours is a contiguous memory scan
 * (cache friendly, no object headers, no boxing). Memory ≈ 16 bytes/edge versus ~60 with objects.
 *
 * <p>Both the forward graph (out-edges) and the reverse graph (in-edges) are stored, because
 * bidirectional search and "time from every node TO this hospital" need to walk edges backwards.
 */
public final class RoadGraph {
    private final int n;
    private final double[] lat, lon;
    private final int[] outStart, outTo, inStart, inFrom;
    private final double[] outW, inW;      // travel time in seconds
    private final double maxSpeedMps;       // used for an admissible A* heuristic

    private RoadGraph(double[] lat, double[] lon, int[] from, int[] to, double[] w, double maxSpeedMps) {
        this.n = lat.length;
        this.lat = lat;
        this.lon = lon;
        this.maxSpeedMps = maxSpeedMps;
        int m = from.length;
        outStart = new int[n + 1];
        inStart = new int[n + 1];
        for (int e = 0; e < m; e++) { outStart[from[e] + 1]++; inStart[to[e] + 1]++; }
        for (int i = 0; i < n; i++) { outStart[i + 1] += outStart[i]; inStart[i + 1] += inStart[i]; }
        outTo = new int[m]; outW = new double[m]; inFrom = new int[m]; inW = new double[m];
        int[] po = Arrays.copyOf(outStart, n), pi = Arrays.copyOf(inStart, n);
        for (int e = 0; e < m; e++) {
            int o = po[from[e]]++; outTo[o] = to[e]; outW[o] = w[e];
            int i = pi[to[e]]++; inFrom[i] = from[e]; inW[i] = w[e];
        }
    }

    /** Builds a graph from parallel arrays (used by tests and by the CSV loader). */
    public static RoadGraph of(double[] lat, double[] lon, int[] from, int[] to, double[] travelSeconds) {
        double maxSpeed = 1.0;
        for (int e = 0; e < from.length; e++) {
            double d = Geo.haversineMeters(lat[from[e]], lon[from[e]], lat[to[e]], lon[to[e]]);
            if (travelSeconds[e] > 0) maxSpeed = Math.max(maxSpeed, d / travelSeconds[e]);
        }
        return new RoadGraph(lat, lon, from, to, travelSeconds, maxSpeed);
    }

    /** Loads nodes.csv (id,lat,lon) and edges.csv (from,to,length_m,speed_kph,travel_s,road_class). */
    public static RoadGraph load(Path dir) throws IOException {
        List<String[]> nodes = readCsv(dir.resolve("nodes.csv"));
        double[] lat = new double[nodes.size()], lon = new double[nodes.size()];
        for (String[] r : nodes) {
            int id = Integer.parseInt(r[0]);
            lat[id] = Double.parseDouble(r[1]);
            lon[id] = Double.parseDouble(r[2]);
        }
        List<String[]> edges = readCsv(dir.resolve("edges.csv"));
        int m = edges.size();
        int[] from = new int[m], to = new int[m];
        double[] w = new double[m];
        for (int e = 0; e < m; e++) {
            String[] r = edges.get(e);
            from[e] = Integer.parseInt(r[0]);
            to[e] = Integer.parseInt(r[1]);
            w[e] = Double.parseDouble(r[4]);
        }
        return of(lat, lon, from, to, w);
    }

    private static List<String[]> readCsv(Path p) throws IOException {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(p)) {
            br.readLine(); // header
            for (String line; (line = br.readLine()) != null; ) if (!line.isBlank()) rows.add(line.split(","));
        }
        return rows;
    }

    public int nodeCount() { return n; }
    public int edgeCount() { return outTo.length; }
    public double lat(int v) { return lat[v]; }
    public double lon(int v) { return lon[v]; }
    public double maxSpeedMps() { return maxSpeedMps; }
    public int outBegin(int v) { return outStart[v]; }
    public int outEnd(int v) { return outStart[v + 1]; }
    public int outTarget(int e) { return outTo[e]; }
    public double outWeight(int e) { return outW[e]; }
    public int inBegin(int v) { return inStart[v]; }
    public int inEnd(int v) { return inStart[v + 1]; }
    public int inSource(int e) { return inFrom[e]; }
    public double inWeight(int e) { return inW[e]; }

    /** Lower bound on travel time between two nodes: straight-line distance at the fastest speed in the graph. */
    public double lowerBoundSeconds(int a, int b) {
        return Geo.haversineMeters(lat[a], lon[a], lat[b], lon[b]) / maxSpeedMps;
    }

    /** Weight of the cheapest direct edge u→v, or +∞ if none (used when reconstructing paths). */
    public double edgeWeight(int u, int v) {
        double best = Double.POSITIVE_INFINITY;
        for (int e = outStart[u]; e < outStart[u + 1]; e++) if (outTo[e] == v) best = Math.min(best, outW[e]);
        return best;
    }
}
