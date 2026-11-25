package dev.siliciumx.client.mixin.core;

import com.mojang.brigadier.CommandDispatcher;
import dev.siliciumx.command.SiliciumXCommand;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.client.multiplayer.CommonListenerCookie;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Enables native zero-dependency client-side {@code /siliciumx} execution and Tab completion when
 * connected to remote multiplayer servers (Vanilla, Paper, Spigot, etc.) or non-OP worlds where
 * the server does not expose the {@code /siliciumx} command.
 *
 * <p>Never transmits intercepted client-side {@code /siliciumx} commands to the remote server.</p>
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientCommandMixin extends ClientCommonPacketListenerImpl {
	@Shadow
	private CommandDispatcher<ClientSuggestionProvider> commands;

	@Unique
	private boolean siliciumx$serverHasCommand;

	protected ClientCommandMixin(Minecraft minecraft, Connection connection, CommonListenerCookie cookie) {
		super(minecraft, connection, cookie);
	}

	@Inject(method = "handleCommands(Lnet/minecraft/network/protocol/game/ClientboundCommandsPacket;)V", at = @At("TAIL"))
	private void siliciumx$registerClientCommandSuggestions(ClientboundCommandsPacket packet, CallbackInfo ci) {
		this.siliciumx$serverHasCommand = this.commands.getRoot().getChild("siliciumx") != null;
		if (!this.siliciumx$serverHasCommand) {
			SiliciumXCommand.registerClientSuggestions(this.commands);
		}
	}

	@Inject(method = "sendCommand(Ljava/lang/String;)V", at = @At("HEAD"), cancellable = true)
	private void siliciumx$handleClientSideCommand(String command, CallbackInfo ci) {
		if (!this.siliciumx$serverHasCommand
			&& SiliciumXCommand.tryExecuteClientCommand(
				command,
				this.minecraft.getSingleplayerServer() == null,
				this::siliciumx$sendLocalMessage
			)
		) {
			ci.cancel();
		}
	}

	@Unique
	private void siliciumx$sendLocalMessage(Component message) {
		if (this.minecraft.player != null) {
			this.minecraft.player.sendSystemMessage(message);
		} else {
			this.minecraft.gui.hud.getChat().addClientSystemMessage(message);
		}
	}
}
