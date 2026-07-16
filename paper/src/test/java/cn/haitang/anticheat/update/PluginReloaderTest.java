package cn.haitang.anticheat.update;

import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 守护 {@link PluginReloader} 依赖的反射契约：隔离工人字节码必须随 jar 一起发布，
 * 且其 {@code run} 方法签名要与编排类反射调用的参数完全一致——这两者一旦漂移，
 * 编译期不会报错，只会在运行时把热重载降级为「不支持」。
 */
class PluginReloaderTest {

    private static final String WORKER_RESOURCE = "/cn/haitang/anticheat/update/IsolatedReload.class";

    @Test
    void shipsIsolatedWorkerBytecodeAsResource() throws Exception {
        assertNotNull(readWorkerBytes(), "IsolatedReload.class 必须能作为资源从 jar 内读取");
    }

    @Test
    void workerRunSignatureMatchesReflectiveInvocation() {
        // 与 PluginReloader.reload 中反射查找的 10 个参数逐一对应；签名一旦漂移这里立即失败。
        // （隔离加载本身依赖运行期父加载器缺失本插件类，无法在扁平的测试类路径中复现，故只校验契约。）
        Method run = assertDoesNotThrow(() -> IsolatedReload.class.getMethod("run",
                String.class, String.class, String.class, String.class, String.class,
                String.class, CommandSender.class, String.class, String.class, String.class));
        assertEquals(String.class, run.getReturnType(), "run 应返回错误信息字符串或 null");
    }

    private static byte[] readWorkerBytes() throws Exception {
        try (InputStream stream = PluginReloaderTest.class.getResourceAsStream(WORKER_RESOURCE)) {
            if (stream == null) return null;
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) != -1) buffer.write(chunk, 0, read);
            return buffer.toByteArray();
        }
    }
}
