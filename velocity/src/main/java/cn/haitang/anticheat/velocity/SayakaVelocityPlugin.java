package cn.haitang.anticheat.velocity;

import cn.haitang.anticheat.velocity.boot.CoreBridge;
import cn.haitang.anticheat.velocity.boot.CoreClassLoader;
import cn.haitang.anticheat.velocity.boot.CoreContext;
import cn.haitang.anticheat.velocity.boot.HotReloader;
import com.google.inject.Inject;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Driver;
import java.sql.DriverManager;
import java.util.Enumeration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 宿主壳：Velocity 不支持运行时替换插件，本类与 boot 桥接包在代理重启前
 * 永驻，因此只做两件事——把事件转发给当前内核、以及在热重载时换载内核
 * （{@link VelocityCore} 承载全部业务）。
 *
 * 热重载流程：面板"下载并暂存"完成后点"热重载应用"，内核经
 * {@link HotReloader} 请求宿主调度换载；宿主先从暂存 jar 的子优先
 * 加载器构造新内核（未启动，端口未占用），再停旧内核、关闭旧加载器、
 * 启动新内核。新内核启动失败时回退到宿主 jar 内嵌的内核，服务不失联。
 */
@Plugin(
        id = "sayaka-anticheat",
        name = "Sayaka AntiCheat Velocity",
        version = "2.1.0.7",
        authors = {"haitang"}
)
public final class SayakaVelocityPlugin {

    /** 热重载跨版本查找的内核入口：类名与 create(CoreContext) 签名不可漂移 */
    private static final String CORE_CLASS = "cn.haitang.anticheat.velocity.VelocityCore";

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final AtomicBoolean reloading = new AtomicBoolean();

    private volatile CoreBridge core;
    /** 当前内核的加载器；内嵌内核（宿主自带类）时为 null */
    private volatile CoreClassLoader coreLoader;

    @Inject
    public SayakaVelocityPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        startCore(VelocityCore.create(context()), null, "内嵌");
    }

    @Subscribe
    public EventTask onServerPreConnect(ServerPreConnectEvent event) {
        CoreBridge current = core;
        return current == null ? null : current.onServerPreConnect(event);
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        CoreBridge current = core;
        if (current != null) current.onPluginMessage(event);
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        CoreBridge current = core;
        core = null;
        if (current != null) current.stop();
        closeLoader(coreLoader);
        coreLoader = null;
    }

    private CoreContext context() {
        return new CoreContext(this, proxy, logger, dataDirectory, this::scheduleApply);
    }

    /** {@link HotReloader} 实现：受理后延迟数秒执行，让面板线程先把响应发回浏览器 */
    private void scheduleApply(Path stagedJar, String stagedVersion) throws IOException {
        if (!Files.isRegularFile(stagedJar)) {
            throw new IOException("暂存文件不存在: " + stagedJar);
        }
        if (!reloading.compareAndSet(false, true)) {
            throw new IOException("已有一次热重载正在进行");
        }
        logger.info("Sayaka 热重载已调度：{} → {}", currentVersion(), stagedVersion);
        proxy.getScheduler().buildTask(this, () -> swapCore(stagedJar, stagedVersion))
                .delay(2, TimeUnit.SECONDS).schedule();
    }

    private void swapCore(Path stagedJar, String stagedVersion) {
        try {
            CoreClassLoader nextLoader;
            CoreBridge next;
            try {
                nextLoader = new CoreClassLoader(stagedJar, getClass().getClassLoader());
            } catch (IOException error) {
                logger.error("Sayaka 热重载失败：无法打开暂存 jar，当前内核继续运行", error);
                return;
            }
            try {
                next = createFromLoader(nextLoader);
            } catch (ReflectiveOperationException | RuntimeException error) {
                closeLoader(nextLoader);
                logger.error("Sayaka 热重载失败：新内核不兼容当前宿主，当前内核继续运行。"
                        + "如需升级请手动替换 jar 并重启代理", error);
                return;
            }

            // 新内核已可构造，才停掉旧内核（面板端口此刻才释放）
            CoreBridge previous = core;
            CoreClassLoader previousLoader = coreLoader;
            core = null;
            if (previous != null) previous.stop();
            closeLoader(previousLoader);
            coreLoader = null;

            if (startCore(next, nextLoader, stagedVersion)) {
                persistStagedJar(stagedJar);
            } else {
                closeLoader(nextLoader);
                logger.warn("Sayaka 正在回退到宿主内嵌内核");
                startCore(VelocityCore.create(context()), null, "内嵌回退");
            }
        } finally {
            reloading.set(false);
        }
    }

    /**
     * 把暂存 jar 覆盖到 plugins 中正在运行的 jar，让下次代理重启直接落在新版本。
     * Linux 上打开的类加载器按 inode 持有旧文件，覆盖安全；Windows 文件被锁定时
     * 覆盖失败，热重载仍已生效，仅提示手动替换。
     */
    private void persistStagedJar(Path stagedJar) {
        try {
            Path running = Path.of(getClass().getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (!Files.isRegularFile(running)) {
                throw new IOException("未定位到宿主 jar: " + running);
            }
            Files.copy(stagedJar, running, StandardCopyOption.REPLACE_EXISTING);
            logger.info("Sayaka 已把新版本同步到 {}，下次代理重启直接生效", running.getFileName());
        } catch (Exception error) {
            logger.warn("Sayaka 无法自动覆盖 plugins 中的旧 jar：{}。热重载已生效，"
                    + "但代理重启会回到旧版本，请用暂存文件手动替换", error.getMessage());
        }
    }

    private boolean startCore(CoreBridge next, CoreClassLoader loader, String label) {
        try {
            next.start();
            core = next;
            coreLoader = loader;
            logger.info("Sayaka Velocity 内核已上线（{}，版本 {}）", label, next.coreVersion());
            return true;
        } catch (Exception error) {
            logger.error("Sayaka Velocity 内核启动失败（{}）", label, error);
            return false;
        }
    }

    private CoreBridge createFromLoader(CoreClassLoader loader) throws ReflectiveOperationException {
        Class<?> coreClass = Class.forName(CORE_CLASS, true, loader);
        Method factory = coreClass.getMethod("create", CoreContext.class);
        return (CoreBridge) factory.invoke(null, context());
    }

    private String currentVersion() {
        CoreBridge current = core;
        return current != null ? current.coreVersion() : "unknown";
    }

    private void closeLoader(CoreClassLoader loader) {
        if (loader == null) return;
        deregisterDrivers(loader);
        try {
            loader.close();
        } catch (IOException error) {
            logger.warn("旧内核类加载器关闭失败: {}", error.getMessage());
        }
    }

    /** 注销旧内核 jar 里自动注册的 JDBC 驱动，避免旧加载器被 DriverManager 长期持有 */
    private void deregisterDrivers(ClassLoader loader) {
        Enumeration<Driver> drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            Driver driver = drivers.nextElement();
            if (driver.getClass().getClassLoader() == loader) {
                try {
                    DriverManager.deregisterDriver(driver);
                } catch (Exception ignored) {
                    // 注销失败只影响加载器回收，不影响功能
                }
            }
        }
    }
}
