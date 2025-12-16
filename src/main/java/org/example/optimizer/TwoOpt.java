package org.example.optimizer;

public final class TwoOpt {

    private TwoOpt() {}

    /**
     * 2-opt optimization over a path (not necessarily a cycle).
     * We keep the first index fixed (start point), and optimize the rest order.
     */
    public static int[] improvePath(int[] order, double[][] cost) {
        boolean improved = true;
        int n = order.length;

        // If too small - nothing to do
        if (n < 4) return order;

        while (improved) {
            improved = false;

            // i starts from 1 to keep start fixed at 0
            for (int i = 1; i < n - 2; i++) {
                for (int k = i + 1; k < n - 1; k++) {
                    double delta = gain(order, cost, i, k);
                    if (delta < -1e-9) {
                        reverse(order, i, k);
                        improved = true;
                    }
                }
            }
        }
        return order;
    }

    // Gain if we reverse segment [i..k]
    private static double gain(int[] order, double[][] cost, int i, int k) {
        int a = order[i - 1];
        int b = order[i];
        int c = order[k];
        int d = order[k + 1];

        double before = cost[a][b] + cost[c][d];
        double after  = cost[a][c] + cost[b][d];

        return after - before;
    }

    private static void reverse(int[] order, int i, int k) {
        while (i < k) {
            int tmp = order[i];
            order[i] = order[k];
            order[k] = tmp;
            i++;
            k--;
        }
    }
}
