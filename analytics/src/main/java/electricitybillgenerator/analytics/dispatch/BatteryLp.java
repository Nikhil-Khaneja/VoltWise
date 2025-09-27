package electricitybillgenerator.analytics.dispatch;

import electricitybillgenerator.analytics.battery.BatterySpec;
import org.ojalgo.optimisation.Expression;
import org.ojalgo.optimisation.ExpressionsBasedModel;
import org.ojalgo.optimisation.Optimisation;
import org.ojalgo.optimisation.Variable;

/**
 * Linear program for battery scheduling over a horizon of T hours.
 *
 * <pre>
 * minimise   sum_t price_t * (c_t - d_t)  +  D * P  +  eps * sum_t (c_t + d_t)
 * subject to s_t = s_{t-1} + eta_c * c_t - d_t / eta_d          (energy balance)
 *            load_t + c_t - d_t <= P                            (P is the billed peak)
 *            load_t + c_t - d_t >= 0                            (no export)
 *            0 <= c_t, d_t <= Pmax,  Smin <= s_t <= Smax,  P >= peakFloor
 *            s_T >= terminalSoc
 * </pre>
 *
 * The small eps term breaks ties against charging and discharging in the same hour.
 */
public final class BatteryLp {

    private static final double EPS = 1e-4;

    public record Plan(double[] charge, double[] discharge, double[] soc, double plannedPeak, double objective) {
    }

    private BatteryLp() {
    }

    public static Plan solve(double[] load, double[] pricePerKwh, double demandChargePerKw, double peakFloor,
                             double soc0, double terminalSoc, BatterySpec spec) {
        return solve(load, pricePerKwh, new int[load.length], new double[]{demandChargePerKw},
                new double[]{peakFloor}, soc0, terminalSoc, spec);
    }

    /**
     * Multi-period version: hour t belongs to billing period {@code group[t]}, which has its own
     * peak variable P_g charged at {@code demandCharge[g]} with floor {@code peakFloor[g]}.
     * Solving a whole year this way, one peak per month, gives the true perfect-foresight optimum.
     * {@link Plan#plannedPeak()} reports the largest P_g.
     */
    public static Plan solve(double[] load, double[] pricePerKwh, int[] group, double[] demandCharge,
                             double[] peakFloor, double soc0, double terminalSoc, BatterySpec spec) {
        int n = load.length;
        double etaC = spec.chargeEfficiency();
        double etaD = spec.dischargeEfficiency();
        ExpressionsBasedModel model = new ExpressionsBasedModel();

        Variable[] c = new Variable[n];
        Variable[] d = new Variable[n];
        Variable[] s = new Variable[n];
        for (int t = 0; t < n; t++) {
            c[t] = model.addVariable("c" + t).lower(0).upper(spec.powerKw()).weight(pricePerKwh[t] + EPS);
            d[t] = model.addVariable("d" + t).lower(0).upper(spec.powerKw()).weight(-pricePerKwh[t] + EPS);
            s[t] = model.addVariable("s" + t).lower(spec.minSocKwh()).upper(spec.maxSocKwh());
        }
        Variable[] peak = new Variable[demandCharge.length];
        for (int g = 0; g < peak.length; g++) {
            peak[g] = model.addVariable("P" + g).lower(Math.max(0, peakFloor[g])).weight(demandCharge[g]);
        }

        for (int t = 0; t < n; t++) {
            Expression balance = model.addExpression("bal" + t).level(t == 0 ? soc0 : 0);
            balance.set(s[t], 1).set(c[t], -etaC).set(d[t], 1 / etaD);
            if (t > 0) {
                balance.set(s[t - 1], -1);
            }
            model.addExpression("peak" + t).upper(-load[t]).set(c[t], 1).set(d[t], -1).set(peak[group[t]], -1);
            model.addExpression("noexp" + t).upper(load[t]).set(d[t], 1).set(c[t], -1);
        }
        double terminal = Math.min(terminalSoc, spec.maxSocKwh());
        model.addExpression("terminal").lower(terminal).set(s[n - 1], 1);

        Optimisation.Result result = model.minimise();
        if (!result.getState().isFeasible()) {
            throw new IllegalStateException("battery LP infeasible: " + result.getState());
        }
        double[] cv = new double[n];
        double[] dv = new double[n];
        double[] sv = new double[n];
        for (int t = 0; t < n; t++) {
            cv[t] = Math.max(0, c[t].getValue().doubleValue());
            dv[t] = Math.max(0, d[t].getValue().doubleValue());
            sv[t] = s[t].getValue().doubleValue();
        }
        double maxPeak = 0;
        for (Variable p : peak) {
            maxPeak = Math.max(maxPeak, p.getValue().doubleValue());
        }
        return new Plan(cv, dv, sv, maxPeak, result.getValue());
    }
}
