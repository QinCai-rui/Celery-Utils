package xyz.qincai.celeryutils.updatechecker;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UpdateChecker {
    private static final Pattern TAG_NAME_PATTERN = Pattern.compile("\\\"tag_name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern HTML_URL_PATTERN = Pattern.compile("\\\"html_url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern ASSET_DOWNLOAD_URL_PATTERN = Pattern.compile("\\\"browser_download_url\\\"\\s*:\\s*\\\"([^\\\"]+\\.jar)\\\"");

    private final JavaPlugin plugin;
    private final HttpClient httpClient;

    private final Set<UUID> notifiedPlayers = ConcurrentHashMap.newKeySet();
    private volatile State state = State.none();
    private volatile BukkitTask repeatingTask;

    public UpdateChecker(JavaPlugin plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    public void start() {
        stop();
        ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("update-checker");
        if (cfg == null || !cfg.getBoolean("enabled", true)) {
            state = State.none();
            return;
        }

        runCheckAsync();

        long intervalMinutes = cfg.getLong("interval-minutes", 60L);
        if (intervalMinutes > 0L) {
            long intervalTicks = Math.max(20L, intervalMinutes * 60L * 20L);
            repeatingTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                    plugin,
                    () -> checkNow(),
                    intervalTicks,
                    intervalTicks
            );
        }
    }

    public void reload() {
        notifiedPlayers.clear();
        start();
    }

    public void stop() {
        if (repeatingTask != null) {
            repeatingTask.cancel();
            repeatingTask = null;
        }
    }

    public void runCheckAsync() {
        ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("update-checker");
        if (cfg == null || !cfg.getBoolean("enabled", true)) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::checkNow);
    }

    public void runCheckAsync(
            java.util.function.Consumer<UpdateResult> callback
    ) {
        ConfigurationSection cfg =
                plugin.getConfig()
                        .getConfigurationSection("update-checker");

        if (cfg == null || !cfg.getBoolean("enabled", true)) {
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            UpdateResult result = checkNow();

            Bukkit.getScheduler().runTask(plugin, () -> {
                callback.accept(result);
            });
        });
    }

    public void notifyIfUpdateAvailable(Player player) {
        ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("update-checker");
        if (cfg == null || !cfg.getBoolean("enabled", true) || !cfg.getBoolean("notify-on-join", true)) {
            return;
        }
        if (!canReceiveJoinNotice(player, cfg)) {
            return;
        }

        State snapshot = state;
        if (!snapshot.updateAvailable()) {
            return;
        }
        if (!notifiedPlayers.add(player.getUniqueId())) {
            return;
        }

        String link = snapshot.downloadUrl().isBlank() ? cfg.getString("api-url", "") : snapshot.downloadUrl();
        player.sendMessage("§e[CeleryUtils] Update available: §f" + snapshot.currentVersion() + " §e-> §f" + snapshot.latestVersion());
        player.sendMessage("§7" + link);
    }

    public String statusSummary() {
        State snapshot = state;
        if (snapshot.errorMessage() != null) {
            return "error: " + snapshot.errorMessage();
        }
        if (snapshot.currentVersion().isBlank()) {
            return "pending";
        }
        if (snapshot.updateAvailable()) {
            return "update available " + snapshot.currentVersion() + " -> " + snapshot.latestVersion();
        }
        return "up-to-date " + snapshot.currentVersion();
    }

    private boolean canReceiveJoinNotice(Player player, ConfigurationSection cfg) {
        String perm = cfg.getString("notify-permission", "celeryutils.update");
        if (perm != null && !perm.isBlank() && player.hasPermission(perm)) {
            return true;
        }
        return cfg.getBoolean("notify-ops-without-permission", true) && player.isOp();
    }

    private UpdateResult checkNow() {
        ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("update-checker");
        if (cfg == null) return UpdateResult.ERROR;
        
        String apiUrl = cfg.getString("api-url");
        if (apiUrl == null || apiUrl.isBlank()) {
            return UpdateResult.ERROR;
        }

        long timeoutMillis = cfg.getLong("timeout-millis", 10000L);
        boolean autoDownload = cfg.getBoolean("auto-download", true);

        String currentVersion = normalizeVersion(plugin.getDescription().getVersion());
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "CeleryUtils-UpdateChecker")
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                state = new State(false, currentVersion, "", "", "HTTP " + response.statusCode());
                plugin.getLogger().warning("Update check failed with HTTP status " + response.statusCode());
                return UpdateResult.ERROR;
            }

            String body = response.body();
            String latestVersion = normalizeVersion(findFirstGroup(TAG_NAME_PATTERN, body));
            String downloadUrl = findFirstGroup(HTML_URL_PATTERN, body);
            if (latestVersion.isBlank()) {
                state = new State(false, currentVersion, "", downloadUrl, "missing tag_name in response");
                plugin.getLogger().warning("Update check failed: missing tag_name in response body");
                return UpdateResult.ERROR;
            }

            boolean updateAvailable = !currentVersion.equalsIgnoreCase(latestVersion);
            state = new State(updateAvailable, currentVersion, latestVersion, downloadUrl, null);

            if (updateAvailable) {
                File updateFolder = plugin.getServer().getUpdateFolderFile();
                File targetFile = new File(updateFolder, "CeleryUtils-" + latestVersion + ".jar");

                if (targetFile.exists()) {
                    cleanOldUpdateFiles(latestVersion);
                    plugin.getLogger().info("CeleryUtils update (" + latestVersion + ") is already downloaded and pending restart/reload.");
                    return UpdateResult.UPDATE_DOWNLOADED;
                } else {
                    plugin.getLogger().warning("New CeleryUtils version available: " + currentVersion + " -> " + latestVersion +
                            (downloadUrl.isBlank() ? "" : " (" + downloadUrl + ")"));
                    
                    if (autoDownload) {
                        String assetUrl = findFirstGroup(
                                ASSET_DOWNLOAD_URL_PATTERN,
                                body
                        );

                        if (!assetUrl.isBlank()) {
                            boolean success =
                                    downloadUpdate(assetUrl, latestVersion);

                            return success
                                    ? UpdateResult.UPDATE_DOWNLOADED
                                    : UpdateResult.DOWNLOAD_FAILED;
                        }

                        return UpdateResult.DOWNLOAD_FAILED;
                    }

                    return UpdateResult.UPDATE_AVAILABLE;
                }
            } else {
                plugin.getLogger().fine("CeleryUtils is up-to-date (" + currentVersion + ")");
                return UpdateResult.UP_TO_DATE;
            }
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            state = new State(false, currentVersion, "", "", ex.getMessage());
            plugin.getLogger().warning("Update check failed: " + ex.getMessage());
            return UpdateResult.ERROR;
        } catch (IllegalArgumentException ex) {
            state = new State(false, currentVersion, "", "", ex.getMessage());
            plugin.getLogger().warning("Update check failed: invalid URL configured");
            return UpdateResult.ERROR;
        }
    }

    private static String findFirstGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private boolean downloadUpdate(String url, String version) {
        try {
            File updateFolder = plugin.getServer().getUpdateFolderFile();

            cleanOldUpdateFiles(version);

            if (!updateFolder.exists() && !updateFolder.mkdirs()) {
                plugin.getLogger().warning("Could not create update folder.");
                return false;
            }

            Path targetFile = new File(
                    updateFolder,
                    "CeleryUtils-" + version + ".jar"
            ).toPath();

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "CeleryUtils-Updater")
                    .timeout(Duration.ofMinutes(1))
                    .GET()
                    .build();

            plugin.getLogger().info("Downloading auto-update from " + url + "...");

            HttpResponse<Path> response =
                    httpClient.send(
                            request,
                            HttpResponse.BodyHandlers.ofFile(targetFile)
                    );

            boolean success =
                    response.statusCode() >= 200
                            && response.statusCode() < 300;

            if (success) {
                plugin.getLogger().info(
                        "Update downloaded successfully to "
                                + targetFile
                                + "! It will be applied on the next server restart."
                );
            }

            return success;

        } catch (Exception ex) {
            plugin.getLogger().warning(
                    "Failed to auto-download update: " + ex.getMessage()
            );

            return false;
        }
    }

    private void cleanOldUpdateFiles(String keepVersion) {
        File updateFolder = plugin.getServer().getUpdateFolderFile();
        if (!updateFolder.exists()) {
            return;
        }
        File[] oldFiles = updateFolder.listFiles((dir, name) ->
                name.startsWith("CeleryUtils-") && name.endsWith(".jar"));
        if (oldFiles == null) {
            return;
        }
        for (File oldFile : oldFiles) {
            String name = oldFile.getName();
            if (name.equals("CeleryUtils-" + keepVersion + ".jar")) {
                continue;
            }
            if (oldFile.delete()) {
                plugin.getLogger().info("Deleted old update file: " + name);
            } else {
                plugin.getLogger().warning("Failed to delete old update file: " + name);
            }
        }
    }

    private static String normalizeVersion(String version) {
        if (version == null) {
            return "";
        }
        String normalized = version.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private record State(
            boolean updateAvailable,
            String currentVersion,
            String latestVersion,
            String downloadUrl,
            String errorMessage
    ) {
        private static State none() {
            return new State(false, "", "", "", null);
        }
    }
    public enum UpdateResult {
        UP_TO_DATE,
        UPDATE_AVAILABLE,
        UPDATE_DOWNLOADED,
        DOWNLOAD_FAILED,
        ERROR
    }
}
