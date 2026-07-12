package cn.haitang.anticheat.check.chat;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.Check;
import cn.haitang.anticheat.check.CheckType;
import org.bukkit.entity.Player;

abstract class ChatCheck extends Check {

    ChatCheck(AntiCheatPlugin plugin, CheckType type) {
        super(plugin, type);
    }

    protected boolean isChatExempt(Player player, String specificBypassPermission) {
        if (!isEnabled() || !player.isOnline()) return true;
        if (player.hasPermission(PERM_BYPASS)
                || player.hasPermission(specificBypassPermission)) return true;
        if (plugin.getConfig().getBoolean("settings.exempt-ops", false) && player.isOp()) return true;
        if (player.hasMetadata("NPC")) return true;
        String worldName = player.getWorld().getName();
        return plugin.getConfig().getStringList("settings.disabled-worlds").stream()
                .anyMatch(world -> world.equalsIgnoreCase(worldName));
    }

    protected void dispatchViolation(Player player, double weight, String detail,
                                     String feedbackMessageKey) {
        if (weight <= 0 && feedbackMessageKey == null) return;
        Runnable action = () -> {
            if (!player.isOnline()) return;
            if (feedbackMessageKey != null) {
                player.sendMessage(plugin.getMessages().prefixed(feedbackMessageKey, null));
            }
            if (weight > 0) flag(player, weight, detail);
        };

        if (plugin.getServer().isPrimaryThread()) {
            action.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, action);
        }
    }
}
