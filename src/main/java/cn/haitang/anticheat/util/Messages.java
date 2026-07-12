package cn.haitang.anticheat.util;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 从 config.yml 的 messages 段读取文本，处理颜色代码与占位符。 */
public class Messages {

    private final JavaPlugin plugin;
    private final YamlConfiguration bundledDefaults;

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
        this.bundledDefaults = loadBundledDefaults(plugin);
    }

    public String prefix() {
        return color(getString("messages.prefix", "&8[&c反作弊&8]&7 "));
    }

    /** 取单行文本并替换占位符（不带前缀） */
    public String get(String key, Map<String, String> placeholders) {
        String raw = getString("messages." + key, "&c<缺失文本: " + key + ">");
        return color(apply(raw, placeholders));
    }

    /** 取单行文本，带前缀 */
    public String prefixed(String key, Map<String, String> placeholders) {
        return prefix() + get(key, placeholders);
    }

    /** 取多行文本 */
    public List<String> getList(String key, Map<String, String> placeholders) {
        List<String> out = new ArrayList<>();
        String path = "messages." + key;
        List<String> lines = plugin.getConfig().isList(path)
                ? plugin.getConfig().getStringList(path)
                : bundledDefaults.getStringList(path);
        for (String line : lines) {
            out.add(color(apply(line, placeholders)));
        }
        return out;
    }

    private String getString(String path, String fallback) {
        String configured = plugin.getConfig().getString(path);
        if (configured != null) return configured;
        return bundledDefaults.getString(path, fallback);
    }

    private static YamlConfiguration loadBundledDefaults(JavaPlugin plugin) {
        YamlConfiguration defaults = new YamlConfiguration();
        InputStream stream = plugin.getResource("config.yml");
        if (stream == null) {
            plugin.getLogger().warning("插件包内缺少 config.yml，新增消息将无法使用默认文案");
            return defaults;
        }
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            plugin.getLogger().warning("读取插件内置消息失败: " + e.getMessage());
            return defaults;
        }
    }

    private String apply(String raw, Map<String, String> placeholders) {
        if (placeholders != null) {
            for (Map.Entry<String, String> e : placeholders.entrySet()) {
                raw = raw.replace("%" + e.getKey() + "%", e.getValue());
            }
        }
        return raw;
    }

    public static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
