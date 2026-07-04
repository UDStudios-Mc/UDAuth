package dev.underground.udauth;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class UDAuthPlugin extends JavaPlugin {
    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, Integer> attempts = new ConcurrentHashMap<>();
    private DataStore store;
    private PasswordHasher hasher;
    private Object fastLoginHook;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        store = new DataStore(getDataFolder());
        hasher = new PasswordHasher(Math.max(100_000,
                getConfig().getInt("security.pbkdf2-iterations", 210_000)));
        getServer().getPluginManager().registerEvents(new AuthListener(this), this);
        fastLoginHook = FastLoginHook.register(this);
        long titleRepeat = Math.max(20L, getConfig().getLong("titles.repeat-every-ticks", 60L));
        getServer().getScheduler().runTaskTimer(this, () -> getServer().getOnlinePlayers().forEach(player -> {
            if (!isAuthenticated(player)) {
                showAuthTitle(player);
                applyTemporaryBlindness(player, titleRepeat);
            }
        }), titleRepeat, titleRepeat);
        getServer().getOnlinePlayers().forEach(this::handleJoin);
        getLogger().info("UDAuth enabled. FastLogin hook is "
                + (fastLoginHook != null ? "registered." : "not active."));
    }

    @Override
    public void onDisable() {
        getServer().getOnlinePlayers().forEach(player -> player.removePotionEffect(PotionEffectType.BLINDNESS));
        authenticated.clear();
        pending.clear();
        attempts.clear();
        fastLoginHook = null;
    }

    boolean isAuthenticated(Player player) {
        return authenticated.contains(player.getUniqueId());
    }

    void handleJoin(Player player) {
        UUID id = player.getUniqueId();
        authenticated.remove(id);
        pending.remove(id);
        attempts.put(id, 0);

        if (isVerifiedPremium(player) && store.isPremium(player.getName(), player.getUniqueId())) {
            authenticated.add(id);
            attempts.remove(id);
            clearBlindness(player);
            message(player, "premium-bypass");
            teleportToConfigured(player, "spawns.end");
            return;
        }

        long titleRepeat = Math.max(20L, getConfig().getLong("titles.repeat-every-ticks", 60L));
        applyTemporaryBlindness(player, titleRepeat);

        getServer().getScheduler().runTask(this, () -> {
            if (!player.isOnline() || isAuthenticated(player)) return;
            teleportToConfigured(player, "spawns.start");
            message(player, store.isRegistered(player.getName()) ? "login" : "register");
            showAuthTitle(player);
        });

        long timeout = Math.max(10, getConfig().getLong("security.login-timeout-seconds", 90));
        getServer().getScheduler().runTaskLater(this, () -> {
            if (player.isOnline() && !isAuthenticated(player)) {
                player.kickPlayer(colored(rawMessage("kicked-timeout")));
            }
        }, timeout * 20L);
    }

    void handleQuit(Player player) {
        UUID id = player.getUniqueId();
        authenticated.remove(id);
        pending.remove(id);
        attempts.remove(id);
        player.removePotionEffect(PotionEffectType.BLINDNESS);
    }

    private boolean premiumBypassAvailable() {
        if (!getConfig().getBoolean("premium-bypass.enabled", true)) return false;
        boolean requireFastLogin = getConfig().getBoolean("premium-bypass.require-fastlogin", true);
        return !requireFastLogin || getServer().getPluginManager().isPluginEnabled("FastLogin");
    }

    private boolean isVerifiedPremium(Player player) {
        return premiumBypassAvailable() && player.getUniqueId().version() == 4;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("authspawnstart") || name.equals("authspawnend")) {
            return setSpawn(sender, name.equals("authspawnstart") ? "start" : "end");
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(colored(rawMessage("players-only")));
            return true;
        }
        if (name.equals("login")) return login(player, args);
        if (name.equals("register")) return register(player, args);
        return false;
    }

    private boolean login(Player player, String[] args) {
        if (isAuthenticated(player)) {
            message(player, "already-authenticated");
            return true;
        }
        if (args.length != 1) {
            message(player, "usage-login");
            return true;
        }
        if (!store.isRegistered(player.getName())) {
            message(player, "not-registered");
            return true;
        }
        if (!pending.add(player.getUniqueId())) {
            message(player, "authenticating");
            return true;
        }

        String storedHash = store.passwordHash(player.getName());
        char[] password = args[0].toCharArray();
        message(player, "authenticating");
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            boolean valid = hasher.verify(password, storedHash);
            Arrays.fill(password, '\0');
            getServer().getScheduler().runTask(this, () -> finishLogin(player, valid));
        });
        return true;
    }

    private void finishLogin(Player player, boolean valid) {
        pending.remove(player.getUniqueId());
        if (!player.isOnline() || isAuthenticated(player)) return;
        if (!valid) {
            int used = attempts.merge(player.getUniqueId(), 1, Integer::sum);
            int maximum = Math.max(1, getConfig().getInt("security.max-login-attempts", 5));
            int remaining = Math.max(0, maximum - used);
            if (remaining == 0) {
                player.kickPlayer(colored(rawMessage("kicked-attempts")));
            } else {
                player.sendMessage(prefixed(rawMessage("wrong-password")
                        .replace("%attempts%", Integer.toString(remaining))));
            }
            return;
        }
        try {
            store.markLogin(player.getName());
        } catch (IOException exception) {
            getLogger().warning("Could not save last login for " + player.getName() + ": " + exception.getMessage());
        }
        authenticate(player, "success-login");
    }

    private boolean register(Player player, String[] args) {
        if (isAuthenticated(player)) {
            message(player, "already-authenticated");
            return true;
        }
        if (store.isRegistered(player.getName())) {
            message(player, "already-registered");
            return true;
        }
        if (args.length != 2) {
            message(player, "usage-register");
            return true;
        }
        if (!args[0].equals(args[1])) {
            message(player, "passwords-differ");
            return true;
        }
        int min = Math.max(1, getConfig().getInt("security.password-min-length", 6));
        int max = Math.max(min, getConfig().getInt("security.password-max-length", 128));
        if (args[0].length() < min || args[0].length() > max) {
            player.sendMessage(prefixed(rawMessage("password-length")
                    .replace("%min%", Integer.toString(min))
                    .replace("%max%", Integer.toString(max))));
            return true;
        }
        if (!pending.add(player.getUniqueId())) {
            message(player, "authenticating");
            return true;
        }

        char[] password = args[0].toCharArray();
        message(player, "authenticating");
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            String hash = hasher.hash(password);
            Arrays.fill(password, '\0');
            getServer().getScheduler().runTask(this, () -> finishRegistration(player, hash));
        });
        return true;
    }

    private void finishRegistration(Player player, String hash) {
        pending.remove(player.getUniqueId());
        if (!player.isOnline() || isAuthenticated(player)) return;
        if (store.isRegistered(player.getName())) {
            message(player, "already-registered");
            return;
        }
        try {
            store.register(player.getName(), hash);
            authenticate(player, "success-register");
        } catch (IOException exception) {
            getLogger().severe("Could not save account for " + player.getName() + ": " + exception.getMessage());
            message(player, "account-save-error");
        }
    }

    private void authenticate(Player player, String message) {
        authenticated.add(player.getUniqueId());
        attempts.remove(player.getUniqueId());
        pending.remove(player.getUniqueId());
        clearBlindness(player);
        player.resetTitle();
        message(player, message);
        teleportToConfigured(player, "spawns.end");
    }

    boolean fastLoginIsRegistered(String name) {
        return store.isRegistered(name);
    }

    boolean fastLoginForceLogin(Player player) {
        try {
            store.enablePremium(player.getName(), player.getUniqueId());
            if (getServer().isPrimaryThread()) {
                if (!player.isOnline()) return false;
                authenticate(player, "premium-bypass");
                return true;
            }
            return getServer().getScheduler().callSyncMethod(this, () -> {
                if (!player.isOnline()) return false;
                authenticate(player, "premium-bypass");
                return true;
            }).get();
        } catch (Exception exception) {
            getLogger().severe("FastLogin could not save premium account " + player.getName()
                    + ": " + exception.getMessage());
            return false;
        }
    }

    boolean fastLoginForceRegister(Player player, String generatedPassword) {
        if (!player.isOnline()) return false;
        try {
            if (!store.isRegistered(player.getName())) {
                char[] password = generatedPassword.toCharArray();
                String hash;
                try {
                    hash = hasher.hash(password);
                } finally {
                    Arrays.fill(password, '\0');
                }
                store.register(player.getName(), hash);
            }
            return fastLoginForceLogin(player);
        } catch (IOException exception) {
            getLogger().severe("FastLogin could not register premium account " + player.getName()
                    + ": " + exception.getMessage());
            return false;
        }
    }

    private boolean setSpawn(CommandSender sender, String type) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(colored(rawMessage("players-only")));
            return true;
        }
        if (!sender.hasPermission("udauth.admin")) {
            message(player, "no-permission");
            return true;
        }
        Location location = player.getLocation().clone();
        location.setX(location.getBlockX() + 0.5);
        location.setY(location.getBlockY());
        location.setZ(location.getBlockZ() + 0.5);
        DataStore.setLocation(getConfig(), "spawns." + type, location);
        saveConfig();
        message(player, type.equals("start") ? "setup-start" : "setup-end");
        return true;
    }

    private void teleportToConfigured(Player player, String path) {
        Location location = getConfig().getLocation(path);
        if (location == null || location.getWorld() == null) {
            if (path.endsWith("start")) message(player, "spawn-missing");
            return;
        }
        player.teleport(location);
    }

    private void message(Player player, String key) {
        player.sendMessage(prefixed(rawMessage(key)));
    }

    private String rawMessage(String key) {
        return getConfig().getString("messages." + key, key);
    }

    private String prefixed(String text) {
        return colored(getConfig().getString("messages.prefix", "&8[&bUDAuth&8] &7") + text);
    }

    private String colored(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    void showAuthTitle(Player player) {
        boolean registered = store.isRegistered(player.getName());
        String key = registered ? "login" : "register";
        String title = getConfig().getString("titles." + key,
                registered ? "&b&lPlease Login" : "&b&lPlease Register");
        String subtitle = getConfig().getString("titles." + key + "-subtitle",
                registered ? "&f/login [password]" : "&f/register [password] [repeat]");
        int fadeIn = Math.max(0, getConfig().getInt("titles.fade-in-ticks", 10));
        int stay = Math.max(1, getConfig().getInt("titles.stay-ticks", 45));
        int fadeOut = Math.max(0, getConfig().getInt("titles.fade-out-ticks", 10));
        player.sendTitle(colored(title), colored(subtitle), fadeIn, stay, fadeOut);
    }

    private void applyTemporaryBlindness(Player player, long refreshTicks) {
        int duration = (int) Math.min(Integer.MAX_VALUE, refreshTicks + 40L);
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS,
                duration, 0, false, false, false), true);
    }

    private void clearBlindness(Player player) {
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        // Repeat the cleanup after authentication packets/teleports settle.
        getServer().getScheduler().runTask(this,
                () -> player.removePotionEffect(PotionEffectType.BLINDNESS));
        getServer().getScheduler().runTaskLater(this,
                () -> player.removePotionEffect(PotionEffectType.BLINDNESS), 5L);
    }
}
