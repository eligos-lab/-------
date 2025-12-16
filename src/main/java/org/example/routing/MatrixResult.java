package org.example.routing;

public class MatrixResult {
    public final double[][] durationsSeconds;
    public final double[][] distancesMeters;
    public final boolean fromMatrixApi; // true if matrix endpoint used

    public MatrixResult(double[][] durationsSeconds, double[][] distancesMeters, boolean fromMatrixApi) {
        this.durationsSeconds = durationsSeconds;
        this.distancesMeters = distancesMeters;
        this.fromMatrixApi = fromMatrixApi;
    }
}
