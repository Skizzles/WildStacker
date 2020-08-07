package com.bgsoftware.wildstacker.listeners;

import com.bgsoftware.wildstacker.Locale;
import com.bgsoftware.wildstacker.WildStackerPlugin;
import com.bgsoftware.wildstacker.api.enums.SpawnCause;
import com.bgsoftware.wildstacker.api.enums.StackSplit;
import com.bgsoftware.wildstacker.api.enums.UnstackResult;
import com.bgsoftware.wildstacker.api.objects.StackedEntity;
import com.bgsoftware.wildstacker.hooks.JobsHook;
import com.bgsoftware.wildstacker.hooks.McMMOHook;
import com.bgsoftware.wildstacker.hooks.PluginHooks;
import com.bgsoftware.wildstacker.hooks.ProtocolLibHook;
import com.bgsoftware.wildstacker.listeners.events.EntityPickupItemEvent;
import com.bgsoftware.wildstacker.objects.WStackedEntity;
import com.bgsoftware.wildstacker.utils.GeneralUtils;
import com.bgsoftware.wildstacker.utils.ServerVersion;
import com.bgsoftware.wildstacker.utils.entity.EntityStorage;
import com.bgsoftware.wildstacker.utils.entity.EntityUtils;
import com.bgsoftware.wildstacker.utils.items.ItemUtils;
import com.bgsoftware.wildstacker.utils.legacy.EntityTypes;
import com.bgsoftware.wildstacker.utils.legacy.Materials;
import com.bgsoftware.wildstacker.utils.reflection.ReflectionUtils;
import com.bgsoftware.wildstacker.utils.statistics.StatisticsUtils;
import com.bgsoftware.wildstacker.utils.threads.Executor;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.DyeColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.block.Beehive;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Animals;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fish;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.MushroomCow;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityEnterBlockEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.SheepDyeWoolEvent;
import org.bukkit.event.entity.SheepRegrowWoolEvent;
import org.bukkit.event.entity.SlimeSplitEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@SuppressWarnings({"unused", "WeakerAccess"})
public final class EntitiesListener implements Listener {

    private static final Map<EntityDamageEvent.DamageModifier, ? extends Function<? super Double, Double>> damageModifiersFunctions =
            Maps.newEnumMap(ImmutableMap.of(EntityDamageEvent.DamageModifier.BASE, Functions.constant(-0.0D)));
    private final static Map<Location, Integer[]> beesAmount = new HashMap<>();

    public final static Set<UUID> noStackEntities = new HashSet<>();

    private final Set<UUID> noDeathEvent = new HashSet<>();
    private final WildStackerPlugin plugin;

    public EntitiesListener(WildStackerPlugin plugin){
        this.plugin = plugin;
        if(ServerVersion.isAtLeast(ServerVersion.v1_13))
            plugin.getServer().getPluginManager().registerEvents(new TransformListener(plugin), plugin);
        if(ServerVersion.isAtLeast(ServerVersion.v1_15))
            plugin.getServer().getPluginManager().registerEvents(new BeeListener(plugin), plugin);
        if(ReflectionUtils.isPluginEnabled("com.ome_r.wildstacker.enchantspatch.events.EntityKillEvent"))
            plugin.getServer().getPluginManager().registerEvents(new EntityKillListener(), plugin);
        if(ReflectionUtils.isPluginEnabled("com.ome_r.wildstacker.enchantspatch.events.EntityRemoveEvent"))
            plugin.getServer().getPluginManager().registerEvents(new EntityRemoveListener(plugin), plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeathMonitor(EntityDeathEvent e){
        if(EntityStorage.hasMetadata(e.getEntity(), "corpse") && !((WStackedEntity) WStackedEntity.of(e.getEntity())).hasDeadFlag()){
            try {
                e.getDrops().clear();
                e.setDroppedExp(0);
            }catch(Throwable ex){
                WildStackerPlugin.log("Seems like the array of EntityDeathEvent is not an ArrayList, but a " + e.getDrops().getClass());
                ex.printStackTrace();
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityPickup(EntityPickupItemEvent e){
        if(EntityStorage.hasMetadata(e.getEntity(), "corpse")){
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCustomEntityDeath(EntityDeathEvent e){
        //Checks if the entity is not a corpse.
        if(EntityStorage.hasMetadata(e.getEntity(), "corpse") || !EntityUtils.isStackable(e.getEntity()))
            return;

        if(((WStackedEntity) WStackedEntity.of(e.getEntity())).hasDeadFlag()){
            //deadEntities.remove(e.getEntity().getUniqueId());
            return;
        }

        noDeathEvent.add(e.getEntity().getUniqueId());

        //Calling the onEntityLastDamage function with default parameters.
        onEntityLastDamage(createDamageEvent(e.getEntity(), EntityDamageEvent.DamageCause.CUSTOM, e.getEntity().getHealth(), null));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDeadEntityDamage(EntityDamageEvent e){
        if(EntityUtils.isStackable(e.getEntity()) &&
                ((WStackedEntity) WStackedEntity.of(e.getEntity())).hasDeadFlag()) {
            e.setDamage(0);
        }
        if(EntityStorage.hasMetadata(e.getEntity(), "corpse")) {
            e.setCancelled(true);
            e.getEntity().remove();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityNerfDamage(EntityDamageByEntityEvent e){
        if(!EntityUtils.isStackable(e.getDamager()))
            return;

        StackedEntity stackedEntity = WStackedEntity.of(e.getDamager());

        if(stackedEntity.isNerfed()){
            e.setCancelled(true);
            e.setDamage(0);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityNerfTeleport(EntityTeleportEvent e){
        if(!plugin.getSettings().nerfedEntitiesTeleport && EntityUtils.isStackable(e.getEntity()) &&
                WStackedEntity.of(e.getEntity()).isNerfed())
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityLastDamage(EntityDamageEvent e){
        if(!EntityUtils.isStackable(e.getEntity()))
            return;

        LivingEntity livingEntity = (LivingEntity) e.getEntity();
        StackedEntity stackedEntity = WStackedEntity.of(livingEntity);

        if(((WStackedEntity) stackedEntity).hasDeadFlag()){
            e.setDamage(0);
            return;
        }

        Entity entityDamager = null;
        Player damager = null;

        if (e instanceof EntityDamageByEntityEvent) {
            entityDamager = ((EntityDamageByEntityEvent) e).getDamager();
            if(entityDamager instanceof Player) {
                damager = (Player) entityDamager;
            }
            else if(entityDamager instanceof Projectile){
                Projectile projectile = (Projectile) entityDamager;
                if(projectile.getShooter() instanceof Player)
                    damager = (Player) projectile.getShooter();
            }
        }

        ItemStack damagerTool = damager == null ? new ItemStack(Material.AIR) : damager.getItemInHand();
        boolean creativeMode = damager != null && damager.getGameMode() == GameMode.CREATIVE;

        boolean oneShot = damager != null && plugin.getSettings().entitiesOneShotEnabled &&
                GeneralUtils.contains(plugin.getSettings().entitiesOneShotWhitelist, stackedEntity) &&
                plugin.getSettings().entitiesOneShotTools.contains(damagerTool.getType().toString());

        //Checks that it's the last hit of the entity
        if(stackedEntity.getHealth() - e.getFinalDamage() > 0 && !oneShot)
            return;

        if(plugin.getSettings().entitiesStackingEnabled || stackedEntity.getStackAmount() > 1) {
            EntityDamageEvent.DamageCause lastDamageCause = e.getCause();
            int stackAmount = Math.min(stackedEntity.getStackAmount(),
                    stackedEntity.isInstantKill(lastDamageCause) ? stackedEntity.getStackAmount() : stackedEntity.getDefaultUnstack());

            int fireTicks = livingEntity.getFireTicks();

            if(!plugin.getSettings().nextStackKnockback)
                e.setCancelled(true);

            if(damager != null)
                McMMOHook.handleCombat(damager, entityDamager, livingEntity, e.getFinalDamage());

            EntityDamageEvent clonedEvent = createDamageEvent(e.getEntity(), e.getCause(), e.getDamage(), entityDamager);

            e.setDamage(0);
            livingEntity.setHealth(livingEntity.getMaxHealth());

            //Villager was killed by a zombie - should be turned into a zombie villager.
            if(livingEntity.getType() == EntityType.VILLAGER && EntityUtils.killedByZombie(livingEntity)){
                boolean spawnZombie = false;

                switch (livingEntity.getWorld().getDifficulty()){
                    case NORMAL:
                        spawnZombie = ThreadLocalRandom.current().nextBoolean();
                        break;
                    case HARD:
                        spawnZombie = true;
                        break;
                }

                if(spawnZombie){
                    Zombie zombieVillager = plugin.getNMSAdapter().spawnZombieVillager((Villager) livingEntity);
                    if(zombieVillager != null) {
                        StackedEntity stackedZombie = WStackedEntity.of(zombieVillager);
                        if (StackSplit.VILLAGER_INFECTION.isEnabled()) {
                            stackedEntity.runUnstack(1, entityDamager);
                        } else {
                            stackedZombie.setStackAmount(stackedEntity.getStackAmount(), true);
                            stackedEntity.remove();
                        }
                        stackedZombie.updateName();
                        stackedZombie.runStackAsync(null);
                    }

                    return;
                }
            }

            if(stackedEntity.runUnstack(stackAmount, entityDamager) == UnstackResult.SUCCESS) {
                ((WStackedEntity) stackedEntity).setDeadFlag(true);

                EntityUtils.setKiller(livingEntity, damager);

                ItemStack itemInHand = livingEntity.getKiller() == null ? null : livingEntity.getKiller().getItemInHand();

                int lootBonusLevel = itemInHand == null ? 0 : itemInHand.getEnchantmentLevel(Enchantment.LOOT_BONUS_MOBS);

                Executor.async(() -> {
                    livingEntity.setLastDamageCause(clonedEvent);
                    livingEntity.setFireTicks(fireTicks);

                    List<ItemStack> drops = stackedEntity.getDrops(lootBonusLevel, stackAmount);
                    int droppedExp = stackedEntity.getExp(stackAmount, 0);

                    Executor.sync(() -> {
                        plugin.getNMSAdapter().setEntityDead(livingEntity, true);

                        int currentStackAmount = stackedEntity.getStackAmount();

                        stackedEntity.setStackAmount(stackAmount, false);

                        McMMOHook.updateCachedName(livingEntity);
                        boolean isMcMMOSpawnedEntity = McMMOHook.isSpawnedEntity(livingEntity);

                        EntityDeathEvent entityDeathEvent = new EntityDeathEvent(livingEntity, new ArrayList<>(drops), droppedExp);

                        boolean spawnDuplicate = false;

                        if(!noDeathEvent.contains(e.getEntity().getUniqueId()))
                            Bukkit.getPluginManager().callEvent(entityDeathEvent);
                        else {
                            spawnDuplicate = true;
                            noDeathEvent.remove(e.getEntity().getUniqueId());
                        }

                        plugin.getNMSAdapter().setEntityDead(livingEntity, false);

                        stackedEntity.setStackAmount(currentStackAmount, false);

                        livingEntity.setLastDamageCause(null);

                        if(isMcMMOSpawnedEntity)
                            McMMOHook.updateSpawnedEntity(livingEntity);

                        JobsHook.updateSpawnReason(livingEntity, stackedEntity.getSpawnCause());

                        List<ItemStack> eventDrops = new ArrayList<>(entityDeathEvent.getDrops());
                        entityDeathEvent.getDrops().clear();
                        entityDeathEvent.getDrops().addAll(eventDrops.stream()
                                .filter(itemStack -> itemStack != null && itemStack.getType() != Material.AIR).collect(Collectors.toList()));

                        //Multiply items that weren't added in the first place
                        subtract(drops, entityDeathEvent.getDrops())
                                .forEach(itemStack -> itemStack.setAmount(itemStack.getAmount() * stackAmount));

                        Location dropLocation = livingEntity.getLocation();

                        entityDeathEvent.getDrops().stream()
                                .filter(itemStack -> itemStack != null && itemStack.getType() != Material.AIR)
                                .forEach(itemStack -> ItemUtils.dropItem(itemStack, dropLocation));

                        if (entityDeathEvent.getDroppedExp() > 0) {
                            if(GeneralUtils.contains(plugin.getSettings().entitiesAutoExpPickup, stackedEntity) && livingEntity.getKiller() != null) {
                                livingEntity.getKiller().giveExp(entityDeathEvent.getDroppedExp());
                                livingEntity.getKiller().playSound(livingEntity.getLocation(),
                                        Sound.valueOf(ServerVersion.isAtLeast(ServerVersion.v1_9) ? "ENTITY_EXPERIENCE_ORB_PICKUP" : "LEVEL_UP"), 0.1F, 0.1F);
                            }
                            else {
                                EntityUtils.spawnExp(livingEntity.getLocation(), entityDeathEvent.getDroppedExp());
                            }
                        }

                        if(livingEntity.getKiller() != null && EntityTypes.fromEntity(livingEntity).isRaider()){
                            org.bukkit.entity.Raider raider = (org.bukkit.entity.Raider) livingEntity;
                            if(raider.isPatrolLeader()){
                                livingEntity.getKiller().addPotionEffect(new PotionEffect(
                                        PotionEffectType.getByName("BAD_OMEN"),
                                        120000,
                                        EntityUtils.getBadOmenAmplifier(livingEntity.getKiller()),
                                        false
                                ));
                            }

                            plugin.getNMSAdapter().attemptJoinRaid(livingEntity.getKiller(), raider);
                        }

                        //Decrease durability when next-stack-knockback is false
                        if(e.isCancelled() && itemInHand != null && !creativeMode) {
                            int damage = ItemUtils.isSword(itemInHand.getType()) ? 1 : ItemUtils.isTool(itemInHand.getType()) ? 2 : 0;
                            ThreadLocalRandom random = ThreadLocalRandom.current();
                            if(damage > 0) {
                                int unbreakingLevel = itemInHand.getEnchantmentLevel(Enchantment.DURABILITY);
                                int damageDecrease = 0;

                                for(int i = 0; unbreakingLevel > 0 && i < damage; i++){
                                    if(random.nextInt(damage + 1) > 0)
                                        damageDecrease++;
                                }

                                damage -= damageDecrease;

                                if(damage > 0) {
                                    if(itemInHand.getDurability() + damage > itemInHand.getType().getMaxDurability())
                                        livingEntity.getKiller().setItemInHand(new ItemStack(Material.AIR));
                                    else
                                        itemInHand.setDurability((short) (itemInHand.getDurability() + damage));
                                }
                            }
                        }

                        ((WStackedEntity) stackedEntity).setDeadFlag(false);

                        if(spawnDuplicate && stackedEntity.getStackAmount() > 1){
                            stackedEntity.spawnDuplicate(stackedEntity.getStackAmount());
                            stackedEntity.remove();
                        }

                    });
                });

                if (plugin.getSettings().keepFireEnabled && livingEntity.getFireTicks() > -1)
                    livingEntity.setFireTicks(160);

                if (livingEntity.getKiller() != null) {
                    Player killer = livingEntity.getKiller();

                    try {
                        StatisticsUtils.incrementStatistic(killer, Statistic.MOB_KILLS, stackAmount);
                        StatisticsUtils.incrementStatistic(killer, Statistic.KILL_ENTITY, stackedEntity.getType(), stackAmount);
                    } catch (IllegalArgumentException ignored) { }

                    //Achievements
                    EntityType victimType = livingEntity.getType();

                    //Monster Hunter
                    grandAchievement(killer, victimType, "KILL_ENEMY");
                    grandAchievement(killer, victimType, "adventure/kill_a_mob");
                    //Monsters Hunted
                    grandAchievement(killer, victimType, "adventure/kill_all_mobs");
                    //Sniper Duel
                    if(killer.getLocation().distanceSquared(livingEntity.getLocation()) >= 50*50){
                        grandAchievement(killer, "", "SNIPE_SKELETON");
                        grandAchievement(killer, "killed_skeleton", "adventure/sniper_duel");
                    }

                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityTeleport(EntityTeleportEvent e){
        if(EntityUtils.isStackable(e.getEntity()) && ((WStackedEntity) WStackedEntity.of(e.getEntity())).hasDeadFlag())
            e.setCancelled(true);
    }

    private void grandAchievement(Player killer, EntityType entityType, String name){
        try{
            plugin.getNMSAdapter().grandAchievement(killer, entityType, name);
        }catch(Throwable ignored){}
    }

    private void grandAchievement(Player killer, String criteria, String name){
        try{
            plugin.getNMSAdapter().grandAchievement(killer, criteria, name);
        }catch(Throwable ignored){}
    }

    private List<ItemStack> subtract(List<ItemStack> list1, List<ItemStack> list2){
        List<ItemStack> toReturn = new ArrayList<>(list2);
        toReturn.removeAll(list1);
        return toReturn;
    }

    private int mooshroomFlag = -1;

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntitySpawnLow(CreatureSpawnEvent e){
        if(EntityUtils.isStackable(e.getEntity()) && EntityTypes.fromEntity(e.getEntity()).isSlime()){
            int originalSize = ((Slime) e.getEntity()).getSize();
            EntityUtils.getNearbyEntities(e.getEntity().getLocation(), 2,
                    entity -> entity instanceof Slime && originalSize * 2 == ((Slime) entity).getSize() && EntityStorage.hasMetadata(entity, "original-amount"))
                    .whenComplete((nearbyEntities, ex) -> nearbyEntities.stream().findFirst().ifPresent(entity -> {
                        int entitySize = EntityStorage.getMetadata(entity, "original-amount", Integer.class);
                        WStackedEntity.of(e.getEntity()).setStackAmount(entitySize, true);
                    }));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntitySpawn(CreatureSpawnEvent e){
        handleEntitySpawn(e.getEntity(), e.getSpawnReason());
    }

    private void handleEntitySpawn(LivingEntity entity, CreatureSpawnEvent.SpawnReason spawnReason){
        if(!plugin.getSettings().entitiesStackingEnabled)
            return;

        if(!EntityUtils.isStackable(entity) || EntityStorage.hasMetadata(entity, "corpse"))
            return;

        SpawnCause spawnCause = SpawnCause.valueOf(spawnReason);

        EntityStorage.setMetadata(entity, "spawn-cause", spawnCause);
        StackedEntity stackedEntity = WStackedEntity.of(entity);

        if(mooshroomFlag != -1){
            stackedEntity.setStackAmount(mooshroomFlag, true);
            mooshroomFlag = -1;
        }

        if(spawnCause == SpawnCause.BEEHIVE && EntityTypes.fromEntity(entity) == EntityTypes.BEE){
            org.bukkit.entity.Bee bee = (org.bukkit.entity.Bee) entity;
            Integer[] beesAmount = EntitiesListener.beesAmount.get(bee.getHive());
            if(beesAmount != null){
                for(int i = beesAmount.length - 1; i >= 0; i--){
                    if(beesAmount[i] != -1){
                        stackedEntity.setStackAmount(beesAmount[i], true);
                        beesAmount[i] = -1;
                        break;
                    }
                    if(i == 0)
                        EntitiesListener.beesAmount.remove(bee.getHive());
                }
            }
        }

        if(stackedEntity.isBlacklisted() || !stackedEntity.isWhitelisted() || stackedEntity.isWorldDisabled())
            return;

        if(noStackEntities.contains(entity.getUniqueId())) {
            noStackEntities.remove(entity.getUniqueId());
            return;
        }

        //Chunk Limit
        Executor.sync(() -> {
            if(isChunkLimit(entity.getLocation().getChunk()))
                stackedEntity.remove();
        }, 5L);

        //EpicSpawners has it's own event
        if(stackedEntity.getSpawnCause() == SpawnCause.EPIC_SPAWNERS)
            return;

        if (!PluginHooks.isMergedSpawnersEnabled && spawnReason == CreatureSpawnEvent.SpawnReason.SPAWNER)
            return;

        Consumer<Optional<LivingEntity>> entityConsumer = entityOptional -> {
            if(!entityOptional.isPresent())
                stackedEntity.updateNerfed();
        };

        //Need to add a delay so eggs will get removed from inventory
        if(spawnReason == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG || entity.getType() == EntityType.WITHER ||
                entity.getType() == EntityType.IRON_GOLEM || entity.getType() == EntityType.SNOWMAN ||
                PluginHooks.isMythicMobsEnabled || PluginHooks.isEpicBossesEnabled)
            Executor.sync(() -> stackedEntity.runStackAsync(entityConsumer), 1L);
        else
            stackedEntity.runStackAsync(entityConsumer);
    }

    private int nextEntityStackAmount = -1;
    private EntityTypes nextEntityType = null;

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpawnerEggUse(PlayerInteractEvent e){
        if(!plugin.getSettings().entitiesStackingEnabled || e.getItem() == null || e.getAction() != Action.RIGHT_CLICK_BLOCK ||
                plugin.getSettings().blacklistedEntitiesSpawnReasons.contains("SPAWNER_EGG") ||
                (!Materials.isValidAndSpawnEgg(e.getItem()) && !Materials.isFishBucket(e.getItem())))
            return;

        nextEntityStackAmount = ItemUtils.getSpawnerItemAmount(e.getItem());

        if(Materials.isValidAndSpawnEgg(e.getItem())) {
            nextEntityType = ItemUtils.getEntityType(e.getItem());

            if (nextEntityType == null) {
                nextEntityStackAmount = -1;
                return;
            }

            EntityType nmsEntityType = ItemUtils.getNMSEntityType(e.getItem());
            if (nmsEntityType != null) {
                e.setCancelled(true);
                nextEntityType = EntityTypes.fromName(nmsEntityType.name());
                Location toSpawn = e.getClickedBlock().getRelative(e.getBlockFace()).getLocation().add(0.5, 0, 0.5);
                if (toSpawn.getBlock().getType() != Material.AIR)
                    toSpawn = toSpawn.add(0, 1, 0);
                plugin.getSystemManager().spawnEntityWithoutStacking(toSpawn, nmsEntityType.getEntityClass(), SpawnCause.SPAWNER_EGG);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntitySpawnFromEgg(CreatureSpawnEvent e){
        if(!EntityUtils.isStackable(e.getEntity()))
            return;

        if(e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.SPAWNER_EGG || nextEntityStackAmount <= 0 ||
                (nextEntityType != null && EntityTypes.fromEntity(e.getEntity()) != nextEntityType))
            return;

        EntityStorage.setMetadata(e.getEntity(), "spawn-cause", SpawnCause.valueOf(e.getSpawnReason()));
        StackedEntity stackedEntity = WStackedEntity.of(e.getEntity());
        stackedEntity.setStackAmount(nextEntityStackAmount, false);

        Executor.sync(() -> {
            //Resetting the name, so updateName will work.
            e.getEntity().setCustomName("");
            stackedEntity.updateName();
        }, 1L);

        nextEntityStackAmount = -1;
        nextEntityType = null;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerCatchFishWithBucket(PlayerInteractEntityEvent e){
        ItemStack inHand = e.getPlayer().getItemInHand();

        //noinspection deprecation
        if(!(e.getRightClicked() instanceof Fish) || ServerVersion.isLegacy() || inHand == null || inHand.getType() != Material.WATER_BUCKET)
            return;

        StackedEntity stackedEntity = WStackedEntity.of(e.getRightClicked());

        if(stackedEntity.getStackAmount() <= 1)
            return;

        Material fishBucketType = null;

        try {
            switch (EntityTypes.fromEntity((LivingEntity) e.getRightClicked())) {
                case COD:
                    fishBucketType = Material.valueOf("COD_BUCKET");
                    break;
                case PUFFERFISH:
                    fishBucketType = Material.valueOf("PUFFERFISH_BUCKET");
                    break;
                case SALMON:
                    fishBucketType = Material.valueOf("SALMON_BUCKET");
                    break;
                case TROPICAL_FISH:
                    fishBucketType = Material.valueOf("TROPICAL_FISH_BUCKET");
                    break;
            }
        }catch (Exception ignored){}

        if(fishBucketType == null)
            return;

        e.setCancelled(true);

        ItemStack fishBucketItem = ItemUtils.setSpawnerItemAmount(new ItemStack(fishBucketType), stackedEntity.getStackAmount());

        stackedEntity.remove();

        inHand = inHand.clone();
        inHand.setAmount(1);
        ItemUtils.removeItem(inHand, e);

        ItemUtils.addItem(fishBucketItem, e.getPlayer().getInventory(), e.getRightClicked().getLocation());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSlimeSplit(SlimeSplitEvent e){
        //Fixes a async catch exception with auto-clear task
        if(!Bukkit.isPrimaryThread())
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSheepDye(SheepDyeWoolEvent e){
        if(!plugin.getSettings().entitiesStackingEnabled || !StackSplit.SHEEP_DYE.isEnabled())
            return;

        StackedEntity stackedEntity = WStackedEntity.of(e.getEntity());
        int amount = stackedEntity.getStackAmount();

        DyeColor originalColor = e.getEntity().getColor();

        Executor.sync(() -> {
            if(amount > 1) {
                e.getEntity().setColor(originalColor);
                stackedEntity.setStackAmount(amount - 1, true);
                StackedEntity duplicate = stackedEntity.spawnDuplicate(1);
                ((Sheep) duplicate.getLivingEntity()).setColor(e.getColor());
                //EntityData.of(duplicate).loadEntityData(duplicate.getLivingEntity());
                duplicate.runStackAsync(null);
            }else{
                stackedEntity.runStackAsync(null);
            }
        }, 1L);
    }


    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerShear(PlayerShearEntityEvent e){
        if(!plugin.getSettings().entitiesStackingEnabled || !EntityUtils.isStackable(e.getEntity()))
            return;

        StackedEntity stackedEntity = WStackedEntity.of(e.getEntity());

        if(e.getEntity() instanceof MushroomCow){
            if(StackSplit.MUSHROOM_SHEAR.isEnabled()) {
                stackedEntity.spawnDuplicate(stackedEntity.getStackAmount() - 1);
                stackedEntity.remove();
            }
            else{
                int mushroomAmount = 5 * (stackedEntity.getStackAmount() - 1);
                ItemStack mushroomItem = new ItemStack(Material.RED_MUSHROOM, mushroomAmount);
                ItemUtils.dropItem(mushroomItem, e.getEntity().getLocation());
                mooshroomFlag = stackedEntity.getStackAmount();
            }
        }

        else if(e.getEntity() instanceof Sheep){
            int stackAmount = stackedEntity.getStackAmount();
            if(StackSplit.SHEEP_SHEAR.isEnabled()) {
                Executor.sync(() -> {
                    if (stackAmount > 1) {
                        ((Sheep) e.getEntity()).setSheared(false);
                        stackedEntity.setStackAmount(stackAmount - 1, true);
                        StackedEntity duplicate = stackedEntity.spawnDuplicate(1);
                        ((Sheep) duplicate.getLivingEntity()).setSheared(true);
                        duplicate.runStackAsync(null);
                    } else {
                        stackedEntity.runStackAsync(null);
                    }
                }, 0L);
            }
            else if(stackAmount > 1){
                int woolAmount = ThreadLocalRandom.current().nextInt(2 * (stackAmount - 1)) + stackAmount - 1;
                ItemStack woolItem = Materials.getWool((((Sheep) e.getEntity()).getColor()));
                woolItem.setAmount(woolAmount);
                ItemUtils.dropItem(woolItem, e.getEntity().getLocation());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWoolRegrow(SheepRegrowWoolEvent e){
        if(!plugin.getSettings().entitiesStackingEnabled)
            return;

        StackedEntity stackedEntity = WStackedEntity.of(e.getEntity());

        int amount = stackedEntity.getStackAmount();

        if(!e.getEntity().isSheared())
            return;

        Executor.sync(() -> {
            if(amount > 1){
                e.getEntity().setSheared(true);
                stackedEntity.setStackAmount(amount - 1, true);
                StackedEntity duplicated = stackedEntity.spawnDuplicate(1);
                ((Sheep) duplicated.getLivingEntity()).setSheared(false);
                duplicated.runStackAsync(null);
            }else{
                stackedEntity.runStackAsync(null);
            }
        }, 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityNameTag(PlayerInteractEntityEvent e){
        ItemStack inHand = e.getPlayer().getInventory().getItemInHand();

        if(inHand == null || inHand.getType() != Material.NAME_TAG || !inHand.hasItemMeta() || !inHand.getItemMeta().hasDisplayName()
                || e.getRightClicked() instanceof EnderDragon || e.getRightClicked() instanceof Player)
                return;

        if(plugin.getSettings().entitiesStackingEnabled && EntityUtils.isStackable(e.getRightClicked()) && StackSplit.NAME_TAG.isEnabled()) {
            StackedEntity stackedEntity = WStackedEntity.of(e.getRightClicked());
            int amount = stackedEntity.getStackAmount();

            Executor.sync(() -> {
                if (amount > 1) {
                    stackedEntity.setCustomName("");
                    stackedEntity.setStackAmount(amount - 1, true);
                    StackedEntity duplicated = stackedEntity.spawnDuplicate(1);
                    duplicated.setCustomName(inHand.getItemMeta().getDisplayName());
                    EntityStorage.setMetadata(duplicated.getLivingEntity(), "nameTag", true);
                } else {
                    EntityStorage.setMetadata(stackedEntity.getLivingEntity(), "nameTag", true);
                    stackedEntity.runStackAsync(null);
                }
            }, 2L);
        }
        else{
            EntityStorage.setMetadata(e.getRightClicked(), "nameTag", true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityFeed(PlayerInteractEntityEvent e){
        if(!StackSplit.ENTITY_BREED.isEnabled() || !(e.getRightClicked() instanceof Animals) || ItemUtils.isOffHand(e))
            return;

        if(!plugin.getNMSAdapter().isAnimalFood((Animals) e.getRightClicked(), e.getPlayer().getItemInHand()))
            return;

        if(!plugin.getNMSAdapter().canBeBred((Animals) e.getRightClicked()))
            return;

        StackedEntity stackedEntity = WStackedEntity.of(e.getRightClicked());
        int amount = stackedEntity.getStackAmount();

        if(amount > 1){
            e.setCancelled(true);

            stackedEntity.setStackAmount(amount - 1, true);
            StackedEntity duplicated = stackedEntity.spawnDuplicate(1);

            plugin.getNMSAdapter().setInLove((Animals) duplicated.getLivingEntity(), e.getPlayer(), true);

            if(e.getPlayer().getGameMode() != GameMode.CREATIVE) {
                ItemStack inHand = e.getPlayer().getItemInHand().clone();
                inHand.setAmount(inHand.getAmount() - 1);
                ItemUtils.setItemInHand(e.getPlayer().getInventory(), e.getPlayer().getItemInHand(), inHand);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityBreed(CreatureSpawnEvent e){
        if(e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.BREEDING && plugin.getSettings().stackAfterBreed){
            EntityUtils.getNearbyEntities(e.getEntity().getLocation(), 5, entity -> EntityUtils.isStackable(entity) &&
                    (!(entity instanceof Animals) || !plugin.getNMSAdapter().isInLove((Animals) entity)))
                    .whenComplete((nearbyEntities, ex) -> nearbyEntities.forEach(entity -> WStackedEntity.of(entity).runStackAsync(null)));
        }
    }

    @EventHandler
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent e){
        if(!plugin.getSettings().entitiesNamesToggleEnabled)
            return;

        String commandSyntax = "/" + plugin.getSettings().entitiesNamesToggleCommand;

        if(!e.getMessage().equalsIgnoreCase(commandSyntax) && !e.getMessage().startsWith(commandSyntax + " "))
            return;

        e.setCancelled(true);

        if(!PluginHooks.isProtocolLibEnabled){
            e.getPlayer().sendMessage(ChatColor.RED + "The command is enabled but ProtocolLib is not installed. Please contact the administrators of the server to solve the issue.");
            return;
        }

        if(ProtocolLibHook.entitiesDisabledNames.contains(e.getPlayer().getUniqueId())){
            ProtocolLibHook.entitiesDisabledNames.remove(e.getPlayer().getUniqueId());
            Locale.ENTITY_NAMES_TOGGLE_ON.send(e.getPlayer());
        }
        else{
            ProtocolLibHook.entitiesDisabledNames.add(e.getPlayer().getUniqueId());
            Locale.ENTITY_NAMES_TOGGLE_OFF.send(e.getPlayer());
        }

        //Refresh item names
        EntityUtils.getNearbyEntities(e.getPlayer().getLocation(), 48, entity -> EntityUtils.isStackable(entity) && entity.isCustomNameVisible())
                .whenComplete((nearbyEntities, ex) -> nearbyEntities.forEach(entity -> ProtocolLibHook.updateName(e.getPlayer(), entity)));
    }

    private boolean isChunkLimit(Chunk chunk){
        int chunkLimit = plugin.getSettings().entitiesChunkLimit;

        if(chunkLimit <= 0)
            return false;

        return (int) Arrays.stream(chunk.getEntities()).filter(EntityUtils::isStackable).count() > chunkLimit;
    }

    class EntityKillListener implements Listener {

        @EventHandler
        public void onEntityKill(com.ome_r.wildstacker.enchantspatch.events.EntityKillEvent e){
            if(EntityStorage.hasMetadata(e.getEntity(), "corpse"))
                return;

            e.setCancelled(true);

            onEntityLastDamage(createDamageEvent(e.getEntity(), EntityDamageEvent.DamageCause.CUSTOM, e.getEntity().getHealth(), null));
        }

    }

    private static class EntityRemoveListener implements Listener {

        private final WildStackerPlugin plugin;

        private EntityRemoveListener(WildStackerPlugin plugin){
            this.plugin = plugin;
        }

        @EventHandler
        public void onEntityKill(com.ome_r.wildstacker.enchantspatch.events.EntityRemoveEvent e){
            WStackedEntity stackedEntity = (WStackedEntity) WStackedEntity.of(e.getEntity());

            if(stackedEntity.wasRemoved())
                return;

            plugin.getSystemManager().removeStackObject(stackedEntity);
        }

    }

    private static EntityDamageEvent createDamageEvent(Entity entity, EntityDamageEvent.DamageCause damageCause, double damage, Entity damager){
        Map<EntityDamageEvent.DamageModifier, Double> damageModifiers = Maps.newEnumMap(ImmutableMap.of(EntityDamageEvent.DamageModifier.BASE, damage));
        if(damager == null){
            return new EntityDamageEvent(entity, damageCause, damageModifiers, damageModifiersFunctions);
        }
        else{
            return new EntityDamageByEntityEvent(damager, entity, damageCause, damageModifiers, damageModifiersFunctions);
        }
    }

    private static class TransformListener implements Listener {

        private final WildStackerPlugin plugin;

        TransformListener(WildStackerPlugin plugin){
            this.plugin = plugin;
        }

        @EventHandler
        public void onEntityTransform(org.bukkit.event.entity.EntityTransformEvent e){
            if(e.getTransformReason() != EntityTransformEvent.TransformReason.DROWNED &&
                    e.getTransformReason() != EntityTransformEvent.TransformReason.CURED)
                return;

            StackedEntity stackedEntity = WStackedEntity.of(e.getEntity());

            if(stackedEntity.getStackAmount() > 1){
                StackedEntity transformedEntity = WStackedEntity.of(e.getTransformedEntity());
                boolean multipleEntities = !plugin.getSettings().entitiesStackingEnabled || !transformedEntity.isWhitelisted() ||
                        transformedEntity.isBlacklisted() || transformedEntity.isWorldDisabled();

                if(multipleEntities){
                    for(int i = 0; i < stackedEntity.getStackAmount() - 1; i++){
                        plugin.getSystemManager().spawnEntityWithoutStacking(e.getTransformedEntity().getLocation(), e.getTransformedEntity().getClass());
                    }
                }
                else {
                    StackedEntity transformed = WStackedEntity.of(e.getTransformedEntity());
                    transformed.setStackAmount(stackedEntity.getStackAmount(), true);
                }

                stackedEntity.remove();
            }
        }

    }

    private static class BeeListener implements Listener {

        private final WildStackerPlugin plugin;

        BeeListener(WildStackerPlugin plugin){
            this.plugin = plugin;
        }

        @EventHandler
        public void onBee(EntityEnterBlockEvent e){
            if(e.getBlock().getState() instanceof Beehive && EntityUtils.isStackable(e.getEntity())){
                Beehive beehive = (Beehive) e.getBlock().getState();
                StackedEntity stackedEntity = WStackedEntity.of(e.getEntity());

                if(!stackedEntity.isWhitelisted() || stackedEntity.isBlacklisted() || stackedEntity.isWorldDisabled())
                    return;

                Integer[] arr = beesAmount.computeIfAbsent(e.getBlock().getLocation(), l -> {
                    Integer[] newArr = new Integer[beehive.getEntityCount()];
                    Arrays.fill(newArr, -1);
                    return newArr;
                });

                for(int i = 0; i < arr.length; i++){
                    if(arr[i] == -1){
                        arr[i] = stackedEntity.getStackAmount();
                        break;
                    }
                }

                plugin.getSystemManager().removeStackObject(stackedEntity);
            }
        }

    }

}
