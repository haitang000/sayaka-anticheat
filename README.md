# 🛡️ Sayaka AntiCheat — 轻量级服务端反作弊插件

[![Release](https://img.shields.io/github/v/release/haitang000/sayaka-anticheat?include_prereleases&label=release)](https://github.com/haitang000/sayaka-anticheat/releases)
[![Java](https://img.shields.io/badge/Java-17%2B-orange)](https://adoptium.net/)
[![Paper API](https://img.shields.io/badge/Paper%2FPurpur-1.20.4%2B-blue)](https://papermc.io/)
[![PacketEvents](https://img.shields.io/badge/PacketEvents-2.13.0%2B-green)](https://github.com/retrooper/packetevents)
[![Issues](https://img.shields.io/github/issues/haitang000/sayaka-anticheat)](https://github.com/haitang000/sayaka-anticheat/issues)
[![Last commit](https://img.shields.io/github/last-commit/haitang000/sayaka-anticheat)](https://github.com/haitang000/sayaka-anticheat/commits/main)

⚔️ 适用于 **Paper / Purpur 1.20.4+** 的 PVP / 生存服反作弊插件。2.0 使用 PacketEvents 数据包时间线验证攻击、移动时钟、挥臂、击退和延迟回放，不再把 Bukkit 合成事件当作客户端证据。

## 💡 设计理念

1. **宁可漏判，不可误判** — 阈值预留物理余量 + 缓冲值，偶发抖动/击退/传送不触发违规，只有持续异常才累积。
2. **递进式处置** — 先警告再处罚，力度随违规次数逐级上升。
3. **一切可配置** — 阈值、文案、惩罚阶梯、封禁时长全部在 `config.yml`。

## ⛓️ 递进式惩罚链

每项检测独立累积 **VL（违规值）**，默认每秒衰减 0.2（可按检测项覆盖），触发后有短暂保护期不衰减。警告、拦截和处罚全部按单项 VL 判断，综合 VL 仅用于管理界面展示，互不相关的弱证据不会共同触发封禁。

```
单项 VL ≥ 5    ①  ⚠️ 警告（标题 + 聊天 + 音效）
单项 VL ≥ 8    ②  🛑 拦截（回弹传送 / 取消命中 / 恢复挖掘）
单项 VL ≥ 12   ③  📢 最后通牒
单项 VL ≥ 动态阈值  ④  👢 enforcement=punish 的检测踢出并记 strike
单项 VL ≥ 100       ⑤  👢 enforcement=mitigate 的检测触发兜底踢出（可配置）
24h 内 3 次 strike → ⑥  🔨 临时封禁，时长递增：1h → 6h → 24h → 72h
```

每项检测可配置 `enforcement: alert|mitigate|punish`。`mitigate` 在单项 VL 达到 `punishment.mitigate-kick-vl` 时会兜底踢出，避免持续作弊时 VL 无限累积；聊天、点击节奏、自动图腾等 `alert` 弱启发式证据仍不会产生 strike。基岩版默认使用 `settings.bedrock-profile: conservative`，只记录警报而不拦截或处罚。

管理员（`anticheat.alerts`）全程收到实时警报；踢出/封禁默认全服公告。

## 🔍 检测项

| 类别 | 检测项 |
|---|---|
| 🏃 移动 | Speed、Flight、GroundSpoof、Timer、FastLadder、Step、LiquidWalk、Rotation |
| ⚔️ 战斗 | Reach、KillAura、AutoClicker、NoSwing、Criticals、Velocity |
| 🎒 玩家行为 | AutoTotem、InventoryMove、NoSlow、FastUse、FastBow、ChestStealer |
| 🌍 世界交互 | FastBreak、Scaffold |
| 💬 聊天 | AntiSpam、AntiAds |

内置误判防护覆盖鞘翅/攀爬/液体/床弹跳/载具/击退/传送/进服宽限等常见合法场景，并兼容技能插件位移、MMO 移速属性、区域保护击退削弱、建筑法杖批量放置等第三方插件行为。

## 📦 安装与构建

先安装并启动 **PacketEvents 2.13.0 或更新的 2.x 版本**，再安装 Sayaka AntiCheat。缺少前置或版本过低时插件会拒绝启动。

```bash
./mvnw package   # 需要 JDK 17+；无需预装 Maven
```

产物在 `target/Sayaka-AntiCheat-2.1.0-beta.1.jar`。PacketEvents 使用 `provided` 依赖，不会被重复打包进产物。

### 跨服处罚同步

多子服部署时，在每台服的 `config.yml` 中启用 `cross-server.enabled`，并配置相同的 Redis 地址与 `namespace`。插件会同步 strike、封禁档位、处罚历史、完整处罚快照和当前有效封禁；封禁与解封实时广播，节点重启后也会从 Redis 补齐离线期间的数据。

```yaml
cross-server:
  enabled: true
  required: true
  namespace: "my-network"
  redis:
    host: "10.0.0.10"
    port: 6379
    password: "change-me"
```

Redis 只应监听内网或通过防火墙限制来源。`required: true` 会在 Redis 首次连接失败时停止插件加载，避免玩家利用同步故障切服绕过封禁；配置变更需要重启子服。

## 🎮 命令与权限

| 命令 | 说明 | 权限 |
|---|---|---|
| `/sac status <玩家>` | 实时 VL、strike、封禁史 | `anticheat.admin` |
| `/sac history <玩家>` | 违规明细 + 历史惩罚 | `anticheat.admin` |
| `/sac punishment <处罚ID>` | 封禁详情、封禁前警告与检测失败日志 | `anticheat.admin` |
| `/sac reset <玩家> [all]` | 清空 VL（`all` 连同 strike/封禁档案） | `anticheat.admin` |
| `/sac whitelist add/remove/list` | 反作弊白名单管理 | `anticheat.whitelist` |
| `/sac unban <玩家> [reset]` | 解封并清空 strike | `anticheat.unban` |
| `/sac alerts` | 开关个人实时警报 | `anticheat.alerts` |
| `/sac reload` | 重载配置 | `anticheat.admin` |
| `/sac update [check]` | 安装 GitHub 最新 Release 并热重载；`check` 仅检查 | `anticheat.admin` |

插件默认每 30 分钟检查一次 GitHub 的最新 Release（包括预览版）。发现更高版本后，控制台与在线管理员会收到提示；执行 `/sac update` 后会下载并校验 JAR，再通过 Bukkit 更新目录进行一次服务器级热重载，整个过程无需重启 JVM。可在 `updates` 配置段关闭或调整后台检查；关闭后台检查不影响手动使用 `/sac update` 或 `/sac update check`。

`anticheat.bypass` 完全绕过检测（默认无人持有）；`anticheat.antispam.bypass` / `anticheat.antiads.bypass` 仅绕过对应聊天检测。以上权限默认均不给 OP 之外的人。

## 🎛️ 调参建议

- 上线初期开 `settings.debug: true` 观察警报 `detail`，再收紧阈值；误判多优先调大 `buffer-to-flag`。
- 新版本建议先把新增检测设为 `enforcement: alert` 观察 7 天，核对警报后再切换为 `mitigate` 或 `punish`。
- 纯计算检测使用独立线程池（`settings.parallel-analysis` 可调），队列满时丢弃分析以保 TPS，不阻塞主线程。
- `settings.packet-analysis` 控制有界数据包历史与主线程完成队列；修改这些容量需要重启。
- TPS 低于 18 时 Timer 检测自动暂停；`fast-break.nuker-detect` 与连锁挖掘类插件冲突需关闭。
- 配置采用 `config-version: 2`。无效阈值、负封禁时间或未知执行模式会在启动时阻止加载；`/sac reload` 校验失败时继续使用上一份有效快照。

## 🧭 能力边界

2.0 已用数据包时序加强 Timer、Reach、NoSwing、Velocity 和攻击来源，但仍未从零实现跨版本完整物理模拟。高对抗移动场景建议由 GrimAC 负责精确物理，Sayaka 负责行为、聊天、处罚审计与互补检测；部署时关闭双方重叠的回弹项。

## 🧩 扩展新检测

继承 `cn.haitang.anticheat.check.Check`，调用 `flag(player, 权重, "证据")`，在 `AntiCheatPlugin#onEnable` 注册并在 `config.yml` 的 `checks` 段加配置即可。VL 累积、警告、拦截、踢出、封禁均由框架自动接管。
