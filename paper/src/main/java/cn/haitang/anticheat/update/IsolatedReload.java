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
import java.util.Iterator;
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
        boolean unloaded = false;
        try {
            Plugin plugin = pluginManager.getPlugin(pluginName);
            if (plugin != null) {
                unloadPlugin(pluginManager, plugin, pluginName);
                unloaded = true;
            }

            replaceJar(current, staged, target, backup);

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
            Bukkit.getLogger().severe("[Sayaka AntiCheat] In-place hot reload failed: " + message);
            if (unloaded) {
                restoreJar(current, staged, target, backup);
                try {
                    loadAndEnable(pluginManager, current.toFile());
                } catch (Throwable rollbackError) {
                    Bukkit.getLogger().severe("[Sayaka AntiCheat] Failed to reload the previous "
                            + pluginName + " jar: " + describe(rollbackError));
                }
            }
            if (sender != null) sender.sendMessage(failedPrefix + message + failedSuffix);
            return message;
        }
    }

    /** 复刻 PlugManX 的卸载流程：禁用、注销监听器/命令、移出插件注册表、关闭类加载器。 */
    private static void unloadPlugin(PluginManager pluginManager, Plugin plugin, String pluginName) {
        ClassLoader classLoader = plugin.getClass().getClassLoader();

        pluginManager.disablePlugin(plugin);
        HandlerList.unregisterAll(plugin);
        unregisterCommands(pluginManager, plugin);
        removeFromRegistries(pluginManager, plugin, pluginName);
        closeClassLoader(classLoader);
        System.gc();
    }

    private static Plugin loadAndEnable(PluginManager pluginManager, java.io.File jar) throws Exception {
        Plugin loaded = pluginManager.loadPlugin(jar);
        if (loaded == null) return null;
        loaded.onLoad();
        pluginManager.enablePlugin(loaded);
        return loaded;
    }

    private static void unregisterCommands(PluginManager pluginManager, Plugin plugin) {
        CommandMap commandMap = readField(pluginManager, "commandMap", CommandMap.class);
        if (commandMap == null) return;
        @SuppressWarnings("unchecked")
        Map<String, Command> known = readField(commandMap, "knownCommands", Map.class);
        if (known == null) return;
        for (Iterator<Map.Entry<String, Command>> it = known.entrySet().iterator(); it.hasNext(); ) {
            Command command = it.next().getValue();
            if (command instanceof PluginCommand pluginCommand && pluginCommand.getPlugin() == plugin) {
                command.unregister(commandMap);
                it.remove();
            }
        }
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
        if (plugins != null) plugins.removeIf(entry -> entry == plugin);

        Map<?, ?> lookupNames = readField(holder, "lookupNames", Map.class);
        if (lookupNames != null) {
            lookupNames.values().removeIf(entry -> entry == plugin);
            lookupNames.remove(pluginName);
            lookupNames.remove(pluginName.toLowerCase(java.util.Locale.ROOT));
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
            Bukkit.getLogger().severe("[Sayaka AntiCheat] Failed to restore the previous plugin jar: "
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

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
