# Celery-Utils

A modular Minecraft plugin for PaperMC servers / SMPs with optional server-side features that can be enabled or disabled independently.

## Build

This project uses Maven with Java 21.

```bash
mvn -B -ntp test
mvn -B -ntp package
```

## Features

This plugin has a modular design that allows for specific modules to be enabled or disabled as needed

### Modules

- **Discord Link**: generates a 6-digit code in Minecraft and links the account to Discord so nicknames stay in sync.
- **Discord Whitelist Channel**: manages Minecraft server whitelist requests through a Discord channel.
- **Economy Permissions**: grants permissions based on a player's balance or lets permissions be purchased using the economy.
- **Death Penalty**: applies configurable penalties on death, such as item loss or economy deductions. Useful for servers with `keep_inventory` on to add consequences to dying.
- **Utility Tools**: includes `/afk` (manual + automatic AFK, auto-kick, TAB AFK tag) and `/killall` (clear drops/mobs by category or exact entity type).
- **Gamemode Shortcuts**: quick gamemode switching with `/gm <mode>` (0/1/2/3/survival/creative/adventure/spectator).
- **Totems work in inventory**: allows totems of undying to activate from the player's inventory instead of just the offhand (configurable and toggleable).

## HOW TO - server admins

1. Place the plugin JAR, downloaded from [the releases page](https://github.com/QinCai-rui/Celery-Utils/releases/latest), in your server `plugins/` folder and start the server *once* to generate config files.
2. Open `plugins/CeleryUtils/config.yml` and edit the module settings you want enabled.
3. Configure each module under `plugins/CeleryUtils/modules/` if needed, then restart the server, or run `/celeryutils reload` in-game or in the console to apply changes without restarting (experimental).
4. Use your permission plugin to grant access to Celery Utils admin commands and features.
5. For Discord-related modules, follow the instructions in the respective module config files to set up a Discord bot and get necessary IDs and tokens.
6. Use `/celeryutils help` in-game for command usage and more details on each module's features.

## HOW TO - players

1. Use the commands provided by the enabled modules, such as `/cu link` for the Discord Link module, to access features.

WIP: more detailed player instructions
