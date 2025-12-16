package org.example.optimizer;

import org.example.model.Location;
import org.example.model.RouteResult;
import org.example.routing.MatrixResult;
import org.example.routing.RoutingProvider;

import java.util.ArrayList;
import java.util.List;

public class RouteOptimizer {

    private final RoutingProvider routing;

    public RouteOptimizer(RoutingProvider routing) {
        this.routing = routing;
    }

    /**
     * @param startAddress optional (can be null/blank)
     * @param deliveryAddresses list of addresses (each must be non-blank)
     */
    public RouteResult optimize(String startAddress, List<String> deliveryAddresses) throws Exception {
        List<String> all = new ArrayList<>();

        boolean hasStart = startAddress != null && !startAddress.trim().isEmpty();
        if (hasStart) all.add(startAddress.trim());
        all.addAll(deliveryAddresses);

        // 1) Geocode all
        List<Location> locs = new ArrayList<>();
        for (String a : all) {
            locs.add(routing.geocode(a));
        }

        // 2) Build matrix
        MatrixResult m = routing.buildMatrix(locs);

        // 3) Solve order (path). If hasStart: fix index 0 as start.
        // Otherwise: choose best "start" by trying nearest-neighbor from each node (small n).
        int n = locs.size();
        int[] order;
        if (hasStart) {
            order = nearestNeighborPathFromStart(0, m.durationsSeconds);
            order = TwoOpt.improvePath(order, m.durationsSeconds);
        } else {
            order = bestStartByGreedy(m.durationsSeconds);
            order = TwoOpt.improvePath(order, m.durationsSeconds);
        }

        // 4) Build ordered list + totals
        List<Location> ordered = new ArrayList<>();
        double totalDur = 0.0;
        double totalDist = 0.0;

        for (int idx : order) ordered.add(locs.get(idx));

        for (int i = 0; i < order.length - 1; i++) {
            int a = order[i];
            int b = order[i + 1];
            double d = m.durationsSeconds[a][b];
            double s = m.distancesMeters[a][b];
            if (Double.isInfinite(d) || Double.isInfinite(s)) {
                throw new RuntimeException("Нет маршрута между точками: " + locs.get(a).address() + " -> " + locs.get(b).address());
            }
            totalDur += d;
            totalDist += s;
        }

        return new RouteResult(ordered, totalDist, totalDur);
    }

    private int[] nearestNeighborPathFromStart(int startIdx, double[][] cost) {
        int n = cost.length;
        boolean[] used = new boolean[n];
        int[] order = new int[n];

        order[0] = startIdx;
        used[startIdx] = true;

        for (int pos = 1; pos < n; pos++) {
            int prev = order[pos - 1];
            int best = -1;
            double bestCost = Double.POSITIVE_INFINITY;

            for (int j = 0; j < n; j++) {
                if (used[j]) continue;
                double c = cost[prev][j];
                if (c < bestCost) {
                    bestCost = c;
                    best = j;
                }
            }

            if (best == -1) throw new RuntimeException("Не удалось построить маршрут (разрыв в матрице).");
            order[pos] = best;
            used[best] = true;
        }
        return order;
    }

    private int[] bestStartByGreedy(double[][] cost) {
        int n = cost.length;
        int[] bestOrder = null;
        double bestTotal = Double.POSITIVE_INFINITY;

        for (int start = 0; start < n; start++) {
            int[] o = nearestNeighborPathFromStart(start, cost);
            double t = totalCost(o, cost);
            if (t < bestTotal) {
                bestTotal = t;
                bestOrder = o;
            }
        }
        if (bestOrder == null) throw new RuntimeException("Не удалось выбрать старт.");
        return bestOrder;
    }

    private double totalCost(int[] order, double[][] cost) {
        double sum = 0.0;
        for (int i = 0; i < order.length - 1; i++) {
            double c = cost[order[i]][order[i + 1]];
            if (Double.isInfinite(c)) return Double.POSITIVE_INFINITY;
            sum += c;
        }
        return sum;
    }
}
