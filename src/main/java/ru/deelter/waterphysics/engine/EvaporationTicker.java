package ru.deelter.waterphysics.engine;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import ru.deelter.waterphysics.config.PluginConfig;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Emulates Minecraft's random block tick for evaporation.
 * <p>
 * Every server tick, for the 3x3 of loaded chunks around each online player,
 * picks {@code RANDOM_TICK_SPEED} (the world's game rule) random blocks in each
 * chunk and asks the flow engine to evaporate any that are water. A chunk is
 * visited at most once per tick even when several players' 3x3 areas overlap.
 * <p>
 * The actual block reads/writes are dispatched through the
 * {@link io.papermc.paper.threadedregions.scheduler.RegionScheduler} so they
 * run on the region that owns each chunk (the correct thread under Folia; the
 * main thread on standard Paper). The per-tick driver is the
 * {@link io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler}.
 */
public final class EvaporationTicker {

	private final Plugin plugin;
	private final PluginConfig config;
	private final FlowEngine engine;

	private ScheduledTask task;

	// Reused across ticks to avoid per-tick allocation; cleared each tick.
	private final Map<UUID, Set<Long>> visited = new HashMap<>();

	public EvaporationTicker(Plugin plugin, PluginConfig config, FlowEngine engine) {
		this.plugin = plugin;
		this.config = config;
		this.engine = engine;
	}

	/** Begin ticking every server tick. */
	public void start() {
		if (task != null) return;
		task = plugin.getServer().getGlobalRegionScheduler()
				.runAtFixedRate(plugin, t -> tick(), 1L, 1L);
	}

	/** Stop ticking. Safe to call more than once. */
	public void stop() {
		if (task != null) {
			task.cancel();
			task = null;
		}
	}

	private void tick() {
		visited.values().forEach(Set::clear);

		for (Player player : plugin.getServer().getOnlinePlayers()) {
			World world = player.getWorld();
			if (!config.isWorldEnabled(world.getName())) continue;

			int rate = randomTickSpeed(world);
			if (rate <= 0) continue;

			Set<Long> seen = visited.computeIfAbsent(world.getUID(), k -> new HashSet<>());
			int pcx = player.getLocation().getBlockX() >> 4;
			int pcz = player.getLocation().getBlockZ() >> 4;

			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					int cx = pcx + dx;
					int cz = pcz + dz;
					if (!world.isChunkLoaded(cx, cz)) continue;
					if (!seen.add(chunkKey(cx, cz))) continue; // already ticked this tick
					scheduleChunk(world, cx, cz, rate);
				}
			}
		}
	}

	/** Dispatch the block checks for one chunk onto the region that owns it. */
	private void scheduleChunk(World world, int cx, int cz, int rate) {
		plugin.getServer().getRegionScheduler().execute(plugin, world, cx, cz,
				() -> tickChunk(world, cx, cz, rate));
	}

	private void tickChunk(World world, int cx, int cz, int rate) {
		if (!world.isChunkLoaded(cx, cz)) return;

		int minY = world.getMinHeight();
		int height = world.getMaxHeight() - minY;
		if (height <= 0) return;

		int baseX = cx << 4;
		int baseZ = cz << 4;
		ThreadLocalRandom rnd = ThreadLocalRandom.current();

		for (int i = 0; i < rate; i++) {
			int x = baseX + rnd.nextInt(16);
			int z = baseZ + rnd.nextInt(16);
			int y = minY + rnd.nextInt(height);
			engine.randomTickEvaporate(world, x, y, z);
		}
	}

	@SuppressWarnings("removal") // GameRule.RANDOM_TICK_SPEED constant deprecated in Paper 26.2 but still functional
	private static int randomTickSpeed(World world) {
		Integer v = world.getGameRuleValue(GameRule.RANDOM_TICK_SPEED);
		return v == null ? 0 : v;
	}

	private static long chunkKey(int x, int z) {
		return ((long) x << 32) | (z & 0xFFFFFFFFL);
	}
}
