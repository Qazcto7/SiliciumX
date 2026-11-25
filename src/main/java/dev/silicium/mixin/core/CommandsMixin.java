package dev.silicium.mixin.core;

import com.mojang.brigadier.CommandDispatcher;
import dev.silicium.command.SiliciumCommand;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Registers the {@code /silicium} command natively via Brigadier without requiring Fabric API at runtime.
 */
@Mixin(Commands.class)
public abstract class CommandsMixin {
	@Shadow
	@Final
	private CommandDispatcher<CommandSourceStack> dispatcher;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void silicium$registerCommands(
		Commands.CommandSelection selection,
		CommandBuildContext context,
		CallbackInfo ci
	) {
		SiliciumCommand.register(this.dispatcher);
	}
}
