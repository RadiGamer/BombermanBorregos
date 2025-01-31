package org.imradigamer.bombermanMinigame;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

public class BombermanMinigame extends JavaPlugin implements Listener {

    public static final String PREFIX = ChatColor.DARK_AQUA + "[BORREGOS] " + ChatColor.RESET;

    private Map<String, GameInstance> games = new HashMap<>();
    private int gameCounter = 1;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Bomberman Minigame Enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("Bomberman Minigame Disabled!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(PREFIX + ChatColor.RED + "¡Solo los jugadores pueden usar este comando!");
            return true;
        }

        Player player = (Player) sender;
        if (command.getName().equalsIgnoreCase("bomberman")) {
            joinGame(player);
            return true;
        }
        return false;
    }

    private void joinGame(Player player) {
        GameInstance availableGame = games.values().stream()
                .filter(game -> !game.isStarted() && game.getPlayerCount() < 4)
                .findFirst()
                .orElseGet(() -> createNewGame());

        availableGame.addPlayer(player);
    }

    private GameInstance createNewGame() {
        String gameName = "Game" + gameCounter++;
        String newWorldName = "BombermanArena_" + gameName;

        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv clone BombermanArena " + newWorldName);
        World newWorld = Bukkit.getWorld(newWorldName);

        if (newWorld == null) {
            getLogger().severe("Failed to create duplicate world for " + gameName);
            return null;
        }

        GameInstance newGame = new GameInstance(gameName, newWorld);
        games.put(gameName, newGame);
        return newGame;
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();

        for (GameInstance game : games.values()) {
            if (game.containsPlayer(player)) {
                game.handleBlockPlace(event);
                return;
            }
        }
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().clear();
    }

    private class GameInstance {
        private final String gameName;
        private final World world;
        private final List<UUID> players = new ArrayList<>();
        private final Map<UUID, Boolean> hasTNT = new HashMap<>();
        private boolean started = false;

        private final Location[] spawnPoints;

        public GameInstance(String gameName, World world) {
            this.gameName = gameName;
            this.world = world;

            this.spawnPoints = new Location[]{
                    new Location(world, -33, 59, 64),
                    new Location(world, -34, 59, -62),
                    new Location(world, 35, 59, -62),
                    new Location(world, 35, 59, 63)
            };
        }

        public void addPlayer(Player player) {
            if (players.size() < 4 && !players.contains(player.getUniqueId())) {
                players.add(player.getUniqueId());
                hasTNT.put(player.getUniqueId(), true);
                player.teleport(world.getSpawnLocation());
                player.getInventory().clear();
                player.sendMessage(PREFIX + ChatColor.GREEN + "Te has unido a " + gameName);
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
            }

            if (players.size() == 2 && !started) {
                startCountdown();
            }
        }

        public boolean containsPlayer(Player player) {
            return players.contains(player.getUniqueId());
        }

        public int getPlayerCount() {
            return players.size();
        }

        public boolean isStarted() {
            return started;
        }

        public void handleBlockPlace(BlockPlaceEvent event) {
            Player player = event.getPlayer();

            if (players.contains(player.getUniqueId()) && event.getBlock().getType() == Material.TNT) {
                if (!hasTNT.get(player.getUniqueId())) {
                    player.sendMessage(PREFIX + ChatColor.RED + "¡Solo puedes colocar un TNT a la vez!");
                    event.setCancelled(true);
                    return;
                }

                hasTNT.put(player.getUniqueId(), false);
                Location tntLocation = event.getBlock().getLocation();
                event.setCancelled(true);

                TNTPrimed tnt = (TNTPrimed) world.spawn(tntLocation.add(0.5, 0.0, 0.5), TNTPrimed.class);
                tnt.setFuseTicks(40);
                tnt.setIsIncendiary(false);

                new BukkitRunnable() {
                    @Override
                    public void run() {
                        world.createExplosion(tntLocation, 0F, false, false);
                        removeWallCluster(tntLocation);

                        Iterator<UUID> iterator = players.iterator();
                        while (iterator.hasNext()) {
                            UUID playerId = iterator.next();
                            Player target = Bukkit.getPlayer(playerId);
                            if (target != null && target.getLocation().distance(tntLocation) <= 3) {
                                iterator.remove();
                                hasTNT.remove(playerId);
                                target.sendMessage(PREFIX + ChatColor.RED + "¡Has sido eliminado!");
                                target.playSound(target.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 1.0f);
                                Bukkit.broadcastMessage(PREFIX + target.getName() + ChatColor.RED + " ha sido eliminado!");

                                World mainWorld = Bukkit.getWorld("world");
                                if (mainWorld != null) {
                                    target.teleport(mainWorld.getSpawnLocation());
                                    target.sendMessage(PREFIX + ChatColor.YELLOW + "Has sido enviado al mundo principal.");
                                }

                                if (players.size() == 1) {
                                    declareWinner();
                                }
                            }
                        }

                        player.getInventory().addItem(new ItemStack(Material.TNT, 1));
                        hasTNT.put(player.getUniqueId(), true);
                    }
                }.runTaskLater(BombermanMinigame.this, 40L);
            }
        }
        private void removeWallCluster(Location explosionLocation) {
            World world = explosionLocation.getWorld();
            if (world == null) return;

            Material targetMaterial = Material.BLUE_WOOL;
            Set<Block> visited = new HashSet<>();
            Queue<Block> queue = new LinkedList<>();

            Block centerBlock = explosionLocation.getBlock();

            Block[] adjacentBlocks = new Block[]{
                    centerBlock.getRelative(1, 0, 0),
                    centerBlock.getRelative(-1, 0, 0),
                    centerBlock.getRelative(0, 0, 1),
                    centerBlock.getRelative(0, 0, -1),
                    centerBlock.getRelative(0, 1, 0),
                    centerBlock.getRelative(0, -1, 0)
            };

            for (Block block : adjacentBlocks) {
                if (block.getType() == targetMaterial) {
                    queue.add(block);
                }
            }

            while (!queue.isEmpty()) {
                Block currentBlock = queue.poll();

                if (visited.contains(currentBlock)) continue;
                visited.add(currentBlock);

                if (currentBlock.getType() == targetMaterial) {
                    currentBlock.setType(Material.AIR);

                    queue.add(currentBlock.getRelative(1, 0, 0));
                    queue.add(currentBlock.getRelative(-1, 0, 0));
                    queue.add(currentBlock.getRelative(0, 0, 1));
                    queue.add(currentBlock.getRelative(0, 0, -1));
                    queue.add(currentBlock.getRelative(0, 1, 0));
                    queue.add(currentBlock.getRelative(0, -1, 0));
                }
            }
        }

        private void startCountdown() {
            started = true;
            sendMessageToLobby(PREFIX + ChatColor.YELLOW + gameName + " comenzará en 30 segundos!");

            new BukkitRunnable() {
                @Override
                public void run() {
                    if (players.size() >= 2) {
                        freezePlayers();
                        startGame();
                    } else {
                        started = false;
                        sendMessageToLobby(PREFIX + ChatColor.RED + gameName + " fue cancelado por falta de jugadores.");
                    }
                }
            }.runTaskLater(BombermanMinigame.this, 600L); // 30 seconds
        }

        private void freezePlayers() {
            for (UUID playerId : players) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null) {
                    player.setWalkSpeed(0.0f);
                    player.setFlySpeed(0.0f);
                }
            }
        }

        private void unfreezePlayers() {
            for (UUID playerId : players) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null) {
                    player.setWalkSpeed(0.2f);
                    player.setFlySpeed(0.2f);
                }
            }
        }

        private void sendMessageToLobby(String message) {
            for (UUID playerId : players) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null) {
                    player.sendMessage(message);
                }
            }
        }

        private void declareWinner() {
            if (players.size() == 1) {
                UUID winnerId = players.get(0);
                Player winner = Bukkit.getPlayer(winnerId);
                if (winner != null) {
                    sendMessageToLobby(PREFIX + ChatColor.GOLD + winner.getName() + " es el ganador!");
                    winner.playSound(winner.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
                }
            }
            endGame();
        }

        private void endGame() {
            sendMessageToLobby(PREFIX + ChatColor.YELLOW + "El juego ha terminado.");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv delete " + world.getName());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv confirm");
            games.remove(gameName);
        }
        private void startGame() {
            new BukkitRunnable() {
                int countdown = 3;

                @Override
                public void run() {
                    if (countdown > 0) {
                        for (UUID playerId : players) {
                            Player player = Bukkit.getPlayer(playerId);
                            if (player != null) {
                                player.sendTitle(ChatColor.RED + String.valueOf(countdown), "", 0, 20, 0);
                                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1.0f, 1.0f);
                            }
                        }
                        countdown--;
                    } else {
                        int index = 0;
                        for (UUID playerId : players) {
                            Player player = Bukkit.getPlayer(playerId);
                            if (player != null && index < spawnPoints.length) {
                                player.teleport(spawnPoints[index]);
                                index++;
                            }
                        }

                        unfreezePlayers();
                        for (UUID playerId : players) {
                            Player player = Bukkit.getPlayer(playerId);
                            if (player != null) {
                                player.sendTitle(ChatColor.GREEN + "¡Comienza!", "", 0, 20, 0);
                                player.getInventory().addItem(new ItemStack(Material.TNT, 1));
                            }
                        }

                        cancel();
                    }
                }
            }.runTaskTimer(BombermanMinigame.this, 0L, 20L);
        }

    }
}