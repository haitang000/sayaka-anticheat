package cn.haitang.anticheat.update;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.io.Closeable;
import java.lang.reflect.Field;
import java.net.URLClassLoader;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 在隔离类加载器中执行的插件自我热重载工人。
 *
 * <p>此类被 {@link PluginReloader} 通过一个父加载器为 Bukkit 加载器的独立
 * {@code ClassLoader} 重新定义并调用，因此它<strong>只能</strong>引用 {@code java.*}
 * 与 {@code org.bukkit.*}——绝不能触及任何 {@code cn.haitang.*} 类型。旧插件的
 * {@code PluginClassLoader} 会在卸载步骤中被关闭；由于本工人不属于该加载器，关闭之后
 * 它仍可安全地进行字符串拼接、lambda 及新类加载，从而规避了「插件卸载自己」的类加载陷阱。
 *
 * <p>所有入口参数与返回值都只使用 JDK 与 Bukkit API 里的类型（这些类型在两个加载器中
 * 解析到同一个 {@code Class}），因此 {@link PluginReloader} 可以用普通反射调用 {@link #run}。
 */
public final class IsolatedReload {

    private IsolatedReload() {
    }

    /**
     * 卸载当前插件、替换 jar、重新加载并启用新版本。全程在主线程同步执行。
     *
     * @return 成功返回 {@code null}；失败返回错误信息（此时已尝试回滚到旧 jar）。
     */
    public static String run(String pluginName,
                             String currentJar, String stagedJar, String targetJar, String backupJar,
                             String expectedVersion,
                             CommandSender sender,
                             String successMessage, String failedPrefix, String failedSuffix) {
        Path current = Path.of(currentJar);
        Path staged = Path.of(stagedJar);
        Path target = Path.of(targetJar);
        Path backup = Path.of(backupJar);
        PluginManager pluginManager = Bukkit.getPluginManager();
        Plugin previous = pluginManager.getPlugin(pluginName);
        // 三级进度标记决定失败时如何把服务端恢复到可用状态：只禁用过 -> 重新启用同一实例；
        // 已移出注册表 -> 从旧 jar 重新加载；已换 jar -> 先还原文件再从旧 jar 重新加载。
        boolean disabled = false;
        boolean unregistered = false;
        boolean jarSwapped = false;
        try {
            if (previous != null) {
                ClassLoader classLoader = previous.getClass().getClassLoader();
                // Paper 1.20.6+ 的 SimplePluginManager.disablePlugin 内部会遍历命令表并调用
                // 迭代器 remove() 清理属于本插件的命令；现代 Paper 的 knownCommands 是 Brigadier
                // 视图 Map（只实现 get/put/remove/clear），entrySet().iterator().remove() 未实现，
                // 会直接抛 UnsupportedOperationException("remove")，导致热重载在禁用阶段就失败。
                // 因此在 disablePlugin 之前先把本插件的命令全部安全摘掉（逐个 remove(label)），
                // Paper 内部遍历时找不到归属本插件的命令，就不会触发迭代器 remove。
                quietly("unregister commands before disable", () -> unregisterCommands(pluginManager, previous));
                pluginManager.disablePlugin(previous);
                disabled = true;

                detach(pluginManager, previous, pluginName);
                // 注册表必须确实腾空，否则新 jar 的 loadPlugin 会因重名失败；此时旧插件的类加载器
                // 尚未关闭，仍可安全地重新启用旧实例，所以要在关闭加载器与换 jar 之前校验。
                if (pluginManager.getPlugin(pluginName) != null) {
                    throw new IllegalStateException("could not remove " + pluginName
                            + " from the plugin registry");
                }
                unregistered = true;

                closeClassLoader(classLoader);
                System.gc();
            }

            replaceJar(current, staged, target, backup);
            jarSwapped = true;

            Plugin loaded = loadAndEnable(pluginManager, target.toFile());
            if (loaded == null || !loaded.isEnabled()
                    || !expectedVersion.equals(loaded.getDescription().getVersion())) {
                throw new IllegalStateException("updated " + pluginName
                        + " did not enable with version " + expectedVersion);
            }

            Files.deleteIfExists(backup);
            if (sender != null) sender.sendMessage(successMessage);
            return null;
        } catch (Throwable error) {
            String message = describe(error);
            severe("In-place hot reload failed: " + message);
            // 新版本可能已半加载：先把它彻底摘掉并释放 jar 句柄，Windows 上否则无法还原文件。
            Plugin lingering = pluginManager.getPlugin(pluginName);
            if (lingering != null && lingering != previous) discard(pluginManager, lingering, pluginName);
            if (jarSwapped) restoreJar(current, staged, target, backup);
            recover(pluginManager, previous, pluginName, current, disabled, unregistered);
            if (sender != null) sender.sendMessage(failedPrefix + message + failedSuffix);
            return message;
        }
    }

    /**
     * 复刻 PlugManX 的解绑流程：注销监听器与命令，并移出插件注册表。各步骤彼此独立地容错——
     * 单个服务端内部结构不合预期不应让整次重载半途而废（真正的成功判据是调用方随后的注册表校验）。
     */
    private static void detach(PluginManager pluginManager, Plugin plugin, String pluginName) {
        quietly("unregister event listeners", () -> HandlerList.unregisterAll(plugin));
        quietly("unregister commands", () -> unregisterCommands(pluginManager, plugin));
        quietly("remove from plugin registries", () -> removeFromRegistries(pluginManager, plugin, pluginName));
    }

    /** 把一个半加载的插件实例彻底摘除（禁用 + 解绑 + 关闭加载器），用于失败回滚前的清场。 */
    private static void discard(PluginManager pluginManager, Plugin plugin, String pluginName) {
        ClassLoader classLoader = plugin.getClass().getClassLoader();
        quietly("unregister commands before discard", () -> unregisterCommands(pluginManager, plugin));
        quietly("disable the half-loaded " + pluginName, () -> pluginManager.disablePlugin(plugin));
        detach(pluginManager, plugin, pluginName);
        closeClassLoader(classLoader);
        System.gc();
    }

    /** 失败后尽力让旧版本重新跑起来：只被禁用过就直接启用，已摘除则从旧 jar 重新加载。 */
    private static void recover(PluginManager pluginManager, Plugin previous, String pluginName,
                                Path current, boolean disabled, boolean unregistered) {
        if (!disabled) return;
        try {
            if (!unregistered && pluginManager.getPlugin(pluginName) == previous) {
                pluginManager.enablePlugin(previous);
            } else {
                loadAndEnable(pluginManager, current.toFile());
            }
        } catch (Throwable rollbackError) {
            severe("Failed to reload the previous "
                    + pluginName + " jar: " + describe(rollbackError));
        }
    }

    private static void quietly(String step, Runnable action) {
        try {
            action.run();
        } catch (Throwable error) {
            warn("Hot reload could not " + step + ": "
                    + describe(error));
        }
    }

    private static Plugin loadAndEnable(PluginManager pluginManager, java.io.File jar) throws Exception {
        // Paper 的 PluginManager.loadPlugin 内部（JavaPluginLoader）已经调用过 onLoad()，
        // 这里再手动调一次会导致插件初始化逻辑执行两遍（注册两遍监听器/任务），必须去掉。
        Plugin loaded = pluginManager.loadPlugin(jar);
        if (loaded == null) return null;
        pluginManager.enablePlugin(loaded);
        return loaded;
    }

    private static void unregisterCommands(PluginManager pluginManager, Plugin plugin) {
        CommandMap commandMap = commandMap(pluginManager);
        if (commandMap == null) return;
        @SuppressWarnings("unchecked")
        Map<String, Command> known = readField(commandMap, "knownCommands", Map.class);
        if (known == null) return;
        List<String> stale = removeOwnedCommands(known, commandMap, plugin);
        if (!stale.isEmpty()) {
            warn("Hot reload could not drop stale command"
                    + " mappings " + String.join(", ", stale)
                    + "; they will keep pointing at the old plugin until the server restarts.");
        }
    }

    /**
     * 从命令表里摘掉属于 {@code plugin} 的所有条目，返回未能摘掉的标签。
     *
     * <p>现代 Paper 的 {@code SimpleCommandMap.knownCommands} 不再是 {@code HashMap}，而是一个由
     * Brigadier 调度器支撑、经构造器注入的视图 Map：它实现了 {@code get/put/remove/clear}
     * （Paper 自身就在用），但 {@code entrySet().iterator()} 未实现 {@code remove()}——直接在迭代器上
     * 删除会命中 {@code Iterator} 的默认方法并抛出 {@code UnsupportedOperationException("remove")}。
     * 因此这里先只读收集标签，再逐个调用 {@code remove(key)}，最后才让命令自身注销。
     */
    static List<String> removeOwnedCommands(Map<String, Command> known, CommandMap commandMap, Plugin plugin) {
        List<String> labels = new ArrayList<>();
        List<Command> owned = new ArrayList<>();
        quietly("scan the command map", () -> {
            for (Map.Entry<String, Command> entry : known.entrySet()) {
                collect(known, plugin, entry.getKey(), labels, owned);
            }
        });
        // 视图 Map 万一连遍历都不支持（或值不是 PluginCommand），再按 plugin.yml 声明的标签逐个探测；
        // 两条路径都只删除归属校验通过的条目，不会误伤别的插件抢注的同名命令。
        quietly("probe the declared command labels", () -> {
            for (String declared : declaredLabels(plugin)) {
                collect(known, plugin, declared, labels, owned);
            }
        });

        List<String> stale = new ArrayList<>();
        for (String label : labels) {
            try {
                known.remove(label);
            } catch (RuntimeException ignored) {
                // 视图 Map 也可能拒绝 remove；记为残留，由调用方告警
            }
            if (ownedBy(known.get(label), plugin)) stale.add(label);
        }
        for (Command command : owned) {
            try {
                command.unregister(commandMap);
            } catch (RuntimeException ignored) {
                // 注销只是让命令允许被重新注册，失败不影响新版本加载
            }
        }
        return stale;
    }

    private static void collect(Map<String, Command> known, Plugin plugin, String label,
                               List<String> labels, List<Command> owned) {
        Command command = known.get(label);
        if (!ownedBy(command, plugin)) return;
        if (!labels.contains(label)) labels.add(label);
        if (!containsSame(owned, command)) owned.add(command);
    }

    /** plugin.yml 声明的命令名与别名，连同 {@code 插件名:标签} 回退前缀形式。 */
    private static List<String> declaredLabels(Plugin plugin) {
        List<String> labels = new ArrayList<>();
        String prefix = plugin.getDescription().getName().toLowerCase(java.util.Locale.ROOT) + ":";
        for (Map.Entry<String, Map<String, Object>> entry : plugin.getDescription().getCommands().entrySet()) {
            addLabel(labels, prefix, entry.getKey());
            Object aliases = entry.getValue() == null ? null : entry.getValue().get("aliases");
            if (aliases instanceof List<?> list) {
                for (Object alias : list) addLabel(labels, prefix, String.valueOf(alias));
            } else if (aliases instanceof String alias) {
                addLabel(labels, prefix, alias);
            }
        }
        return labels;
    }

    private static void addLabel(List<String> labels, String prefix, String name) {
        String label = name.toLowerCase(java.util.Locale.ROOT).trim();
        if (label.isEmpty()) return;
        labels.add(label);
        labels.add(prefix + label);
    }

    private static boolean ownedBy(Command command, Plugin plugin) {
        return command instanceof PluginCommand pluginCommand && pluginCommand.getPlugin() == plugin;
    }

    private static boolean containsSame(List<Command> commands, Command command) {
        for (Command existing : commands) {
            if (existing == command) return true;
        }
        return false;
    }

    /** 优先使用 Paper API 暴露的命令表；纯 Spigot 或异常情况下回退到插件管理器的内部字段。 */
    private static CommandMap commandMap(PluginManager pluginManager) {
        try {
            CommandMap fromApi = Bukkit.getServer().getCommandMap();
            if (fromApi != null) return fromApi;
        } catch (Throwable ignored) {
            // 老服务端没有该 API，走反射回退
        }
        return readField(pluginManager, "commandMap", CommandMap.class);
    }

    /**
     * 从插件注册表移除插件。同时覆盖传统 {@code SimplePluginManager} 与现代 Paper
     * （{@code PaperPluginManagerImpl -> PaperPluginInstanceManager}）两条路径；无论哪条
     * 存在，都按引用相等移除 {@code plugins} 列表与 {@code lookupNames} 映射中的条目。
     */
    private static void removeFromRegistries(PluginManager pluginManager, Plugin plugin, String pluginName) {
        removeFrom(pluginManager, plugin, pluginName);
        Object paperManager = readField(pluginManager, "paperPluginManager", Object.class);
        if (paperManager != null) {
            Object instanceManager = readField(paperManager, "instanceManager", Object.class);
            if (instanceManager != null) removeFrom(instanceManager, plugin, pluginName);
        }
    }

    private static void removeFrom(Object holder, Plugin plugin, String pluginName) {
        List<?> plugins = readField(holder, "plugins", List.class);
        // 两个注册表各自容错：其一是不可变/视图集合时，另一个仍应被清理干净。
        if (plugins != null) {
            quietly("drop the plugin list entry", () -> plugins.removeIf(entry -> entry == plugin));
        }

        Map<?, ?> lookupNames = readField(holder, "lookupNames", Map.class);
        if (lookupNames != null) {
            quietly("drop the plugin name lookups", () -> {
                lookupNames.values().removeIf(entry -> entry == plugin);
                lookupNames.remove(pluginName);
                lookupNames.remove(pluginName.toLowerCase(java.util.Locale.ROOT));
            });
        }
    }

    private static void closeClassLoader(ClassLoader classLoader) {
        clearField(classLoader, "plugin");
        clearField(classLoader, "pluginInit");
        if (classLoader instanceof URLClassLoader urlClassLoader) {
            closeQuietly(urlClassLoader);
        } else if (classLoader instanceof Closeable closeable) {
            closeQuietly(closeable);
        }
    }

    static void replaceJar(Path current, Path staged, Path target, Path backup) throws Exception {
        Files.deleteIfExists(backup);
        moveReplacing(current, backup);
        try {
            moveReplacing(staged, target);
        } catch (Exception error) {
            moveReplacing(backup, current);
            throw error;
        }
    }

    static void restoreJar(Path current, Path staged, Path target, Path backup) {
        try {
            if (Files.exists(target)) moveReplacing(target, staged);
            if (Files.exists(backup)) moveReplacing(backup, current);
        } catch (Exception error) {
            severe("Failed to restore the previous plugin jar: "
                    + describe(error));
        }
    }

    private static void moveReplacing(Path source, Path destination) throws java.io.IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static <T> T readField(Object target, String name, Class<T> type) {
        if (target == null) return null;
        Field field = findField(target.getClass(), name);
        if (field == null) return null;
        try {
            Object value = field.get(target);
            return type.isInstance(value) ? type.cast(value) : null;
        } catch (ReflectiveOperationException error) {
            return null;
        }
    }

    private static void clearField(Object target, String name) {
        if (target == null) return;
        Field field = findField(target.getClass(), name);
        if (field == null) return;
        try {
            field.set(target, null);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 该字段对回收只是辅助，失败无碍
        }
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // 继续向父类查找
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Windows 上偶发的句柄延迟释放不应中断重载
        }
    }

    private static void severe(String message) {
        log(java.util.logging.Level.SEVERE, message);
    }

    private static void warn(String message) {
        log(java.util.logging.Level.WARNING, message);
    }

    /** 日志本身也可能失败（回滚阶段服务端状态未必完好），绝不能让它盖掉真正的错误。 */
    private static void log(java.util.logging.Level level, String message) {
        try {
            Bukkit.getLogger().log(level, "[Sayaka AntiCheat] " + message);
        } catch (Throwable ignored) {
            // 无从记录时静默放弃
        }
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
