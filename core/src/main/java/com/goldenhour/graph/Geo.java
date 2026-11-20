package com.goldenhour.graph;

/** Small geodesy helpers. */
public final class Geo {
    private static final double R = 6_371_000.0;
    private static final String BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";

    private Geo() {}

    /** Great-circle distance in metres (haversine formula). */
    public static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * R * Math.asin(Math.sqrt(a));
    }

    /**
     * Geohash: interleaves longitude/latitude bisection bits and encodes 5 bits per base-32 character.
     * Nearby points share prefixes, so a geohash is a cheap zone id (precision 6 ≈ 1.2 km × 0.6 km).
     */
    public static String geohash(double lat, double lon, int precision) {
        double[] la = {-90, 90}, lo = {-180, 180};
        StringBuilder sb = new StringBuilder(precision);
        boolean even = true;
        int bit = 0, ch = 0;
        while (sb.length() < precision) {
            double[] r = even ? lo : la;
            double v = even ? lon : lat, mid = (r[0] + r[1]) / 2;
            if (v >= mid) { ch |= 1 << (4 - bit); r[0] = mid; } else r[1] = mid;
            even = !even;
            if (++bit == 5) { sb.append(BASE32.charAt(ch)); bit = 0; ch = 0; }
        }
        return sb.toString();
    }
}
