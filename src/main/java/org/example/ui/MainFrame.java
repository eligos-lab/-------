package org.example.ui;

import org.example.config.AppConfig;
import org.example.model.Location;
import org.example.model.RouteResult;
import org.example.optimizer.RouteOptimizer;
import org.example.routing.OrsClient;
import org.example.routing.RoutingProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.PDPageContentStream;

import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.WritableImage;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.concurrent.Worker;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.time.Duration;
import java.util.List;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class MainFrame extends JFrame {

    // -------- UI --------
    private final JTextField startField = new JTextField();
    private final JTextArea addressesArea = new JTextArea(10, 40);

    private final JButton optimizeBtn = new JButton("Оптимизировать");
    private final JButton openMapsBtn = new JButton("Открыть в Google Maps");
    private final JButton copyLinkBtn = new JButton("Скопировать ссылку");
    private final JButton exportCsvBtn = new JButton("Экспорт CSV");
    private final JButton exportPdfBtn = new JButton("Экспорт PDF");

    private final JLabel statusLabel = new JLabel("Готово.");
    private final JLabel totalsLabel = new JLabel("—");

    private final DefaultTableModel tableModel = new DefaultTableModel(
            new Object[]{"#", "Адрес (нормализованный)", "Lat", "Lon"}, 0
    );
    private final JTable table = new JTable(tableModel);

    private final FxMapPanel mapPanel = new FxMapPanel();

    // -------- Data --------
    private RouteResult lastResult = null;
    private String lastGoogleMapsLink = "";

    public MainFrame() {
        super("Оптимизатор маршрута курьера (ORS + интерактивная карта)");

        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1150, 720));
        setLocationRelativeTo(null);

        addressesArea.setLineWrap(true);
        addressesArea.setWrapStyleWord(true);
        table.setFillsViewportHeight(true);

        openMapsBtn.setEnabled(false);
        copyLinkBtn.setEnabled(false);
        exportCsvBtn.setEnabled(false);
        exportPdfBtn.setEnabled(false);

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(new EmptyBorder(12, 12, 12, 12));
        setContentPane(root);

        root.add(buildTopPanel(), BorderLayout.NORTH);
        root.add(buildCenterPanel(), BorderLayout.CENTER);
        root.add(buildBottomPanel(), BorderLayout.SOUTH);

        optimizeBtn.addActionListener(e -> onOptimize());
        openMapsBtn.addActionListener(e -> onOpenGoogleMaps());
        copyLinkBtn.addActionListener(e -> onCopyLink());
        exportCsvBtn.addActionListener(e -> onExportCsv());
        exportPdfBtn.addActionListener(e -> onExportPdf());

        // Example
        addressesArea.setText(String.join("\n",
                "Alexanderplatz, Berlin",
                "Brandenburger Tor, Berlin",
                "Potsdamer Platz, Berlin",
                "East Side Gallery, Berlin"
        ));
        startField.setText("Berlin Hauptbahnhof");
    }

    private JPanel buildTopPanel() {
        JPanel p = new JPanel(new BorderLayout(8, 8));

        JPanel line1 = new JPanel(new BorderLayout(8, 8));
        line1.add(new JLabel("Старт (опционально):"), BorderLayout.WEST);
        line1.add(startField, BorderLayout.CENTER);

        JPanel line2 = new JPanel(new BorderLayout(8, 8));
        line2.add(new JLabel("Адреса доставок (1 адрес = 1 строка):"), BorderLayout.NORTH);
        line2.add(new JScrollPane(addressesArea), BorderLayout.CENTER);

        p.add(line1, BorderLayout.NORTH);
        p.add(line2, BorderLayout.CENTER);
        return p;
    }

    private JComponent buildCenterPanel() {
        JPanel p = new JPanel(new BorderLayout(8, 8));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.add(optimizeBtn);
        actions.add(openMapsBtn);
        actions.add(copyLinkBtn);
        actions.add(exportCsvBtn);
        actions.add(exportPdfBtn);

        p.add(actions, BorderLayout.NORTH);

        JScrollPane tableScroll = new JScrollPane(table);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        split.setLeftComponent(tableScroll);
        split.setRightComponent(mapPanel);
        split.setResizeWeight(0.45);

        p.add(split, BorderLayout.CENTER);
        return p;
    }

    private JPanel buildBottomPanel() {
        JPanel p = new JPanel(new BorderLayout(8, 8));
        p.add(statusLabel, BorderLayout.CENTER);
        p.add(totalsLabel, BorderLayout.EAST);
        return p;
    }

    private void setBusy(boolean busy, String msg) {
        optimizeBtn.setEnabled(!busy);
        startField.setEnabled(!busy);
        addressesArea.setEnabled(!busy);

        statusLabel.setText(msg);
        setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
    }

    private void resetOutputs() {
        tableModel.setRowCount(0);
        totalsLabel.setText("—");
        lastResult = null;
        lastGoogleMapsLink = "";

        openMapsBtn.setEnabled(false);
        copyLinkBtn.setEnabled(false);
        exportCsvBtn.setEnabled(false);
        exportPdfBtn.setEnabled(false);

        mapPanel.clear();
    }

    private void onOptimize() {
        resetOutputs();
        setBusy(true, "Считаю...");

        SwingWorker<Void, Void> w = new SwingWorker<>() {
            RouteResult result;
            List<double[]> geometry;

            @Override
            protected Void doInBackground() throws Exception {
                AppConfig cfg = AppConfig.load();

                RoutingProvider routing = new OrsClient(cfg);
                RouteOptimizer opt = new RouteOptimizer(routing);

                String start = startField.getText();
                List<String> deliveries = parseAddresses(addressesArea.getText());
                if (deliveries.isEmpty()) throw new IllegalArgumentException("Добавь хотя бы 1 адрес доставки.");

                // 1) Optimize order (matrix based)
                result = opt.optimize(start, deliveries);

                // 2) Fetch ORS directions geometry for the ordered route (for map drawing)
                geometry = OrsDirections.fetchRouteGeoJsonGeometry(cfg, result.orderedStops());
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    lastResult = result;

                    renderResult(result);

                    // Render interactive map
                    mapPanel.setRoute(result.orderedStops(), geometry);

                    setBusy(false, "Готово.");
                } catch (Exception ex) {
                    setBusy(false, "Ошибка: " + ex.getMessage());
                    JOptionPane.showMessageDialog(MainFrame.this, ex.getMessage(), "Ошибка", JOptionPane.ERROR_MESSAGE);
                }
            }
        };

        w.execute();
    }

    private void renderResult(RouteResult res) {
        DecimalFormat df = new DecimalFormat("0.000000");

        int i = 1;
        for (Location l : res.orderedStops()) {
            tableModel.addRow(new Object[]{
                    i++,
                    l.address(),
                    df.format(l.lat()),
                    df.format(l.lon())
            });
        }

        double km = res.totalDistanceMeters() / 1000.0;
        double minutes = res.totalDurationSeconds() / 60.0;

        totalsLabel.setText(String.format(Locale.ROOT, "Итого: %.2f км, %.0f мин", km, minutes));

        lastGoogleMapsLink = buildGoogleMapsLink(res.orderedStops());

        openMapsBtn.setEnabled(!lastGoogleMapsLink.isBlank());
        copyLinkBtn.setEnabled(!lastGoogleMapsLink.isBlank());
        exportCsvBtn.setEnabled(true);
        exportPdfBtn.setEnabled(true);
    }

    private void onOpenGoogleMaps() {
        if (lastGoogleMapsLink == null || lastGoogleMapsLink.isBlank()) return;
        try {
            Desktop.getDesktop().browse(new URI(lastGoogleMapsLink));
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Не удалось открыть браузер", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void onCopyLink() {
        if (lastGoogleMapsLink == null || lastGoogleMapsLink.isBlank()) return;
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(lastGoogleMapsLink), null);
        statusLabel.setText("Ссылка скопирована в буфер обмена.");
    }

    private void onExportCsv() {
        if (lastResult == null) return;

        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Сохранить CSV");
        fc.setFileFilter(new FileNameExtensionFilter("CSV files (*.csv)", "csv"));
        fc.setSelectedFile(new File("route.csv"));

        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File file = fc.getSelectedFile();
        if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".csv")) {
            file = new File(file.getParentFile(), file.getName() + ".csv");
        }

        try {
            CsvExporter.writeCsv(file, lastResult, lastGoogleMapsLink);
            statusLabel.setText("CSV сохранён: " + file.getName());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Ошибка экспорта CSV", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void onExportPdf() {
        if (lastResult == null) return;

        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Сохранить PDF");
        fc.setFileFilter(new FileNameExtensionFilter("PDF files (*.pdf)", "pdf"));
        fc.setSelectedFile(new File("route.pdf"));

        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File file = fc.getSelectedFile();
        if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            file = new File(file.getParentFile(), file.getName() + ".pdf");
        }

        try {
            BufferedImage mapSnap = mapPanel.getSnapshot(1200, 700);
            PdfExporter.writePdf(file, lastResult, lastGoogleMapsLink, mapSnap);
            statusLabel.setText("PDF сохранён: " + file.getName());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Ошибка экспорта PDF", JOptionPane.ERROR_MESSAGE);
        }
    }

    private List<String> parseAddresses(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String line : raw.split("\\R")) {
            String t = line.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    // ---------- Google Maps link ----------
    private static String buildGoogleMapsLink(List<Location> ordered) {
        if (ordered == null || ordered.size() < 2) return "";
        Location origin = ordered.get(0);
        Location dest = ordered.get(ordered.size() - 1);

        List<String> waypoints = new ArrayList<>();
        for (int i = 1; i < ordered.size() - 1; i++) {
            Location w = ordered.get(i);
            waypoints.add(w.lat() + "," + w.lon());
        }

        String o = origin.lat() + "," + origin.lon();
        String d = dest.lat() + "," + dest.lon();

        HttpUrl base = Objects.requireNonNull(HttpUrl.parse("https://www.google.com/maps/dir/"),
                "Bad base URL");

        HttpUrl.Builder b = base.newBuilder()
                .addQueryParameter("api", "1")
                .addQueryParameter("origin", o)
                .addQueryParameter("destination", d);

        if (!waypoints.isEmpty()) b.addQueryParameter("waypoints", String.join("|", waypoints));
        return b.build().toString();
    }

    // =========================================================================================
    // Interactive Map via JavaFX WebView embedded into Swing (JFXPanel)
    // =========================================================================================
    private static class FxMapPanel extends JPanel {
        private static final ObjectMapper om = new ObjectMapper();

        private final JFXPanel fxPanel = new JFXPanel();
        private volatile WebEngine engine;
        private volatile WebView webView;

        FxMapPanel() {
            setLayout(new BorderLayout());
            setBorder(BorderFactory.createTitledBorder("Карта (интерактивная)"));
            add(fxPanel, BorderLayout.CENTER);

            initFx();
        }

        private void initFx() {
            Platform.setImplicitExit(false);

            Platform.runLater(() -> {
                webView = new WebView();
                engine = webView.getEngine();
                engine.loadContent(buildHtml());
                fxPanel.setScene(new Scene(webView));
            });
        }

        void clear() {
            Platform.runLater(() -> {
                if (engine != null) engine.executeScript("window.__clearRoute && window.__clearRoute();");
            });
        }

        void setRoute(List<Location> stops, List<double[]> routeLonLat) {
            if (stops == null || stops.isEmpty() || routeLonLat == null || routeLonLat.size() < 2) return;
            if (engine == null) return;

            // GeoJSON LineString (coordinates are [lon,lat])
            Map<String, Object> geojson = new LinkedHashMap<>();
            geojson.put("type", "Feature");
            Map<String, Object> geom = new LinkedHashMap<>();
            geom.put("type", "LineString");
            List<List<Double>> coords = new ArrayList<>();
            for (double[] p : routeLonLat) coords.add(List.of(p[0], p[1]));
            geom.put("coordinates", coords);
            geojson.put("geometry", geom);
            geojson.put("properties", Map.of());

            List<Map<String, Object>> markers = new ArrayList<>();
            for (int i = 0; i < stops.size(); i++) {
                Location s = stops.get(i);
                markers.add(Map.of(
                        "idx", i + 1,
                        "name", s.address(),
                        "lon", s.lon(),
                        "lat", s.lat()
                ));
            }

            try {
                String geojsonStr = om.writeValueAsString(geojson);
                String markersStr = om.writeValueAsString(markers);

                Platform.runLater(() -> waitLoadedThen(() -> {
                    engine.executeScript("window.__setRoute(" + geojsonStr + "," + markersStr + ");");
                }));
            } catch (Exception ignored) {}
        }

        BufferedImage getSnapshot(int width, int height) throws Exception {
            if (webView == null) throw new IllegalStateException("WebView not initialized");

            CountDownLatch latch = new CountDownLatch(1);
            final BufferedImage[] out = new BufferedImage[1];
            final Exception[] err = new Exception[1];

            Platform.runLater(() -> {
                try {
                    waitLoadedThen(() -> {
                        webView.setPrefSize(width, height);
                        WritableImage fxImg = webView.snapshot(new SnapshotParameters(), null);
                        out[0] = SwingFXUtils.fromFXImage(fxImg, null);
                        latch.countDown();
                    });
                } catch (Exception e) {
                    err[0] = e;
                    latch.countDown();
                }
            });

            if (!latch.await(8, TimeUnit.SECONDS)) {
                throw new RuntimeException("Не удалось сделать снимок карты (timeout).");
            }
            if (err[0] != null) throw err[0];
            return out[0];
        }

        private void waitLoadedThen(Runnable r) {
            if (engine == null) return;

            // document.readyState check (fast) + JavaFX worker state fallback
            Object ready = engine.executeScript("document.readyState");
            boolean domReady = "complete".equals(String.valueOf(ready)) || "interactive".equals(String.valueOf(ready));

            Worker.State state = engine.getLoadWorker().getState();
            boolean workerReady = (state == Worker.State.SUCCEEDED);

            if (domReady && workerReady) {
                r.run();
            } else {
                javafx.animation.PauseTransition pt =
                        new javafx.animation.PauseTransition(javafx.util.Duration.millis(150));
                pt.setOnFinished(e -> waitLoadedThen(r));
                pt.play();
            }
        }

        private String buildHtml() {
            // Leaflet via CDN + OSM tiles
            return """
            <!doctype html>
            <html>
            <head>
              <meta charset="utf-8"/>
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
              <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
              <style>
                html, body, #map { height: 100%; width: 100%; margin: 0; }
                .marker-label {
                  background: white;
                  border: 1px solid #333;
                  border-radius: 10px;
                  padding: 2px 6px;
                  font: 12px/14px sans-serif;
                }
              </style>
            </head>
            <body>
              <div id="map"></div>
              <script>
                const map = L.map('map', { zoomControl: true });
                L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
                  maxZoom: 19,
                  attribution: '&copy; OpenStreetMap contributors'
                }).addTo(map);

                let routeLayer = null;
                const markersLayer = L.layerGroup().addTo(map);

                function fitToLayers() {
                  const group = L.featureGroup([]);
                  if (routeLayer) group.addLayer(routeLayer);
                  markersLayer.eachLayer(l => group.addLayer(l));
                  if (group.getLayers().length > 0) map.fitBounds(group.getBounds().pad(0.15));
                }

                window.__clearRoute = function() {
                  if (routeLayer) { map.removeLayer(routeLayer); routeLayer = null; }
                  markersLayer.clearLayers();
                };

                window.__setRoute = function(geojsonFeature, markers) {
                  window.__clearRoute();

                  routeLayer = L.geoJSON(geojsonFeature, {
                    style: { weight: 5, opacity: 0.9 }
                  }).addTo(map);

                  markers.forEach(m => {
                    const icon = L.divIcon({
                      className: 'marker-label',
                      html: String(m.idx),
                      iconSize: [22, 22],
                      iconAnchor: [11, 11]
                    });
                    const marker = L.marker([m.lat, m.lon], { icon }).addTo(markersLayer);
                    marker.bindPopup('<b>' + m.idx + '</b>: ' + (m.name || ''));
                  });

                  fitToLayers();
                };

                map.setView([52.52, 13.405], 12);
              </script>
            </body>
            </html>
            """;
        }
    }

    // =========================================================================================
    // ORS directions geometry fetcher (GeoJSON)
    // =========================================================================================
    private static class OrsDirections {
        private static final OkHttpClient http = new OkHttpClient.Builder()
                .callTimeout(Duration.ofSeconds(45))
                .build();

        private static final ObjectMapper om = new ObjectMapper();

        static List<double[]> fetchRouteGeoJsonGeometry(AppConfig cfg, List<Location> orderedStops) throws Exception {
            if (orderedStops == null || orderedStops.size() < 2) return List.of();

            var coords = new ArrayList<List<Double>>();
            for (Location l : orderedStops) coords.add(List.of(l.lon(), l.lat()));

            String url = "https://api.openrouteservice.org/v2/directions/" + cfg.orsProfile() + "/geojson";

            String bodyJson = om.createObjectNode()
                    .set("coordinates", om.valueToTree(coords))
                    .toString();

            Request req = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", cfg.orsApiKey())
                    .addHeader("Content-Type", "application/json; charset=utf-8")
                    .post(RequestBody.create(bodyJson, MediaType.parse("application/json")))
                    .build();

            try (Response r = http.newCall(req).execute()) {
                String body = r.body() != null ? r.body().string() : "";
                if (!r.isSuccessful()) {
                    throw new RuntimeException("ORS Directions error: HTTP " + r.code() + " body=" + body);
                }

                JsonNode root = om.readTree(body);
                JsonNode feats = root.path("features");
                if (!feats.isArray() || feats.size() == 0) return List.of();

                JsonNode coordsNode = feats.get(0).path("geometry").path("coordinates");
                if (!coordsNode.isArray() || coordsNode.size() < 2) return List.of();

                List<double[]> out = new ArrayList<>(coordsNode.size());
                for (JsonNode p : coordsNode) out.add(new double[]{p.get(0).asDouble(), p.get(1).asDouble()});
                return out;
            }
        }
    }

    // =========================================================================================
    // CSV export
    // =========================================================================================
    private static class CsvExporter {
        static void writeCsv(File file, RouteResult res, String googleMapsLink) throws Exception {
            try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
                w.write('\uFEFF'); // BOM for Excel

                w.write("order,address,lat,lon\n");
                int i = 1;
                for (Location l : res.orderedStops()) {
                    w.write(i++ + ","
                            + csvEscape(l.address()) + ","
                            + l.lat() + ","
                            + l.lon()
                            + "\n");
                }

                w.write("\n");
                w.write("total_distance_m," + res.totalDistanceMeters() + "\n");
                w.write("total_duration_s," + res.totalDurationSeconds() + "\n");
                if (googleMapsLink != null && !googleMapsLink.isBlank()) {
                    w.write("google_maps_link," + csvEscape(googleMapsLink) + "\n");
                }
            }
        }

        private static String csvEscape(String s) {
            if (s == null) return "\"\"";
            String t = s.replace("\"", "\"\"");
            return "\"" + t + "\"";
        }
    }

    // =========================================================================================
    // PDF export (PDFBox)
    // =========================================================================================
    private static class PdfExporter {
        static void writePdf(File file, RouteResult res, String googleMapsLink, BufferedImage mapImage) throws Exception {
            try (PDDocument doc = new PDDocument()) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);

                PDType0Font font = loadCyrillicFont(doc);

                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    float margin = 40;
                    float y = page.getMediaBox().getHeight() - margin;

                    cs.beginText();
                    cs.setFont(font, 18);
                    cs.newLineAtOffset(margin, y);
                    cs.showText("Маршрут курьера (ORS)");
                    cs.endText();
                    y -= 26;

                    double km = res.totalDistanceMeters() / 1000.0;
                    double min = res.totalDurationSeconds() / 60.0;

                    cs.beginText();
                    cs.setFont(font, 12);
                    cs.newLineAtOffset(margin, y);
                    cs.showText(String.format(Locale.ROOT, "Итого: %.2f км, %.0f мин", km, min));
                    cs.endText();
                    y -= 18;

                    if (googleMapsLink != null && !googleMapsLink.isBlank()) {
                        cs.beginText();
                        cs.setFont(font, 9);
                        cs.newLineAtOffset(margin, y);
                        cs.showText("Google Maps: " + safePdfText(googleMapsLink, 140));
                        cs.endText();
                        y -= 16;
                    }

                    if (mapImage != null) {
                        PDImageXObject img = LosslessFactory.createFromImage(doc, mapImage);

                        float maxW = page.getMediaBox().getWidth() - margin * 2;
                        float maxH = 260;

                        float iw = img.getWidth();
                        float ih = img.getHeight();

                        float scale = Math.min(maxW / iw, maxH / ih);
                        float drawW = iw * scale;
                        float drawH = ih * scale;

                        cs.drawImage(img, margin, y - drawH, drawW, drawH);
                        y -= (drawH + 18);
                    }

                    cs.beginText();
                    cs.setFont(font, 12);
                    cs.newLineAtOffset(margin, y);
                    cs.showText("Точки маршрута:");
                    cs.endText();
                    y -= 16;

                    int idx = 1;
                    for (Location l : res.orderedStops()) {
                        if (y < 70) break;
                        String line = String.format(Locale.ROOT, "%d) %s  (%.6f, %.6f)",
                                idx++, safePdfText(l.address(), 95), l.lat(), l.lon());
                        cs.beginText();
                        cs.setFont(font, 10);
                        cs.newLineAtOffset(margin, y);
                        cs.showText(line);
                        cs.endText();
                        y -= 12;
                    }

                    cs.beginText();
                    cs.setFont(font, 8);
                    cs.newLineAtOffset(margin, 28);
                    cs.showText("Map © OpenStreetMap contributors, rendered in JavaFX WebView (Leaflet)");
                    cs.endText();
                }

                doc.save(file);
            }
        }

        private static PDType0Font loadCyrillicFont(PDDocument doc) throws IOException {
            List<String> candidates = List.of(
                    "C:\\Windows\\Fonts\\DejaVuSans.ttf",
                    "C:\\Windows\\Fonts\\arial.ttf",
                    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                    "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf"
            );
            for (String p : candidates) {
                File f = new File(p);
                if (f.exists()) {
                    try (InputStream in = new FileInputStream(f)) {
                        return PDType0Font.load(doc, in, true);
                    } catch (Exception ignored) {}
                }
            }
            try (InputStream in = MainFrame.class.getClassLoader().getResourceAsStream("DejaVuSans.ttf")) {
                if (in != null) return PDType0Font.load(doc, in, true);
            }
            throw new RuntimeException("Не найден шрифт с поддержкой кириллицы (поставь DejaVuSans или LiberationSans).");
        }

        private static String safePdfText(String s, int maxLen) {
            if (s == null) return "";
            String t = s.replace("\r", " ").replace("\n", " ").trim();
            if (t.length() > maxLen) t = t.substring(0, maxLen - 1) + "…";
            return t;
        }
    }
}