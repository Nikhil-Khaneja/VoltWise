package electricitybillgenerator.analytics.data;

import java.time.LocalDateTime;

/**
 * Raw 1-minute meter readings. Missing readings are NaN.
 *
 * @param start     timestamp of index 0
 * @param activeKw  Global_active_power (kW)
 * @param voltage   Voltage (V)
 * @param intensity Global_intensity (A)
 */
public record MinuteData(LocalDateTime start, float[] activeKw, float[] voltage, float[] intensity) {

    public int size() {
        return activeKw.length;
    }

    public LocalDateTime time(int i) {
        return start.plusMinutes(i);
    }

    public MinuteData copy() {
        return new MinuteData(start, activeKw.clone(), voltage.clone(), intensity.clone());
    }
}
