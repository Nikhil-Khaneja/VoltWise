package electricitybillgenerator.analytics.anomaly;

/** A run of flagged minutes [start, end). */
public record Anomaly(Type type, int start, int end) {

    public enum Type {
        /** Meter reported nothing. */
        MISSING,
        /** Same non-trivial reading repeated for a long time: stuck register or comms freeze. */
        FLATLINE,
        /** One-minute jump far above both neighbours, or above the service limit. */
        SPIKE,
        /** Readings that violate physics: active power above apparent power, or voltage out of tolerance. */
        PHYSICS
    }

    public int length() {
        return end - start;
    }
}
