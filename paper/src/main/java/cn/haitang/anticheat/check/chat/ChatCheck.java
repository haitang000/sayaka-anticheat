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
        return !isEnabled() || plugin.getChatExemptions()
                .isExempt(player.getUniqueId(), specificBypassPermission);
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
