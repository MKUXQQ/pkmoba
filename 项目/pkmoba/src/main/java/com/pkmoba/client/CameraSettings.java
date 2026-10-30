package com.pkmoba.client;

import com.pkmoba.PkmobaMod;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.Reader;
import java.io.Writer;
import java.util.Properties;

/** Local camera settings, percentages of window size. */
public final class CameraSettings {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("pkmoba-camera.properties");
    public static double left = 10, right = 10, top = 18, bottom = 18, speed = 18;
    private static long previewUntil;
    private CameraSettings() { }
    public static boolean isPreviewVisible() { return System.nanoTime() < previewUntil; }
    public static void showPreview() { previewUntil = System.nanoTime() + 5_000_000_000L; }
    public static void reset() {
        left = right = 10; top = bottom = 18; speed = 18;
        save(); showPreview();
    }
    public static void load() {
        if (!Files.exists(FILE)) return;
        Properties p = new Properties();
        boolean migrate = false;
        try (Reader reader = Files.newBufferedReader(FILE)) {
            p.load(reader);
            // Migrate old four-edge defaults explicitly once; retain pan speed.
            speed = read(p, "speed", 18, 1, 100);
            migrate = !"2".equals(p.getProperty("schema"));
            if (migrate) {
                left = right = 10; top = bottom = 18;
            } else {
                left = read(p, "left", 10, 0, 40);
                right = read(p, "right", 10, 0, 40);
                top = bottom = read(p, "vertical", 18, 0, 40);
            }
        } catch (Exception e) { PkmobaMod.LOGGER.warn("[pkmoba] 无法读取镜头设置", e); }
        if (migrate) save();
    }
    private static double read(Properties p, String key, double fallback, double min, double max) {
        try {
            double value = Double.parseDouble(p.getProperty(key, Double.toString(fallback)));
            return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
        } catch (NumberFormatException e) { return fallback; }
    }
    public static void save() {
        Properties p = new Properties();
        p.setProperty("schema", "2");
        p.setProperty("left", Double.toString(left)); p.setProperty("right", Double.toString(right));
        p.setProperty("vertical", Double.toString(top)); p.setProperty("speed", Double.toString(speed));
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE)) { p.store(writer, "PKMOBA client camera"); }
        } catch (Exception e) { PkmobaMod.LOGGER.warn("[pkmoba] 无法保存镜头设置", e); }
    }
}
