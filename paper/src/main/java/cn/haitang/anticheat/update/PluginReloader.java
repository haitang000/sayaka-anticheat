package cn.haitang.anticheat.update;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.PluginManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 不依赖 PlugManX 的插件自我热重载引擎。
 *
 * <p>真正的卸载/加载逻辑位于 {@link IsolatedReload}。为了让「插件卸载自己」时不受旧插件
 * {@code PluginClassLoader} 关闭的影响，本类在重载时把 {@code IsolatedReload} 的字节码从
 * 插件 jar 中读出，用一个父加载器为 Bukkit 加载器的一次性 {@link ClassLoader} 重新定义一份
 * 全新副本，再反射调用其 {@code run}。这个一次性加载器不打开任何 jar 文件（字节码来自内存），
 * 因此不会在 Windows 上占用 jar 句柄，也不会随旧插件加载器一起失效。
 */
public final class PluginReloader {

    private static final String WORKER_CLASS = "cn.haitang.anticheat.update.IsolatedReload";
    private static final String WORKER_RESOURCE = "/cn/haitang/anticheat/update/IsolatedReload.class";

    private PluginReloader() {
    }

    /**
     * 结果只区分两种情况：{@code UNSUPPORTED}——工人从未运行，本插件仍完好，调用方应提示手动
     * 替换 jar 并重启；{@code EXECUTED}——工人已运行（无论新版本启用成功还是已回滚），面向用户的
     * 成功/失败提示都已由工人自行发送，调用方不应再做任何可能触发新类加载的工作。
     */
    public enum Status { EXECUTED, UNSUPPORTED }

    public record Result(Status status) {
        static Result executed() {
            return new Result(Status.EXECUTED);
        }

        static Result unsupported() {
            return new Result(Status.UNSUPPORTED);
        }
    }

    /**
     * 只读探测：确认本服务端构建暴露了自我热重载所需的内部字段，且工人字节码可读。
     * 不修改任何状态，可在下载前用于提前拒绝并给出清晰提示。
     */
    public static boolean isSupported() {
        try {
            PluginManager pluginManager = Bukkit.getPluginManager();
            if (!hasField(pluginManager, "commandMap") || workerBytes() == null) return false;
            // 现代 Paper 的 SimplePluginManager 只是门面，真正的注册表在 PaperPluginInstanceManager；
            // 此时必须能够到达该实例管理器，否则卸载会静默失败。纯 Spigot 无此门面，回退校验自身的
            // plugins 字段即可。
            Object paperManager = readField(pluginManager, "paperPluginManager");
            if (paperManager != null) return reachablePaperInstanceManager(pluginManager);
            return hasField(pluginManager, "plugins");
        } catch (Throwable error) {
            return false;
        }
    }

    /**
     * 卸载当前插件、替换为已暂存的 jar，并加载启用新版本。必须在主线程调用。
     *
     * <p><strong>调用方约定：</strong>本方法返回后旧插件的类加载器已被关闭，调用方
     * （运行在旧加载器中的代码）不得再执行任何可能触发新类加载的操作——应立即返回。
     * 所有面向用户的成功/失败消息都已在隔离工人内部发送。
     */
    public static Result reload(String pluginName,
                                String currentJar, String stagedJar, String targetJar, String backupJar,
                                String expectedVersion,
                                CommandSender sender,
                                String successMessage, String failedPrefix, String failedSuffix) {
        byte[] bytes = workerBytes();
        if (bytes == null) return Result.unsupported();

        Method run;
        try {
            ClassLoader isolated = new IsolatedWorkerClassLoader(Bukkit.class.getClassLoader(), bytes);
            Class<?> worker = Class.forName(WORKER_CLASS, true, isolated);
            run = worker.getMethod("run",
                    String.class, String.class, String.class, String.class, String.class,
                    String.class, CommandSender.class, String.class, String.class, String.class);
        } catch (Throwable error) {
            return Result.unsupported();
        }

        // 在关闭本插件类加载器之前，预先构造好返回值并完成 Result/Status 类的初始化：工人一旦
        // 运行完，旧类加载器已关闭，此后再触发任何类加载/初始化都可能失败。调用方只区分
        // 「工人是否运行过」——UNSUPPORTED（上面提前返回，仍存活）与「已运行」——故此处统一返回
        // 同一个已构造实例，成功/失败的用户提示已由工人在其隔离加载器内自行发送。
        Result ranResult = Result.executed();
        try {
            run.invoke(null, pluginName,
                    currentJar, stagedJar, targetJar, backupJar,
                    expectedVersion, sender, successMessage, failedPrefix, failedSuffix);
        } catch (Throwable ignored) {
            // 反射调用本身的异常（工人内部已捕获并回滚），此处无法再安全地做本插件侧处理。
        }
        return ranResult;
    }

    private static byte[] workerBytes() {
        try (InputStream stream = PluginReloader.class.getResourceAsStream(WORKER_RESOURCE)) {
            if (stream == null) return null;
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } catch (Exception error) {
            return null;
        }
    }

    private static boolean reachablePaperInstanceManager(PluginManager pluginManager) {
        Object paperManager = readField(pluginManager, "paperPluginManager");
        if (paperManager == null) return false;
        Object instanceManager = readField(paperManager, "instanceManager");
        return instanceManager != null && hasField(instanceManager, "plugins");
    }

    private static boolean hasField(Object target, String name) {
        return target != null && findField(target.getClass(), name) != null;
    }

    private static Object readField(Object target, String name) {
        if (target == null) return null;
        Field field = findField(target.getClass(), name);
        if (field == null) return null;
        try {
            return field.get(target);
        } catch (ReflectiveOperationException error) {
            return null;
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

    /**
     * 从内存字节码定义 {@link IsolatedReload} 的一次性加载器；其父加载器为 Bukkit 加载器，
     * 因此 {@code java.*} 与 {@code org.bukkit.*} 都能正常解析，而工人本身与旧插件加载器无关。
     */
    private static final class IsolatedWorkerClassLoader extends ClassLoader {
        private final byte[] workerBytes;

        IsolatedWorkerClassLoader(ClassLoader parent, byte[] workerBytes) {
            super(parent);
            this.workerBytes = workerBytes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (WORKER_CLASS.equals(name)) {
                return defineClass(name, workerBytes, 0, workerBytes.length);
            }
            throw new ClassNotFoundException(name);
        }
    }
}
