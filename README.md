# Celery-Utils

Utility plugin for Minecraft Servers and SMPs

## Build

This project uses Maven with Java 21.

```bash
mvn -B -ntp test
mvn -B -ntp package
```

## Features

Modular design that allows for specific modules to be enabled or disabled as needed

### Modules

- **Discord-Minecraft username sync**: very basic module that syncs Minecraft usernames to Discord nicknames.

- **Economy Permissions**: allows permissions to be granted based on a player's balance in the economy or be purchased using the economy.
