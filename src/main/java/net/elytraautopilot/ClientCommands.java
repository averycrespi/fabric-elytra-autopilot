package net.elytraautopilot;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v1.ClientCommandManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Formatting;

public class ClientCommands {
    public static void register(ElytraAutoPilot main, ElytraConfig config, MinecraftClient minecraftClient) {
        ClientCommandManager.DISPATCHER.register(
                ClientCommandManager.literal("flyto")
                        .then(ClientCommandManager.argument("X", IntegerArgumentType.integer(-2000000000, 2000000000))
                                .then(ClientCommandManager
                                        .argument("Z", IntegerArgumentType.integer(-2000000000, 2000000000))
                                        .executes(context -> {
                                            if (minecraftClient.player == null)
                                                return 1;
                                            if (minecraftClient.player.isFallFlying()) { // If the player is flying
                                                if (main.distanceToGround > config.minHeight) { // If above required
                                                                                                // height
                                                    main.autoFlightEnabled = true;
                                                    main.argXpos = IntegerArgumentType.getInteger(context, "X");
                                                    main.argZpos = IntegerArgumentType.getInteger(context, "Z");
                                                    main.isFlyingTo = true;
                                                    context.getSource().sendFeedback(new TranslatableText(
                                                            "text.elytraautopilot.flyto", main.argXpos, main.argZpos)
                                                                    .formatted(Formatting.GREEN));
                                                } else {
                                                    minecraftClient.player.sendMessage(new TranslatableText(
                                                            "text.elytraautopilot.autoFlightFail.tooLow")
                                                                    .formatted(Formatting.RED),
                                                            true);
                                                }
                                            } else {
                                                minecraftClient.player.sendMessage(new TranslatableText(
                                                        "text.elytraautopilot.flytoFail.flyingRequired")
                                                                .formatted(Formatting.RED),
                                                        true);
                                            }
                                            return 1;
                                        }))));
        ClientCommandManager.DISPATCHER.register(
                ClientCommandManager.literal("takeoff")
                        .then(ClientCommandManager.argument("X", IntegerArgumentType.integer(-2000000000, 2000000000))
                                .then(ClientCommandManager
                                        .argument("Z", IntegerArgumentType.integer(-2000000000, 2000000000))
                                        .executes(context -> { // With coordinates
                                            main.argXpos = IntegerArgumentType.getInteger(context, "X");
                                            main.argZpos = IntegerArgumentType.getInteger(context, "Z");
                                            main.shouldFlyToAfterTakeoff = true; // Chains fly-to command
                                            main.startTakeoff();
                                            return 1;
                                        })))
                        .executes(context -> { // Without coordinates
                            main.startTakeoff();
                            return 1;
                        }));
    }
}
