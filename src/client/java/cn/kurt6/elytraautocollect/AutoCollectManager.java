package cn.kurt6.elytraautocollect;

import cn.kurt6.elytraautocollect.managers.*;
import cn.kurt6.elytraautocollect.utils.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class AutoCollectManager {
    private static final AutoCollectManager INSTANCE = new AutoCollectManager();

    private final AtomicBoolean isActive = new AtomicBoolean(false);
    private volatile FlightState flightState = FlightState.CRUISING;
    private volatile Vec3 targetPosition = null;
    private volatile Vec3 shipApproachPosition = null;
    private volatile Vec3 shipFinalApproachPosition = null;
    private String currentDimension = "";

    final FlightManager flightManager = new FlightManager();
    private final ShipScanner shipScanner = new ShipScanner();
    private final AtomicInteger shipsFound = new AtomicInteger(0);

    private volatile boolean landingMode = false;
    private volatile Vec3 landingTarget = null;
    private volatile Vec3 landingApproachPosition = null;
    private volatile boolean elytraFound = false;

    private volatile boolean lowDurabilityDetected = false;
    private volatile boolean lowFireworksDetected = false;
    private volatile Vec3 purpurTargetPosition = null;
    private volatile boolean takeoffSuccessAnnounced = false;
    private long lastHudUpdateAt = 0;
    private long lastScanAt = 0;
    private long lastCleanupAt = 0;

    public static AutoCollectManager getInstance() { return INSTANCE; }

    private Vec3 getPlayerPosition(LocalPlayer player) {
        return new Vec3(player.getX(), player.getY(), player.getZ());
    }

    private static void sendClientMessage(LocalPlayer player, Component message, boolean actionBar) {
        if (player == null) return;
        if (actionBar) player.sendOverlayMessage(message);
        else player.sendSystemMessage(message);
    }

    public void toggle() {
        boolean wasActive = isActive.getAndSet(!isActive.get());
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            sendClientMessage(client.player, Component.translatable(isActive.get() ? "msg.elytraautocollect.toggle.on" :
                    "msg.elytraautocollect.toggle.off"), true);
            if (isActive.get()) {
                sendClientMessage(client.player, Component.translatable("msg.elytraautocollect.session.start"), false);
                validateConfiguration(client);
            } else {
                sendClientMessage(client.player, Component.translatable("msg.elytraautocollect.session.end", shipsFound.get()), false);
            }
        }
        if (!isActive.get()) {
            resetState();
            flightManager.stopMovement(client);
        }
    }

    private void validateConfiguration(Minecraft client) {
        ModConfig config = ModConfig.getInstance();
        sendClientMessage(client.player, Component.translatable("msg.elytraautocollect.config.header"), false);
        int renderDistance = client.options != null ? client.options.renderDistance().get() : 12;
        int scanRadius = config.getEffectiveScanRadius();
        sendClientMessage(client.player, Component.translatable("msg.elytraautocollect.config.render", renderDistance, scanRadius), false);
    }

    private void resetState() {
        flightState = FlightState.CRUISING;
        targetPosition = null;
        shipApproachPosition = null;
        shipFinalApproachPosition = null;
        landingMode = false;
        landingTarget = null;
        landingApproachPosition = null;
        elytraFound = false;
        lowDurabilityDetected = false;
        lowFireworksDetected = false;
        purpurTargetPosition = null;
        takeoffSuccessAnnounced = false;
    }

    public void tick(Minecraft client) {
        if (!isActive.get() || client.player == null || client.level == null) return;

        String nowDim = client.level.dimension().identifier().toString();
        if (!nowDim.equals(currentDimension)) {
            currentDimension = nowDim;
            flightManager.discardWaypoint();
        }

        long now = System.currentTimeMillis();
        if (client.player != null && now - lastHudUpdateAt >= 1000) {
            lastHudUpdateAt = now;
            String key = switch (flightState) {
                case TAKING_OFF -> "msg.elytraautocollect.state.takeoff";
                case CRUISING -> "msg.elytraautocollect.state.cruise";
                case APPROACHING_SHIP -> "msg.elytraautocollect.state.approach.ship";
                case APPROACHING_PURPUR -> "msg.elytraautocollect.state.approach.purpur";
            };
            sendClientMessage(client.player, Component.translatable(key, shipsFound.get()), true);
        }
        LocalPlayer player = client.player;
        if (elytraFound && !landingMode) {
            initiateSafeLanding(client, player, Component.translatable("msg.elytraautocollect.landing.reason.found"), false, false);
            return;
        }
        if (!performSafetyChecks(client, player)) { toggle(); return; }
        if (landingMode) { handleSafeLanding(client, player); return; }
        handleTakeoffLogic(client, player);
        handleCurrentState(client);
        if (now - lastCleanupAt >= 30000) {
            lastCleanupAt = now;
            shipScanner.cleanup();
        }
    }

    private boolean performSafetyChecks(Minecraft client, LocalPlayer player) {
        if (!flightManager.tryEquipElytra(player)) {
            sendClientMessage(client.player, Component.translatable("msg.elytraautocollect.safety.noelytra"), false);
            return false;
        }
        if (player.getHealth() < 6.0f) {
            sendClientMessage(client.player, Component.translatable("msg.elytraautocollect.safety.health"), false);
            return false;
        }
        if (isElytraDurabilityLow(player)) {
            if (!lowDurabilityDetected) {
                if (tryReplaceElytra(client, player)) {
                    sendClientMessage(client.player, Component.translatable("msg.elytraautocollect.safety.elytra.swap"), false);
                    lowDurabilityDetected = false;
                } else {
                    int percent = getElytraDurabilityPercent(player);
                    sendClientMessage(client.player, Component.translatable("msg.elytraautocollect.safety.elytra.low", percent), false);
                    lowDurabilityDetected = true;
                    initiateSafeLanding(client, player, Component.translatable("msg.elytraautocollect.landing.reason.dura"), true, true);
                    return true;
                }
            }
        }
        if (!hasEnoughFireworks(player)) {
            if (!lowFireworksDetected) {
                int count = getFireworkCount(player);
                sendClientMessage(client.player, Component.translatable("msg.elytraautocollect.safety.fireworks.low", count), false);
                lowFireworksDetected = true;
                initiateSafeLanding(client, player, Component.translatable("msg.elytraautocollect.landing.reason.fireworks"), true, true);
                return true;
            }
        }
        return true;
    }

    private boolean tryReplaceElytra(Minecraft client, LocalPlayer player) {
        ItemStack current = player.getItemBySlot(EquipmentSlot.CHEST);
        int currentDura = current.getMaxDamage() - current.getDamageValue();
        int bestSlot = -1, bestDura = currentDura;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack != null && stack.getItem() == Items.ELYTRA) {
                int d = stack.getMaxDamage() - stack.getDamageValue();
                if (d > bestDura + 20) { bestDura = d; bestSlot = i; }
            }
        }
        if (bestSlot != -1) {
            ItemStack better = player.getInventory().getItem(bestSlot);
            player.getInventory().setItem(bestSlot, current);
            player.setItemSlot(EquipmentSlot.CHEST, better);
            return true;
        }
        return false;
    }

    private boolean isElytraDurabilityLow(LocalPlayer player) {
        ItemStack stack = player.getItemBySlot(EquipmentSlot.CHEST);
        if (stack == null || stack.getItem() != Items.ELYTRA) return false;
        int max = stack.getMaxDamage(), dmg = stack.getDamageValue();
        return (double) dmg / max > 0.85 || (max - dmg) < 50;
    }
    private int getElytraDurabilityPercent(LocalPlayer player) {
        ItemStack stack = player.getItemBySlot(EquipmentSlot.CHEST);
        if (stack == null || stack.getItem() != Items.ELYTRA) return 100;
        return 100 - (int) ((double) stack.getDamageValue() / stack.getMaxDamage() * 100);
    }
    private boolean hasEnoughFireworks(LocalPlayer player) { return getFireworkCount(player) >= 5; }
    private int getFireworkCount(LocalPlayer player) {
        int c = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (s != null && s.getItem() == Items.FIREWORK_ROCKET) c += s.getCount();
        }
        return c;
    }

    private void initiateSafeLanding(Minecraft client, LocalPlayer player, Component reason, boolean announceStart, boolean announceLandingSpot) {
        landingMode = true;
        calculateSafeLandingPosition(client, player, announceLandingSpot);
        if (announceStart) sendClientMessage(client.player, Component.translatable("msg.elytraautocollect.landing.head").append(reason), false);
    }

    private void calculateSafeLandingPosition(Minecraft client, LocalPlayer player, boolean announceLandingSpot) {
        Vec3 playerPos = getPlayerPosition(player);
        Level world = client.level;
        ModConfig config = ModConfig.getInstance();
        Vec3 best = null;
        double bestScore = -1;
        landingApproachPosition = null;

        for (int r = 50; r <= 300; r += 50) {
            for (int ang = 0; ang < 360; ang += 20) {
                double rad = Math.toRadians(ang), x = playerPos.x + Math.cos(rad) * r, z = playerPos.z + Math.sin(rad) * r;
                BlockPos g = findSafeGroundLevel(world, new BlockPos((int) x, (int) playerPos.y, (int) z));
                if (g != null) {
                    Vec3 lp = new Vec3(x, g.getY() + 2, z);
                    double sc = evaluateLandingSpotImproved(world, lp, playerPos, config);
                    if (sc > bestScore) { bestScore = sc; best = lp; }
                }
            }
            if (bestScore > 0.8) break;
        }
        if (best != null) {
            landingTarget = best;
            if (announceLandingSpot) sendClientMessage(client.player, Component.translatable("msg.elytraautocollect.landing.found"), false);
        } else {
            landingTarget = findEmergencyLandingSpot(world, playerPos);
            if (announceLandingSpot) sendClientMessage(client.player, Component.translatable("msg.elytraautocollect.landing.emergency"), false);
        }
    }

    private double evaluateLandingSpotImproved(Level world, Vec3 lp, Vec3 pp, ModConfig cfg) {
        double sc = 1.0; BlockPos bp = new BlockPos((int) lp.x, (int) lp.y, (int) lp.z);
        try {
            double dist = pp.distanceTo(lp);
            sc *= (dist < 30 ? 0.3 : dist < 100 ? 1.0 : dist < 200 ? 0.8 : 0.4);
            if (lp.y < cfg.minSafeHeight) sc *= 0.2;
            else if (lp.y < cfg.minSafeHeight + 20) sc *= 0.6;
            int pur = countPurpurBlocksNearby(world, bp, 8);
            if (pur > 0) sc *= Math.max(0.1, 1 - pur * 0.2);
            if (pur > 5) sc *= 0.05;
            int danger = 0, air = 0, solid = 0;
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                BlockPos cp = bp.offset(x, 0, z); Block b = world.getBlockState(cp).getBlock();
                if (b == Blocks.LAVA || b == Blocks.FIRE) danger += 5;
                else if (b == Blocks.WATER) danger += 1;
                else if (b == Blocks.VOID_AIR) danger += 10;
                else if (isPurpurBlock(world, cp)) danger += 2;
                if (world.getBlockState(cp.above()).isAir() && world.getBlockState(cp.above(2)).isAir()) air++;
                if (!world.getBlockState(cp).isAir() && b != Blocks.LAVA && b != Blocks.WATER && b != Blocks.VOID_AIR) solid++;
            }
            sc *= Math.max(0.1, 1 - danger * 0.05);
            sc *= Math.min(1.0, air / 25.0 + 0.3);
            sc *= Math.min(1.0, solid / 25.0 + 0.5);
            int flat = calculateFlatnessScore(world, bp);
            sc *= (0.5 + flat / 20.0);
            Block cb = world.getBlockState(bp).getBlock();
            if (cb == Blocks.GRASS_BLOCK || cb == Blocks.DIRT) sc *= 1.2;
            else if (cb == Blocks.STONE || cb == Blocks.COBBLESTONE) sc *= 1.1;
            else if (cb == Blocks.SAND || cb == Blocks.GRAVEL) sc *= 0.9;
            return Math.max(0, Math.min(1, sc));
        } catch (Exception e) { return 0; }
    }

    private int countPurpurBlocksNearby(Level world, BlockPos c, int r) {
        int cnt = 0;
        for (int x = -r; x <= r; x++) for (int y = -r / 2; y <= r / 2; y++) for (int z = -r; z <= r; z++)
            if (isPurpurBlock(world, c.offset(x, y, z))) cnt++;
        return cnt;
    }
    private boolean isPurpurBlock(Level world, BlockPos p) {
        try { Block b = world.getBlockState(p).getBlock();
            return b == Blocks.PURPUR_BLOCK || b == Blocks.PURPUR_PILLAR || b == Blocks.PURPUR_STAIRS || b == Blocks.PURPUR_SLAB;
        } catch (Exception e) { return false; }
    }
    private int calculateFlatnessScore(Level world, BlockPos c) {
        int flat = 0, cy = c.getY();
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            BlockPos g = findSafeGroundLevel(world, c.offset(x, 0, z));
            if (g != null && Math.abs(g.getY() - cy) <= 1) flat++;
        }
        return flat;
    }
    private Vec3 findEmergencyLandingSpot(Level world, Vec3 pp) {
        BlockPos pb = new BlockPos((int) pp.x, (int) pp.y, (int) pp.z);
        for (int y = pb.getY(); y >= 0; y--) {
            BlockPos cp = new BlockPos(pb.getX(), y, pb.getZ());
            if (isSafeEmergencyLanding(world, cp)) return new Vec3(pp.x, y + 2, pp.z);
        }
        return new Vec3(pp.x, Math.max(80, pp.y), pp.z);
    }
    private boolean isSafeEmergencyLanding(Level world, BlockPos p) {
        try { Block b = world.getBlockState(p).getBlock();
            if (b == Blocks.LAVA || b == Blocks.FIRE || b == Blocks.VOID_AIR) return false;
            if (isPurpurBlock(world, p)) return false;
            if (world.getBlockState(p).isAir()) return false;
            return world.getBlockState(p.above()).isAir() && world.getBlockState(p.above(2)).isAir();
        } catch (Exception e) { return false; }
    }

    private void handleSafeLanding(Minecraft client, LocalPlayer player) {
        if (landingTarget == null) return;
        if (player.onGround()) { completeSafeLanding(client); return; }
        Vec3 playerPos = getPlayerPosition(player);
        double dist = playerPos.distanceTo(landingTarget);
        Vec3 navigationTarget = getLandingNavigationTarget(client, playerPos);
        if (dist > 50) flightManager.flyTowardsWithSpeed(client, navigationTarget, 0.75);
        else if (dist > 20) improvedFlyTowardsLanding(client, navigationTarget, 0.55);
        else improvedFlyTowardsLanding(client, navigationTarget, 0.3);
    }

    private void improvedFlyTowardsLanding(Minecraft client, Vec3 target, double speed) {
        LocalPlayer p = client.player;
        Vec3 pp = getPlayerPosition(p);
        Vec3 dir = target.subtract(pp);
        if (dir.lengthSqr() < 0.01) return;
        double dist = pp.distanceTo(target);
        if (dist > 8.0) dir = flightManager.avoidObstacles(client, dir.normalize(), Math.max(8.0, Math.min(14.0, dist * 0.45)));
        else dir = dir.normalize();
        float yaw = (float) (Math.atan2(dir.z, dir.x) * 180 / Math.PI) - 90;
        float pitch; double hd = pp.y - target.y;
        if (hd > 50) pitch = Math.min(25, (float) (hd / 3));
        else if (hd > 20) pitch = 15;
        else if (hd > 5) pitch = 8;
        else pitch = 2;
        float avoidancePitch = (float) Math.toDegrees(Math.asin(-dir.y));
        if (avoidancePitch < pitch) pitch = avoidancePitch;
        if (!flightManager.isPathMostlyLoaded(client, pp, dir, Math.max(8.0, Math.min(14.0, dist)))) pitch = Math.min(pitch, 4.0f);
        pitch = Math.max(-25f, Math.min(25f, pitch));
        p.setYRot(yaw); p.setXRot(pitch);
        if (client.options != null) {
            if (dist > 10) client.options.keyUp.setDown(true);
            else client.options.keyUp.setDown((System.currentTimeMillis() / 300) % 2 == 0);
        }
    }

    private Vec3 getLandingNavigationTarget(Minecraft client, Vec3 playerPos) {
        if (landingTarget == null) return playerPos;
        double dist = playerPos.distanceTo(landingTarget);
        if (dist <= 18.0) {
            landingApproachPosition = null;
            return landingTarget;
        }
        boolean directDescentSafe = isLandingCorridorSafe(client, playerPos, landingTarget, dist);
        if (landingApproachPosition == null || playerPos.distanceTo(landingApproachPosition) < 12.0) {
            landingApproachPosition = buildLandingApproachPoint(playerPos, landingTarget);
        }
        return (!directDescentSafe || dist > 36.0) ? landingApproachPosition : landingTarget;
    }

    private boolean isLandingCorridorSafe(Minecraft client, Vec3 playerPos, Vec3 target, double dist) {
        Vec3 dir = target.subtract(playerPos);
        if (dir.lengthSqr() < 0.01) return true;
        Vec3 normalized = dir.normalize();
        double sampleDistance = Math.max(8.0, Math.min(18.0, dist));
        double forwardClearance = flightManager.measurePathClearance(client, playerPos, normalized, sampleDistance);
        Vec3 descentDirection = normalized.add(0.0, -0.3, 0.0).normalize();
        double descentClearance = flightManager.measurePathClearance(client, playerPos, descentDirection, Math.max(6.0, sampleDistance * 0.8));
        return flightManager.isPathMostlyLoaded(client, playerPos, normalized, sampleDistance)
                && forwardClearance >= sampleDistance * 0.72
                && descentClearance >= Math.max(5.0, sampleDistance * 0.45);
    }

    private Vec3 buildLandingApproachPoint(Vec3 playerPos, Vec3 target) {
        Vec3 horizontal = new Vec3(playerPos.x - target.x, 0.0, playerPos.z - target.z);
        if (horizontal.lengthSqr() < 0.01) horizontal = new Vec3(1.0, 0.0, 0.0);
        horizontal = horizontal.normalize();
        double backoff = clamp(playerPos.distanceTo(target) * 0.35, 12.0, 26.0);
        Vec3 stage = target.add(horizontal.scale(backoff));
        double stageY = Math.max(target.y + 6.0, Math.min(playerPos.y + 4.0, target.y + 18.0));
        return new Vec3(stage.x, stageY, stage.z);
    }

    private void completeSafeLanding(Minecraft client) {
        flightManager.stopMovement(client);
        if (client.player != null) {
            if (lowDurabilityDetected) sendClientMessage(client.player, Component.translatable("msg.elytraautocollect.landing.done.dura"), false);
            else if (lowFireworksDetected) sendClientMessage(client.player, Component.translatable("msg.elytraautocollect.landing.done.fireworks"), false);
            else sendClientMessage(client.player, Component.translatable("msg.elytraautocollect.landing.done.success"), false);
        }
        if (isActive.get()) toggle();
    }

    private BlockPos findSafeGroundLevel(Level world, BlockPos sp) {
        for (int y = sp.getY(); y >= 0; y--) {
            BlockPos cp = new BlockPos(sp.getX(), y, sp.getZ());
            try { Block b = world.getBlockState(cp).getBlock();
                if (!world.getBlockState(cp).isAir() && b != Blocks.VOID_AIR && b != Blocks.LAVA && b != Blocks.WATER && !isPurpurBlock(world, cp))
                    if (world.getBlockState(cp.above()).isAir() && world.getBlockState(cp.above(2)).isAir()) return cp;
            } catch (Exception e) { continue; }
        }
        return null;
    }

    private void handleTakeoffLogic(Minecraft client, LocalPlayer player) {
        if (!flightManager.isPlayerElytraFlying(player) && flightState != FlightState.TAKING_OFF)
            flightState = FlightState.TAKING_OFF;
    }

    private void handleCurrentState(Minecraft client) {
        switch (flightState) {
            case TAKING_OFF -> handleTakeoff(client);
            case CRUISING -> handleCruising(client);
            case APPROACHING_SHIP -> handleApproachShip(client);
            case APPROACHING_PURPUR -> handleApproachPurpur(client);
        }
    }

    private void handleTakeoff(Minecraft client) {
        if (flightManager.takeOff(client)) {
            flightState = FlightState.CRUISING;
            if (!takeoffSuccessAnnounced && client.player != null) {
                sendClientMessage(client.player, Component.translatable("msg.elytraautocollect.takeoff.success"), false);
                takeoffSuccessAnnounced = true;
            }
        }
    }

    private void handleCruising(Minecraft client) {
        LocalPlayer p = client.player; ModConfig cfg = ModConfig.getInstance();
        Vec3 playerPos = getPlayerPosition(p);
        if (flightManager.getCurrentWaypoint() == null || playerPos.distanceTo(flightManager.getCurrentWaypoint()) < 50)
            flightManager.generateNextWaypoint(p, cfg);
        flightManager.flyTowardsWithSpeed(client, flightManager.getCurrentWaypoint(), cfg.cruiseSpeed);
        long now = System.currentTimeMillis();
        if (now - lastScanAt >= 1500) {
            lastScanAt = now;
            shipScanner.scanForEndShipsAsync(client);
        }
    }

    private void handleApproachShip(Minecraft client) {
        if (targetPosition == null) { flightState = FlightState.CRUISING; return; }
        LocalPlayer p = client.player;
        Vec3 playerPos = getPlayerPosition(p);
        if (playerPos.distanceTo(targetPosition) < 30.0) {
            shipApproachPosition = null;
            shipFinalApproachPosition = null;
            elytraFound = true;
            if (client.player != null) sendClientMessage(client.player, Component.translatable("msg.elytraautocollect.ship.found"), false);
            initiateSafeLanding(client, p, Component.translatable("msg.elytraautocollect.landing.reason.found"), false, false);
        } else {
            Vec3 navigationTarget = getShipNavigationTarget(client, playerPos);
            double speed = navigationTarget == targetPosition ? ModConfig.getInstance().approachSpeed : Math.max(0.65, ModConfig.getInstance().approachSpeed + 0.15);
            flightManager.flyTowardsWithSpeed(client, navigationTarget, speed);
        }
    }

    private Vec3 getShipNavigationTarget(Minecraft client, Vec3 playerPos) {
        double dist = playerPos.distanceTo(targetPosition);
        if (dist <= 26.0 && isShipDirectApproachSafe(client, playerPos, targetPosition, dist)) {
            shipApproachPosition = null;
            shipFinalApproachPosition = null;
            return targetPosition;
        }
        if (dist <= 60.0) {
            if (shipFinalApproachPosition == null || playerPos.distanceTo(shipFinalApproachPosition) < 10.0
                    || !isShipWaypointStillGood(client, playerPos, shipFinalApproachPosition, true)) {
                shipFinalApproachPosition = selectShipApproachPoint(client, playerPos, true);
            }
            if (shipFinalApproachPosition != null) return shipFinalApproachPosition;
        } else {
            shipFinalApproachPosition = null;
        }

        if (shipApproachPosition == null || playerPos.distanceTo(shipApproachPosition) < 14.0
                || !isShipWaypointStillGood(client, playerPos, shipApproachPosition, false)) {
            shipApproachPosition = selectShipApproachPoint(client, playerPos, false);
        }
        if (isShipDirectApproachSafe(client, playerPos, targetPosition, dist) && dist <= 72.0) return targetPosition;
        return shipApproachPosition != null ? shipApproachPosition : targetPosition;
    }

    private boolean isShipDirectApproachSafe(Minecraft client, Vec3 playerPos, Vec3 target, double dist) {
        Vec3 dir = target.subtract(playerPos);
        if (dir.lengthSqr() < 0.01) return true;
        Vec3 normalized = dir.normalize();
        double sampleDistance = Math.max(12.0, Math.min(26.0, dist));
        double clearance = flightManager.measurePathClearance(client, playerPos, normalized, sampleDistance);
        double corridorRisk = estimatePurpurRiskAlongPath(client.level, playerPos, target, Math.max(4.0, sampleDistance * 0.35), 4.0);
        return flightManager.isPathMostlyLoaded(client, playerPos, normalized, sampleDistance)
                && clearance >= sampleDistance * 0.82
                && corridorRisk <= 1.6;
    }

    private Vec3 selectShipApproachPoint(Minecraft client, Vec3 playerPos, boolean finalPhase) {
        Level world = client.level;
        if (world == null || targetPosition == null) return null;

        Vec3 horizontal = new Vec3(playerPos.x - targetPosition.x, 0.0, playerPos.z - targetPosition.z);
        if (horizontal.lengthSqr() < 0.01) {
            int sign = getStableSideSign(targetPosition);
            horizontal = new Vec3(sign, 0.0, 1.0);
        }
        horizontal = horizontal.normalize();

        double[] radii = finalPhase ? new double[]{14.0, 18.0, 22.0} : new double[]{22.0, 28.0, 34.0, 40.0};
        double[] heights = finalPhase ? new double[]{10.0, 14.0, 18.0} : new double[]{14.0, 18.0, 24.0};
        int[] angles = finalPhase ? new int[]{0, 28, -28, 52, -52, 76, -76} : new int[]{0, 32, -32, 58, -58, 88, -88, 128, -128};

        Vec3 best = null;
        double bestScore = -1_000_000.0;
        for (double radius : radii) {
            for (double height : heights) {
                for (int angle : angles) {
                    Vec3 horizontalDir = horizontal.yRot((float) Math.toRadians(angle));
                    Vec3 candidate = targetPosition.add(horizontalDir.scale(radius)).add(0.0, height, 0.0);
                    double score = evaluateShipApproachPoint(client, world, playerPos, candidate, finalPhase);
                    if (score > bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
                }
            }
        }
        return bestScore > -5000.0 ? best : null;
    }

    private double evaluateShipApproachPoint(Minecraft client, Level world, Vec3 playerPos, Vec3 candidate, boolean finalPhase) {
        Vec3 toCandidate = candidate.subtract(playerPos);
        if (toCandidate.lengthSqr() < 0.01) return -10000.0;
        Vec3 candidateToTarget = targetPosition.subtract(candidate);
        if (candidateToTarget.lengthSqr() < 0.01) return -10000.0;

        double legDistance = playerPos.distanceTo(candidate);
        double legSampleDistance = Math.max(10.0, Math.min(finalPhase ? 22.0 : 28.0, legDistance));
        double legClearance = flightManager.measurePathClearance(client, playerPos, toCandidate.normalize(), legSampleDistance);
        if (!flightManager.isPathMostlyLoaded(client, playerPos, toCandidate.normalize(), legSampleDistance)) return -10000.0;

        double finalDistance = candidate.distanceTo(targetPosition);
        double finalSampleDistance = Math.max(8.0, Math.min(finalPhase ? 18.0 : 24.0, finalDistance));
        double finalClearance = flightManager.measurePathClearance(client, candidate, candidateToTarget.normalize(), finalSampleDistance);
        if (!flightManager.isPathMostlyLoaded(client, candidate, candidateToTarget.normalize(), finalSampleDistance)) return -10000.0;

        double localRisk = countPurpurBlocksNearby(world, BlockPos.containing(candidate.x, candidate.y, candidate.z), finalPhase ? 5 : 6) / (finalPhase ? 10.0 : 12.0);
        double corridorRisk = estimatePurpurRiskAlongPath(world, candidate, targetPosition, finalPhase ? 4.0 : 5.0, 4.0);
        double targetRisk = countPurpurBlocksNearby(world, BlockPos.containing(targetPosition.x, targetPosition.y, targetPosition.z), 5) / 18.0;
        double altitudeBonus = clamp((candidate.y - targetPosition.y) / (finalPhase ? 14.0 : 18.0), 0.0, 1.0);
        double alignment = toCandidate.normalize().dot(targetPosition.subtract(playerPos).normalize());

        double score = 0.0;
        score += (legClearance / legSampleDistance) * 3.0;
        score += (finalClearance / finalSampleDistance) * 4.2;
        score += altitudeBonus * (finalPhase ? 0.8 : 1.2);
        score += alignment * 0.6;
        score -= localRisk * 2.4;
        score -= corridorRisk * 2.8;
        score -= targetRisk * 0.4;
        if (finalPhase && candidate.y < targetPosition.y + 8.0) score -= 1.5;
        if (!finalPhase && candidate.y < targetPosition.y + 12.0) score -= 1.2;
        return score;
    }

    private boolean isShipWaypointStillGood(Minecraft client, Vec3 playerPos, Vec3 waypoint, boolean finalPhase) {
        if (waypoint == null || targetPosition == null) return false;
        Vec3 toWaypoint = waypoint.subtract(playerPos);
        if (toWaypoint.lengthSqr() < 0.01) return false;
        double sampleDistance = Math.max(8.0, Math.min(finalPhase ? 18.0 : 24.0, playerPos.distanceTo(waypoint)));
        double clearance = flightManager.measurePathClearance(client, playerPos, toWaypoint.normalize(), sampleDistance);
        if (!flightManager.isPathMostlyLoaded(client, playerPos, toWaypoint.normalize(), sampleDistance)) return false;
        double corridorRisk = estimatePurpurRiskAlongPath(client.level, waypoint, targetPosition, finalPhase ? 4.0 : 5.0, 4.0);
        return clearance >= sampleDistance * 0.72 && corridorRisk <= (finalPhase ? 1.8 : 2.2);
    }

    private double estimatePurpurRiskAlongPath(Level world, Vec3 start, Vec3 end, double sampleRadius, double step) {
        if (world == null) return 0.0;
        Vec3 path = end.subtract(start);
        double distance = path.length();
        if (distance < 0.01) return 0.0;
        Vec3 direction = path.normalize();
        double risk = 0.0;
        int samples = 0;
        for (double travelled = step; travelled < distance; travelled += step) {
            Vec3 point = start.add(direction.scale(travelled));
            int purpur = countPurpurBlocksNearby(world, BlockPos.containing(point.x, point.y, point.z), (int) Math.max(3.0, Math.ceil(sampleRadius)));
            risk += purpur / 10.0;
            samples++;
        }
        if (samples == 0) return 0.0;
        return risk / samples;
    }

    private int getStableSideSign(Vec3 anchor) {
        long hash = ((long) Math.floor(anchor.x) * 31L) ^ ((long) Math.floor(anchor.z) * 17L);
        return (hash & 1L) == 0L ? 1 : -1;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private volatile long lastPurpurApproachTime = 0;
    private static final long MIN_PURPUR_APPROACH_INTERVAL = 30000;

    private void handleApproachPurpur(Minecraft client) {
        if (purpurTargetPosition == null) { flightState = FlightState.CRUISING; return; }
        LocalPlayer p = client.player;
        Vec3 playerPos = getPlayerPosition(p);
        if (playerPos.distanceTo(purpurTargetPosition) < 50.0) {
            flightState = FlightState.CRUISING;
            purpurTargetPosition = null;
            shipScanner.scanForEndShipsAsync(client);
            if (client.player != null) sendClientMessage(client.player, Component.translatable("msg.elytraautocollect.purpur.reached"), false);
        } else {
            flightManager.flyTowardsWithSpeed(client, purpurTargetPosition, ModConfig.getInstance().cruiseSpeed);
            long now = System.currentTimeMillis();
            if (now - lastScanAt >= 1500) {
                lastScanAt = now;
                shipScanner.scanForEndShipsAsync(client);
            }
        }
    }

    public void setPurpurTargetPosition(Vec3 pos) {
        if (flightState == FlightState.APPROACHING_SHIP || flightState == FlightState.APPROACHING_PURPUR) return;
        long now = System.currentTimeMillis();
        if (now - lastPurpurApproachTime < MIN_PURPUR_APPROACH_INTERVAL) return;
        purpurTargetPosition = pos; flightState = FlightState.APPROACHING_PURPUR; lastPurpurApproachTime = now;
        if (Minecraft.getInstance().player != null)
            sendClientMessage(Minecraft.getInstance().player, Component.translatable("msg.elytraautocollect.purpur.found"), false);
    }

    public boolean isActive() { return isActive.get(); }
    public void setTargetPosition(Vec3 pos) { targetPosition = pos; shipApproachPosition = null; shipFinalApproachPosition = null; flightState = FlightState.APPROACHING_SHIP; }
    public void incrementShipsFound() { shipsFound.incrementAndGet(); }
}
