# Sayaka AntiCheat — 轻量级服务端反作弊插件

[![Release](https://img.shields.io/github/v/release/haitang000/sayaka-anticheat?include_prereleases&label=release)](https://github.com/haitang000/sayaka-anticheat/releases)
[![Java](https://img.shields.io/badge/Java-17%2B-orange)](https://adoptium.net/)
[![Paper API](https://img.shields.io/badge/Paper%2FPurpur-1.20%2B-blue)](https://papermc.io/)
[![Issues](https://img.shields.io/github/issues/haitang000/sayaka-anticheat)](https://github.com/haitang000/sayaka-anticheat/issues)
[![Last commit](https://img.shields.io/github/last-commit/haitang000/sayaka-anticheat)](https://github.com/haitang000/sayaka-anticheat/commits/main)

适用于 **Paper / Purpur 1.20+** 的 PVP / 生存服反作弊插件。服务端事件 + 数据包双层判定，不信任客户端数据；数据包引擎（PacketEvents）已内置进插件，无需安装任何前置。

## 设计理念

1. **宁可漏判，不可误判** — 阈值预留物理余量 + 缓冲值，偶发抖动/击退/传送不触发违规，只有持续异常才累积。
2. **递进式处置** — 先警告再处罚，力度随违规次数逐级上升。
3. **一切可配置** — 阈值、文案、惩罚阶梯、封禁时长全部在 `config.yml`。

## 递进式惩罚链

每项检测独立累积 **VL（违规值）**，默认每秒衰减 0.2（可按检测项覆盖），触发后有短暂保护期不衰减。警告/踢出按综合 VL 判断，拦截按单项 VL 判断：

```
综合 VL ≥ 5    ① 警告（标题 + 聊天 + 音效）
单项 VL ≥ 8    ② 拦截（回弹传送 / 取消命中 / 恢复挖掘）
综合 VL ≥ 12   ③ 最后通牒
综合 VL ≥ 动态阈值  ④ 踢出（20 → 首次警告后 18 → 最后警告后 15），记一次 strike
24h 内 3 次 strike → ⑤ 临时封禁，时长递增：1h → 6h → 24h → 72h
```

管理员（`anticheat.alerts`）全程收到实时警报；踢出/封禁默认全服公告。

## 检测项

| 类别 | 检测项 |
|---|---|
| 移动 | Speed、Flight、GroundSpoof、Timer、FastLadder、Step、Rotation |
| 协议层 | BadPackets（崩服包拦截）＋ Timer/Rotation 的包级数据源 |
| 战斗 | Reach、KillAura、AutoClicker、NoSwing、Criticals、Velocity |
| 玩家行为 | AutoTotem、InventoryMove、NoSlow、FastUse、FastBow、ChestStealer |
| 世界交互 | FastBreak、Scaffold |
| 聊天 | AntiSpam、AntiAds |

数据包引擎（`packet-engine.enabled`）工作时，Timer 以包的真实到达时间测速——不受服务端卡顿影响、可覆盖任意倍速与静止 Timer；Rotation 与 BadPackets 在非法数据进入服务端之前直接丢弃。引擎关闭或初始化失败时自动回退到事件级检测。

内置误判防护覆盖鞘翅/攀爬/液体/床弹跳/载具/击退/传送/进服宽限等常见合法场景，并兼容技能插件位移、MMO 移速属性、区域保护击退削弱、建筑法杖批量放置等第三方插件行为。

## 安装与构建

```bash
./mvnw package   # 需要 JDK 17+；无需预装 Maven
```

产物在 `target/Sayaka-AntiCheat-1.1.0.jar`，放入服务端 `plugins/` 目录即可。

## 命令与权限

| 命令 | 说明 | 权限 |
|---|---|---|
| `/sac status <玩家>` | 实时 VL、strike、封禁史 | `anticheat.admin` |
| `/sac history <玩家>` | 违规明细 + 历史惩罚 | `anticheat.admin` |
| `/sac reset <玩家> [all]` | 清空 VL（`all` 连同 strike/封禁档案） | `anticheat.admin` |
| `/sac whitelist add/remove/list` | 反作弊白名单管理 | `anticheat.whitelist` |
| `/sac unban <玩家> [reset]` | 解封并清空 strike | `anticheat.unban` |
| `/sac alerts` | 开关个人实时警报 | `anticheat.alerts` |
| `/sac reload` | 重载配置 | `anticheat.admin` |

`anticheat.bypass` 完全绕过检测（默认无人持有）；`anticheat.antispam.bypass` / `anticheat.antiads.bypass` 仅绕过对应聊天检测。以上权限默认均不给 OP 之外的人。

## 调参建议

- 上线初期开 `settings.debug: true` 观察警报 `detail`，再收紧阈值；误判多优先调大 `buffer-to-flag`。
- 数据包引擎默认开启；若与其他协议层插件（ViaVersion/Geyser 之外的注入类插件）疑似冲突，可临时 `packet-engine.enabled: false` 回退到事件级对比排查。
- 纯计算检测使用独立线程池（`settings.parallel-analysis` 可调），队列满时丢弃分析以保 TPS，不阻塞主线程。
- TPS 低于 18 时 Timer 检测自动暂停；`fast-break.nuker-detect` 与连锁挖掘类插件冲突需关闭。
- 想更严：调低 `punishment.kick-vl` 或 `strikes.to-tempban`；想只警告不处罚：把 `kick-vl` 调到 9999。

## 能力边界

事件层检测覆盖常见作弊，内置数据包引擎补上协议层视野：包级测速（Timer）、非法包前置拦截（Rotation/BadPackets）。尚未做完整的移动物理回放模拟，对精调"合法范围内"作弊和更深的包级伪装（blink、AntiKB 变种等）仍有上限。极高对抗场景可叠加全模拟方案（如 GrimAC），与本插件共存。

## 扩展新检测

继承 `cn.haitang.anticheat.check.Check`，调用 `flag(player, 权重, "证据")`，在 `AntiCheatPlugin#onEnable` 注册并在 `config.yml` 的 `checks` 段加配置即可。VL 累积、警告、拦截、踢出、封禁均由框架自动接管。
