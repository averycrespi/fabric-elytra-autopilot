package net.elytraautopilot;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.MessageType;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.BaseText;
import net.minecraft.text.LiteralText;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

public class ElytraAutoPilot implements ModInitializer, net.fabricmc.api.ClientModInitializer {
    public ElytraConfig config;
    public ElytraAutoPilot main = this;

    private static KeyBinding keyBinding;
    public static ElytraAutoPilot instance;

    private boolean keybindingWasPressedOnPreviousTick = false;

    public MinecraftClient minecraftClient;

    public boolean showHud;
    public boolean autoFlightEnabled;

    private boolean doTakeoffCooldown;
    private int takeoffCooldown = 0;

    private boolean isTakingOff;
    private double pitchMod = 1f;

    private Vec3d previousPosition;
    private double currentVelocity;
    private double currentHorizontalVelocity;

    public boolean isDescending;
    public boolean isPullingUp;
    public boolean isPullingDown;

    private double velHigh = 0f; // ?
    private double velLow = 0f; // ?

    public int argXpos;
    public int argZpos;
    public boolean shouldFlyToAfterTakeoff = false;
    public boolean isFlyingTo = false;
    private boolean isLanding = false;

    private int _tick = 0;
    private int _velocityIndex = -1;
    private double distanceToTarget = 0f;
    public double distanceToGround;
    private List<Double> velocityList = new ArrayList<>();
    private List<Double> velocityListHorizontal = new ArrayList<>();

    public BaseText[] hudString;

    @Override
    public void onInitialize() {
        keyBinding = new KeyBinding(
                "key.elytraautopilot.toggle", // The translation key of the keybinding's name
                InputUtil.Type.KEYSYM, // The type of the keybinding, KEYSYM for keyboard, MOUSE for mouse.
                GLFW.GLFW_KEY_R, // The keycode of the key
                "text.elytraautopilot.title" // The translation key of the keybinding's category.
        );

        KeyBindingHelper.registerKeyBinding(keyBinding);

        keybindingWasPressedOnPreviousTick = false;
        HudRenderCallback.EVENT.register((matrixStack, tickDelta) -> ElytraAutoPilot.this.onScreenTick());
        ClientTickEvents.END_CLIENT_TICK.register(e -> this.onClientTick());
        ElytraAutoPilot.instance = this;
        initConfig();
    }

    public void startTakeOff() {
        if (isTakingOff) {
            return;
        }

        PlayerEntity player = minecraftClient.player;
        if (player == null) {
            return;
        }

        Item chestItem = player.getInventory().armor.get(2).getItem();
        int elytraDurability = player.getInventory().armor.get(2).getMaxDamage()
                - player.getInventory().armor.get(2).getDamage();
        if (!chestItem.toString().equals("elytra")) {
            player.sendMessage(new TranslatableText("text.elytraautopilot.takeoffFail.noElytraEquipped")
                    .formatted(Formatting.RED), true);
            return;
        }
        if (elytraDurability == 1) {
            player.sendMessage(new TranslatableText("text.elytraautopilot.takeoffFail.elytraBroken")
                    .formatted(Formatting.RED), true);
            return;
        }

        Item mainHandItem = player.getMainHandStack().getItem();
        Item offHandItem = player.getOffHandStack().getItem();
        if (!mainHandItem.toString().equals("firework_rocket") && !offHandItem.toString().equals("firework_rocket")) {
            player.sendMessage(new TranslatableText("text.elytraautopilot.takeoffFail.fireworkRequired")
                    .formatted(Formatting.RED), true);
            return;
        }

        World world = player.world;
        Vec3d clientPos = player.getPos();
        double clientY = clientPos.getY();
        double topY = world.getTopY();
        for (double y = clientY; y < topY; y++) {
            BlockPos blockPos = new BlockPos(clientPos.getX(), y + 2, clientPos.getZ());
            if (!world.getBlockState(blockPos).isAir()) {
                player.sendMessage(new TranslatableText("text.elytraautopilot.takeoffFail.clearSkyNeeded")
                        .formatted(Formatting.RED), true);
                return;
            }
        }

        doTakeoffCooldown = true;
        minecraftClient.options.keyJump.setPressed(true);
    }

    public void endTakeoff() {
        if (!isTakingOff) {
            return;
        }

        PlayerEntity player = minecraftClient.player;
        if (player == null) {
            return;
        }

        isTakingOff = false;
        autoFlightEnabled = true;

        minecraftClient.options.keyUse.setPressed(false);
        minecraftClient.options.keyJump.setPressed(false);
        pitchMod = 3f;

        if (shouldFlyToAfterTakeoff) {
            isFlyingTo = true;
            shouldFlyToAfterTakeoff = false;

            minecraftClient.inGameHud.addChatMessage(
                    MessageType.SYSTEM,
                    new TranslatableText("text.elytraautopilot.flyto", argXpos, argZpos)
                            .formatted(Formatting.GREEN),
                    player.getUuid());
        }
    }

    public void useFireworkDuringTakeoff() {
        if (!isTakingOff) {
            return;
        }

        PlayerEntity player = minecraftClient.player;
        if (player == null) {
            return;
        }

        Item mainHandItem = player.getMainHandStack().getItem();
        Item offHandItem = player.getOffHandStack().getItem();
        boolean hasFirework = (mainHandItem.toString().equals("firework_rocket")
                || offHandItem.toString().equals("firework_rocket"));
        if (!hasFirework) {
            isTakingOff = false;

            minecraftClient.options.keyUse.setPressed(false);
            minecraftClient.options.keyJump.setPressed(false);

            player.sendMessage(
                    new TranslatableText("text.elytraautopilot.takeoffAbort.noFirework").formatted(Formatting.RED),
                    true);
            return;
        }

        minecraftClient.options.keyUse.setPressed(currentVelocity < 0.75f && player.getPitch() == -90f);
    }

    public void takeoff() {
        PlayerEntity player = minecraftClient.player;
        if (player == null) {
            return;
        }

        if (!isTakingOff) {
            startTakeOff();
            return;
        }

        if (distanceToGround > config.minHeight) {
            endTakeoff();
            return;
        }

        if (!player.isFallFlying()) {
            minecraftClient.options.keyJump.setPressed(!minecraftClient.options.keyJump.isPressed());
        }

        useFireworkDuringTakeoff();
    }

    private void onScreenTick() // Once every screen frame
    {
        // Fps adaptation (not perfect but works nicely most of the time)
        float fps_delta = minecraftClient.getLastFrameDuration();
        float fps_result = 20 / fps_delta;
        double speedMod = 60 / fps_result; // Adapt to base 60 FPS

        if (minecraftClient.isPaused() && minecraftClient.isInSingleplayer())
            return;
        PlayerEntity player = minecraftClient.player;

        if (player == null) {
            return;
        }

        // If we're taking off, adjust the angle to look straight up
        if (isTakingOff) {
            float pitch = player.getPitch();
            if (pitch > -90f) {
                player.setPitch((float) (pitch - config.takeOffPull * speedMod));
            }
            if (pitch <= -90f)
                player.setPitch(-90f);
        }

        if (autoFlightEnabled) {
            // If hotswap is enabled, swap out the elytra if it's nearly broken
            if (config.elytraHotswap) {
                int elytraDurability = player.getInventory().armor.get(2).getMaxDamage()
                        - player.getInventory().armor.get(2).getDamage();
                if (elytraDurability <= 5) { // Leave some leeway so we don't stop flying
                    // Optimization: find the first elytra with sufficient durability
                    ItemStack newElytra = null;
                    int minDurability = 10;
                    for (ItemStack itemStack : player.getInventory().main) {
                        if (itemStack.getItem().toString().equals("elytra")) {
                            int itemDurability = itemStack.getMaxDamage() - itemStack.getDamage();
                            if (itemDurability >= minDurability) {
                                newElytra = itemStack;
                                break;
                            }
                        }
                    }
                    if (newElytra != null) {
                        int chestSlot = 6;
                        minecraftClient.interactionManager.clickSlot(
                                player.playerScreenHandler.syncId,
                                chestSlot,
                                player.getInventory().main.indexOf(newElytra),
                                SlotActionType.SWAP,
                                player);
                        player.playSound(SoundEvents.ITEM_ARMOR_EQUIP_ELYTRA, 1.0F, 1.0F);
                        player.sendMessage(
                                new TranslatableText("text.elytraautopilot.swappedElytra").formatted(Formatting.GREEN),
                                true);
                    }
                }
            }

            float pitch = player.getPitch();
            if (isFlyingTo) {
                if (isLanding) {
                    if (!config.autoLanding) {
                        isFlyingTo = false;
                        isLanding = false;
                        return;
                    }
                    isDescending = true;
                    if (config.riskyLanding && distanceToGround > 60) {
                        if (currentHorizontalVelocity > 0.3f || currentVelocity > 1.0f) { // TODO make it smoother
                            smoothLanding(player, speedMod);
                        } else {
                            riskyLanding(player, speedMod);
                        }
                    } else {
                        smoothLanding(player, speedMod);
                    }

                } else {
                    // Precondition: we're flying but not landing
                    Vec3d playerPosition = player.getPos();
                    double xDifference = (double) argXpos - playerPosition.x;
                    double zDifference = (double) argZpos - playerPosition.z;
                    float targetYaw = MathHelper
                            .wrapDegrees(
                                    (float) (MathHelper.atan2(zDifference, xDifference) * 57.2957763671875D) - 90.0F);
                    float yaw = MathHelper.wrapDegrees(player.getYaw());
                    if (Math.abs(yaw - targetYaw) < config.turningSpeed * 2 * speedMod)
                        player.setYaw(targetYaw);
                    else {
                        if (yaw < targetYaw)
                            player.setYaw((float) (yaw + config.turningSpeed * speedMod));
                        if (yaw > targetYaw)
                            player.setYaw((float) (yaw - config.turningSpeed * speedMod));
                    }
                    distanceToTarget = Math.sqrt(xDifference * xDifference + zDifference * zDifference);

                    // If we're close enough, start landing
                    if (distanceToTarget < 20) {
                        isLanding = true;
                    }
                }
            }
            if (isPullingUp && !isLanding) { // TODO add powered flight
                player.setPitch((float) (pitch - config.pullUpSpeed * speedMod));
                pitch = player.getPitch();
                if (pitch <= config.pullUpAngle) {
                    player.setPitch((float) config.pullUpAngle);
                }
            }
            if (isPullingDown && !isLanding) {
                player.setPitch((float) (pitch + config.pullDownSpeed * pitchMod * speedMod));
                pitch = player.getPitch();
                if (pitch >= config.pullDownAngle) {
                    player.setPitch((float) config.pullDownAngle);
                }
            }

        } else {
            // Precondition: auto-flight is disabled
            velHigh = 0f;
            velLow = 0f;
            isLanding = false;
            isFlyingTo = false;
            isPullingUp = false;
            pitchMod = 1f;
            isPullingDown = false;
        }
    }

    private void onClientTick() // 20 times a second, before first screen tick
    {
        _tick++;
        double velMod;

        initConfig(); // Backup check

        PlayerEntity player = minecraftClient.player;

        if (player == null) {
            autoFlightEnabled = false;
            isTakingOff = false;
            return;
        }

        if (player.isFallFlying())
            showHud = true;
        else {
            showHud = false;
            autoFlightEnabled = false;
            distanceToGround = -1f;
        }

        double altitude;
        if (autoFlightEnabled) {
            altitude = player.getPos().y;

            if (player.isTouchingWater() || player.isInLava()) {
                isFlyingTo = false;
                isLanding = false;
                autoFlightEnabled = false;
                return;
            }

            if (isDescending) {
                isPullingUp = false;
                isPullingDown = true;
                // If we're over (or nearly over) the max height, allow a faster descent before
                // starting to pull up
                if (altitude > config.maxHeight) { // TODO fix this maybe
                    velHigh = 0.3f;
                } else if (altitude > config.maxHeight - 10) {
                    velLow = 0.28475f;
                }
                velMod = Math.max(velHigh, velLow);
                if (currentVelocity >= config.pullDownMaxVelocity + velMod) {
                    isDescending = false;
                    isPullingDown = false;
                    isPullingUp = true;
                    pitchMod = 1f;
                }
            } else {
                // Precondition: we're not descending
                velHigh = 0f;
                velLow = 0f;
                isPullingUp = true;
                isPullingDown = false;

                // If our velocity is too low or we're nearly too high, start descending
                if (currentVelocity <= config.pullUpMinVelocity || altitude > config.maxHeight - 10) {
                    isDescending = true;
                    isPullingDown = true;
                    isPullingUp = false;
                }
            }
        }

        if (!keybindingWasPressedOnPreviousTick && keyBinding.isPressed()) {
            if (player.isFallFlying()) {
                if (!autoFlightEnabled && distanceToGround < config.minHeight) {
                    player.sendMessage(new TranslatableText("text.elytraautopilot.autoFlightFail.tooLow")
                            .formatted(Formatting.RED), true);
                } else {
                    // If the player is flying an elytra, we start the auto flight
                    autoFlightEnabled = !autoFlightEnabled;
                    if (autoFlightEnabled)
                        isDescending = true;
                }
            } else {
                // Otherwise, we open the settings
                ConfigManager.createAndShowSettings();
            }
        }
        keybindingWasPressedOnPreviousTick = keyBinding.isPressed();

        if (doTakeoffCooldown) {
            if (takeoffCooldown < 5)
                takeoffCooldown++;
            if (takeoffCooldown == 5) {
                takeoffCooldown = 0;
                doTakeoffCooldown = false;
                isTakingOff = true;
            }
        }

        if (isTakingOff) {
            takeoff();
        }

        if (showHud) {
            computeVelocity();

            altitude = player.getPos().y;
            double avgVelocity = 0f;
            double avgHorizontalVelocity = 0f;

            // Update the HUD every 20 ticks
            if (_tick >= 20) {
                _velocityIndex++;
                if (_velocityIndex >= 60)
                    _velocityIndex = 0;
                if (velocityList.size() < 60) {
                    velocityList.add(currentVelocity);
                    velocityListHorizontal.add(currentHorizontalVelocity);
                } else {
                    velocityList.set(_velocityIndex, currentVelocity);
                    velocityListHorizontal.set(_velocityIndex, currentHorizontalVelocity);
                }

                World world = player.world;
                int l = world.getBottomY();
                Vec3d clientPos = player.getPos();
                if (!player.world.isChunkLoaded((int) clientPos.getX(), (int) clientPos.getZ())) {
                    distanceToGround = -1f;
                } else {
                    for (double i = clientPos.getY(); i > l; i--) {
                        BlockPos blockPos = new BlockPos(clientPos.getX(), i, clientPos.getZ());
                        if (world.getBlockState(blockPos).isSolidBlock(world, blockPos)) {
                            distanceToGround = clientPos.getY() - i;
                            break;
                        } else
                            distanceToGround = -1f;
                    }
                }

                _tick = 0;
            }
            if (velocityList.size() >= 5) {
                avgVelocity = velocityList.stream().mapToDouble(val -> val).average().orElse(0.0);
                avgHorizontalVelocity = velocityListHorizontal.stream().mapToDouble(val -> val).average().orElse(0.0);
            }

            // Render the HUD string
            if (hudString == null)
                hudString = new BaseText[9];
            if (!config.showgui || minecraftClient.options.debugEnabled) { // TODO make this more colorful
                hudString[0] = new LiteralText("");
                hudString[1] = new LiteralText("");
                hudString[2] = new LiteralText("");
                hudString[3] = new LiteralText("");
                hudString[4] = new LiteralText("");
                hudString[5] = new LiteralText("");
                hudString[6] = new LiteralText("");
                hudString[7] = new LiteralText("");
                hudString[8] = new LiteralText("");
                return;
            }
            hudString[0] = (BaseText) new TranslatableText("text.elytraautopilot.hud.toggleAutoFlight")
                    .append(new TranslatableText(
                            autoFlightEnabled ? "text.elytraautopilot.hud.true" : "text.elytraautopilot.hud.false"));

            hudString[1] = new TranslatableText("text.elytraautopilot.hud.altitude", String.format("%.2f", altitude));
            hudString[2] = new TranslatableText("text.elytraautopilot.hud.heightFromGround",
                    (distanceToGround == -1f ? "???" : String.format("%.2f", distanceToGround)));

            hudString[3] = new TranslatableText("text.elytraautopilot.hud.speed",
                    String.format("%.2f", currentVelocity * 20));
            if (avgVelocity == 0f) {
                hudString[4] = new TranslatableText("text.elytraautopilot.hud.calculating");
                hudString[5] = new LiteralText("");
            } else {
                hudString[4] = new TranslatableText("text.elytraautopilot.hud.avgSpeed",
                        String.format("%.2f", avgVelocity * 20));
                hudString[5] = new TranslatableText("text.elytraautopilot.hud.avgHSpeed",
                        String.format("%.2f", avgHorizontalVelocity * 20));
            }
            if (isFlyingTo) {
                hudString[6] = new TranslatableText("text.elytraautopilot.flyto", argXpos, argZpos);
                if (distanceToTarget != 0f) {
                    hudString[7] = new TranslatableText("text.elytraautopilot.hud.eta",
                            String.format("%.1f", distanceToTarget / (avgHorizontalVelocity * 20)));
                }
                hudString[8] = (BaseText) new TranslatableText("text.elytraautopilot.hud.autoLand")
                        .append(new TranslatableText(config.autoLanding ? "text.elytraautopilot.hud.enabled"
                                : "text.elytraautopilot.hud.disabled"));
                if (isLanding) {
                    hudString[7] = new TranslatableText("text.elytraautopilot.hud.landing");
                }
            } else {
                hudString[6] = new LiteralText("");
                hudString[7] = new LiteralText("");
                hudString[8] = new LiteralText("");
            }
        } else {
            // Precondition: HUD is hidden
            velocityList.clear();
            velocityListHorizontal.clear();
            previousPosition = null;
        }
    }

    private void computeVelocity() {
        Vec3d newPosition;
        PlayerEntity player = minecraftClient.player;
        if (player != null && !(minecraftClient.isPaused() && minecraftClient.isInSingleplayer())) {
            newPosition = player.getPos();
            if (previousPosition == null)
                previousPosition = newPosition;

            Vec3d difference = new Vec3d(newPosition.x - previousPosition.x, newPosition.y - previousPosition.y,
                    newPosition.z - previousPosition.z);
            Vec3d difference_horizontal = new Vec3d(newPosition.x - previousPosition.x, 0,
                    newPosition.z - previousPosition.z);
            previousPosition = newPosition;

            currentVelocity = difference.length();
            currentHorizontalVelocity = difference_horizontal.length();
        }
    }

    private void initConfig() {
        if (minecraftClient == null) {
            minecraftClient = MinecraftClient.getInstance();
            ConfigManager.register(main, minecraftClient);
            ClientCommands.register(main, config, minecraftClient);
        }
    }

    private void smoothLanding(PlayerEntity player, double speedMod) {
        float yaw = MathHelper.wrapDegrees(player.getYaw());
        player.setYaw((float) (yaw + config.autoLandSpeed * speedMod));
        player.setPitch(30f);
    }

    private void riskyLanding(PlayerEntity player, double speedMod) {
        float pitch = player.getPitch();
        player.setPitch((float) (pitch + config.takeOffPull * speedMod));
        pitch = player.getPitch();
        if (pitch > 90f)
            player.setPitch(90f);
    }

    @Override
    public void onInitializeClient() {
        System.out.println("Client ElytraAutoPilot active");
    }
}
