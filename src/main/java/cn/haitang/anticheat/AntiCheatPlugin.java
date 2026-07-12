package cn.haitang.anticheat;

import cn.haitang.anticheat.alert.AlertManager;
import cn.haitang.anticheat.check.Check;
import cn.haitang.anticheat.check.MovementTracker;
import cn.haitang.anticheat.check.chat.AntiAdsCheck;
import cn.haitang.anticheat.check.chat.AntiSpamCheck;
import cn.haitang.anticheat.check.combat.AimCheck;
import cn.haitang.anticheat.check.combat.AutoClickerCheck;
import cn.haitang.anticheat.check.combat.CriticalsCheck;
import cn.haitang.anticheat.check.combat.NoSwingCheck;
import cn.haitang.anticheat.check.combat.ReachCheck;
import cn.haitang.anticheat.check.combat.VelocityCheck;
import cn.haitang.anticheat.check.movement.FastLadderCheck;
import cn.haitang.anticheat.check.movement.FlightCheck;
import cn.haitang.anticheat.check.movement.GroundSpoofCheck;
import cn.haitang.anticheat.check.movement.RotationCheck;
import cn.haitang.anticheat.check.movement.SpeedCheck;
import cn.haitang.anticheat.check.movement.StepCheck;
import cn.haitang.anticheat.check.movement.TimerCheck;
import cn.haitang.anticheat.check.player.AutoTotemCheck;
import cn.haitang.anticheat.check.player.ChestStealerCheck;
import cn.haitang.anticheat.check.player.FastBowCheck;
import cn.haitang.anticheat.check.player.FastUseCheck;
import cn.haitang.anticheat.check.player.InventoryMoveCheck;
import cn.haitang.anticheat.check.player.NoSlowCheck;
import cn.haitang.anticheat.check.world.FastBreakCheck;
import cn.haitang.anticheat.check.world.ScaffoldCheck;
import cn.haitang.anticheat.command.AntiCheatCommand;
import cn.haitang.anticheat.concurrent.ParallelAnalysisExecutor;
import cn.haitang.anticheat.data.PersistentStore;
import cn.haitang.anticheat.data.PlayerDataManager;
import cn.haitang.anticheat.listener.ConnectionListener;
import cn.haitang.anticheat.util.BedrockSupport;
import cn.haitang.anticheat.util.Messages;
import cn.haitang.anticheat.violation.PunishmentExecutor;
import cn.haitang.anticheat.violation.ViolationManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

/**
 * Sayaka AntiCheat 主类。
 *
 * 数据流：
 *   事件 → MovementTracker(状态) → 各 Check(检测) → ViolationManager(VL 累加)
 *        → AlertManager(警告/警报) / PunishmentExecutor(踢出/封禁)
 *        → PersistentStore(strike 与封禁档案持久化)
 */
public final class AntiCheatPlugin extends JavaPlugin {

    private PlayerDataManager dataManager;
    private PersistentStore store;
    private BedrockSupport bedrockSupport;
    private Messages messages;
    private AlertManager alertManager;
    private PunishmentExecutor punishmentExecutor;
    private ViolationManager violationManager;
    private ParallelAnalysisExecutor analysisExecutor;

    private final List<Check> checks = new ArrayList<>();
    private FlightCheck flightCheck;
    private AimCheck aimCheck;
    private BukkitTask saveTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        dataManager = new PlayerDataManager();
        store = new PersistentStore(this);
        bedrockSupport = new BedrockSupport(getLogger());
        messages = new Messages(this);
        alertManager = new AlertManager(this);
        punishmentExecutor = new PunishmentExecutor(this);
        violationManager = new ViolationManager(this);
        analysisExecutor = new ParallelAnalysisExecutor(this);

        // 检测项注册（enabled 开关在事件内部实时判断，reload 即生效）
        flightCheck = new FlightCheck(this);
        checks.add(new SpeedCheck(this));
        checks.add(flightCheck);
        checks.add(new GroundSpoofCheck(this));
        checks.add(new TimerCheck(this));
        checks.add(new FastLadderCheck(this));
        checks.add(new StepCheck(this));
        checks.add(new RotationCheck(this));
        checks.add(new ReachCheck(this));
        aimCheck = new AimCheck(this);
        checks.add(aimCheck);
        checks.add(new AutoClickerCheck(this));
        checks.add(new NoSwingCheck(this));
        checks.add(new CriticalsCheck(this));
        checks.add(new VelocityCheck(this));
        checks.add(new AutoTotemCheck(this));
        checks.add(new InventoryMoveCheck(this));
        checks.add(new NoSlowCheck(this));
        checks.add(new FastUseCheck(this));
        checks.add(new FastBowCheck(this));
        checks.add(new ChestStealerCheck(this));
        checks.add(new FastBreakCheck(this));
        checks.add(new ScaffoldCheck(this));
        checks.add(new AntiSpamCheck(this));
        checks.add(new AntiAdsCheck(this));

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new ConnectionListener(this), this);
        pm.registerEvents(new MovementTracker(this), this);
        for (Check check : checks) {
            pm.registerEvents(check, this);
        }

        PluginCommand command = getCommand("sac");
        if (command != null) {
            AntiCheatCommand executor = new AntiCheatCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        violationManager.startDecayTask();
        aimCheck.start();
        saveTask = getServer().getScheduler().runTaskTimer(this, store::saveAsync, 1200L, 1200L);

        // /reload 或热插拔时，为已在线玩家补上基岩身份标记
        getServer().getOnlinePlayers().forEach(p ->
                dataManager.get(p).setBedrock(bedrockSupport.isBedrock(p)));

        getLogger().info("已启用 " + checks.size() + " 项检测，递进惩罚: 警告 → 拦截 → 踢出 → 临时封禁");
    }

    @Override
    public void onDisable() {
        if (saveTask != null) saveTask.cancel();
        if (violationManager != null) violationManager.shutdown();
        if (flightCheck != null) flightCheck.shutdown();
        if (aimCheck != null) aimCheck.shutdown();
        if (store != null) store.saveNow();
        if (analysisExecutor != null) analysisExecutor.shutdown();
    }

    public PlayerDataManager getDataManager() { return dataManager; }
    public PersistentStore getStore() { return store; }
    public BedrockSupport getBedrockSupport() { return bedrockSupport; }
    public Messages getMessages() { return messages; }
    public AlertManager getAlertManager() { return alertManager; }
    public PunishmentExecutor getPunishmentExecutor() { return punishmentExecutor; }
    public ViolationManager getViolationManager() { return violationManager; }
    public ParallelAnalysisExecutor getAnalysisExecutor() { return analysisExecutor; }
    public List<Check> getChecks() { return checks; }
}
