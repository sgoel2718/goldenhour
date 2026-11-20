package com.goldenhour.dispatch;

import java.util.Set;

/** Domain types. Kept in one file because they are small and change together. */
public final class Model {
    private Model() {}

    /** Triage priority with response-time targets (minutes) in the spirit of NHS/US EMS standards. */
    public enum Severity {
        P1_CRITICAL(8, 4.0), P2_EMERGENT(15, 2.0), P3_URGENT(30, 1.0), P4_ROUTINE(60, 0.5);

        public final int targetMinutes;
        public final double weight;       // used in severity-weighted batch assignment

        Severity(int targetMinutes, double weight) { this.targetMinutes = targetMinutes; this.weight = weight; }
    }

    public enum Status { AVAILABLE, EN_ROUTE, ON_SCENE, TRANSPORTING, RETURNING }

    /** ALS = advanced life support (paramedic, cardiac monitor); BLS = basic life support. */
    public enum Unit { ALS, BLS }

    public enum Specialty { CARDIAC, STROKE, TRAUMA, BURNS, MATERNITY, GENERAL }

    public static final class Ambulance {
        public final int id;
        public final Unit unit;
        public volatile int node;
        public volatile int baseNode;
        public volatile Status status = Status.AVAILABLE;
        public volatile String incidentId;

        public Ambulance(int id, Unit unit, int node) { this.id = id; this.unit = unit; this.node = node; this.baseNode = node; }
    }

    public static final class Hospital {
        public final int id;
        public final String name;
        public final int node;
        public final Set<Specialty> specialties;
        public volatile int freeBeds;

        public Hospital(int id, String name, int node, Set<Specialty> specialties, int freeBeds) {
            this.id = id; this.name = name; this.node = node; this.specialties = specialties; this.freeBeds = freeBeds;
        }
    }

    public record Incident(String id, double lat, double lon, int node, Severity severity, Specialty need,
                           String category, long createdAtMs, String notes) {}

    /** What the dispatcher decided — returned to the caller and appended to the write-ahead log. */
    public record Assignment(String incidentId, int ambulanceId, Unit unit, double etaSeconds, int hospitalId,
                             String hospitalName, double transportSeconds, java.util.List<Integer> route,
                             String reason) {}
}
