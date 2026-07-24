package ru.deelter.waterphysics.listener;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionType;
import org.bukkit.util.RayTraceResult;
import ru.deelter.waterphysics.WaterPhysics;
import ru.deelter.waterphysics.config.PluginConfig;
import ru.deelter.waterphysics.engine.FlowEngine;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Finite-water container interactions: partial water buckets (with a units
 * count stored as item NBT), overflow-preserving placement, and bottle /
 * cauldron unit accounting.
 * <p>
 * Everything is driven from {@link PlayerInteractEvent} with the plugin's own
 * fluid ray-trace. Two things keep a single physical click from turning into
 * a pick-up-then-place cascade or a duplication:
 * <ul>
 *   <li>Once we take over an interaction we <b>deny vanilla</b> for it, so the
 *       server never fills/places/uses the container itself — otherwise, after
 *       we swap an empty bucket to a water bucket, the follow-up event a click
 *       produces (off-hand pass or client re-send) would let vanilla place the
 *       new bucket's water at a different block.</li>
 *   <li>A short per-player debounce ({@link #DEBOUNCE_TICKS}) swallows those
 *       follow-up events — <b>still denying vanilla on them</b> — so neither we
 *       nor vanilla act twice, and rapid clicking can't duplicate water.</li>
 * </ul>
 * When the feature flags are off, or the player isn't targeting water, we do
 * nothing and leave the interaction fully vanilla (so lava pickup, cauldron
 * filling, water-bottle drinking, etc. all still work).
 */
public final class FluidContainerListener implements Listener {

	private static final double REACH = 5.0;
	private static final int DEBOUNCE_TICKS = 4;

	private final PluginConfig config;
	private final WaterPhysics plugin;
	private final NamespacedKey unitsKey;

	// player -> server tick of their last handled container action.
	private final Map<UUID, Integer> lastActionTick = new HashMap<>();
	// player -> server tick on which we last denied their interact (to also
	// cancel the separate vanilla bucket fill/empty event that same tick).
	private final Map<UUID, Integer> deniedTick = new HashMap<>();

	public FluidContainerListener(PluginConfig config, WaterPhysics plugin) {
		this.config = config;
		this.plugin = plugin;
		this.unitsKey = new NamespacedKey(plugin, "water_units");
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onInteract(PlayerInteractEvent event) {
		if (event.getHand() != EquipmentSlot.HAND) return; // main hand only
		Action action = event.getAction();
		if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

		ItemStack item = event.getItem();
		if (item == null) return;

		Player player = event.getPlayer();
		if (!config.isWorldEnabled(player.getWorld().getName())) return;

		FlowEngine engine = plugin.getEngine();
		if (engine == null) return; // physics disabled

		boolean managed = switch (item.getType()) {
			case BUCKET, WATER_BUCKET -> config.isBucketPartialFill();
			case GLASS_BOTTLE, POTION -> config.isBottleConsume();
			default -> false;
		};
		if (!managed) return;

		// A single click can dispatch a follow-up event once the held item has
		// been swapped; only the first is a real action, the rest are the
		// cascade. The handlers still deny vanilla on debounced events so the
		// swapped item can't be placed/used by the server.
		int tick = Bukkit.getCurrentTick();
		Integer last = lastActionTick.get(player.getUniqueId());
		boolean debounced = last != null && tick - last < DEBOUNCE_TICKS;

		boolean acted = switch (item.getType()) {
			case BUCKET -> handleEmptyBucket(event, engine, item, debounced);
			case WATER_BUCKET -> handleWaterBucket(event, engine, item, debounced);
			case GLASS_BOTTLE -> handleGlassBottle(event, engine, debounced);
			case POTION -> handleWaterBottle(event, engine, item, debounced);
			default -> false;
		};
		if (acted) lastActionTick.put(player.getUniqueId(), tick);
	}

	/**
	 * Cancel the vanilla bucket fill/empty events, which fire SEPARATELY from
	 * PlayerInteractEvent (and only for source-level ops — hence bugs only at
	 * the 8-unit boundary). When our interact handler took over the click this
	 * tick, we must also cancel these so vanilla doesn't remove/place a full
	 * source alongside our finite handling.
	 */
	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onBucketFill(PlayerBucketFillEvent event) {
		if (config.isBucketPartialFill() && deniedThisTick(event.getPlayer())) event.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onBucketEmpty(PlayerBucketEmptyEvent event) {
		if (config.isBucketPartialFill() && deniedThisTick(event.getPlayer())) event.setCancelled(true);
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		lastActionTick.remove(event.getPlayer().getUniqueId());
		deniedTick.remove(event.getPlayer().getUniqueId());
	}

	private boolean deniedThisTick(Player player) {
		Integer t = deniedTick.get(player.getUniqueId());
		return t != null && t == Bukkit.getCurrentTick();
	}

	// =========================================================================
	//  Buckets
	// =========================================================================

	private boolean handleEmptyBucket(PlayerInteractEvent event, FlowEngine engine, ItemStack item, boolean debounced) {
		Player player = event.getPlayer();
		World world = player.getWorld();

		// Fill a bucket from a water cauldron with unit accounting.
		Block clicked = event.getClickedBlock();
		if (config.isCauldronUseBottleUnits() && clicked != null && clicked.getType() == Material.WATER_CAULDRON) {
			int level = cauldronLevel(clicked);
			if (level <= 0) return false; // empty cauldron -> leave to vanilla
			deny(event); // we take over this cauldron pickup
			if (debounced) return false;
			int units = Math.min(8, level * config.getBottleUnitValue());
			clicked.setType(Material.CAULDRON, false); // empty the cauldron
			consumeAndGive(player, event.getHand(), makeWaterBucket(units));
			playSound(player, clicked, Sound.ITEM_BUCKET_FILL);
			return true;
		}

		Block water = rayTraceWater(player);
		if (water == null) return false; // not aiming at water -> vanilla (lava, etc.)
		deny(event); // we own water pickup
		if (debounced) return false; // swallow the cascade / rapid clicks

		int w = engine.waterUnitsAt(world, water.getX(), water.getY(), water.getZ());
		if (w <= 0) return false;
		int taken = Math.min(8, w); // an empty bucket takes all of it
		engine.setWaterUnits(world, water.getX(), water.getY(), water.getZ(), w - taken);
		consumeAndGive(player, event.getHand(), makeWaterBucket(taken));
		playSound(player, water, Sound.ITEM_BUCKET_FILL);
		return true;
	}

	private boolean handleWaterBucket(PlayerInteractEvent event, FlowEngine engine, ItemStack item, boolean debounced) {
		Player player = event.getPlayer();
		World world = player.getWorld();

		// Leave water-bucket -> cauldron filling to vanilla.
		Block clicked = event.getClickedBlock();
		if (clicked != null && (clicked.getType() == Material.CAULDRON || clicked.getType() == Material.WATER_CAULDRON)) {
			return false;
		}

		RayTraceResult r = player.rayTraceBlocks(REACH, FluidCollisionMode.ALWAYS);
		if (r == null || r.getHitBlock() == null) return false; // nothing targeted
		Block hit = r.getHitBlock();
		BlockFace face = r.getHitBlockFace();

		// While partial-fill is on we own every water-bucket placement, so vanilla
		// never places a full source that ignores the unit model. Deny it up front.
		deny(event);
		if (debounced) return false; // swallow the cascade / rapid clicks

		boolean hitWater = hit.getType() == Material.WATER;
		boolean sneaking = player.isSneaking();
		int b = bucketUnits(item);
		if (b <= 0) return false;

		// Not sneaking, aimed at water, bucket not full -> top the bucket up.
		if (hitWater && !sneaking && b < 8) {
			int w = engine.waterUnitsAt(world, hit.getX(), hit.getY(), hit.getZ());
			if (w <= 0) return false;
			int taken = Math.min(8 - b, w);
			if (taken <= 0) return false;
			engine.setWaterUnits(world, hit.getX(), hit.getY(), hit.getZ(), w - taken);
			setHeldWaterBucket(player, event.getHand(), b + taken);
			playSound(player, hit, Sound.ITEM_BUCKET_FILL);
			return true;
		}

		// Otherwise deposit: into the aimed water block when sneaking, else onto
		// the air against the face the player clicked ("used on air").
		Block target;
		if (hitWater && sneaking) {
			target = hit;
		} else {
			if (face == null) return false;
			target = hit.getRelative(face);
		}
		if (target.getType() != Material.WATER && !target.isPassable()) return false; // solid

		int leftover = engine.addWaterWithOverflow(world, target.getX(), target.getY(), target.getZ(),
				b, config.isBucketPreserveOverflow());
		int keep = config.isBucketPreserveOverflow() ? leftover : 0;
		setHeldWaterBucket(player, event.getHand(), keep);
		playSound(player, target, Sound.ITEM_BUCKET_EMPTY);
		return true;
	}

	// =========================================================================
	//  Bottles
	// =========================================================================

	private boolean handleGlassBottle(PlayerInteractEvent event, FlowEngine engine, boolean debounced) {
		Player player = event.getPlayer();
		World world = player.getWorld();

		// Filling a bottle from a cauldron already consumes exactly one level,
		// which equals one unit-value — leave it to vanilla.
		Block clicked = event.getClickedBlock();
		if (clicked != null && clicked.getType() == Material.WATER_CAULDRON) return false;

		Block water = rayTraceWater(player);
		if (water == null) return false; // not aiming at water -> vanilla
		deny(event);
		if (debounced) return false;

		int w = engine.waterUnitsAt(world, water.getX(), water.getY(), water.getZ());
		if (w <= 0) return false;
		int deduct = Math.min(config.getBottleUnitValue(), w);
		engine.setWaterUnits(world, water.getX(), water.getY(), water.getZ(), w - deduct);
		consumeAndGive(player, event.getHand(), waterPotion());
		playSound(player, water, Sound.ITEM_BOTTLE_FILL);
		return true;
	}

	private boolean handleWaterBottle(PlayerInteractEvent event, FlowEngine engine, ItemStack item, boolean debounced) {
		Player player = event.getPlayer();
		if (!player.isSneaking()) return false; // only sneaking places water
		if (!(item.getItemMeta() instanceof PotionMeta pm) || pm.getBasePotionType() != PotionType.WATER) return false;

		World world = player.getWorld();
		RayTraceResult r = player.rayTraceBlocks(REACH, FluidCollisionMode.ALWAYS);
		if (r == null || r.getHitBlock() == null) return false;
		Block hit = r.getHitBlock();
		BlockFace face = r.getHitBlockFace();

		Block target;
		if (hit.getType() == Material.WATER) {
			target = hit;
		} else {
			if (face == null) return false;
			target = hit.getRelative(face);
		}
		if (target.getType() != Material.WATER && !target.isPassable()) return false;

		deny(event);
		if (debounced) return false;

		engine.addWaterWithOverflow(world, target.getX(), target.getY(), target.getZ(),
				config.getBottleUnitValue(), config.isBucketPreserveOverflow());
		consumeAndGive(player, event.getHand(), new ItemStack(Material.GLASS_BOTTLE));
		playSound(player, target, Sound.ITEM_BOTTLE_EMPTY);
		return true;
	}

	// =========================================================================
	//  Ray-trace + item helpers
	// =========================================================================

	/**
	 * Fully suppress the vanilla interaction (both block use and item use), and
	 * record the tick so the separate vanilla bucket fill/empty event fired this
	 * same tick is cancelled too (see {@link #onBucketFill}/{@link #onBucketEmpty}).
	 */
	private void deny(PlayerInteractEvent event) {
		event.setUseInteractedBlock(Event.Result.DENY);
		event.setUseItemInHand(Event.Result.DENY);
		event.setCancelled(true);
		deniedTick.put(event.getPlayer().getUniqueId(), Bukkit.getCurrentTick());
	}

	/** The water block the player is aiming at, or null if they aren't aiming at water. */
	private Block rayTraceWater(Player player) {
		RayTraceResult r = player.rayTraceBlocks(REACH, FluidCollisionMode.ALWAYS);
		if (r == null) return null;
		Block b = r.getHitBlock();
		return (b != null && b.getType() == Material.WATER) ? b : null;
	}

	private int bucketUnits(ItemStack waterBucket) {
		ItemMeta meta = waterBucket.getItemMeta();
		if (meta == null) return 8; // vanilla water bucket = full
		Integer u = meta.getPersistentDataContainer().get(unitsKey, PersistentDataType.INTEGER);
		return u == null ? 8 : Math.max(0, Math.min(8, u));
	}

	private ItemStack makeWaterBucket(int units) {
		units = Math.max(1, Math.min(8, units));
		ItemStack bucket = new ItemStack(Material.WATER_BUCKET);
		if (units < 8) {
			ItemMeta meta = bucket.getItemMeta();
			meta.getPersistentDataContainer().set(unitsKey, PersistentDataType.INTEGER, units);
			meta.lore(List.of(Component.text("Water: " + units + "/8")));
			bucket.setItemMeta(meta);
		}
		return bucket;
	}

	private ItemStack waterPotion() {
		ItemStack potion = new ItemStack(Material.POTION);
		if (potion.getItemMeta() instanceof PotionMeta pm) {
			pm.setBasePotionType(PotionType.WATER);
			potion.setItemMeta(pm);
		}
		return potion;
	}

	/** Replace the held container (stack size 1: water bucket / potion) with a water bucket of `units`, or an empty bucket. */
	private void setHeldWaterBucket(Player player, EquipmentSlot hand, int units) {
		if (player.getGameMode() == GameMode.CREATIVE) return;
		setHeld(player, hand, units <= 0 ? new ItemStack(Material.BUCKET) : makeWaterBucket(units));
	}

	/** Consume one of the held stack and give the result (dropping it if the inventory is full). */
	private void consumeAndGive(Player player, EquipmentSlot hand, ItemStack result) {
		if (player.getGameMode() == GameMode.CREATIVE) return; // don't consume/duplicate in creative
		ItemStack held = held(player, hand);
		if (held.getAmount() <= 1) {
			setHeld(player, hand, result);
		} else {
			held.setAmount(held.getAmount() - 1);
			var leftover = player.getInventory().addItem(result);
			for (ItemStack l : leftover.values()) {
				player.getWorld().dropItemNaturally(player.getLocation(), l);
			}
		}
	}

	private ItemStack held(Player player, EquipmentSlot hand) {
		return hand == EquipmentSlot.OFF_HAND
				? player.getInventory().getItemInOffHand()
				: player.getInventory().getItemInMainHand();
	}

	private void setHeld(Player player, EquipmentSlot hand, ItemStack item) {
		if (hand == EquipmentSlot.OFF_HAND) player.getInventory().setItemInOffHand(item);
		else player.getInventory().setItemInMainHand(item);
	}

	private static int cauldronLevel(Block cauldron) {
		return cauldron.getBlockData() instanceof Levelled l ? l.getLevel() : 0;
	}

	private void playSound(Player player, Block at, Sound sound) {
		player.getWorld().playSound(at.getLocation().add(0.5, 0.5, 0.5),
				sound, SoundCategory.BLOCKS, 1.0f, 1.0f);
	}
}
