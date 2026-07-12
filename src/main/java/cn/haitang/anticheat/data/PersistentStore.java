package cn.haitang.anticheat.data;

import cn.haitang.anticheat.AntiCheatPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * 跨会话数据（data.yml）：strike 记录、封禁次数、惩罚历史和反作弊白名单。
 * 玩家退出重进不会清空 strike，因此"踢出 → 重进 → 再踢出"最终仍会升级为封禁。
 */
public class PersistentStore {

    public record WhitelistEntry(UUID uuid, String name) {}

    private static final SimpleDateFormat TIME = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    /** 提交异步保存任务；返回 false 表示执行器拒绝（队列满或已关闭）。 */
    @FunctionalInterface
    interface AsyncTaskSubmitter {
        boolean submit(Runnable task);
    }

    private final File file;
    private final Logger logger;
    private final AsyncTaskSubmitter asyncSubmitter;
    private final Object lock = new Object();
    private final Object saveOrderLock = new Object();
    private final AtomicBoolean saveScheduled = new AtomicBoolean();
    private YamlConfiguration yaml;
    private volatile boolean dirty;

    public PersistentStore(AntiCheatPlugin plugin) {
        this(new File(plugin.getDataFolder(), "data.yml"), plugin.getLogger(),
                task -> plugin.getAnalysisExecutor().execute(task));
    }

    PersistentStore(File file, Logger logger, AsyncTaskSubmitter asyncSubmitter) {
        this.file = file;
        this.logger = logger;
        this.asyncSubmitter = asyncSubmitter;
        load();
    }

    private void load() {
        yaml = YamlConfiguration.loadConfiguration(file);
    }

    private String base(UUID uuid) {
        return "players." + uuid;
    }

    // ---- strike ----

    public void addStrike(UUID uuid, String name) {
        synchronized (lock) {
            yaml.set(base(uuid) + ".name", name);
            List<Long> strikes = yaml.getLongList(base(uuid) + ".strikes");
            strikes.add(System.currentTimeMillis());
            yaml.set(base(uuid) + ".strikes", strikes);
            dirty = true;
        }
    }

    /** 统计窗口期内的 strike 次数，并顺带清理过期记录 */
    public int strikeCount(UUID uuid, int windowHours) {
        synchronized (lock) {
            long cutoff = System.currentTimeMillis() - windowHours * 3600_000L;
            List<Long> strikes = yaml.getLongList(base(uuid) + ".strikes");
            List<Long> valid = new ArrayList<>();
            for (long s : strikes) {
                if (s >= cutoff) valid.add(s);
            }
            if (valid.size() != strikes.size()) {
                yaml.set(base(uuid) + ".strikes", valid);
                dirty = true;
            }
            return valid.size();
        }
    }

    public void clearStrikes(UUID uuid) {
        synchronized (lock) {
            yaml.set(base(uuid) + ".strikes", new ArrayList<Long>());
            dirty = true;
        }
    }

    // ---- 封禁次数（决定下一次临时封禁的时长档位） ----

    public int getBanCount(UUID uuid) {
        synchronized (lock) {
            return yaml.getInt(base(uuid) + ".ban-count", 0);
        }
    }

    public void incrementBanCount(UUID uuid) {
        synchronized (lock) {
            yaml.set(base(uuid) + ".ban-count", getBanCount(uuid) + 1);
            dirty = true;
        }
    }

    public void resetBanCount(UUID uuid) {
        synchronized (lock) {
            yaml.set(base(uuid) + ".ban-count", 0);
            dirty = true;
        }
    }

    public void resetPlayer(UUID uuid) {
        synchronized (lock) {
            yaml.set(base(uuid), null);
            dirty = true;
        }
    }

    // ---- 反作弊白名单 ----

    public boolean isWhitelisted(UUID uuid) {
        synchronized (lock) {
            return yaml.contains("whitelist." + uuid);
        }
    }

    public void addWhitelist(UUID uuid, String name) {
        synchronized (lock) {
            yaml.set("whitelist." + uuid, name);
            dirty = true;
        }
    }

    public boolean removeWhitelist(UUID uuid) {
        synchronized (lock) {
            String path = "whitelist." + uuid;
            if (!yaml.contains(path)) return false;
            yaml.set(path, null);
            dirty = true;
            return true;
        }
    }

    public WhitelistEntry findWhitelistByName(String name) {
        synchronized (lock) {
            for (WhitelistEntry entry : getWhitelistLocked()) {
                if (entry.name().equalsIgnoreCase(name)) return entry;
            }
            return null;
        }
    }

    public List<WhitelistEntry> getWhitelist() {
        synchronized (lock) {
            return getWhitelistLocked();
        }
    }

    private List<WhitelistEntry> getWhitelistLocked() {
        List<WhitelistEntry> entries = new ArrayList<>();
        ConfigurationSection section = yaml.getConfigurationSection("whitelist");
        if (section == null) return entries;
        for (String key : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                entries.add(new WhitelistEntry(uuid, section.getString(key, key)));
            } catch (IllegalArgumentException ignored) {
                logger.warning("忽略 data.yml 中无效的白名单 UUID: " + key);
            }
        }
        entries.sort(Comparator.comparing(WhitelistEntry::name, String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    // ---- 惩罚历史 ----

    public void addHistory(UUID uuid, String line) {
        synchronized (lock) {
            List<String> history = yaml.getStringList(base(uuid) + ".history");
            history.add(TIME.format(new Date()) + " " + line);
            while (history.size() > 25) history.remove(0);
            yaml.set(base(uuid) + ".history", history);
            dirty = true;
        }
    }

    public List<String> getHistory(UUID uuid) {
        synchronized (lock) {
            return yaml.getStringList(base(uuid) + ".history");
        }
    }

    // ---- 保存 ----

    /** Coalesces serialization and disk I/O onto the bounded analysis pool. */
    public void saveAsync() {
        if (!dirty) return;
        if (!saveScheduled.compareAndSet(false, true)) return;
        boolean accepted = asyncSubmitter.submit(this::saveDirtyLoop);
        if (!accepted) {
            saveScheduled.set(false);
        }
    }

    /** 关服时同步保存 */
    public void saveNow() {
        synchronized (saveOrderLock) {
            String content;
            synchronized (lock) {
                content = yaml.saveToString();
                dirty = false;
            }
            write(content);
        }
    }

    private void saveDirtyLoop() {
        try {
            while (true) {
                synchronized (saveOrderLock) {
                    String content;
                    synchronized (lock) {
                        if (!dirty) return;
                        content = yaml.saveToString();
                        dirty = false;
                    }
                    write(content);
                }
            }
        } finally {
            saveScheduled.set(false);
            if (dirty) saveAsync();
        }
    }

    private void write(String content) {
        try {
            if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
                logger.warning("无法创建数据目录: " + file.getParent());
            }
            Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warning("data.yml 保存失败: " + e.getMessage());
        }
    }
}
