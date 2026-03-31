package cn.kurt6.elytraautocollect.managers;

import cn.kurt6.elytraautocollect.ModConfig;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class FlightManager {
    private static final double MIN_FLYING_SPEED = 0.1;
    private static final double MIN_AVOIDANCE_LOOK_AHEAD = 12.0;
    private static final double MAX_AVOIDANCE_LOOK_AHEAD = 32.0;
    private static final double PATH_CLEARANCE_MARGIN = 0.75;
    private static final double PATH_SAMPLE_WIDTH = 0.9;
    private static final double PATH_SAMPLE_HEADROOM = 0.9;
    private static final double CHUNK_LOAD_SAMPLE_STEP = 6.0;
    private static final long AVOIDANCE_MEMORY_MS = 1400L;
    private Vec3 currentWaypoint = null;
    private long lastFireworkTime = 0;
    private volatile boolean hasWarnedOnGround = false;
    private float cruiseYaw = 0.0f;
    private Vec3 rememberedAvoidanceDirection = null;
    private long rememberedAvoidanceUntil = 0;
    private int rememberedTurnSide = 0;

    private long takeoffStartTime = 0;
    private int jumpAttempts = 0;
    private static final int MAX_JUMP_ATTEMPTS = 10;
    private static final long TAKEOFF_TIMEOUT = 15000;
    private long lastJumpAttemptAt = 0;
    private long jumpKeyReleaseAt = 0;
    private static final long JUMP_COOLDOWN_MS = 350;
    private static final long JUMP_PULSE_MS = 120;
    private static final long FALL_PULSE_MS = 200;

    private Vec3 getPlayerPosition(LocalPlayer player) {
        return new Vec3(player.getX(), player.getY(), player.getZ());
    }

    private static void sendClientMessage(LocalPlayer player, Component message) {
        if (player != null) player.sendSystemMessage(message);
    }

    private void updateJumpKeyPulse(Minecraft client) {
        if (client.options == null) return;
        if (jumpKeyReleaseAt > 0 && System.currentTimeMillis() >= jumpKeyReleaseAt) {
            client.options.keyJump.setDown(false);
            jumpKeyReleaseAt = 0;
        }
    }

    private void pulseJumpKey(Minecraft client, long durationMs) {
        if (client.options == null) return;
        long now = System.currentTimeMillis();
        if (jumpKeyReleaseAt > now) return;
        client.options.keyJump.setDown(true);
        jumpKeyReleaseAt = now + durationMs;
    }

    public boolean isPlayerElytraFlying(LocalPlayer player) {
        return hasElytraEquipped(player) && player.isFallFlying() &&
                player.getDeltaMovement().length() > MIN_FLYING_SPEED;
    }

    public boolean hasElytraEquipped(LocalPlayer player) {
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        return chest != null && chest.getItem() == Items.ELYTRA;
    }

    public boolean tryEquipElytra(LocalPlayer player) {
        if (hasElytraEquipped(player)) return true;
        int slot = findBestElytraInInventory(player);
        if (slot == -1) return false;
        ItemStack elytra = player.getInventory().getItem(slot);
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        player.getInventory().setItem(slot, chest);
        player.setItemSlot(EquipmentSlot.CHEST, elytra);
        return true;
    }

    private int findBestElytraInInventory(LocalPlayer player) {
        int best = -1, bestDura = -1;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (s != null && s.getItem() == Items.ELYTRA) {
                int d = s.getMaxDamage() - s.getDamageValue();
                if (d > bestDura) { bestDura = d; best = i; }
            }
        }
        return best;
    }

    public boolean autoDeployElytra(Minecraft client, LocalPlayer player) {
        if (!hasElytraEquipped(player)) return false;
        updateJumpKeyPulse(client);
        if (player.onGround()) {
            if (takeoffStartTime == 0) { takeoffStartTime = System.currentTimeMillis(); jumpAttempts = 0; }
            if (System.currentTimeMillis() - takeoffStartTime > TAKEOFF_TIMEOUT) {
                sendClientMessage(client.player, Component.translatable("msg.elytraautocollect.takeoff.timeout"));
                resetTakeoffState(); return false;
            }
            if (jumpAttempts < MAX_JUMP_ATTEMPTS) {
                long now = System.currentTimeMillis();
                if (now - lastJumpAttemptAt >= JUMP_COOLDOWN_MS) {
                    pulseJumpKey(client, JUMP_PULSE_MS);
                    jumpAttempts++;
                    lastJumpAttemptAt = now;
                }
            }
            useFireworkIfNeeded(client, player);
            return false;
        }
        if (!player.onGround() && !player.isFallFlying()) {
            if (player.fallDistance > 1.0f || player.getDeltaMovement().y < -0.3) {
                pulseJumpKey(client, FALL_PULSE_MS);
                useFireworkIfNeeded(client, player);
            }
            return false;
        }
        if (player.isFallFlying()) { resetTakeoffState(); return true; }
        return false;
    }

    private void resetTakeoffState() { takeoffStartTime = 0; jumpAttempts = 0; }

    public boolean takeOff(Minecraft client) {
        LocalPlayer p = client.player; if (p == null) return false;
        updateJumpKeyPulse(client);
        if (!tryEquipElytra(p)) {
            if (!hasWarnedOnGround && client.player != null) {
                sendClientMessage(client.player, Component.translatable("msg.elytraautocollect.safety.noelytra"));
                hasWarnedOnGround = true;
            }
            return false;
        }
        if (isPlayerElytraFlying(p)) { resetTakeoffState(); return true; }
        if (!autoDeployElytra(client, p)) {
            if (p.onGround() && !hasWarnedOnGround && client.player != null) {
                long elapsed = takeoffStartTime > 0 ? (System.currentTimeMillis() - takeoffStartTime) / 1000 : 0;
                sendClientMessage(client.player, Component.translatable("msg.elytraautocollect.takeoff.progress", elapsed, jumpAttempts, MAX_JUMP_ATTEMPTS));
                hasWarnedOnGround = true;
            }
            return false;
        } else hasWarnedOnGround = false;
        if (p.isFallFlying() && p.getDeltaMovement().length() < MIN_FLYING_SPEED * 2) {
            useFireworkIfNeeded(client, p);
            if (client.options != null) client.options.keyUp.setDown(true);
            return false;
        }
        return isPlayerElytraFlying(p);
    }

    private void useFireworkIfNeeded(Minecraft client, LocalPlayer player) {
        long now = System.currentTimeMillis();
        boolean isTakingOff = !player.isFallFlying() && (takeoffStartTime > 0);
        boolean bypassCooldown = isTakingOff && player.onGround();

        if ((bypassCooldown || now - lastFireworkTime > (long) (ModConfig.getInstance().fireworkInterval * 1000)) && hasFireworkInInventory(player)) {
            useFireworkSafely(client, player);
            if (!bypassCooldown) {
                lastFireworkTime = now;
            }
        }
    }

    private void useFireworkSafely(Minecraft client, LocalPlayer player) {
        int slot = findFireworkSlot(player); if (slot == -1) return;
        final int fSlot = slot;
        if (slot < 9) {
            player.getInventory().setSelectedSlot(slot);
            new Thread(() -> {
                try { Thread.sleep(150); client.execute(() -> {
                    if (client.gameMode != null && client.player != null)
                        client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                }); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }).start();
        } else {
            int hot = findEmptyHotbarSlot(player); if (hot == -1) hot = player.getInventory().getSelectedSlot();
            final int fHot = hot;
            swapSlots(client, player, fSlot, fHot);
            new Thread(() -> {
                try { Thread.sleep(300); client.execute(() -> {
                    if (client.gameMode != null && client.player != null) {
                        client.player.getInventory().setSelectedSlot(fHot);
                        client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                    }
                }); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }).start();
        }
    }

    private void swapSlots(Minecraft client, LocalPlayer player, int s1, int s2) {
        final int fs1 = s1 < 9 ? s1 + 36 : s1, fs2 = s2 < 9 ? s2 + 36 : s2;
        client.execute(() -> {
            if (client.gameMode != null) {
                client.gameMode.handleContainerInput(player.inventoryMenu.containerId, fs1, 0, ContainerInput.PICKUP, player);
                client.gameMode.handleContainerInput(player.inventoryMenu.containerId, fs2, 0, ContainerInput.PICKUP, player);
                if (!player.getInventory().getItem(s1).isEmpty())
                    client.gameMode.handleContainerInput(player.inventoryMenu.containerId, fs1, 0, ContainerInput.PICKUP, player);
            }
        });
    }

    private int findEmptyHotbarSlot(LocalPlayer p) {
        for (int i = 0; i < 9; i++) { ItemStack s = p.getInventory().getItem(i); if (s == null || s.isEmpty()) return i; }
        return -1;
    }
    private boolean hasFireworkInInventory(LocalPlayer p) {
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) { ItemStack s = p.getInventory().getItem(i); if (s != null && s.getItem() == Items.FIREWORK_ROCKET) return true; }
        return false;
    }
    private int findFireworkSlot(LocalPlayer p) {
        for (int i = 0; i < 9; i++) { ItemStack s = p.getInventory().getItem(i); if (s != null && s.getItem() == Items.FIREWORK_ROCKET) return i; }
        for (int i = 9; i < p.getInventory().getContainerSize(); i++) { ItemStack s = p.getInventory().getItem(i); if (s != null && s.getItem() == Items.FIREWORK_ROCKET) return i; }
        return -1;
    }

    public void flyTowardsWithSpeed(Minecraft client, Vec3 target, double speedMult) {
        LocalPlayer p = client.player; if (p == null || client.options == null) return;
        Vec3 playerPos = getPlayerPosition(p);
        Vec3 dir = target.subtract(playerPos);
        if (dir.lengthSqr() < 0.01) { client.options.keyUp.setDown(false); return; }
        double lookAhead = getAvoidanceLookAhead(p, speedMult);
        dir = avoidObstacles(client, dir.normalize(), lookAhead);
        if (!isPathMostlyLoaded(client, playerPos, dir, Math.max(10.0, lookAhead * 0.7))) {
            dir = dir.add(0.0, 0.22, 0.0).normalize();
            speedMult = Math.min(speedMult, 0.65);
        }
        float yaw = (float) (Math.atan2(dir.z, dir.x) * 180 / Math.PI) - 90;
        float pitch = (float) Math.toDegrees(Math.asin(-dir.y));
        pitch = Math.max(-30f, Math.min(30f, pitch * (float) speedMult));
        float lerp = speedMult > 0.7 ? 0.35f : 0.2f;
        p.setYRot(lerpAngle(p.getYRot(), yaw, lerp));
        p.setXRot(lerpAngle(p.getXRot(), pitch, lerp));
        client.options.keyUp.setDown(true);
        if (speedMult > 0.7 && p.isFallFlying()) useFireworkIfNeeded(client, p);
    }

    public void generateNextWaypoint(LocalPlayer player, ModConfig cfg) {
        if (player == null) return;
        Minecraft c = Minecraft.getInstance();
        if (c.gameRenderer == null || c.gameRenderer.getMainCamera() == null) return;

        Vec3 pos = getPlayerPosition(player);
        float yaw = cruiseYaw;
        double rad = Math.toRadians(yaw);
        double lookX = -Math.sin(rad);
        double lookZ = Math.cos(rad);
        double step = cfg.getDynamicStepSize();

        double targetHeight;
        var world = player.level();
        var worldKey = world.dimension();
        if (worldKey.identifier().getPath().equals("the_end")) {
            targetHeight = cfg.height;
        } else if (worldKey.identifier().getPath().equals("the_nether")) {
            targetHeight = 200;
        } else {
            targetHeight = 200;
        }

        currentWaypoint = new Vec3(
                pos.x + lookX * step + (Math.random() - 0.5) * 20,
                targetHeight,
                pos.z + lookZ * step + (Math.random() - 0.5) * 20
        );
    }

    public void setCruiseYaw(float y) { cruiseYaw = y; }

    public Vec3 avoidObstacles(Minecraft client, Vec3 cur, double ahead) {
        LocalPlayer p = client.player; if (p == null) return cur;
        Level w = client.level;
        Vec3 pos = getPlayerPosition(p);
        Vec3 forward = cur.normalize();
        double desiredLookAhead = Math.max(6.0, ahead);
        double forwardClearance = getPathClearance(w, p, pos, forward, desiredLookAhead);
        double forwardLoadedDistance = getLoadedDistance(w, pos, forward, desiredLookAhead);
        if (forwardClearance >= desiredLookAhead - PATH_CLEARANCE_MARGIN && forwardLoadedDistance >= desiredLookAhead - CHUNK_LOAD_SAMPLE_STEP) {
            clearAvoidanceMemory();
            return forward;
        }

        Vec3 best = forward;
        double bestScore = scoreCandidateDirection(forward, forward, forwardClearance, forwardLoadedDistance, desiredLookAhead);
        for (Vec3 candidate : buildAvoidanceCandidates(forward, p.getDeltaMovement())) {
            double clearance = getPathClearance(w, p, pos, candidate, desiredLookAhead);
            double loadedDistance = getLoadedDistance(w, pos, candidate, desiredLookAhead);
            double score = scoreCandidateDirection(forward, candidate, clearance, loadedDistance, desiredLookAhead);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        if (best != forward) {
            rememberAvoidanceDirection(forward, best);
            return best;
        }

        Vec3 climb = forward.add(0.0, 0.8, 0.0).normalize();
        if (getPathClearance(w, p, pos, climb, Math.max(6.0, desiredLookAhead * 0.6)) > forwardClearance) {
            rememberAvoidanceDirection(forward, climb);
            return climb;
        }
        return forward;
    }

    private double getAvoidanceLookAhead(LocalPlayer player, double speedMult) {
        double speed = player.getDeltaMovement().length();
        double lookAhead = 12.0 + speed * 24.0 + Math.max(0.0, speedMult - 0.5) * 6.0;
        return Math.max(MIN_AVOIDANCE_LOOK_AHEAD, Math.min(MAX_AVOIDANCE_LOOK_AHEAD, lookAhead));
    }

    private List<Vec3> buildAvoidanceCandidates(Vec3 forward, Vec3 momentum) {
        List<Vec3> candidates = new ArrayList<>();
        addCandidate(candidates, forward.yRot((float) Math.toRadians(12)));
        addCandidate(candidates, forward.yRot((float) Math.toRadians(-12)));
        addCandidate(candidates, forward.yRot((float) Math.toRadians(24)));
        addCandidate(candidates, forward.yRot((float) Math.toRadians(-24)));
        addCandidate(candidates, forward.yRot((float) Math.toRadians(40)));
        addCandidate(candidates, forward.yRot((float) Math.toRadians(-40)));
        addCandidate(candidates, forward.yRot((float) Math.toRadians(60)));
        addCandidate(candidates, forward.yRot((float) Math.toRadians(-60)));
        addCandidate(candidates, forward.add(0.0, 0.22, 0.0));
        addCandidate(candidates, forward.add(0.0, 0.38, 0.0));
        addCandidate(candidates, forward.add(0.0, -0.12, 0.0));
        addCandidate(candidates, forward.yRot((float) Math.toRadians(24)).add(0.0, 0.24, 0.0));
        addCandidate(candidates, forward.yRot((float) Math.toRadians(-24)).add(0.0, 0.24, 0.0));
        addCandidate(candidates, forward.yRot((float) Math.toRadians(40)).add(0.0, 0.18, 0.0));
        addCandidate(candidates, forward.yRot((float) Math.toRadians(-40)).add(0.0, 0.18, 0.0));
        if (momentum.lengthSqr() > 0.01) addCandidate(candidates, momentum.normalize());
        return candidates;
    }

    private void addCandidate(List<Vec3> candidates, Vec3 candidate) {
        if (candidate.lengthSqr() > 0.0001) candidates.add(candidate.normalize());
    }

    private double scoreCandidateDirection(Vec3 desired, Vec3 candidate, double clearance, double loadedDistance, double maxDistance) {
        double loadedRatio = Math.max(0.0, Math.min(1.0, loadedDistance / maxDistance));
        double clearanceRatio = Math.max(0.0, Math.min(1.0, clearance / maxDistance));
        double alignment = candidate.dot(desired);
        double rememberedBonus = 0.0;
        Vec3 remembered = getRememberedAvoidanceDirection();
        if (remembered != null) {
            rememberedBonus += Math.max(0.0, candidate.dot(remembered)) * 0.45;
            int candidateTurnSide = getTurnSide(desired, candidate);
            if (candidateTurnSide != 0 && candidateTurnSide == rememberedTurnSide) rememberedBonus += 0.2;
            else if (candidateTurnSide != 0 && rememberedTurnSide != 0) rememberedBonus -= 0.25;
        }
        double score = clearanceRatio * 2.6 + loadedRatio * 1.2 + alignment * 1.4 + rememberedBonus;
        if (candidate.y > 0.08) score += 0.12;
        if (candidate.y < -0.18) score -= 0.4;
        if (clearanceRatio < 0.35) score -= 1.2;
        if (loadedRatio < 0.55) score -= 1.1;
        if (alignment < -0.1) score -= 2.0;
        return score;
    }

    private double getPathClearance(Level level, LocalPlayer player, Vec3 start, Vec3 direction, double maxDistance) {
        if (level == null || player == null) return maxDistance;
        Vec3 dir = direction.normalize();
        if (dir.lengthSqr() < 0.0001 || maxDistance <= 0.0) return maxDistance;

        Vec3 side = new Vec3(-dir.z, 0.0, dir.x);
        if (side.lengthSqr() < 0.0001) side = new Vec3(1.0, 0.0, 0.0);
        side = side.normalize().scale(PATH_SAMPLE_WIDTH);

        double clearance = traceClearance(level, player, start, dir, maxDistance);
        clearance = Math.min(clearance, traceClearance(level, player, start.add(side), dir, maxDistance));
        clearance = Math.min(clearance, traceClearance(level, player, start.subtract(side), dir, maxDistance));
        clearance = Math.min(clearance, traceClearance(level, player, start.add(0.0, PATH_SAMPLE_HEADROOM, 0.0), dir, maxDistance));
        return clearance;
    }

    private double traceClearance(Level level, LocalPlayer player, Vec3 start, Vec3 direction, double maxDistance) {
        Vec3 end = start.add(direction.scale(maxDistance));
        HitResult hit = level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.MISS) return maxDistance;
        return Math.max(0.0, start.distanceTo(hit.getLocation()) - PATH_CLEARANCE_MARGIN);
    }

    private double getLoadedDistance(Level level, Vec3 start, Vec3 direction, double maxDistance) {
        if (level == null) return maxDistance;
        Vec3 dir = direction.normalize();
        if (dir.lengthSqr() < 0.0001 || maxDistance <= 0.0) return maxDistance;
        for (double step = CHUNK_LOAD_SAMPLE_STEP; step <= maxDistance; step += CHUNK_LOAD_SAMPLE_STEP) {
            Vec3 sample = start.add(dir.scale(step));
            if (!level.hasChunkAt(BlockPos.containing(sample.x, sample.y, sample.z))) return Math.max(0.0, step - CHUNK_LOAD_SAMPLE_STEP);
        }
        Vec3 end = start.add(dir.scale(maxDistance));
        return level.hasChunkAt(BlockPos.containing(end.x, end.y, end.z)) ? maxDistance : Math.max(0.0, maxDistance - CHUNK_LOAD_SAMPLE_STEP);
    }

    private void rememberAvoidanceDirection(Vec3 desired, Vec3 selected) {
        rememberedAvoidanceDirection = selected;
        rememberedAvoidanceUntil = System.currentTimeMillis() + AVOIDANCE_MEMORY_MS;
        rememberedTurnSide = getTurnSide(desired, selected);
    }

    private Vec3 getRememberedAvoidanceDirection() {
        if (rememberedAvoidanceDirection == null) return null;
        if (System.currentTimeMillis() > rememberedAvoidanceUntil) {
            clearAvoidanceMemory();
            return null;
        }
        return rememberedAvoidanceDirection;
    }

    private void clearAvoidanceMemory() {
        rememberedAvoidanceDirection = null;
        rememberedAvoidanceUntil = 0;
        rememberedTurnSide = 0;
    }

    private int getTurnSide(Vec3 desired, Vec3 candidate) {
        double cross = desired.x * candidate.z - desired.z * candidate.x;
        if (Math.abs(cross) < 0.08) return 0;
        return cross > 0 ? 1 : -1;
    }

    public double measurePathClearance(Minecraft client, Vec3 start, Vec3 direction, double maxDistance) {
        if (client == null || client.level == null || client.player == null) return maxDistance;
        return getPathClearance(client.level, client.player, start, direction, maxDistance);
    }

    public boolean isPathMostlyLoaded(Minecraft client, Vec3 start, Vec3 direction, double maxDistance) {
        if (client == null || client.level == null) return true;
        return getLoadedDistance(client.level, start, direction, maxDistance) >= maxDistance - CHUNK_LOAD_SAMPLE_STEP;
    }

    private float lerpAngle(float from, float to, float t) {
        float delta = (to - from) % 360.0f;
        if (delta < -180.0f) delta += 360.0f;
        else if (delta > 180.0f) delta -= 360.0f;
        return from + delta * t;
    }

    public void stopMovement(Minecraft client) {
        if (client.options != null) {
            client.options.keyUp.setDown(false);
            client.options.keyDown.setDown(false);
            client.options.keyLeft.setDown(false);
            client.options.keyRight.setDown(false);
            client.options.keyJump.setDown(false);
            client.options.keyShift.setDown(false);
        }
        jumpKeyReleaseAt = 0;
        resetTakeoffState();
        clearAvoidanceMemory();
    }
    public Vec3 getCurrentWaypoint() { return currentWaypoint; }
    public void discardWaypoint() {
        currentWaypoint = null;
    }
}
