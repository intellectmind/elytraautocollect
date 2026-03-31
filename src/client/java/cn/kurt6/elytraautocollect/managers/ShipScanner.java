package cn.kurt6.elytraautocollect.managers;

import cn.kurt6.elytraautocollect.AutoCollectManager;
import cn.kurt6.elytraautocollect.ModConfig;

import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class ShipScanner {
    private final Map<BlockPos, Long> visitedShips = new ConcurrentHashMap<>();
    private final Map<BlockPos, Long> visitedPurpurClusters = new ConcurrentHashMap<>();
    private static final long SHIP_COOLDOWN = 300000;
    private static final long PURPUR_COOLDOWN = 300000;

    private Vec3 getPlayerPosition(LocalPlayer player) {
        return new Vec3(player.getX(), player.getY(), player.getZ());
    }

    private Vec3 getEntityPosition(ItemFrame entity) {
        return new Vec3(entity.getX(), entity.getY(), entity.getZ());
    }

    private Vec3 getItemEntityPosition(ItemEntity entity) {
        return new Vec3(entity.getX(), entity.getY(), entity.getZ());
    }

    private static void sendClientMessage(LocalPlayer player, Component message) {
        if (player != null) player.sendSystemMessage(message);
    }

    public void scanForEndShipsAsync(Minecraft client) {
        try {
            LocalPlayer p = client.player; Level w = client.level; if (p == null || w == null) return;
            BlockPos pc = p.blockPosition();
            int r = ModConfig.getInstance().getEffectiveScanRadius();
            List<BlockPos> purpurs = findPurpurBlockClusters(w, pc, r);
            for (BlockPos pp : purpurs) {
                if (!AutoCollectManager.getInstance().isActive()) return;
                if (isPurpurClusterProcessed(pp)) continue;
                visitedPurpurClusters.put(pp, System.currentTimeMillis());
                client.execute(() -> AutoCollectManager.getInstance().setPurpurTargetPosition(Vec3.atCenterOf(pp)));
                Vec3 ship = searchForShipNearPurpur(w, pp);
                if (ship != null && hasElytraInArea(w, new BlockPos((int) ship.x, (int) ship.y, (int) ship.z))) {
                    handleElytraFound(client, new BlockPos((int) ship.x, (int) ship.y, (int) ship.z)); return;
                }
                break;
            }
            List<BlockPos> ships = findEndShipStructures(w, pc, r);
            for (BlockPos s : ships) {
                if (!AutoCollectManager.getInstance().isActive()) return;
                if (hasElytraInArea(w, s)) { handleElytraFound(client, s); return; }
            }
            scanForDirectElytra(client, pc, r);
        } catch (Exception ignored) {}
    }

    private boolean isPurpurClusterProcessed(BlockPos p) {
        for (Map.Entry<BlockPos, Long> e : visitedPurpurClusters.entrySet())
            if (e.getKey().distSqr(p) < 2500 && System.currentTimeMillis() - e.getValue() < PURPUR_COOLDOWN) return true;
        return false;
    }

    private List<BlockPos> findPurpurBlockClusters(Level w, BlockPos c, int rad) {
        Map<BlockPos, Integer> density = new HashMap<>(); int step = Math.max(8, rad / 32);
        for (int x = -rad; x <= rad; x += step) for (int z = -rad; z <= rad; z += step) for (int y = -rad / 4; y <= rad / 4; y += step) {
            if (!AutoCollectManager.getInstance().isActive()) return List.of();
            BlockPos p = c.offset(x, y, z); try {
                if (isPurpurBlock(w, p) && countNearbyPurpurBlocks(w, p, 32) >= 6) density.put(p, countNearbyPurpurBlocks(w, p, 32));
            } catch (Exception e) { continue; }
        }
        return density.entrySet().stream().sorted((a, b) -> {
            int d = Integer.compare(b.getValue(), a.getValue()); if (d != 0) return d;
            return Double.compare(c.distSqr(a.getKey()), c.distSqr(b.getKey()));
        }).limit(5).map(Map.Entry::getKey).toList();
    }

    private Vec3 searchForShipNearPurpur(Level w, BlockPos pc) {
        int r = 200;
        for (int x = -r; x <= r; x += 4) for (int z = -r; z <= r; z += 4) for (int y = -20; y <= 20; y += 4) {
            BlockPos p = pc.offset(x, y, z); if (isShipStructurePattern(w, p)) return Vec3.atCenterOf(p);
        }
        return null;
    }

    private List<BlockPos> findEndShipStructures(Level w, BlockPos c, int rad) {
        List<BlockPos> out = new ArrayList<>(); int step = Math.max(8, rad / 32);
        for (int x = -rad; x <= rad; x += step) for (int z = -rad; z <= rad; z += step) for (int y = -rad / 3; y <= rad / 3; y += step) {
            if (!AutoCollectManager.getInstance().isActive()) return out;
            BlockPos p = c.offset(x, y, z); try {
                if (isShipStructurePattern(w, p)) { out.add(p); if (out.size() >= 3) return out; }
            } catch (Exception e) { continue; }
        }
        out.sort(Comparator.comparingDouble(c::distSqr)); return out;
    }

    private boolean isShipStructurePattern(Level w, BlockPos p) {
        try { int pur = 0, brew = 0, head = 0;
            for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
                Block b = w.getBlockState(p.offset(x, y, z)).getBlock();
                if (isPurpurBlock(w, p.offset(x, y, z))) pur++;
                else if (b == Blocks.BREWING_STAND) brew++;
                else if (b == Blocks.DRAGON_HEAD || b == Blocks.DRAGON_WALL_HEAD) head++;
            }
            return pur >= 3 || brew >= 1 || head >= 1;
        } catch (Exception e) { return false; }
    }

    private boolean isPurpurBlock(Level w, BlockPos p) {
        try { Block b = w.getBlockState(p).getBlock();
            return b == Blocks.PURPUR_BLOCK || b == Blocks.PURPUR_PILLAR || b == Blocks.PURPUR_STAIRS || b == Blocks.PURPUR_SLAB;
        } catch (Exception e) { return false; }
    }

    private int countNearbyPurpurBlocks(Level w, BlockPos c, int rad) {
        int cnt = 0, step = Math.max(2, rad / 8);
        for (int x = -rad; x <= rad; x += step) for (int y = -rad; y <= rad; y += step) for (int z = -rad; z <= rad; z += step) {
            if (cnt > 100) return cnt;
            if (isPurpurBlock(w, c.offset(x, y, z))) cnt++;
        }
        return cnt;
    }

    private boolean hasElytraInArea(Level w, BlockPos c) {
        AABB box = new AABB(c.getX() - 300, c.getY() - 150, c.getZ() - 300, c.getX() + 300, c.getY() + 150, c.getZ() + 300);
        List<ItemFrame> frames = w.getEntitiesOfClass(ItemFrame.class, box, e -> e != null && !e.getItem().isEmpty() && e.getItem().getItem() == Items.ELYTRA);
        List<ItemEntity> drops = w.getEntitiesOfClass(ItemEntity.class, box, e -> e != null && !e.getItem().isEmpty() && e.getItem().getItem() == Items.ELYTRA && e.isAlive());
        return !frames.isEmpty() || !drops.isEmpty();
    }

    private void handleElytraFound(Minecraft client, BlockPos pos) {
        if (isPositionProcessed(pos)) return;
        visitedShips.put(pos, System.currentTimeMillis());
        client.execute(() -> {
            AutoCollectManager mgr = AutoCollectManager.getInstance();
            mgr.setTargetPosition(Vec3.atCenterOf(pos)); mgr.incrementShipsFound();
            sendClientMessage(client.player, Component.translatable("msg.elytraautocollect.ship.discovered", pos.getX(), pos.getY(), pos.getZ()));
        });
    }

    public void scanForDirectElytra(Minecraft client, BlockPos pc, int rad) {
        try { Level w = client.level; if (w == null) return;
            AABB box = new AABB(pc.getX() - rad, pc.getY() - rad / 2, pc.getZ() - rad, pc.getX() + rad, pc.getY() + rad / 2, pc.getZ() + rad);
            List<ItemFrame> frames = w.getEntitiesOfClass(ItemFrame.class, box, e -> e != null && !e.getItem().isEmpty() && e.getItem().getItem() == Items.ELYTRA);
            List<ItemEntity> drops = w.getEntitiesOfClass(ItemEntity.class, box, e -> e != null && !e.getItem().isEmpty() && e.getItem().getItem() == Items.ELYTRA && e.isAlive());
            if (!frames.isEmpty() || !drops.isEmpty()) {
                Vec3 center = calculateElytraCenter(frames, drops);
                BlockPos bp = new BlockPos((int) center.x, (int) center.y, (int) center.z);
                if (!isPositionProcessed(bp)) {
                    visitedShips.put(bp, System.currentTimeMillis());
                    client.execute(() -> {
                        AutoCollectManager mgr = AutoCollectManager.getInstance();
                        mgr.setTargetPosition(center); mgr.incrementShipsFound();
                        sendClientMessage(client.player, Component.translatable("msg.elytraautocollect.elytra.direct", bp.getX(), bp.getY(), bp.getZ()));
                    });
                }
            }
        } catch (Exception ignored) {}
    }

    private Vec3 calculateElytraCenter(List<ItemFrame> f, List<ItemEntity> d) {
        if (f.isEmpty() && d.isEmpty()) return Vec3.ZERO;
        double x = 0, y = 0, z = 0; int c = 0;
        for (ItemFrame e : f) { Vec3 v = getEntityPosition(e); x += v.x; y += v.y; z += v.z; c++; }
        for (ItemEntity e : d) { Vec3 v = getItemEntityPosition(e); x += v.x; y += v.y; z += v.z; c++; }
        return c > 0 ? new Vec3(x / c, y / c, z / c) : Vec3.ZERO;
    }

    private boolean isPositionProcessed(BlockPos p) {
        Long t = visitedShips.get(p); return t != null && System.currentTimeMillis() - t < SHIP_COOLDOWN;
    }

    public void cleanup() {
        long n = System.currentTimeMillis();
        visitedShips.entrySet().removeIf(e -> n - e.getValue() > SHIP_COOLDOWN);
        visitedPurpurClusters.entrySet().removeIf(e -> n - e.getValue() > PURPUR_COOLDOWN);
    }
}
