package ru.deelter.waterphysics.listener;

import lombok.RequiredArgsConstructor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import ru.deelter.waterphysics.WaterPhysics;
import ru.deelter.waterphysics.cache.BlockStateCache;
import ru.deelter.waterphysics.config.PluginConfig;
import ru.deelter.waterphysics.engine.WaterQueue;

/**
 * When a player places a water bucket, re-evaluate all water blocks
 * within scan-radius so the body can spread or overflow if it has an opening.
 * <p>
 * The scan also includes y+1 above each water block — water adjacent to air
 * at a higher Y can act as an overflow point.
 * <p>
 * When a player picks water up with a bucket, wake the freed cell and its
 * neighbours so the surrounding body flows into the gap — otherwise the
 * removal leaves a stale cache entry and the water never re-flows.
 * <p>
 * Both are deferred 1 tick so the world reflects the bucket action before we
 * scan/wake (the placed/removed water isn't applied until after the event).
 */
@RequiredArgsConstructor
public final class BucketListener implements Listener {

	private final PluginConfig config;
	private final BlockStateCache cache;
	private final WaterQueue queue;
	private final WaterPhysics plugin;

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onBucketEmpty(PlayerBucketEmptyEvent event) {
		if (!config.isBucketPhysicsEnabled()) return;
		if (event.getBucket() != Material.WATER_BUCKET) return;

		Block target = event.getBlock();
		World world = target.getWorld();
		if (!config.isWorldEnabled(world.getName())) return;

		int bx = target.getX();
		int by = target.getY();
		int bz = target.getZ();
		int radius = config.getBucketScanRadius();

		// Defer 1 tick: the placed water block isn't in the world yet at event time
		plugin.getServer().getScheduler().runTask(plugin, () -> {
			// Queue the placed block itself
			cache.preload(target);
			queue.enqueue(world, bx, by, bz);

			// Re-evaluate all water blocks in radius at y-1..y+1
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					for (int dy = -1; dy <= 1; dy++) {
						int nx = bx + dx;
						int ny = by + dy;
						int nz = bz + dz;
						if (cache.getType(world, nx, ny, nz) == BlockStateCache.TYPE_WATER) {
							queue.enqueue(world, nx, ny, nz);
						}
					}
				}
			}
		});
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onBucketFill(PlayerBucketFillEvent event) {
		if (!config.isBucketPhysicsEnabled()) return;

		Block clicked = event.getBlockClicked();
		World world = clicked.getWorld();
		if (!config.isWorldEnabled(world.getName())) return;

		// Only react to picking up water — a source block or a waterlogged
		// block losing its water. Lava / powder-snow pickups don't concern the
		// water engine. The removal hasn't applied yet at MONITOR time, so the
		// block still reads as water here.
		if (clicked.getType() != Material.WATER && !isWaterlogged(clicked)) return;

		int bx = clicked.getX();
		int by = clicked.getY();
		int bz = clicked.getZ();

		// Defer 1 tick: the water isn't removed from the world until after the
		// event. wakeArea then invalidates the freed cell's stale cache and
		// re-queues it plus its six neighbours so the body flows into the gap.
		plugin.getServer().getScheduler().runTask(plugin, () ->
				plugin.wakeArea(world, bx, by, bz));
	}

	private static boolean isWaterlogged(Block block) {
		return block.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged();
	}
}
