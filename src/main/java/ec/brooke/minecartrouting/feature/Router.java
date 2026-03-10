package ec.brooke.minecartrouting.feature;

import ec.brooke.minecartrouting.MinecartRouting;
import ec.brooke.minecartrouting.Utils;
import ec.brooke.minecartrouting.store.DyeFilter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.*;

public class Router implements Listener {

    private final NamespacedKey TRAIN_KEY;

    private final Map<Location, Long> keepAlive = new HashMap<>();

    private static final long BUFFER_TIME_MS = 2000;

    public Router() {
        Plugin trainPlugin = Bukkit.getPluginManager().getPlugin("MinecartTrains");
        if (trainPlugin != null) {
            this.TRAIN_KEY = new NamespacedKey(trainPlugin, "coupler");
        } else {
            this.TRAIN_KEY = new NamespacedKey("minecarttrains", "coupler");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onBlockRedstone(BlockRedstoneEvent event) {
        Block block = event.getBlock();

        if (block.getType() != Material.DETECTOR_RAIL) return;

        DyeFilter filter = MinecartRouting.FILTERS.get(block);
        if (filter == null) return;

        Location location = block.getLocation();
        boolean shouldBeActive = false;

        if (event.getNewCurrent() > 0) {
            Location checkLoc = location.clone().add(0.5, 0.5, 0.5);

            Collection<Minecart> cartsOnRail = Utils.getNearbyEntities(Minecart.class, checkLoc, 0.4);

            Set<Entity> wholeTrain = new HashSet<>();
            for (Minecart minecart : cartsOnRail) {
                collectTrain(minecart, wholeTrain);
            }

            if (test(wholeTrain, filter)) {
                shouldBeActive = true;
                keepAlive.put(location, System.currentTimeMillis() + BUFFER_TIME_MS);
            }
        }

        if (!shouldBeActive) {
            Long expireTime = keepAlive.get(location);
            if (expireTime != null) {
                if (System.currentTimeMillis() < expireTime) {
                    shouldBeActive = true;
                } else {
                    keepAlive.remove(location);
                }
            }
        }

        event.setNewCurrent(shouldBeActive ? 15 : 0);
    }

    private void collectTrain(Minecart current, Set<Entity> visited) {
        if (current == null || visited.contains(current)) return;
        visited.add(current);

        PersistentDataContainer pdc = current.getPersistentDataContainer();

        if (pdc.has(TRAIN_KEY, PersistentDataType.STRING)) {
            String uuidStr = pdc.get(TRAIN_KEY, PersistentDataType.STRING);
            try {
                UUID linkedId = UUID.fromString(uuidStr);
                Entity linkedEntity = Bukkit.getEntity(linkedId);
                if (linkedEntity instanceof Minecart nextCart) {
                    collectTrain(nextCart, visited);
                }
            } catch (Exception ignored) { }
        }

        for (Entity nearby : current.getNearbyEntities(3, 3, 3)) {
            if (nearby instanceof Minecart parentCart && !visited.contains(parentCart)) {
                PersistentDataContainer parentPdc = parentCart.getPersistentDataContainer();
                if (parentPdc.has(TRAIN_KEY, PersistentDataType.STRING)) {
                    String targetUuid = parentPdc.get(TRAIN_KEY, PersistentDataType.STRING);
                    if (targetUuid != null && targetUuid.equals(current.getUniqueId().toString())) {
                        collectTrain(parentCart, visited);
                    }
                }
            }
        }
    }

    private boolean test(Collection<? extends Entity> entities, DyeFilter filter) {
        return entities.stream().anyMatch(entity ->
            entity instanceof InventoryHolder holder
            && holder.getInventory().all(Material.FILLED_MAP).values().stream().anyMatch(
                item -> Ticket.isTicket(item) && filter.test(Ticket.getTicket(item)))
            || test(entity.getPassengers(), filter)
        );
    }
}
