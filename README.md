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

- **Discord Link**: generates a 6-digit code in Minecraft and links the account to Discord so nicknames can stay in sync.

- **Economy Permissions**: allows permissions to be granted based on a player's balance in the economy or be purchased using the economy.
