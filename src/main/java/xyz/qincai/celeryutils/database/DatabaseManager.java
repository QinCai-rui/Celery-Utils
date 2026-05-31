package xyz.qincai.celeryutils.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.ConfigurationSection;
import xyz.qincai.celeryutils.CeleryUtils;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;

public class DatabaseManager {

    private final CeleryUtils plugin;
    private HikariDataSource dataSource;
    private DatabaseType type;

    public DatabaseManager(CeleryUtils plugin) {
        this.plugin = plugin;
    }

    public boolean initialize(ConfigurationSection config) {
        if (config == null) {
            plugin.getLogger().warning("Database configuration section missing! Defaulting to SQLite.");
            return setupSQLite();
        }

        String typeStr = config.getString("type", "SQLITE").toUpperCase();
        try {
            this.type = DatabaseType.valueOf(typeStr);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid database type '" + typeStr + "'. Defaulting to SQLITE.");
            this.type = DatabaseType.SQLITE;
        }

        if (this.type == DatabaseType.SQLITE) {
            return setupSQLite();
        } else {
            return setupMySQL(config);
        }
    }

    private boolean setupSQLite() {
        File dbFile = new File(plugin.getDataFolder(), "database.db");
        if (!dbFile.exists()) {
            try {
                dbFile.getParentFile().mkdirs();
                dbFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to create local SQLite database file", e);
                return false;
            }
        }

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        hikariConfig.setDriverClassName("org.sqlite.JDBC");
        hikariConfig.setPoolName("CeleryUtils-SQLite");
        hikariConfig.setMaximumPoolSize(1); // SQLite only supports 1 concurrent connection safely
        hikariConfig.setConnectionTimeout(30000);

        dataSource = new HikariDataSource(hikariConfig);
        return true;
    }

    private boolean setupMySQL(ConfigurationSection config) {
        HikariConfig hikariConfig = new HikariConfig();
        String host = config.getString("host", "localhost");
        int port = config.getInt("port", 3306);
        String database = config.getString("database", "celeryutils");
        
        hikariConfig.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=" + config.getBoolean("use-ssl", false) + "&autoReconnect=true");
        hikariConfig.setDriverClassName("com.mysql.cj.jdbc.Driver");
        hikariConfig.setUsername(config.getString("username", "root"));
        hikariConfig.setPassword(config.getString("password", ""));
        hikariConfig.setPoolName("CeleryUtils-MySQL");
        hikariConfig.setMaximumPoolSize(config.getInt("pool-size", 10));
        hikariConfig.setConnectionTimeout(30000);

        dataSource = new HikariDataSource(hikariConfig);
        return true;
    }

    public Connection getConnection() throws SQLException {
        if (dataSource == null) {
            throw new SQLException("Database not initialized");
        }
        return dataSource.getConnection();
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    public DatabaseType getType() {
        return type;
    }

    public void executeUpdate(String query) {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(query);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to execute update: " + query, e);
        }
    }
}
