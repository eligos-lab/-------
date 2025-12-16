package org.example.routing;

import org.example.model.Location;

import java.util.List;

public interface RoutingProvider {
    Location geocode(String address) throws Exception;

    /**
     * Returns a matrix of durations (seconds) and distances (meters).
     * matrix[i][j] means from i to j.
     */
    MatrixResult buildMatrix(List<Location> locations) throws Exception;
}
