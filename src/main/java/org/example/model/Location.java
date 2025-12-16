package org.example.model;

public class Location {
    private final String address;
    private final double lon;
    private final double lat;

    public Location(String address, double lon, double lat) {
        this.address = address;
        this.lon = lon;
        this.lat = lat;
    }

    public String address() { return address; }
    public double lon() { return lon; }
    public double lat() { return lat; }

    @Override
    public String toString() {
        return address + " (" + lat + ", " + lon + ")";
    }
}
