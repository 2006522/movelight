package dev.movelight;

import net.minecraft.world.item.Item;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 配置读写，文件在 {@code config/movelight.properties}。
 *
 * <p>默认值是"开箱即用"：拿着火把/荧石走就亮，亮度原样，光源贴在脚下。</p>
 */
public final class Config {

    private static final File FILE = new File(new File("config"), "movelight.properties");

    private static final Config INSTANCE = new Config();

    /** 总开关。 */
    private boolean enable = true;

    /** 亮度倍率：0.5 就把 15 压成 7，1.0 保持原样。 */
    private double brightnessScale = 1.0D;

    /** 光源相对玩家位置的偏移（格）。默认贴在脚下那一格。 */
    private double offsetX = 0.0D;
    private double offsetY = 0.0D;
    private double offsetZ = 0.0D;

    /**
     * 是否往下探找可放置的格子。
     * 站在半砖/台阶上时不开这个会顶掉脚下的方块（虽然有防护，但看着别扭）。
     */
    private boolean snapToGround = true;

    /**
     * 不算"方块"但也要发光的物品，格式 {@code 物品id=亮度}。
     *
     * <p>默认给几样原版里常见的：火把是方块所以自动就有，但岩浆桶、萤石粉
     * 这类不是方块，得单独列。</p>
     */
    private final Map<String, Integer> extraItems = new LinkedHashMap<>();

    private Config() {
        extraItems.put("minecraft:lava_bucket", 15);
        extraItems.put("minecraft:glowstone_dust", 7);
        extraItems.put("minecraft:glow_ink_sac", 5);
        extraItems.put("minecraft:blaze_powder", 6);
        extraItems.put("minecraft:blaze_rod", 7);
        extraItems.put("minecraft:magma_cream", 5);
    }

    public static Config get() {
        return INSTANCE;
    }

    public boolean isEnable() {
        return enable;
    }

    public void setEnable(boolean enable) {
        this.enable = enable;
    }

    public double getBrightnessScale() {
        return brightnessScale;
    }

    public void setBrightnessScale(double scale) {
        this.brightnessScale = Math.max(0.0D, Math.min(1.0D, scale));
        MovingLight.invalidate();
    }

    public boolean isSnapToGround() {
        return snapToGround;
    }

    public void setSnapToGround(boolean snap) {
        this.snapToGround = snap;
    }

    public double getOffsetY() {
        return offsetY;
    }

    public void setOffsetY(double y) {
        this.offsetY = Math.max(-2.0D, Math.min(2.0D, y));
    }

    public double getOffsetX() {
        return offsetX;
    }

    public double getOffsetZ() {
        return offsetZ;
    }

    /** 手上物品（非方块）对应的亮度，没有就是 0。 */
    static int lightOfItem(Item item) {
        Map<String, Integer> extra = INSTANCE.extraItems;
        if (extra.isEmpty()) {
            return 0;
        }
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(item).toString();
        Integer light = extra.get(id);
        return light == null ? 0 : light;
    }

    public Map<String, Integer> extraItems() {
        return extraItems;
    }

    // ---------------------------------------------------------------- 读写

    public void load() {
        Properties p = new Properties();
        if (FILE.isFile()) {
            try (FileInputStream in = new FileInputStream(FILE)) {
                p.load(in);
            } catch (Exception e) {
                Log.warn("读取配置失败，使用默认值", e);
            }
        }
        enable = bool(p, "enable", enable);
        brightnessScale = clampD(dbl(p, "brightness_scale", brightnessScale), 0.0D, 1.0D);
        offsetX = clampD(dbl(p, "offset_x", offsetX), -2.0D, 2.0D);
        offsetY = clampD(dbl(p, "offset_y", offsetY), -2.0D, 2.0D);
        offsetZ = clampD(dbl(p, "offset_z", offsetZ), -2.0D, 2.0D);
        snapToGround = bool(p, "snap_to_ground", snapToGround);
        readExtra(p);
    }

    /** 额外发光物品写成 {@code id=亮度,id=亮度} 一行，方便手改。 */
    private void readExtra(Properties p) {
        String raw = p.getProperty("extra_items");
        if (raw == null || raw.isBlank()) {
            return;
        }
        extraItems.clear();
        for (String part : raw.split(",")) {
            String[] kv = part.split("=");
            if (kv.length != 2) {
                continue;
            }
            String id = kv[0].trim().toLowerCase();
            try {
                int light = Integer.parseInt(kv[1].trim());
                if (!id.isEmpty() && light > 0) {
                    extraItems.put(id, Math.min(15, light));
                }
            } catch (NumberFormatException ignored) {
                // 写错的行直接跳过
            }
        }
    }

    public void save() {
        try {
            File parent = FILE.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                return;
            }
            Properties out = new Properties();
            out.setProperty("enable", Boolean.toString(enable));
            out.setProperty("brightness_scale", Double.toString(brightnessScale));
            out.setProperty("offset_x", Double.toString(offsetX));
            out.setProperty("offset_y", Double.toString(offsetY));
            out.setProperty("offset_z", Double.toString(offsetZ));
            out.setProperty("snap_to_ground", Boolean.toString(snapToGround));
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> e : extraItems.entrySet()) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            out.setProperty("extra_items", sb.toString());
            try (FileOutputStream fos = new FileOutputStream(FILE)) {
                out.store(fos, "MoveLight - 移动光源配置（手持发光物品时身边自动亮）");
            }
        } catch (Exception e) {
            Log.warn("写入配置失败", e);
        }
    }

    private static boolean bool(Properties p, String k, boolean def) {
        String v = p.getProperty(k);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }

    private static double dbl(Properties p, String k, double def) {
        try {
            String v = p.getProperty(k);
            return v == null ? def : Double.parseDouble(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static double clampD(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}