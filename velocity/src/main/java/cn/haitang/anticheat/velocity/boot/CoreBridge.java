package cn.haitang.anticheat.velocity.boot;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;

/**
 * 宿主与可热替换内核之间的桥接契约。
 *
 * Velocity 没有运行时卸载/加载插件的 API，@Plugin 宿主类与其类加载器
 * 在代理重启前不可替换。热重载因此采用"宿主 + 内核"架构：宿主只保留
 * 事件转发与换载编排，全部业务在内核中，由 {@link CoreClassLoader}
 * 从新 jar 装载新内核实例完成升级。
 *
 * 本包（boot）由宿主加载器**独占**加载——新 jar 里的同名类会被子优先
 * 加载器排除，保证新旧内核实现的是同一份接口 Class。因此本包必须保持
 * 向后兼容：只能新增默认方法，不得改动既有签名。
 */
public interface CoreBridge {

    /** 启动内核（配置/数据库/面板/定时任务/通道注册）。失败时宿主保持无内核状态。 */
    void start() throws Exception;

    /** 停止内核并释放全部资源；必须幂等，且不得依赖后续再次被调用。 */
    void stop();

    /** 内核自身的版本号（读取所属 jar 的 velocity-plugin.json，而非宿主容器版本）。 */
    String coreVersion();

    /** 玩家切换后端前的封禁拦截；返回 null 表示放行。 */
    EventTask onServerPreConnect(ServerPreConnectEvent event);

    /** 后端转发的面板登录请求。 */
    void onPluginMessage(PluginMessageEvent event);
}
