package org.example.routing;

import org.example.config.AppConfig;
import org.example.model.Location;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class OrsClient implements RoutingProvider {

    private final OkHttpClient http = new OkHttpClient.Builder()
            .callTimeout(Duration.ofSeconds(30))
            .connectTimeout(Duration.ofSeconds(15))
            .readTimeout(Duration.ofSeconds(30))
            .build();

    private final ObjectMapper om = new ObjectMapper();
    private final AppConfig cfg;

    public OrsClient(AppConfig cfg) {
        this.cfg = cfg;
    }

    // ---------- GEOCODING ----------
    @Override
    public Location geocode(String address) throws Exception {
        HttpUrl base = Objects.requireNonNull(
                HttpUrl.parse("https://api.openrouteservice.org/geocode/search"),
                "Bad ORS geocode URL"
        );

        HttpUrl url = base.newBuilder()
                .addQueryParameter("api_key", cfg.orsApiKey())
                .addQueryParameter("text", address)
                .addQueryParameter("size", "1")
                .addQueryParameter("lang", cfg.orsLanguage())
                .build();

        Request req = new Request.Builder().url(url).get().build();

        try (Response r = http.newCall(req).execute()) {
            String body = (r.body() != null) ? r.body().string() : "";
            if (!r.isSuccessful()) {
                throw new RuntimeException("ORS Geocode error: HTTP " + r.code() + " body=" + body);
            }

            JsonNode root = om.readTree(body);
            JsonNode features = root.path("features");
            if (!features.isArray() || features.size() == 0) {
                throw new RuntimeException("Не найдено по адресу: " + address);
            }

            JsonNode f = features.get(0);
            JsonNode c = f.path("geometry").path("coordinates");
            if (!c.isArray() || c.size() < 2) {
                throw new RuntimeException("ORS Geocode: неожиданная структура coordinates");
            }

            double lon = c.get(0).asDouble();
            double lat = c.get(1).asDouble();
            String label = f.path("properties").path("label").asText(address);

            return new Location(label, lon, lat);
        }
    }

    // ---------- MATRIX ----------
    @Override
    public MatrixResult buildMatrix(List<Location> locations) throws Exception {
        int n = locations.size();
        if (n < 2) {
            double[][] d = new double[n][n];
            return new MatrixResult(d, d, true);
        }

        // ORS Matrix endpoint:
        // POST https://api.openrouteservice.org/v2/matrix/{profile}
        // Body: { "locations":[[lon,lat],...], "metrics":["duration","distance"] }
        ObjectNode body = om.createObjectNode();

        // locations
        ArrayNode locs = body.putArray("locations");
        for (Location l : locations) {
            ArrayNode coord = om.createArrayNode();
            coord.add(l.lon());
            coord.add(l.lat());
            locs.add(coord);
        }

        // metrics
        ArrayNode metrics = body.putArray("metrics");
        metrics.add("duration");
        metrics.add("distance");

        // units (optional): "m" for meters is default; leave as is
        // body.put("units", "m");

        String urlStr = String.format(Locale.ROOT,
                "https://api.openrouteservice.org/v2/matrix/%s",
                cfg.orsProfile()
        );

        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(urlStr), "Bad ORS matrix URL");

        Request req = new Request.Builder()
                .url(url)
                .addHeader("Authorization", cfg.orsApiKey())
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                .build();

        try (Response r = http.newCall(req).execute()) {
            String respBody = (r.body() != null) ? r.body().string() : "";
            if (!r.isSuccessful()) {
                throw new RuntimeException("ORS Matrix error: HTTP " + r.code() + " body=" + respBody);
            }

            JsonNode root = om.readTree(respBody);
            JsonNode dur = root.path("durations");
            JsonNode dist = root.path("distances");

            if (!dur.isArray() || !dist.isArray()) {
                throw new RuntimeException("ORS Matrix: нет durations/distances в ответе");
            }

            double[][] durs = new double[n][n];
            double[][] dists = new double[n][n];

            for (int i = 0; i < n; i++) {
                JsonNode rowD = dur.get(i);
                JsonNode rowS = dist.get(i);
                for (int j = 0; j < n; j++) {
                    // ORS может вернуть null, если маршрута нет
                    JsonNode vd = rowD.get(j);
                    JsonNode vs = rowS.get(j);

                    durs[i][j] = (vd == null || vd.isNull()) ? Double.POSITIVE_INFINITY : vd.asDouble();
                    dists[i][j] = (vs == null || vs.isNull()) ? Double.POSITIVE_INFINITY : vs.asDouble();
                }
            }

            return new MatrixResult(durs, dists, true);
        }
    }
}