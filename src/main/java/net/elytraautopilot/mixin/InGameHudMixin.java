package net.elytraautopilot.mixin;

import net.elytraautopilot.ElytraAutoPilot;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public class InGameHudMixin {

	MinecraftClient minecraftClient;
	ElytraAutoPilot elytraAutoPilot;

	@Inject(at = @At(value = "RETURN"), method = "render")
	public void renderPost(MatrixStack matrixStack, float f, CallbackInfo ci) {
		if (!ci.isCancelled()) {
			if (minecraftClient == null) {
				minecraftClient = MinecraftClient.getInstance();
			}

			if (elytraAutoPilot == null) {
				elytraAutoPilot = ElytraAutoPilot.instance;
			}

			if (elytraAutoPilot.showHud && elytraAutoPilot.hudContents != null) {
				float stringX = elytraAutoPilot.config.guiX;
				float stringY = elytraAutoPilot.config.guiY;

				for (int i = 0; i < elytraAutoPilot.hudContents.length; i++) {
					minecraftClient.textRenderer.drawWithShadow(
							matrixStack,
							elytraAutoPilot.hudContents[i],
							stringX,
							stringY + i * (minecraftClient.textRenderer.fontHeight + 1),
							0xFFFFFF);
				}
			}
		}
	}
}
