package com.shivamsinha.payauth.velocity;

import java.util.Map;
import java.util.Optional;

/**
 * Great-circle distance between country centroids.
 *
 * <p>Country granularity is a deliberate simplification: an authorization carries
 * an acquirer country, not a coordinate, so a centroid is the honest resolution.
 * It is enough to separate "Mumbai then Delhi" from "Mumbai then São Paulo",
 * which is what the impossible-travel rule is actually asking.
 *
 * <p>An unknown country yields no distance and the rule abstains, rather than
 * treating "I don't know" as "far away" and declining a legitimate payment.
 */
public final class CountryGeography {

    private record Centroid(double latitude, double longitude) {
    }

    private static final double EARTH_RADIUS_KM = 6371.0088;

    private static final Map<String, Centroid> CENTROIDS = Map.ofEntries(
            Map.entry("IN", new Centroid(22.3511, 78.6677)),
            Map.entry("US", new Centroid(39.7837, -100.4459)),
            Map.entry("GB", new Centroid(54.7024, -3.2766)),
            Map.entry("DE", new Centroid(51.1638, 10.4478)),
            Map.entry("FR", new Centroid(46.6034, 1.8883)),
            Map.entry("ES", new Centroid(39.3261, -4.8380)),
            Map.entry("IT", new Centroid(42.6384, 12.6743)),
            Map.entry("NL", new Centroid(52.2434, 5.6343)),
            Map.entry("IE", new Centroid(52.8653, -7.9794)),
            Map.entry("AE", new Centroid(24.0002, 53.9994)),
            Map.entry("SG", new Centroid(1.3571, 103.8194)),
            Map.entry("AU", new Centroid(-24.7761, 134.7550)),
            Map.entry("JP", new Centroid(36.5748, 139.2394)),
            Map.entry("CN", new Centroid(35.0000, 103.0000)),
            Map.entry("BR", new Centroid(-10.3333, -53.2000)),
            Map.entry("CA", new Centroid(61.0666, -107.9917)),
            Map.entry("MX", new Centroid(23.9494, -102.5234)),
            Map.entry("ZA", new Centroid(-28.8166, 24.9916)),
            Map.entry("NG", new Centroid(9.6000, 7.9999)),
            Map.entry("RU", new Centroid(61.9805, 96.6802)));

    private CountryGeography() {
    }

    public static boolean isKnown(String countryCode) {
        return countryCode != null && CENTROIDS.containsKey(countryCode);
    }

    /**
     * @return kilometres between two country centroids, or empty if either country
     *         is unknown
     */
    public static Optional<Double> distanceKm(String from, String to) {
        Centroid a = from == null ? null : CENTROIDS.get(from);
        Centroid b = to == null ? null : CENTROIDS.get(to);
        if (a == null || b == null) {
            return Optional.empty();
        }
        return Optional.of(haversineKm(a, b));
    }

    private static double haversineKm(Centroid a, Centroid b) {
        double dLat = Math.toRadians(b.latitude() - a.latitude());
        double dLon = Math.toRadians(b.longitude() - a.longitude());
        double lat1 = Math.toRadians(a.latitude());
        double lat2 = Math.toRadians(b.latitude());

        double h = Math.pow(Math.sin(dLat / 2), 2)
                + Math.pow(Math.sin(dLon / 2), 2) * Math.cos(lat1) * Math.cos(lat2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(h));
    }
}
