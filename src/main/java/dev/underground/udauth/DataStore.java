package dev.underground.udauth;

import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

final class DataStore {
    private final File file;
    private final YamlConfiguration data;

    DataStore(File dataFolder) {
        this.file = new File(dataFolder, "users.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    synchronized boolean isRegistered(String name) {
        return data.isString(path(name) + ".password");
    }

    synchronized String passwordHash(String name) {
        return data.getString(path(name) + ".password");
    }

    synchronized boolean isPremium(String name, UUID uuid) {
        String root = path(name);
        return data.getBoolean(root + ".premium", false)
                && uuid.toString().equals(data.getString(root + ".premium-uuid"));
    }

    synchronized void enablePremium(String name, UUID uuid) throws IOException {
        String root = path(name);
        data.set(root + ".name", name);
        data.set(root + ".premium", true);
        data.set(root + ".premium-uuid", uuid.toString());
        data.set(root + ".premium-enabled-at", System.currentTimeMillis());
        save();
    }

    synchronized void register(String name, String hash) throws IOException {
        String root = path(name);
        data.set(root + ".name", name);
        data.set(root + ".password", hash);
        data.set(root + ".registered-at", System.currentTimeMillis());
        data.set(root + ".last-login", System.currentTimeMillis());
        save();
    }

    synchronized void markLogin(String name) throws IOException {
        data.set(path(name) + ".last-login", System.currentTimeMillis());
        save();
    }

    private String path(String name) {
        return "users." + name.toLowerCase(Locale.ROOT);
    }

    private void save() throws IOException {
        data.save(file);
    }

    static void setLocation(org.bukkit.configuration.file.FileConfiguration config,
                            String path, Location location) {
        config.set(path, location);
    }
}
