package electricitybillgenerator.analytics.forecast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Histogram-based gradient-boosted regression trees with squared loss (the same idea as
 * LightGBM, minus the engineering). Features are bucketed into at most {@code maxBins} quantile
 * bins once; each tree split is then a scan over bin histograms.
 */
public final class GradientBoostedTrees {

    /**
     * @param quantile 0 for squared loss (predicts the mean); otherwise the target quantile for
     *                 pinball loss, e.g. 0.9 predicts a level the load stays under 90 % of the time
     */
    public record Params(int trees, int maxDepth, double learningRate, int minLeaf, double subsample,
                         int maxBins, long seed, double quantile) {
        public static Params defaults() {
            return new Params(400, 6, 0.05, 40, 0.8, 64, 42, 0);
        }

        public Params withQuantile(double q) {
            return new Params(trees, maxDepth, learningRate, minLeaf, subsample, maxBins, seed, q);
        }
    }

    private final Params params;
    private double[][] binEdges;   // per feature, ascending upper edges
    private double base;
    private final List<Node> trees = new ArrayList<>();

    public GradientBoostedTrees(Params params) {
        this.params = params;
    }

    public GradientBoostedTrees fit(double[][] x, double[] y) {
        int n = x.length;
        int f = x[0].length;
        binEdges = new double[f][];
        byte[][] binned = new byte[f][n];
        for (int j = 0; j < f; j++) {
            binEdges[j] = quantileEdges(x, j, params.maxBins());
            for (int i = 0; i < n; i++) {
                binned[j][i] = (byte) bin(binEdges[j], x[i][j]);
            }
        }
        base = params.quantile() > 0 ? quantile(y.clone(), params.quantile()) : Arrays.stream(y).average().orElse(0);
        double[] pred = new double[n];
        Arrays.fill(pred, base);
        double[] residual = new double[n];
        Random rnd = new Random(params.seed());

        for (int k = 0; k < params.trees(); k++) {
            for (int i = 0; i < n; i++) {
                residual[i] = y[i] - pred[i];
            }
            int[] rows = sample(n, rnd);
            double[] gradient = residual;
            if (params.quantile() > 0) {
                // Pinball loss: split on the sign-based pseudo-residual, then set each leaf to the
                // quantile of its true residuals (as gradient-boosting libraries do for quantile loss).
                gradient = new double[n];
                for (int i = 0; i < n; i++) {
                    gradient[i] = residual[i] > 0 ? params.quantile() : params.quantile() - 1;
                }
            }
            Node tree = grow(binned, gradient, residual, rows, 0);
            trees.add(tree);
            for (int i = 0; i < n; i++) {
                pred[i] += params.learningRate() * tree.predictBinned(binned, i);
            }
        }
        return this;
    }

    public double predict(double[] row) {
        double p = base;
        for (Node t : trees) {
            p += params.learningRate() * t.predictRaw(row, binEdges);
        }
        return p;
    }

    private int[] sample(int n, Random rnd) {
        if (params.subsample() >= 1) {
            int[] all = new int[n];
            for (int i = 0; i < n; i++) {
                all[i] = i;
            }
            return all;
        }
        int[] tmp = new int[n];
        int m = 0;
        for (int i = 0; i < n; i++) {
            if (rnd.nextDouble() < params.subsample()) {
                tmp[m++] = i;
            }
        }
        return Arrays.copyOf(tmp, m);
    }

    private Node grow(byte[][] binned, double[] g, double[] residual, int[] rows, int depth) {
        double sum = 0;
        for (int r : rows) {
            sum += g[r];
        }
        if (depth >= params.maxDepth() || rows.length < 2 * params.minLeaf()) {
            return Node.leaf(leafValue(residual, rows));
        }
        double parentScore = sum * sum / rows.length;
        double bestGain = 1e-12;
        int bestFeature = -1;
        int bestBin = -1;
        int bins = params.maxBins();
        double[] hs = new double[bins];
        int[] hc = new int[bins];
        for (int j = 0; j < binned.length; j++) {
            Arrays.fill(hs, 0);
            Arrays.fill(hc, 0);
            byte[] col = binned[j];
            for (int r : rows) {
                hs[col[r]] += g[r];
                hc[col[r]]++;
            }
            double ls = 0;
            int lc = 0;
            for (int b = 0; b < bins - 1; b++) {
                ls += hs[b];
                lc += hc[b];
                int rc = rows.length - lc;
                if (lc < params.minLeaf() || rc < params.minLeaf()) {
                    continue;
                }
                double rs = sum - ls;
                double gain = ls * ls / lc + rs * rs / rc - parentScore;
                if (gain > bestGain) {
                    bestGain = gain;
                    bestFeature = j;
                    bestBin = b;
                }
            }
        }
        if (bestFeature < 0) {
            return Node.leaf(leafValue(residual, rows));
        }
        byte[] col = binned[bestFeature];
        int lc = 0;
        for (int r : rows) {
            if (col[r] <= bestBin) {
                lc++;
            }
        }
        int[] left = new int[lc];
        int[] right = new int[rows.length - lc];
        int a = 0, b = 0;
        for (int r : rows) {
            if (col[r] <= bestBin) {
                left[a++] = r;
            } else {
                right[b++] = r;
            }
        }
        return Node.split(bestFeature, bestBin, grow(binned, g, residual, left, depth + 1),
                grow(binned, g, residual, right, depth + 1));
    }

    private double leafValue(double[] residual, int[] rows) {
        if (rows.length == 0) {
            return 0;
        }
        if (params.quantile() > 0) {
            double[] v = new double[rows.length];
            for (int i = 0; i < rows.length; i++) {
                v[i] = residual[rows[i]];
            }
            return quantile(v, params.quantile());
        }
        double sum = 0;
        for (int r : rows) {
            sum += residual[r];
        }
        return sum / rows.length;
    }

    static double quantile(double[] v, double q) {
        Arrays.sort(v);
        return v[(int) Math.min(v.length - 1, Math.floor(q * v.length))];
    }

    private static double[] quantileEdges(double[][] x, int j, int maxBins) {
        double[] v = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            v[i] = x[i][j];
        }
        Arrays.sort(v);
        double[] edges = new double[maxBins - 1];
        int m = 0;
        for (int b = 1; b < maxBins; b++) {
            double e = v[(int) ((long) b * (v.length - 1) / maxBins)];
            if (m == 0 || e > edges[m - 1]) {
                edges[m++] = e;
            }
        }
        return Arrays.copyOf(edges, m);
    }

    /** Bin index = number of edges strictly below the value; values equal to an edge go left. */
    static int bin(double[] edges, double v) {
        int lo = 0, hi = edges.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (edges[mid] < v) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static final class Node {
        final int feature;
        final int bin;
        final double value;
        final Node left;
        final Node right;

        private Node(int feature, int bin, double value, Node left, Node right) {
            this.feature = feature;
            this.bin = bin;
            this.value = value;
            this.left = left;
            this.right = right;
        }

        static Node leaf(double v) {
            return new Node(-1, -1, v, null, null);
        }

        static Node split(int f, int b, Node l, Node r) {
            return new Node(f, b, 0, l, r);
        }

        double predictBinned(byte[][] binned, int i) {
            Node n = this;
            while (n.feature >= 0) {
                n = binned[n.feature][i] <= n.bin ? n.left : n.right;
            }
            return n.value;
        }

        double predictRaw(double[] row, double[][] edges) {
            Node n = this;
            while (n.feature >= 0) {
                n = bin(edges[n.feature], row[n.feature]) <= n.bin ? n.left : n.right;
            }
            return n.value;
        }
    }
}
