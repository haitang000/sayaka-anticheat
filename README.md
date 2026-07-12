# Sayaka AntiCheat — 轻量级服务端反作弊插件

适用于 **Paper / Purpur 1.20+**（`api-version: 1.20`，可在 1.21.x 上运行）的 PVP / 生存服反作弊插件。
纯服务端事件判定，不信任客户端数据，无任何外部依赖。

## 设计理念

1. **宁可漏判，不可误判。** 所有阈值都预留了物理余量，并叠加缓冲值（buffer）机制：
   偶发的网络抖动、击退、传送不会触发违规，只有**持续异常**才会累积到违规。
2. **递进式处置，而非一刀切。** 玩家先收到明确的警告和改正机会，处罚力度随违规次数逐级上升。
3. **一切可配置。** 阈值、警告文案、惩罚阶梯、封禁时长全部在 `config.yml` 中。

## 递进式惩罚链

每项检测独立累积 **VL（违规值）**，VL 每秒衰减（默认 0.2/s），正常游戏可自行"洗白"。刚触发违规的检测项有短暂保护期（`decay.hold-seconds`，默认 6s）不衰减，持续作弊的证据不会边累积边被稀释；衰减速率还可按检测项覆盖（`decay.per-check`，如 KillAura 这类证据稀疏的战斗检测默认 0.1/s）。警告和踢出按所有检测项的**综合 VL** 判断，拦截仍按当前检测项 VL 判断，避免无关检测导致错误回弹或取消事件：

```
综合 VL ≥ 5   ①警告：标题 + 聊天提示 + 音效（"检测到异常行为"）
单项 VL ≥ 8   ②拦截：移动违规回弹传送 / 超距与视角外命中取消 / 违规挖掘恢复
综合 VL ≥ 12  ③最后通牒：更严厉的警告（"再犯将被踢出"）
综合 VL ≥ 动态踢出阈值  ④踢出：未警告为 20，首次警告后为 18，最后警告后为 15；并记录一次 strike（持久化，重进不清零）
24h 内 3 次 strike → ⑤临时封禁，时长按封禁史递增：1h → 6h → 24h → 72h
```

动态踢出阈值由 `punishment.kick-vl` 乘以当前警告阶段对应的
`punishment.warned-kick-multipliers` 计算。警告状态随 VL 洗白或玩家重连清除；新阶段的较低阈值从下一次违规开始生效。

管理员（`anticheat.alerts` 权限）全程收到实时警报；踢出/封禁默认全服公告以形成威慑。

## 检测项

| 检测 | 手段 | 针对的作弊 |
|---|---|---|
| Speed | 1.5s 滚动窗口平均速度 vs 动态上限（含药水/冰面/灵魂疾行修正） | 加速、BunnyHop |
| Flight | 滞空上升超限 + 移动悬浮 + 每秒静止悬浮扫描（三线兜底） | 飞行、悬浮 |
| GroundSpoof | 客户端 onGround 声明 vs 服务端真实碰撞 | NoFall 免摔落 |
| Timer | 3s 窗口移动包速率（含卡顿积压去重 + 低 TPS 暂停） | Timer 加速器 |
| FastLadder | 单包上爬高度 vs 原版攀爬速度（贴梯跳跃豁免） | 快速爬梯 |
| Step | 单包上升高度 + 水平位移（跳跃提升/赋速/活塞等豁免） | 高步进、自动上方块 |
| Rotation | 俯仰角合法范围 ±90°，超出直接判定并纠正 | Derp、非法视角包 |
| Reach | 眼睛到目标碰撞箱距离，按 ping 动态放宽 | 超距攻击 |
| KillAura | 分级攻击方向夹角 + 短间隔快速切换目标 + 侧后方环绕隐身假人探针 | 杀戮光环 |
| AutoClicker | 持续 CPS 超标 + 点击间隔标准差（机械节奏） | 连点器、宏 |
| NoSwing | 攻击必须伴随挥臂动画包（延后 2 刻验证，兼容到达顺序） | NoSwing |
| Criticals | 暴击时服务端推算轨迹必须离地（0.05 格严格判定） | 假暴击 |
| Velocity | 受击 4 刻后位移（贴墙/液体/格挡/无敌帧等全豁免） | 反击退 AntiKB |
| AutoTotem | 图腾破碎 → 副手补装的反应时间 | 自动图腾 |
| InventoryMove | 开容器界面时的持续水平位移 | 背包行走 |
| NoSlow | 使用物品期间的持续水平速度（吃喝/拉弓/举盾，默认只累计 VL） | NoSlow 减速绕过 |
| FastUse | 进食/饮用完成耗时与原版使用时长对比 | FastEat、FastUse |
| FastBow | 蓄力时长与最终箭矢力度联合判断 | FastBow、瞬间满弓 |
| ChestStealer | 真实容器内取物动作的滚动窗口频率 | ChestStealer、自动搜刮 |
| FastBreak | 实际挖掘耗时 vs `getBreakSpeed` 理论耗时 | 快速挖掘、Nuker |
| Scaffold | 垫块俯视角 + 放置频率 + 空中垂直垫块节奏 | 自动搭路、快速放置 |
| AntiSpam | 高频滚动窗口 + 归一化后的重复内容计数 | 刷屏、重复消息轰炸 |
| AntiAds | IPv4、域名、Discord 邀请及 `dot/[dot]/点` 绕过匹配 | 服务器广告、邀请链接 |

**内置误判防护**：鞘翅/激流/漂浮/缓降/跳跃提升/攀爬/液体/蛛网/蜂蜜块/黏液块弹跳/床弹跳/载具/
击退/传送/重生/进服宽限/服务端赋速/站在船或潜影贝上/NPC，均自动豁免对应检测。

**插件位移兼容**：其他插件造成的合法位移不会误判——
- 技能位移/跳板/钩爪等 `setVelocity` 赋速：宽限窗口按赋速强度自动延长（最长 4 倍）；
- MMO/饰品插件的移速属性、`setWalkSpeed` 加速：Speed 上限按实际属性值等比放大；
- 决斗/区域保护插件取消或削弱击退：Velocity 检测只在击退真正下发给客户端时才测量；
- 建筑法杖类插件同刻批量放置：Scaffold 只计第一个方块；
- 插件传送（含瞬移技能）：全检测统一宽限并重置移动轨迹。

## 安装与构建

```bash
# 需要 JDK 17+；首次构建会自动下载依赖
mvn package        # 或 ./mvnw package（使用自带的 Maven Wrapper，无需安装 Maven）
```

产物在 `target/Sayaka-AntiCheat-1.1.0.jar`，放入服务端 `plugins/` 目录即可。

## 命令与权限

| 命令 | 说明 | 权限 |
|---|---|---|
| `/sac status <玩家>` | 实时 VL、strike、封禁史 | `anticheat.admin` |
| `/sac history <玩家>` | 本次会话违规明细 + 历史惩罚 | `anticheat.admin` |
| `/sac reset <玩家> [all]` | 清空 VL（`all` 连同 strike/封禁档案） | `anticheat.admin` |
| `/sac whitelist add <玩家>` | 加入反作弊白名单（支持离线玩家） | `anticheat.whitelist` |
| `/sac whitelist remove <玩家>` | 移出反作弊白名单 | `anticheat.whitelist` |
| `/sac whitelist list` | 查看反作弊白名单 | `anticheat.whitelist` |
| `/sac unban <玩家> [reset]` | 解除封禁并清空 strike；`reset` 同时重置封禁次数阶梯 | `anticheat.unban` |
| `/sac alerts` | 开关个人实时警报 | `anticheat.alerts` |
| `/sac reload` | 重载配置（所有阈值即时生效） | `anticheat.admin` |

`anticheat.bypass`：完全绕过检测（默认无人持有，谨慎授予）。
白名单持久化在插件的 `data.yml` 中；白名单玩家不会被检测、累计 VL、拦截或处罚。
`anticheat.admin` / `anticheat.alerts` / `anticheat.whitelist` / `anticheat.unban` 默认 OP 持有。
`anticheat.antispam.bypass` / `anticheat.antiads.bypass`：仅绕过对应聊天检测，默认无人持有。

## 调参建议

- 纯计算检测会自动使用独立的有界工作线程池，默认线程数为 `min(8, CPU 核心数 - 1)`；可通过
  `settings.parallel-analysis` 调整线程、任务队列和每 tick 回调上限。线程数修改后需重启。
- 工作队列满时系统会主动丢弃本次分析，而不会阻塞服务器主线程；这会产生少量漏判，但能保证高峰期 TPS。
- 上线初期建议把 `settings.debug: true` 开几天，观察警报里的 `detail` 数据再收紧阈值。
- 误判多的项优先调大 `buffer-to-flag`，而不是直接调大物理阈值。
- 服务器 TPS 长期低于 18 时，Timer 会按 `checks.timer.min-tps` 暂停判定；若全服长期卡顿，仍建议先解决性能问题。
- NoSlow 默认覆盖食物、药水/牛奶、弓、弩、盾牌，并只累计 VL；若服务器有自定义高速职业或饰品，优先调高 `checks.no-slow.max-using-bps` 或 `buffer-to-flag`。
- `fast-break.nuker-detect` 与 McMMO/连锁挖掘类插件冲突，装有此类插件请保持关闭。
- 将本服官网、论坛和连接地址加入 `checks.anti-ads.allowed-hosts`，白名单会自动覆盖其子域名。
- AntiSpam 的冷却只限制 VL 和管理员警报频率，冷却期间识别出的刷屏消息仍会被拦截。
- 惩罚想更严：调低 `punishment.kick-vl`、`punishment.warned-kick-multipliers` 或 `strikes.to-tempban`；
  想只警告不处罚：把 `kick-vl` 调到极大值（如 9999），保留警告与警报。

## 能力边界（诚实说明）

本插件工作在 **Bukkit 事件层**，覆盖常见/低门槛作弊已足够，且几乎不误伤正常玩家。
但它看不到原始数据包，对以下对手有天然上限：

- 精心调参的"合法范围内"作弊（如 3.05 格 reach、19 CPS 连点、1.2 倍以下 timer）
- 数据包级伪装（blink/闪现回退、只削横向击退的 AntiKB 变种、部分 scaffold）

若服务器走向高对抗竞技场景，建议叠加数据包级方案（如 GrimAC）——
本插件的警告/惩罚阶梯与其并存不冲突。

## 扩展新检测

继承 `cn.haitang.anticheat.check.Check`，在事件里调用 `flag(player, 权重, "证据")`，
然后在 `AntiCheatPlugin#onEnable` 注册、在 `config.yml` 的 `checks` 段加配置即可。
NoSlow 这类保守检测可以只上报 VL，不做回弹或取消事件；VL 累积、警告、拦截、踢出、封禁全部由框架自动接管。
