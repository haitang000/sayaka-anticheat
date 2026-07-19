package cn.haitang.anticheat.velocity.boot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreClassLoaderTest {

    /** boot 桥接包必须走宿主加载器：新旧内核才能实现同一份接口 Class */
    @Test
    void bootPackageDelegatesToHost() {
        assertFalse(CoreClassLoader.childFirst("cn.haitang.anticheat.velocity.boot.CoreBridge"));
        assertFalse(CoreClassLoader.childFirst("cn.haitang.anticheat.velocity.boot.CoreContext"));
        assertFalse(CoreClassLoader.childFirst("cn.haitang.anticheat.velocity.boot.HotReloader"));
    }

    /** 业务与 shared 类子优先，才能真正换到新 jar 的代码 */
    @Test
    void coreAndSharedClassesAreChildFirst() {
        assertTrue(CoreClassLoader.childFirst("cn.haitang.anticheat.velocity.VelocityCore"));
        assertTrue(CoreClassLoader.childFirst("cn.haitang.anticheat.velocity.DashboardServer"));
        assertTrue(CoreClassLoader.childFirst("cn.haitang.anticheat.shared.JdbcNetworkStore"));
        assertTrue(CoreClassLoader.childFirst("org.mariadb.jdbc.Driver"));
    }

    /** 前缀匹配不能把 boot 的"兄弟包"误委派宿主 */
    @Test
    void bootPrefixMatchesExactPackageOnly() {
        assertTrue(CoreClassLoader.childFirst("cn.haitang.anticheat.velocity.bootstrap.Fake"));
    }
}
