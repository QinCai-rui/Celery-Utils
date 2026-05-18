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
- **Death Penalty**: applies configurable penalties on death, such as item loss or economy deductions. Useful for servers with `keepInventory` on to add consequences to dying.