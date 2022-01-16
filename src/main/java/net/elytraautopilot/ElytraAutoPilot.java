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
import org.lwjgl.system.CallbackI.V;

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

    private double distanceToTarget = 0f;
    public double distanceToGround;

    private Vec3d previousPosition;
    private double currentVelocity;
    private double currentHorizontalVelocity;

    private int velocityIndex = -1;
    private List<Double> velocityList = new ArrayList<>();
    private List<Double> horizontalVelocityList = new ArrayList<>();
    private double averageVelocity = 0f;
    private double averageHorizontalVelocity = 0f;

    public BaseText[] hudString;

    @Override
    public void onInitializeClient() {
        System.out.println("Client ElytraAutoPilot active");
    }

    @Override
    public void onInitialize() {
        keyBinding = new KeyBinding(
                "key.elytraautopilot.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                "text.elytraautopilot.title");
        KeyBindingHelper.registerKeyBinding(keyBinding);
        keybindingWasPressedOnPreviousTick = false;

        HudRenderCallback.EVENT.register((matrixStack, tickDelta) -> ElytraAutoPilot.this.onScreenTick());
        ClientTickEvents.END_CLIENT_TICK.register(e -> this.onClientTick());

        ElytraAutoPilot.instance = this;
        initConfig();
    }

    private void initConfig() {
        if (minecraftClient == null) {
            minecraftClient = MinecraftClient.getInstance();
            ConfigManager.register(main, minecraftClient);
            ClientCommands.register(main, config, minecraftClient);
        }
    }

    /**
     * Start the takeoff sequence.
     */
    public void startTakeoff() {
        PlayerEntity player = minecraftClient.player;
        if (player == null) {
            return;
        }

        if (isTakingOff) {
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

        // Initiate a cooldown so the player has enough time to jump
        minecraftClient.options.keyJump.setPressed(true);
        doTakeoffCooldown = true;
    }

    /**
     * End the takeoff sequence and transition to auto-flight.
     *
     * Optionally, also start flying to a target.
     */
    private void endTakeoff(PlayerEntity player) {
        if (!isTakingOff) {
            return;
        }

        isTakingOff = false;
        autoFlightEnabled = true;

        minecraftClient.options.keyUse.setPressed(false);
        minecraftClient.options.keyJump.setPressed(false);

        // TODO: why do we adjust pitch here?
        pitchMod = 3f;

        if (shouldFlyToAfterTakeoff) {
            shouldFlyToAfterTakeoff = false;
            isFlyingTo = true;
            minecraftClient.inGameHud.addChatMessage(
                    MessageType.SYSTEM,
                    new TranslatableText("text.elytraautopilot.flyto", argXpos, argZpos)
                            .formatted(Formatting.GREEN),
                    player.getUuid());
        }
    }

    /**
     * Use a firework rocket during the takeoff sequence.
     *
     * If the player is not holding a firework, abort the takeoff sequence.
     */
    private void useFireworkDuringTakeoff(PlayerEntity player) {
        if (!isTakingOff) {
            return;
        }

        Item mainHandItem = player.getMainHandStack().getItem();
        Item offHandItem = player.getOffHandStack().getItem();
        boolean isHoldingFirework = (mainHandItem.toString().equals("firework_rocket")
                || offHandItem.toString().equals("firework_rocket"));
        if (!isHoldingFirework) {
            isTakingOff = false;
            minecraftClient.options.keyUse.setPressed(false);
            minecraftClient.options.keyJump.setPressed(false);
            player.sendMessage(
                    new TranslatableText("text.elytraautopilot.takeoffAbort.noFirework").formatted(Formatting.RED),
                    true);
            return;
        }

        // Only use a fiework if the player's velocity is low enough and the player is
        // looking straight up
        minecraftClient.options.keyUse.setPressed(currentVelocity < 0.75f && player.getPitch() == -90f);
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

    /**
     * Runs 20 times per second, before the first screen tick.
     */
    private void onClientTick() {
        _tick++;

        // TODO: do we need this?
        initConfig(); // Backup check

        PlayerEntity player = minecraftClient.player;
        if (player == null) {
            // TODO: do we need these?
            autoFlightEnabled = false;
            isTakingOff = false;
            return;
        }

        if (player.isFallFlying()) {
            showHud = true;
        } else {
            showHud = false;
            autoFlightEnabled = false;
            distanceToGround = -1f;
        }

        updateAutoFlightDuringClientTick(player);
        handleKeybindingDuringClientTick(player);
        updateTakeoffDuringClientTick(player);

        if (showHud) {
            updateCurrentVelocity();
            if (_tick >= 20) {
                _tick = 0;
                updateVelocityList(player);
                updateAverageVelocity();
                updateDistanceToGround(player);
                updateHudString(player);
            }
        } else {
            resetHudMetrics();
        }
    }

    /**
     * Update auto-flight during a client tick.
     */
    private void updateAutoFlightDuringClientTick(PlayerEntity player) {
        if (!autoFlightEnabled) {
            return;
        }

        // TODO: move this into main client tick?
        if (player.isTouchingWater() || player.isInLava()) {
            isFlyingTo = false;
            isLanding = false;
            autoFlightEnabled = false;
            return;
        }

        double altitude = player.getPos().y;
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
            double velMod = Math.max(velHigh, velLow);
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

    /**
     * Update the takeoff sequence during a client tick.
     */
    private void updateTakeoffDuringClientTick(PlayerEntity player) {
        // This cooldown gives the player time to jump before we start taking off
        if (doTakeoffCooldown) {
            if (takeoffCooldown < 5) {
                takeoffCooldown++;
            } else {
                takeoffCooldown = 0;
                doTakeoffCooldown = false;
                isTakingOff = true;
            }
        }

        if (!isTakingOff) {
            return;
        }

        if (distanceToGround > config.minHeight) {
            endTakeoff(player);
            return;
        }

        // TODO: do we need this?
        if (!player.isFallFlying()) {
            minecraftClient.options.keyJump.setPressed(!minecraftClient.options.keyJump.isPressed());
        }

        useFireworkDuringTakeoff(player);
    }

    /**
     * Handle the keybinding during a client tick.
     *
     * If the player presses the keybinding while flying, we toggle auto-flight.
     * Otherwise, we show the configuration menu.
     */
    private void handleKeybindingDuringClientTick(PlayerEntity player) {
        if (!keybindingWasPressedOnPreviousTick && keyBinding.isPressed()) {
            if (player.isFallFlying()) {
                if (autoFlightEnabled) {
                    autoFlightEnabled = false;
                } else if (distanceToGround < config.minHeight) {
                    player.sendMessage(new TranslatableText("text.elytraautopilot.autoFlightFail.tooLow")
                            .formatted(Formatting.RED), true);
                } else {
                    autoFlightEnabled = true;
                    isDescending = true;
                }
            } else {
                ConfigManager.createAndShowSettings();
            }
        }
        keybindingWasPressedOnPreviousTick = keyBinding.isPressed();
    }

    /**
     * Update the player's current velocity.
     */
    private void updateCurrentVelocity() {
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

    /**
     * Update the list of player velocities.
     */
    private void updateVelocityList(PlayerEntity player) {
        velocityIndex = (velocityIndex + 1) % 60;
        if (velocityList.size() < 60) {
            velocityList.add(currentVelocity);
            horizontalVelocityList.add(currentHorizontalVelocity);
        } else {
            velocityList.set(velocityIndex, currentVelocity);
            horizontalVelocityList.set(velocityIndex, currentHorizontalVelocity);
        }
    }

    /**
     * Update the player's average velocity.
     */
    private void updateAverageVelocity() {
        if (velocityList.size() >= 5) {
            averageVelocity = velocityList.stream().mapToDouble(val -> val).average().orElse(0.0);
            averageHorizontalVelocity = horizontalVelocityList.stream().mapToDouble(val -> val).average().orElse(0.0);
        }
    }

    /**
     * Update the player's distance to the ground.
     */
    private void updateDistanceToGround(PlayerEntity player) {
        World world = player.world;
        Vec3d clientPos = player.getPos();
        double clientY = clientPos.getY();
        double bottomY = (double) world.getBottomY();
        if (!player.world.isChunkLoaded((int) clientPos.getX(), (int) clientPos.getZ())) {
            distanceToGround = -1f;
        } else {
            for (double y = clientY; y > bottomY; y--) {
                BlockPos blockPos = new BlockPos(clientPos.getX(), y, clientPos.getZ());
                if (world.getBlockState(blockPos).isSolidBlock(world, blockPos)) {
                    distanceToGround = clientY - y;
                    return;
                } else {
                    // TODO: refactor this to be less weird?
                    distanceToGround = -1f;
                }
            }
        }
    }

    /**
     * Update the HUD string.
     */
    private void updateHudString(PlayerEntity player) {
        if (hudString == null) {
            hudString = new BaseText[9];
        }

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

        hudString[1] = new TranslatableText("text.elytraautopilot.hud.altitude",
                String.format("%.2f", player.getPos().y));

        hudString[2] = new TranslatableText("text.elytraautopilot.hud.heightFromGround",
                (distanceToGround == -1f ? "???" : String.format("%.2f", distanceToGround)));

        hudString[3] = new TranslatableText("text.elytraautopilot.hud.speed",
                String.format("%.2f", currentVelocity * 20));

        if (averageVelocity == 0f) {
            hudString[4] = new TranslatableText("text.elytraautopilot.hud.calculating");
            hudString[5] = new LiteralText("");
        } else {
            hudString[4] = new TranslatableText("text.elytraautopilot.hud.avgSpeed",
                    String.format("%.2f", averageVelocity * 20));
            hudString[5] = new TranslatableText("text.elytraautopilot.hud.avgHSpeed",
                    String.format("%.2f", averageHorizontalVelocity * 20));
        }

        if (isFlyingTo) {
            hudString[6] = new TranslatableText("text.elytraautopilot.flyto", argXpos, argZpos);
            if (distanceToTarget != 0f) {
                hudString[7] = new TranslatableText("text.elytraautopilot.hud.eta",
                        String.format("%.1f", distanceToTarget / (averageHorizontalVelocity * 20)));
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
    }

    /**
     * Reset all metric data used in the HUD.
     */
    private void resetHudMetrics() {
        velocityList.clear();
        horizontalVelocityList.clear();
        previousPosition = null;
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

}
