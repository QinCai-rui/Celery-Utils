# Celery-Utils
[![CI](https://github.com/QinCai-rui/Celery-Utils/actions/workflows/ci.yml/badge.svg)](https://github.com/QinCai-rui/Celery-Utils/actions/workflows/ci.yml) [![Release](https://github.com/QinCai-rui/Celery-Utils/actions/workflows/release.yml/badge.svg)](https://github.com/QinCai-rui/Celery-Utils/actions/workflows/release.yml)

A modular Minecraft plugin for **PaperMC** servers. Each feature is a self-contained module that can be independently enabled or disabled via `config.yml`. Built with Java 21.

## Quick Start

```bash
mvn -B -ntp package
```

Place the JAR from `target/` (or [releases](https://github.com/QinCai-rui/Celery-Utils/releases/latest)) into your server's `plugins/` folder. Start the server once to generate config files, then configure `plugins/CeleryUtils/config.yml` and individual `modules/*/config.yml` files. Run `/celeryutils reload` to apply changes while the server is running.

## Modules

| Module               | Description                           | Deps      |
|----------------------|---------------------------------------|-----------|
| **Discord Link**     | Link MC to Discord via 6-digit code   | JDA, DB   |
| **Discord Whitelist**| Manage whitelist via Discord channel  | JDA, DB   |
| **Economy Perms**    | Grant/purchase perms based on balance | Vault     |
| **Death Penalty**    | XP/money penalty on death             | Vault(*)  |
| **PvP Module**       | Toggleable PvP with gear loadouts     | DB        |
| **TotemEnhancements**| Inventory totem activation + broadcast| —         |
| **Essentials**       | AFK, /killall, /gm, /tempban, MOTD    | —         |

## Commands

| Command                      | Permission              | Module   |
|------------------------------|-------------------------|----------|
| `/celeryutils` (`/cu`)       | `celeryutils.admin`     | Core     |
| `/pvp toggle\|gear`          | `celeryutils.pvp`       | PvP      |
| `/afk`                       | `celeryutils.afk`       | Essent.  |
| `/killall [cat] [world]`     | `celeryutils.killall`   | Essent.  |
| `/gm <0\|1\|2\|3\|name>`     | —                       | Essent.  |
| `/tempban <p> <dur> [r]`     | `celeryutils.tempban`   | Essent.  |
| `/kickall [reason]`          | `celeryutils.kickall`   | Essent.  |

## Permissions

| Permission                       | Default | Use                |
|----------------------------------|---------|--------------------|
| `celeryutils.admin`              | op      | Admin commands     |
| `celeryutils.update`             | op      | Update notify      |
| `celeryutils.afk`                | **true**| /afk command       |
| `celeryutils.afk.bypass`         | op      | Skip auto-kick     |
| `celeryutils.deathpenalty.bypass`| op      | Skip death penalty |
| `celeryutils.totem`              | op      | Totem features     |
| Others (pvp, killall, etc.)      | op      | See plugin.yml     |

## Database

Supports **SQLite** (default) and **MySQL**. Configured via `config.yml`.

| Table                 | Module             |
|-----------------------|--------------------|
| `discord_links`       | Discord Link       |
| `discord_whitelist`   | Discord Whitelist  |
| `economy_permissions` | Economy Permissions|
| `pvp_loadouts`        | PvP Module         |

## Build & Development

- **Java 21** + **Maven**
- Paper API `1.21.4-R0.1-SNAPSHOT`
- JDA and HikariCP are shaded into the final JAR
- Version auto-stamped in CI (`YY.MM.DD-<branch>.<sha>`) and releases (`YY.MM.DD-dev.<run>.<sha>`)
- CI runs on every push/PR; releases push to `main`

## License

GNU General Public License v3. See [LICENSE](LICENSE).
