package com.server.moderation.mixin;

import com.server.moderation.Moderation;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(net.minecraft.server.command.CommandManager.class)
public class CommandLoggingMixin {
    // Note: Command logging mixin is disabled due to method signature changes in 1.21.4
    // Stealth mode functionality is handled through other means
    // The CommandExecutionMixin handles feedback suppression which is sufficient
}
