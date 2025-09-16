package com.server.moderation.mixin;

import com.server.moderation.Moderation;
import net.minecraft.server.dedicated.MinecraftDedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftDedicatedServer.class)
public class CommandMixin {
    // Note: logCommand method doesn't exist in MinecraftDedicatedServer in 1.21.4
    // This mixin is kept for future compatibility but currently disabled
    // The stealth mode functionality is handled through other means
}
