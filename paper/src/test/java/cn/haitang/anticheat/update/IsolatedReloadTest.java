package cn.haitang.anticheat.update;

import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守护热重载摘除旧命令的方式：现代 Paper 的 {@code SimpleCommandMap.knownCommands} 是由
 * Brigadier 调度器支撑的视图 Map——{@code get/put/remove/clear} 可用，但
 * {@code entrySet().iterator().remove()} 会命中 {@code Iterator} 默认方法并抛
 * {@code UnsupportedOperationException("remove")}，把整次热重载打断在「旧插件已禁用」的状态。
 */
class IsolatedReloadTest {

    @Test
    void dropsOwnedLabelsWithoutTouchingTheEntrySetIterator() throws Exception {
        Plugin mine = fakePlugin();
        Plugin other = fakePlugin();
        PluginCommand sac = pluginCommand("sac", mine);
        PluginCommand alias = pluginCommand("anticheat", mine);
        PluginCommand foreign = pluginCommand("elsewhere", other);

        BrigadierStyleMap known = new BrigadierStyleMap();
        known.put("sac", sac);
        known.put("sayakaanticheat:sac", sac);
        known.put("anticheat", alias);
        known.put("elsewhere", foreign);

        CommandMap commandMap = fakeCommandMap();
        List<String> stale = IsolatedReload.removeOwnedCommands(known, commandMap, mine);

        assertEquals(List.of(), stale, "视图 Map 支持 remove(key)，不应有残留标签");
        assertNull(known.get("sac"), "本插件的主标签应被摘除");
        assertNull(known.get("sayakaanticheat:sac"), "回退前缀标签同样要摘除");
        assertNull(known.get("anticheat"));
        assertSame(foreign, known.get("elsewhere"), "其他插件的命令不能被牵连");
        assertFalse(sac.isRegistered(), "摘除后命令应处于未注册状态，以便新版本重新注册");
        assertFalse(alias.isRegistered());
    }

    @Test
    void reportsStaleLabelsWhenTheMapRefusesRemoval() throws Exception {
        Plugin mine = fakePlugin();
        PluginCommand sac = pluginCommand("sac", mine);

        BrigadierStyleMap known = new BrigadierStyleMap();
        known.put("sac", sac);
        known.rejectRemoval = true;

        List<String> stale = IsolatedReload.removeOwnedCommands(known, fakeCommandMap(), mine);

        assertEquals(List.of("sac"), stale, "无法摘除的标签要被报告出来，而不是抛出异常打断重载");
        assertSame(sac, known.get("sac"));
    }

    /**
     * 模拟 Paper 的命令表视图：实现 {@code get/put/remove}，但 {@code entrySet()} 只可读——
     * 其迭代器不支持 {@code remove()}，与真实服务端上观测到的行为一致。
     */
    private static final class BrigadierStyleMap extends AbstractMap<String, Command> {
        private final Map<String, Command> backing = new LinkedHashMap<>();
        boolean rejectRemoval;
        boolean rejectIteration;

        @Override
        public Command put(String key, Command value) {
            return backing.put(key, value);
        }

        @Override
        public Command get(Object key) {
            return backing.get(key);
        }

        @Override
        public Command remove(Object key) {
            if (rejectRemoval) throw new UnsupportedOperationException("remove");
            return backing.remove(key);
        }

        @Override
        public Set<Entry<String, Command>> entrySet() {
            if (rejectIteration) throw new UnsupportedOperationException("entrySet");
            // 只读快照 + 默认 Iterator.remove()：在其上删除会抛 UnsupportedOperationException("remove")
            Set<Entry<String, Command>> snapshot = new LinkedHashMap<>(backing).entrySet();
            return new java.util.AbstractSet<>() {
                @Override
                public Iterator<Entry<String, Command>> iterator() {
                    Iterator<Entry<String, Command>> delegate = snapshot.iterator();
                    return new Iterator<>() {
                        @Override
                        public boolean hasNext() {
                            return delegate.hasNext();
                        }

                        @Override
                        public Entry<String, Command> next() {
                            return delegate.next();
                        }
                        // 故意不实现 remove()，沿用 Iterator 的默认抛出实现
                    };
                }

                @Override
                public int size() {
                    return snapshot.size();
                }
            };
        }
    }

    @Test
    void entrySetIteratorRemovalIsUnsupportedOnTheViewMap() throws Exception {
        BrigadierStyleMap known = new BrigadierStyleMap();
        known.put("sac", pluginCommand("sac", fakePlugin()));
        Iterator<Map.Entry<String, Command>> iterator = known.entrySet().iterator();
        iterator.next();
        UnsupportedOperationException error =
                org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, iterator::remove);
        assertEquals("remove", error.getMessage(), "这正是线上热重载失败时看到的错误信息");
    }

    private static PluginCommand pluginCommand(String name, Plugin owner) throws Exception {
        Constructor<PluginCommand> constructor = PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
        constructor.setAccessible(true);
        return constructor.newInstance(name, owner);
    }

    private static Plugin fakePlugin() {
        return fakePlugin("name: SayakaAntiCheat\nversion: 1.0\nmain: cn.haitang.Fake\n");
    }

    private static Plugin fakePlugin(String pluginYml) {
        PluginDescriptionFile description;
        try {
            description = new PluginDescriptionFile(new StringReader(pluginYml));
        } catch (InvalidDescriptionException error) {
            throw new IllegalStateException(error);
        }
        return (Plugin) Proxy.newProxyInstance(IsolatedReloadTest.class.getClassLoader(),
                new Class<?>[]{Plugin.class}, (proxy, method, args) ->
                        "getDescription".equals(method.getName())
                                ? description
                                : defaultValue(method.getReturnType()));
    }

    private static CommandMap fakeCommandMap() {
        return (CommandMap) Proxy.newProxyInstance(IsolatedReloadTest.class.getClassLoader(),
                new Class<?>[]{CommandMap.class}, (proxy, method, args) -> defaultValue(method.getReturnType()));
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == String.class) return "";
        if (type == List.class) return new ArrayList<>();
        if (type == Map.class) return new HashMap<>();
        if (type.isPrimitive()) return 0;
        return null;
    }

    @Test
    void fallsBackToDeclaredLabelsWhenTheMapCannotBeIterated() throws Exception {
        Plugin mine = fakePlugin("name: SayakaAntiCheat\nversion: 1.0\nmain: cn.haitang.Fake\n"
                + "commands:\n  sac:\n    aliases: [anticheat]\n");
        PluginCommand sac = pluginCommand("sac", mine);

        BrigadierStyleMap known = new BrigadierStyleMap();
        known.put("sac", sac);
        known.put("sayakaanticheat:sac", sac);
        known.put("anticheat", sac);
        known.rejectIteration = true;

        assertEquals(List.of(), IsolatedReload.removeOwnedCommands(known, fakeCommandMap(), mine));
        assertNull(known.get("sac"));
        assertNull(known.get("sayakaanticheat:sac"));
        assertNull(known.get("anticheat"), "别名与回退前缀形式都应按 plugin.yml 声明被摘除");
    }

    @Test
    void keepsCommandsOfOtherPluginsRegistered() throws Exception {
        Plugin mine = fakePlugin();
        Plugin other = fakePlugin();
        PluginCommand foreign = pluginCommand("elsewhere", other);
        CommandMap commandMap = fakeCommandMap();
        assertTrue(foreign.register(commandMap));

        BrigadierStyleMap known = new BrigadierStyleMap();
        known.put("elsewhere", foreign);

        assertEquals(List.of(), IsolatedReload.removeOwnedCommands(known, commandMap, mine));
        assertTrue(foreign.isRegistered(), "别的插件的命令不应被注销");
    }
}
