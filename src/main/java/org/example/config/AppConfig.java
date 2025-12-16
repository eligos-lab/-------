package org.example.config;

import java.io.InputStream;
import java.util.Properties;

public final class AppConfig {

    private final String orsApiKey;
    private final String orsProfile;
    private final String orsLanguage;

    private AppConfig(String key, String profile, String lang) {
        this.orsApiKey = key;
        this.orsProfile = profile;
        this.orsLanguage = lang;
    }

    public static AppConfig load() {
        Properties p = new Properties();
        try (InputStream in = AppConfig.class
                .getClassLoader()
                .getResourceAsStream("config.properties")) {
            if (in != null) p.load(in);
        } catch (Exception ignored) {}

        String key = envOrProp("ORS_API_KEY", p.getProperty("ors.apiKey"));
        if (key == null) {
            throw new IllegalStateException("ORS API ключ не задан");
        }

        String profile = p.getProperty("ors.profile", "driving-car");
        String lang = p.getProperty("ors.language", "ru");

        return new AppConfig(key, profile, lang);
    }

    private static String envOrProp(String env, String prop) {
        String e = System.getenv(env);
        if (e != null && !e.isBlank()) return e;
        if (prop != null && !prop.isBlank()) return prop;
        return null;
    }

    public String orsApiKey() { return orsApiKey; }
    public String orsProfile() { return orsProfile; }
    public String orsLanguage() { return orsLanguage; }
}
