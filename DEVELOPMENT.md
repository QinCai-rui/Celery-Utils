# CeleryUtils - Development Guide

## Project Structure

```
CeleryUtils/
├── src/main/java/xyz/qincai/celeryutils/
│   ├── CeleryUtils.java              # Main plugin class
│   ├── api/
│   │   └── CeleryModule.java          # Module interface
│   └── modules/
│       ├── DiscordMinecraftSyncModule.java
│       └── EconomyPermissionsModule.java
├── src/main/resources/
│   ├── plugin.yml                     # Plugin metadata
│   └── config.yml                     # Configuration
└── pom.xml                             # Maven build file
```

## Building

### Prerequisites
- Java 21 or higher
- Maven 3.9+ 

### Commands

```bash
# Build the plugin
mvn -B -ntp test

# Clean build
mvn -B -ntp clean package

# Build the shaded plugin JAR
mvn -B -ntp package
```

The compiled JAR will be in `target/CeleryUtils.jar`

## Module Configuration

### Discord-Minecraft Sync

1. Create a Discord bot at https://discord.com/developers/applications
2. Copy the bot token to `config.yml`
3. Get your server (guild) ID and add it to `config.yml`
4. Link player accounts with Discord IDs

**Config:**
```yaml
modules:
  discord-sync:
    enabled: true
    bot-token: "YOUR_BOT_TOKEN"
    guild-id: 1234567890
```

### Economy Permissions

Integrates with Vault to grant/revoke permissions based on balance.

**Config:**
```yaml
modules:
  economy-permissions:
    enabled: true
    rules:
      vip-tier:
        min-balance: 100.0
        permission: "celeryutils.vip"
        revoke-on-balance-below: true
```

Players automatically get permissions when their balance reaches the threshold!

## In-Game Commands

- `/celeryutils status` - Show module status
- `/celeryutils help` - Show help

## Dependencies

- **Paper API** 1.20.6 - Minecraft server API
- **JDA 5.0.0** - Discord integration (shaded)
- **Vault API 1.7.1** - Economy/Permission abstraction

## Architecture

CeleryUtils uses a modular design:

1. **Main Plugin** (`CeleryUtils.java`) - Manages module loading
2. **Module Interface** (`CeleryModule.java`) - Base for all modules
3. **Modules** - Implement specific features independently
4. **Config** - YAML-based configuration for each module

Each module can be enabled/disabled via config without affecting others.

## Development

To add a new module:

1. Implement the `CeleryModule` interface
2. Add module class to `modules/` package
3. Add initialization in `CeleryUtils.onEnable()`
4. Add config section to `config.yml`

Example:

```java
public class MyModule implements CeleryModule {
    private final CeleryUtils plugin;
    private boolean enabled = false;
    
    public MyModule(CeleryUtils plugin) {
        this.plugin = plugin;
    }
    
    @Override
    public String getName() {
        return "My Module";
    }
    
    @Override
    public boolean initialize() {
        // Initialize logic
        enabled = true;
        return true;
    }
    
    @Override
    public void disable() {
        enabled = false;
    }
    
    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
```

## Troubleshooting

**Discord module not connecting:**
- Check bot token is correct
- Verify guild ID is correct
- Ensure bot has proper permissions on the server

**Economy module not working:**
- Make sure Vault is installed
- Ensure an economy plugin (e.g., EssentialsX) is installed
- Check config rules are properly formatted

## License

See LICENSE file
