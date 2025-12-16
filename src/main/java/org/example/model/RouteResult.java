package org.example.model;

import java.util.List;

public class RouteResult {
    private final List<Location> orderedStops; // includes start if provided
    private final double totalDistanceMeters;
    private final double totalDurationSeconds;

    public RouteResult(List<Location> orderedStops, double totalDistanceMeters, double totalDurationSeconds) {
        this.orderedStops = orderedStops;
        this.totalDistanceMeters = totalDistanceMeters;
        this.totalDurationSeconds = totalDurationSeconds;
    }

    public List<Location> orderedStops() { return orderedStops; }
    public double totalDistanceMeters() { return totalDistanceMeters; }
    public double totalDurationSeconds() { return totalDurationSeconds; }
}
