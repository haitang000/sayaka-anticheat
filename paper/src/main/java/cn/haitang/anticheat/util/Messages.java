package cn.haitang.anticheat.util;

import cn.haitang.anticheat.AntiCheatPlugin;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从 config.yml 的 messages 段读取文本，处理颜色代码与占位符。 */
public class Messages {

    private final AntiCheatPlugin plugin;
    private final YamlConfiguration bundledDefaults;

    public Messages(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.bundledDefaults = loadBundledDefaults(plugin);
    }

    public String prefix() {
        return color(getString("messages.prefix", "&8[&c反作弊&8]&7 "));
    }

    /** 取单行文本并替换占位符（不带前缀） */
    public String get(String key, Map<String, String> placeholders) {
        String raw = getString("messages." + key, "&c<缺失文本: " + key + ">");
        // 先着色模板、再插入值：反过来会把占位符值里的 & 也翻译成颜色码。
        return apply(color(raw), placeholders);
    }

    /** 取单行文本，带前缀 */
    public String prefixed(String key, Map<String, String> placeholders) {
        return prefix() + get(key, placeholders);
    }

    /** 取多行文本 */
    public List<String> getList(String key, Map<String, String> placeholders) {
        List<String> out = new ArrayList<>();
        String path = "messages." + key;
        List<String> lines = plugin.config().isList(path)
                ? plugin.config().getStringList(path)
                : bundledDefaults.getStringList(path);
        for (String line : lines) {
            out.add(apply(color(line), placeholders));
        }
        return out;
    }

    private String getString(String path, String fallback) {
        String configured = plugin.config().getString(path);
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

    private static final Pattern PLACEHOLDER = Pattern.compile("%([A-Za-z0-9_-]+)%");

    /**
     * 单趟替换占位符，值原样插入。
     *
     * <p>原先是按 {@code Map} 迭代顺序逐个 {@link String#replace} —— 调用方传的是
     * {@code Map.of(...)}，而它的迭代顺序按 JVM 每次启动随机化，所以值里若含
     * {@code %xxx%} 会被后续轮次再次替换，行为不确定。单趟扫描杜绝了值被二次解释。
     */
    static String apply(String raw, Map<String, String> placeholders) {
        if (placeholders == null || placeholders.isEmpty()) return raw;
        Matcher matcher = PLACEHOLDER.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = placeholders.get(matcher.group(1));
            matcher.appendReplacement(out,
                    Matcher.quoteReplacement(value == null ? matcher.group() : sanitize(value)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * 去掉占位符值里的颜色码引导符。
     *
     * <p>值可以是玩家可控文本——例如 AntiAds 的 detail 直接取自玩家聊天内容——
     * 而着色过去发生在替换**之后**，于是玩家发 {@code &k&4example.com}
     * 就能把乱码/变色注入每个管理员的警报行，{@code &r} 还能抹掉前缀。
     */
    private static String sanitize(String value) {
        return value.indexOf('&') < 0 && value.indexOf(ChatColor.COLOR_CHAR) < 0
                ? value
                : value.replace('&', '＆').replace(ChatColor.COLOR_CHAR, '＆');
    }

    public static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
