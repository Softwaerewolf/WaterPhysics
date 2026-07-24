package ru.deelter.waterphysics.listener;

import net.kyori.adventure.text.Component;
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
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
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

import java.util.List;

/**
 * Finite-water container interactions: partial water buckets (with a units
 * count stored as item NBT), overflow-preserving placement, and bottle /
 * cauldron unit accounting.
 * <p>
 * Everything is driven from {@link PlayerInteractEvent} with the plugin's own
 * fluid ray-trace, cancelling vanilla only for the cases it actually takes
 * over — so when the relevant feature flags are off, buckets and bottles keep
 * their vanilla behaviour and the existing {@link BucketListener} still runs.
 */
public final class FluidContainerListener implements Listener {

	private static final double REACH = 5.0;

	private final PluginConfig config;
	private final WaterPhysics plugin;
	private final NamespacedKey unitsKey;

	public FluidContainerListener(PluginConfig config, WaterPhysics plugin) {
		this.config = config;
		this.plugin = plugin;
		this.unitsKey = new NamespacedKey(plugin, "water_units");
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onInteract(PlayerInteractEvent event) {
		if (event.getHand() != EquipmentSlot.HAND) return; // main hand only, avoid double-fire
		Action action = event.getAction();
		if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

		ItemStack item = event.getItem();
		if (item == null) return;

		Player player = event.getPlayer();
		if (!config.isWorldEnabled(player.getWorld().getName())) return;

		FlowEngine engine = plugin.getEngine();
		if (engine == null) return; // physics disabled

		switch (item.getType()) {
			case BUCKET -> { if (config.isBucketPartialFill()) handleEmptyBucket(event, engine, item); }
			case WATER_BUCKET -> { if (config.isBucketPartialFill()) handleWaterBucket(event, engine, item); }
			case GLASS_BOTTLE -> { if (config.isBottleConsume()) handleGlassBottle(event, engine); }
			case POTION -> { if (config.isBottleConsume()) handleWaterBottle(event, engine, item); }
			default -> { }
		}
	}

	// =========================================================================
	//  Buckets
	// =========================================================================

	private void handleEmptyBucket(PlayerInteractEvent event, FlowEngine engine, ItemStack item) {
		Player player = event.getPlayer();
		World world = player.getWorld();

		// Fill a bucket from a water cauldron with unit accounting.
		Block clicked = event.getClickedBlock();
		if (config.isCauldronUseBottleUnits() && clicked != null && clicked.getType() == Material.WATER_CAULDRON) {
			int level = cauldronLevel(clicked);
			if (level <= 0) return;
			int units = Math.min(8, level * config.getBottleUnitValue());
			clicked.setType(Material.CAULDRON, false); // empty the cauldron
			event.setCancelled(true);
			consumeAndGive(player, event.getHand(), makeWaterBucket(units));
			playSound(player, clicked, Sound.ITEM_BUCKET_FILL);
			return;
		}

		Block water = rayTraceWater(player);
		if (water == null) return;
		int w = engine.waterUnitsAt(world, water.getX(), water.getY(), water.getZ());
		if (w <= 0) return;

		int taken = Math.min(8, w); // an empty bucket takes all of it
		engine.setWaterUnits(world, water.getX(), water.getY(), water.getZ(), w - taken);
		event.setCancelled(true);
		consumeAndGive(player, event.getHand(), makeWaterBucket(taken));
		playSound(player, water, Sound.ITEM_BUCKET_FILL);
	}

	private void handleWaterBucket(PlayerInteractEvent event, FlowEngine engine, ItemStack item) {
		Player player = event.getPlayer();
		World world = player.getWorld();

		// Leave water-bucket -> cauldron filling to vanilla.
		Block clicked = event.getClickedBlock();
		if (clicked != null && (clicked.getType() == Material.CAULDRON || clicked.getType() == Material.WATER_CAULDRON)) {
			return;
		}

		RayTraceResult r = player.rayTraceBlocks(REACH, FluidCollisionMode.ALWAYS);
		if (r == null || r.getHitBlock() == null) return;
		Block hit = r.getHitBlock();
		BlockFace face = r.getHitBlockFace();
		boolean hitWater = hit.getType() == Material.WATER;
		boolean sneaking = player.isSneaking();
		int b = bucketUnits(item);
		if (b <= 0) return;

		// Not sneaking, aimed at water, bucket not full -> top the bucket up.
		if (hitWater && !sneaking && b < 8) {
			int w = engine.waterUnitsAt(world, hit.getX(), hit.getY(), hit.getZ());
			if (w <= 0) return;
			int taken = Math.min(8 - b, w);
			if (taken <= 0) return;
			engine.setWaterUnits(world, hit.getX(), hit.getY(), hit.getZ(), w - taken);
			event.setCancelled(true);
			setHeldWaterBucket(player, event.getHand(), b + taken);
			playSound(player, hit, Sound.ITEM_BUCKET_FILL);
			return;
		}

		// Otherwise deposit: into the aimed water block when sneaking, else onto
		// the air against the face the player clicked ("used on air").
		Block target;
		if (hitWater && sneaking) {
			target = hit;
		} else {
			if (face == null) return;
			target = hit.getRelative(face);
		}
		if (target.getType() != Material.WATER && !target.isPassable()) return; // can't place into a solid

		int leftover = engine.addWaterWithOverflow(world, target.getX(), target.getY(), target.getZ(),
				b, config.isBucketPreserveOverflow());
		int keep = config.isBucketPreserveOverflow() ? leftover : 0;
		event.setCancelled(true);
		setHeldWaterBucket(player, event.getHand(), keep);
		playSound(player, target, Sound.ITEM_BUCKET_EMPTY);
	}

	// =========================================================================
	//  Bottles
	// =========================================================================

	private void handleGlassBottle(PlayerInteractEvent event, FlowEngine engine) {
		Player player = event.getPlayer();
		World world = player.getWorld();

		// Filling a bottle from a cauldron already consumes exactly one level,
		// which equals one unit-value — leave it to vanilla.
		Block clicked = event.getClickedBlock();
		if (clicked != null && clicked.getType() == Material.WATER_CAULDRON) return;

		Block water = rayTraceWater(player);
		if (water == null) return;
		int w = engine.waterUnitsAt(world, water.getX(), water.getY(), water.getZ());
		if (w <= 0) return;

		int deduct = Math.min(config.getBottleUnitValue(), w);
		engine.setWaterUnits(world, water.getX(), water.getY(), water.getZ(), w - deduct);
		event.setCancelled(true);
		consumeAndGive(player, event.getHand(), waterPotion());
		playSound(player, water, Sound.ITEM_BOTTLE_FILL);
	}

	private void handleWaterBottle(PlayerInteractEvent event, FlowEngine engine, ItemStack item) {
		Player player = event.getPlayer();
		if (!player.isSneaking()) return; // only sneaking places water
		if (!(item.getItemMeta() instanceof PotionMeta pm) || pm.getBasePotionType() != PotionType.WATER) return;

		World world = player.getWorld();
		RayTraceResult r = player.rayTraceBlocks(REACH, FluidCollisionMode.ALWAYS);
		if (r == null || r.getHitBlock() == null) return;
		Block hit = r.getHitBlock();
		BlockFace face = r.getHitBlockFace();

		Block target;
		if (hit.getType() == Material.WATER) {
			target = hit;
		} else {
			if (face == null) return;
			target = hit.getRelative(face);
		}
		if (target.getType() != Material.WATER && !target.isPassable()) return;

		engine.addWaterWithOverflow(world, target.getX(), target.getY(), target.getZ(),
				config.getBottleUnitValue(), config.isBucketPreserveOverflow());
		event.setCancelled(true);
		consumeAndGive(player, event.getHand(), new ItemStack(Material.GLASS_BOTTLE));
		playSound(player, target, Sound.ITEM_BOTTLE_EMPTY);
	}

	// =========================================================================
	//  Ray-trace + item helpers
	// =========================================================================

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
