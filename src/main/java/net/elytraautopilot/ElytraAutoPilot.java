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
    public static ElytraAutoPilot instance;
    private ElytraState state = ElytraState.Inactive;

    public MinecraftClient minecraftClient;

    private static KeyBinding keyBinding;
    private boolean keyBindingWasPressedOnPreviousTick = false;

    public boolean isDescending;
    public boolean isPullingUp;
    public boolean isPullingDown;

    private static final int MAX_TAKEOFF_COUNTDOWN = 5;
    private int takeoffCountdown = 0;
    public boolean shouldFlyToAfterTakeoff = false;

    private double speedModifier = 1f;
    private double pitchModifier = 1f;

    public int argXpos;
    public int argZpos;

    private double distanceToTarget = 0f;

    private int ticksSinceDistanceToGroundUpdate = 0;
    public double distanceToGround = 0f;

    private Vec3d previousPosition;
    private double currentVelocity = 0f;
    private double currentHorizontalVelocity = 0f;

    private static final int MAX_VELOCITIES = 60;
    private int velocityIndex = 0;
    private List<Double> velocityList = new ArrayList<>();
    private List<Double> horizontalVelocityList = new ArrayList<>();
    private double averageVelocity = 0f;
    private double averageHorizontalVelocity = 0f;

    private int ticksSinceHudUpdate = 0;
    public boolean showHud;
    public List<BaseText> hudLines = new ArrayList<>();

    @Override
    public void onInitializeClient() {
        System.out.println("Client ElytraAutoPilot active");
    }

    @Override
    public void onInitialize() {
        ElytraAutoPilot.instance = this;

        keyBinding = new KeyBinding(
                "key.elytraautopilot.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                "text.elytraautopilot.title");
        KeyBindingHelper.registerKeyBinding(keyBinding);

        HudRenderCallback.EVENT.register((matrixStack, tickDelta) -> ElytraAutoPilot.this.onScreenTick());
        ClientTickEvents.END_CLIENT_TICK.register(e -> this.onClientTick());

        if (minecraftClient == null) {
            minecraftClient = MinecraftClient.getInstance();
            ConfigManager.register(main, minecraftClient);
            ClientCommands.register(main, config, minecraftClient);
        }
    }

    /**
     * Update the autopilot state for a given reason.
     */
    public void setState(ElytraState newState, String reason) {
        System.out.println(
                String.format("Changing ElytraAutoPilot state from %s to %s because %s", state, newState, reason));
        state = newState;
    }

    /**
     * Start the takeoff sequence.
     */
    public void startTakeoff() {
        PlayerEntity player = minecraftClient.player;
        if (player == null) {
            return;
        }

        if (state != ElytraState.Inactive) {
            return; // TODO: send message that player cannot takeoff while doing something else
        } else if (!hasElytraEquipped(player)) {
            player.sendMessage(
                    new TranslatableText("text.elytraautopilot.takeoff.error.noElytraEquipped")
                            .formatted(Formatting.RED),
                    true);
        } else if (getElytraDurability(player) == 1) {
            player.sendMessage(
                    new TranslatableText("text.elytraautopilot.takeoff.error.elytraBroken").formatted(Formatting.RED),
                    true);
        } else if (!isHoldingFirework(player)) {
            player.sendMessage(
                    new TranslatableText("text.elytraautopilot.takeoff.error.fireworkRequired")
                            .formatted(Formatting.RED),
                    true);
        } else if (!hasClearSky(player)) {
            player.sendMessage(
                    new TranslatableText("text.elytraautopilot.takeoff.error.clearSkyNeeded").formatted(Formatting.RED),
                    true);
        } else {
            // Immediately jump, then start taking off after a countdown
            minecraftClient.options.keyJump.setPressed(true);
            setState(ElytraState.CountingDownToTakeOff, "started takeoff");
        }
    }

    private boolean hasElytraEquipped(PlayerEntity player) {
        Item chestItem = player.getInventory().armor.get(2).getItem();
        return chestItem.toString().equals("elytra");
    }

    private int getElytraDurability(PlayerEntity player) {
        return player.getInventory().armor.get(2).getMaxDamage()
                - player.getInventory().armor.get(2).getDamage();
    }

    private boolean isHoldingFirework(PlayerEntity player) {
        Item mainHandItem = player.getMainHandStack().getItem();
        Item offHandItem = player.getOffHandStack().getItem();
        return (mainHandItem.toString().equals("firework_rocket")
                || offHandItem.toString().equals("firework_rocket"));
    }

    private boolean hasClearSky(PlayerEntity player) {
        World world = player.world;
        Vec3d clientPos = player.getPos();
        double clientX = clientPos.getX();
        double clientY = clientPos.getY();
        double clientZ = clientPos.getZ();
        double topY = world.getTopY();
        for (double y = clientY; y < topY; y++) {
            BlockPos blockPos = new BlockPos(clientX, y + 2, clientZ);
            if (!world.getBlockState(blockPos).isAir()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Runs once every screen frame.
     */
    private void onScreenTick() {
        if (minecraftClient.isPaused() && minecraftClient.isInSingleplayer()) {
            return;
        }

        PlayerEntity player = minecraftClient.player;
        if (player == null) {
            return;
        }

        if (state != ElytraState.Inactive
                && config.elytraHotswap
                && player.isFallFlying()
                && getElytraDurability(player) < 5) {
            swapElytra(player);
        }

        updateSpeedModifier();
        updateFlightAngleAndDistance(player);
        updateFlightPull(player);
    }

    private void swapElytra(PlayerEntity player) {
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

    private void updateSpeedModifier() {
        float fpsDelta = minecraftClient.getLastFrameDuration();
        float fpsResult = 20 / fpsDelta;
        speedModifier = 60 / fpsResult; // Adapt to 60 FPS base
    }

    private void updateFlightAngleAndDistance(PlayerEntity player) {
        switch (state) {
            case TakingOff:
                updatePitchDuringTakeoff(player);
                break;
            case FlyingToTarget:
                updateYawAndDistanceWhileFlyingToTarget(player);
                if (distanceToTarget < 20) {
                    setState(ElytraState.Landing, "player is close to target");
                }
                break;
            case Landing:
                if (config.autoLanding) {
                    isDescending = true;
                    updatePitchAndYawDuringLanding(player);
                } else {
                    setState(ElytraState.Inactive, "auto landing is disabled");
                }
                break;
            default:
                break;
        }
    }

    private void updatePitchDuringTakeoff(PlayerEntity player) {
        float pitch = player.getPitch();
        if (pitch > -90f) {
            player.setPitch((float) (pitch - config.takeOffPull * speedModifier));
        } else if (pitch <= -90f) {
            player.setPitch(-90f);
        }
    }

    private void updatePitchAndYawDuringLanding(PlayerEntity player) {
        float yaw = MathHelper.wrapDegrees(player.getYaw());
        player.setYaw((float) (yaw + config.landingSpeed * speedModifier));
        player.setPitch(30f);
    }

    private void updateYawAndDistanceWhileFlyingToTarget(PlayerEntity player) {
        Vec3d playerPosition = player.getPos();
        double xDifference = (double) argXpos - playerPosition.x;
        double zDifference = (double) argZpos - playerPosition.z;
        float targetYaw = MathHelper
                .wrapDegrees((float) (MathHelper.atan2(zDifference, xDifference) * 57.2957763671875D) - 90.0F);
        float yaw = MathHelper.wrapDegrees(player.getYaw());
        if (Math.abs(yaw - targetYaw) < config.turningSpeed * 2 * speedModifier) {
            player.setYaw(targetYaw);
        } else if (yaw < targetYaw) {
            player.setYaw((float) (yaw + config.turningSpeed * speedModifier));
        } else if (yaw > targetYaw) {
            player.setYaw((float) (yaw - config.turningSpeed * speedModifier));
        }
        distanceToTarget = Math.sqrt(xDifference * xDifference + zDifference * zDifference);
    }

    private void updateFlightPull(PlayerEntity player) {
        switch (state) {
            case Flying:
            case FlyingToTarget:
                if (isPullingUp) {
                    pullUp(player);
                }
                if (isPullingDown) {
                    pullDown(player);
                }
                break;
            case Landing:
                break;
            default:
                isPullingUp = false;
                isPullingDown = false;
                pitchModifier = 1f;
                break;
        }
    }

    // TODO: add powered flight
    private void pullUp(PlayerEntity player) {
        float pitch = player.getPitch();
        player.setPitch((float) (pitch - config.pullUpSpeed * speedModifier));
        if (player.getPitch() <= config.pullUpAngle) {
            player.setPitch((float) config.pullUpAngle);
        }
    }

    private void pullDown(PlayerEntity player) {
        float pitch = player.getPitch();
        player.setPitch((float) (pitch + config.pullDownSpeed * pitchModifier * speedModifier));
        if (player.getPitch() >= config.pullDownAngle) {
            player.setPitch((float) config.pullDownAngle);
        }
    }

    /**
     * Runs 20 times per second, before the first screen tick.
     */
    private void onClientTick() {
        ticksSinceDistanceToGroundUpdate++;
        ticksSinceHudUpdate++;

        if (minecraftClient.isPaused() && minecraftClient.isInSingleplayer()) {
            return;
        }

        PlayerEntity player = minecraftClient.player;
        if (player == null) {
            return;
        }

        if (player.isFallFlying()) {
            showHud = true;
            if (ticksSinceDistanceToGroundUpdate >= 20) {
                ticksSinceDistanceToGroundUpdate = 0;
                updateDistanceToGround(player);
            }
        } else {
            showHud = false;
            distanceToGround = 0f;
            if (state == ElytraState.Flying || state == ElytraState.FlyingToTarget || state == ElytraState.Landing) {
                setState(ElytraState.Inactive, "player stopped flying");
            }
        }

        updateFlightState(player);

        if (showHud) {
            updateCurrentVelocity(player);
            if (ticksSinceHudUpdate >= 20) {
                ticksSinceHudUpdate = 0;
                updateVelocityList(player);
                updateAverageVelocity();
                updateHudLines(player);
            }
        } else {
            resetVelocityData();
        }

        if (!keyBindingWasPressedOnPreviousTick && keyBinding.isPressed()) {
            handleKeyBindingPress(player);
        }
        keyBindingWasPressedOnPreviousTick = keyBinding.isPressed();
    }

    private void updateDistanceToGround(PlayerEntity player) {
        World world = player.world;
        Vec3d clientPos = player.getPos();
        double clientX = clientPos.getX();
        double clientY = clientPos.getY();
        double clientZ = clientPos.getZ();
        double bottomY = (double) world.getBottomY();

        distanceToGround = -1f;
        if (player.world.isChunkLoaded((int) clientX, (int) clientZ)) {
            for (double y = clientY; y > bottomY; y--) {
                BlockPos blockPos = new BlockPos(clientX, y, clientZ);
                if (world.getBlockState(blockPos).isSolidBlock(world, blockPos)) {
                    distanceToGround = clientY - y;
                    break;
                }
            }
        }
    }

    private void updateFlightState(PlayerEntity player) {
        switch (state) {
            case CountingDownToTakeOff:
                updateTakeoffCountdown();
                break;
            case TakingOff:
                if (distanceToGround > config.minHeight) {
                    endTakeoff(player);
                } else {
                    // Sanity check to ensure that we're still flying while taking off
                    if (!player.isFallFlying()) {
                        minecraftClient.options.keyJump.setPressed(!minecraftClient.options.keyJump.isPressed());
                    }
                    useFireworkDuringTakeoff(player);
                }
                break;
            case Flying:
            case FlyingToTarget:
            case Landing:
                if (player.isTouchingWater() || player.isInLava()) {
                    setState(ElytraState.Inactive, "player touched liquid while flying");
                } else {
                    monitorAltitudeAndVelocity(player);
                }
                break;
            default:
                break;
        }
    }

    private void updateTakeoffCountdown() {
        if (takeoffCountdown < MAX_TAKEOFF_COUNTDOWN) {
            takeoffCountdown++;
        } else {
            takeoffCountdown = 0;
            setState(ElytraState.TakingOff, "finished takeoff countdown");
        }
    }

    private void endTakeoff(PlayerEntity player) {
        minecraftClient.options.keyUse.setPressed(false);
        minecraftClient.options.keyJump.setPressed(false);

        // Allow a faster pitch adjustment immediately after ending takeoff
        pitchModifier = 3f;

        if (shouldFlyToAfterTakeoff) {
            shouldFlyToAfterTakeoff = false;
            minecraftClient.inGameHud.addChatMessage(
                    MessageType.SYSTEM,
                    new TranslatableText("text.elytraautopilot.flyToTarget", argXpos, argZpos)
                            .formatted(Formatting.GREEN),
                    player.getUuid());
            setState(ElytraState.FlyingToTarget, "finished taking off");
        } else {
            setState(ElytraState.Flying, "finished taking off");
        }
    }

    private void useFireworkDuringTakeoff(PlayerEntity player) {
        if (!isHoldingFirework(player)) {
            minecraftClient.options.keyUse.setPressed(false);
            minecraftClient.options.keyJump.setPressed(false);
            player.sendMessage(
                    new TranslatableText("text.elytraautopilot.takeoff.error.aborted").formatted(Formatting.RED),
                    true);
            setState(ElytraState.Inactive, "player does not have firework");
            return;
        }

        // Only use a fiework if the player's velocity is low enough and the player is
        // looking straight up
        minecraftClient.options.keyUse.setPressed(currentVelocity < 0.75f && player.getPitch() == -90f);
    }

    private void monitorAltitudeAndVelocity(PlayerEntity player) {
        double altitude = player.getPos().y;
        if (isDescending) {
            isPullingUp = false;
            isPullingDown = true;

            // If we're too high, allow a faster descent before pulling up
            double velocityModifier = 0f;
            if (altitude > config.maxHeight) {
                velocityModifier = 0.3f;
            } else if (altitude > config.maxHeight - 10) {
                velocityModifier = 0.28475f;
            }

            if (currentVelocity >= config.pullDownMaxVelocity + velocityModifier) {
                isDescending = false;
                isPullingDown = false;
                isPullingUp = true;
                pitchModifier = 1f;
            }
        } else {
            isPullingUp = true;
            isPullingDown = false;

            // If our velocity is too low or we're too high, start descending
            if (currentVelocity <= config.pullUpMinVelocity || altitude > config.maxHeight - 10) {
                isDescending = true;
                isPullingDown = true;
                isPullingUp = false;
            }
        }
    }

    private void updateCurrentVelocity(PlayerEntity player) {
        Vec3d newPosition = player.getPos();

        if (previousPosition == null) {
            previousPosition = newPosition;
        }

        Vec3d difference = new Vec3d(newPosition.x - previousPosition.x, newPosition.y - previousPosition.y,
                newPosition.z - previousPosition.z);
        Vec3d horizontalDifference = new Vec3d(newPosition.x - previousPosition.x, 0,
                newPosition.z - previousPosition.z);

        previousPosition = newPosition;
        currentVelocity = difference.length();
        currentHorizontalVelocity = horizontalDifference.length();
    }

    private void updateVelocityList(PlayerEntity player) {
        if (velocityList.size() < MAX_VELOCITIES) {
            velocityList.add(currentVelocity);
            horizontalVelocityList.add(currentHorizontalVelocity);
        } else {
            velocityList.set(velocityIndex, currentVelocity);
            horizontalVelocityList.set(velocityIndex, currentHorizontalVelocity);
        }
        velocityIndex = (velocityIndex + 1) % MAX_VELOCITIES;
    }

    private void updateAverageVelocity() {
        if (velocityList.size() >= 5) {
            averageVelocity = velocityList.stream().mapToDouble(val -> val).average().orElse(0.0);
            averageHorizontalVelocity = horizontalVelocityList.stream().mapToDouble(val -> val).average().orElse(0.0);
        }
    }

    private void resetVelocityData() {
        previousPosition = null;
        currentVelocity = 0f;
        currentHorizontalVelocity = 0f;

        velocityIndex = 0;
        velocityList.clear();
        horizontalVelocityList.clear();
        averageVelocity = 0f;
        averageHorizontalVelocity = 0f;
    }

    private void updateHudLines(PlayerEntity player) {
        hudLines.clear();

        if (!config.showgui || minecraftClient.options.debugEnabled) {
            return;
        }

        hudLines.add((BaseText) new TranslatableText("text.elytraautopilot.hud.state")
                .append(new LiteralText(state.toString())));

        hudLines.add(new TranslatableText("text.elytraautopilot.hud.altitude",
                String.format("%.2f", player.getPos().y)));

        hudLines.add(new TranslatableText("text.elytraautopilot.hud.heightFromGround",
                (distanceToGround < 0 ? "???" : String.format("%.2f", distanceToGround))));

        hudLines.add(new TranslatableText("text.elytraautopilot.hud.speed",
                String.format("%.2f", currentVelocity * 20)));

        if (averageVelocity == 0f) {
            hudLines.add(new TranslatableText("text.elytraautopilot.hud.calculating"));
        } else {
            hudLines.add(new TranslatableText("text.elytraautopilot.hud.averageSpeed",
                    String.format("%.2f", averageVelocity * 20)));
            hudLines.add(new TranslatableText("text.elytraautopilot.hud.averageHorizontalSpeed",
                    String.format("%.2f", averageHorizontalVelocity * 20)));
        }

        switch (state) {
            case CountingDownToTakeOff:
            case TakingOff:
            case Flying:
            case FlyingToTarget:
                hudLines.add((BaseText) new TranslatableText("text.elytraautopilot.hud.poweredFlight")
                        .append(new TranslatableText(config.poweredFlight ? "text.elytraautopilot.hud.enabled"
                                : "text.elytraautopilot.hud.disabled")));
                hudLines.add((BaseText) new TranslatableText("text.elytraautopilot.hud.elytraHotswap")
                        .append(new TranslatableText(config.elytraHotswap ? "text.elytraautopilot.hud.enabled"
                                : "text.elytraautopilot.hud.disabled")));
                break;
            default:
                break;
        }

        if (state == ElytraState.FlyingToTarget) {
            hudLines.add(new TranslatableText("text.elytraautopilot.flyToTarget", argXpos, argZpos));
            hudLines.add(new TranslatableText("text.elytraautopilot.hud.eta",
                    String.format("%.1f", distanceToTarget / (averageHorizontalVelocity * 20))));
        }
    }

    private void handleKeyBindingPress(PlayerEntity player) {
        if (player.isFallFlying()) {
            if (state == ElytraState.Flying || state == ElytraState.FlyingToTarget || state == ElytraState.Landing) {
                setState(ElytraState.Inactive, "pressed keybind while flying");
            } else if (distanceToGround < config.minHeight) {
                player.sendMessage(new TranslatableText("text.elytraautopilot.fly.error.tooLow")
                        .formatted(Formatting.RED), true);
            } else {
                setState(ElytraState.Flying, "pressed keybind while flying");
                isDescending = true;
            }
        } else {
            ConfigManager.createAndShowSettings();
        }
    }
}
