package cn.haitang.anticheat.velocity.boot;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/**
 * 内核类加载器：除 boot 桥接包外一律子优先。
 *
 * - boot 包必须委派宿主，保证桥接接口在新旧内核间是同一份 Class；
 * - 插件自身与 shared 模块的类都在新 jar 里，子优先才能真正换到新代码
 *   （含被打进 jar 的 MariaDB 驱动）；
 * - Velocity API / adventure / slf4j / java.* 不在 jar 内，findClass
 *   失败后自然回落宿主链路。
 */
public final class CoreClassLoader extends URLClassLoader {

    private static final String BOOT_PACKAGE = "cn.haitang.anticheat.velocity.boot.";

    static {
        ClassLoader.registerAsParallelCapable();
    }

    public CoreClassLoader(Path jar, ClassLoader host) throws IOException {
        super(new URL[] {jar.toUri().toURL()}, host);
    }

    /** boot 桥接包永远走宿主；其余类先在内核 jar 里找 */
    static boolean childFirst(String name) {
        return !name.startsWith(BOOT_PACKAGE);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null && childFirst(name)) {
                try {
                    loaded = findClass(name);
                } catch (ClassNotFoundException notInJar) {
                    // 回落宿主链路（Velocity API、java.* 等）
                }
            }
            if (loaded == null) {
                return super.loadClass(name, resolve);
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    @Override
    public URL getResource(String name) {
        URL url = findResource(name);
        return url != null ? url : super.getResource(name);
    }
}
