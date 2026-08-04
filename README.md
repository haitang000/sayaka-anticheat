# Sayaka AntiCheat — 轻量级服务端反作弊插件

[![Release](https://img.shields.io/github/v/release/haitang000/sayaka-anticheat?include_prereleases&label=release)](https://github.com/haitang000/sayaka-anticheat/releases)
[![Java](https://img.shields.io/badge/Java-17%2B-orange)](https://adoptium.net/)
[![Paper API](https://img.shields.io/badge/Paper%2FPurpur-1.20.4%2B-blue)](https://papermc.io/)
[![PacketEvents](https://img.shields.io/badge/PacketEvents-2.13.0%2B-green)](https://github.com/retrooper/packetevents)
[![Issues](https://img.shields.io/github/issues/haitang000/sayaka-anticheat)](https://github.com/haitang000/sayaka-anticheat/issues)
[![Last commit](https://img.shields.io/github/last-commit/haitang000/sayaka-anticheat)](https://github.com/haitang000/sayaka-anticheat/commits/main)

适用于 **Paper / Purpur 1.20.4+** 的 PVP / 生存服反作弊插件，附带可选 **Velocity 3.4+** 群组服组件。2.0 基于 PacketEvents 数据包时间线验证攻击、移动、击退等行为，不把 Bukkit 合成事件当作客户端证据。

## 检测项

| 类别 | 检测项 |
|---|---|
| 移动 | Speed、Sprint、Flight、Glide、Elytra、GroundSpoof、Timer、FastLadder、Step、Phase、LiquidWalk、Rotation |
| 战斗 | Reach、KillAura、AutoClicker、NoSwing、Criticals、Velocity、AutoBlock |
| 玩家行为 | AutoTotem、InventoryMove、NoSlow、FastUse、FastBow、ChestStealer |
| 世界交互 | FastBreak、Scaffold |
| 聊天 | AntiSpam、AntiAds、CommandSpam |
| 数据包 | BadPackets（非法坐标 / 自击包 / 物品 NBT 与数量校验） |

内置误判防护覆盖鞘翅/攀爬/液体/击退/传送/进服宽限等常见场景，兼容技能插件位移、区域保护击退削弱等第三方行为。

## 安装与构建

需要先安装 **PacketEvents 2.13.0+**（前置缺失时插件拒绝启动）。

```bash
./mvnw package   # 需要 JDK 17+；无需预装 Maven
```

产物：

- `paper/target/Sayaka-AntiCheat-Paper-2.1.0.3.jar` → 放入每个 Paper/Purpur 的 `plugins/`
- `velocity/target/Sayaka-AntiCheat-Velocity-2.1.0.3.jar` → 仅放入 Velocity 的 `plugins/`

## Velocity 群组服

群组模式用 MariaDB/MySQL 共享 strike、处罚与封禁，Velocity 组件提供带申诉入口的 HTTP 管理面板（默认仅监听 `127.0.0.1:8080`，建议 Nginx/Caddy 反代 HTTPS）。

1. 创建数据库，所有 Paper 后端与 Velocity 使用同一连接。
2. 每个 Paper 设置 `network.enabled: true` + 唯一 `network.server-id`，保持 `web.enabled: false`，完整重启。
3. Velocity 启动一次生成 `config.toml`，用环境变量 `SAYAKA_DATABASE_PASSWORD` / `SAYAKA_ADMIN_TOKEN` 提供凭据。
4. 用防火墙限制后端端口仅 Velocity 可访问，启用 modern forwarding 防绕过。

`[protection]` 段可按后端决定是否拦截共享封禁（如 `lobby = false`）。数据库不可用时 Paper 继续检测，临时封禁降级为踢出；恢复后无需重启。

## 命令与权限

| 命令 | 说明 | 权限 |
|---|---|---|
| `/sac status <玩家>` | 实时 VL、strike、封禁史 | `anticheat.admin` |
| `/sac history <玩家>` | 违规明细 + 历史惩罚 | `anticheat.admin` |
| `/sac punishment <ID>` | 封禁详情与证据日志 | `anticheat.admin` |
| `/sac reset <玩家> [all]` | 清空 VL（`all` 连同档案） | `anticheat.admin` |
| `/sac whitelist add/remove/list` | 白名单管理 | `anticheat.whitelist` |
| `/sac unban <玩家> [reset]` | 解封并清空 strike | `anticheat.unban` |
| `/sac web` | 生成一次性管理后台登录链接 | `anticheat.admin` |
| `/sac alerts` | 开关个人实时警报 | `anticheat.alerts` |
| `/sac reload` | 重载配置 | `anticheat.admin` |
| `/sac update [check]` | 热更新插件（`check` 仅检查） | `anticheat.admin` |

插件每 30 分钟检查一次 GitHub Release，支持免 PlugManX 热更新（失败自动回滚旧 JAR）。Velocity 端采用"宿主 + 可热替换内核"架构，同样支持免重启换载。`anticheat.bypass` 完全绕过检测（默认无人持有）。

## 第三方插件 API

`sayaka-anticheat-api` 模块只依赖 Paper API，供其他插件编译与运行时集成：

```java
// 1. 通过 ServicesManager 获取服务
SayakaApi api = Bukkit.getServicesManager().load(SayakaApi.class);
double vl = api.getVl(player, "KillAura");       // 查询单项 VL
api.registerExemptionChecker(p -> p.hasPermission("vip.fly")); // 自定义豁免

// 2. 监听违规与处罚事件（可取消）
@EventHandler
public void onFlag(PlayerFlagEvent event) {      // 取消 = 否决本次记录/警报/处罚
    if (event.getCheckId().equals("KillAura")) event.setCancelled(true);
}
@EventHandler
public void onPunish(PlayerPunishEvent event) {  // 取消 = 免除本次踢出/封禁
    if (event.getPlayer().hasPermission("staff.immune")) event.setCancelled(true);
}
```

接口能力：单项/综合 VL 查询、违规明细、strike 计数、白名单与豁免判定、`/sac reset` 等价的重置方法、已启用检测项列表。

## 扩展新检测

继承 `cn.haitang.anticheat.check.Check`，调用 `flag(player, 权重, "证据")` 并在 `onEnable` 注册、`config.yml` 的 `checks` 段加配置即可。VL 累积与处罚由框架自动接管。
