package cn.haitang.anticheat.velocity.boot;

import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * 宿主传给内核的运行环境。字段类型只允许 java.* / Velocity API / slf4j /
 * 本包类型——它们都由宿主侧加载器提供，新旧内核看到同一份 Class。
 *
 * @param pluginInstance 宿主 @Plugin 实例；Velocity 调度器等 API 需要以它为归属
 */
public record CoreContext(Object pluginInstance, ProxyServer proxy, Logger logger,
                          Path dataDirectory, HotReloader reloader) {
}
