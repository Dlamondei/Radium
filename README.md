# Radium

A client-side Fabric mod for **Minecraft 26.1.2** that lets you locally customize scoreboard displays.

Radium changes only what is rendered on your own client. It does **not** change server-side balances, statistics, permissions, or what other players see.

## Features

### Variables
Change the displayed value of a scoreboard objective/player score.

### Lines
Replace values on plugin-driven sidebars that use scoreboard team prefixes.

- Multiple independent entries
- Up to 50 configured lines
- Flicker-free server refresh interception
- Mouse-wheel scrolling
- Draggable scrollbar
- Automatic restoration when entries are removed or the mode is disabled

### Dynamic
Start from a fake displayed value and follow changes observed in the server-sent scoreboard value.

Example:

```text
Server:  10.3M -> 10.2M
Radium:  20M   -> 19.9M
```

Dynamic uses whole-unit `BigDecimal` values internally so its own abbreviated display does not introduce cumulative rounding drift.

Supported compact suffixes:

`K`, `M`, `B`, `T`, `Q`, `Qi`, `Sx`, `Sp`, `Oc`, `No`, `Dc`

For compact Dynamic values, Radium truncates rather than rounds upward:

- Below 100 of a suffix: one decimal place (`19.99B -> 19.9B`)
- 100 or above: whole numbers (`100.9M -> 100M`)
- The suffix is recalculated as the value crosses boundaries (`1B -> 999M`)

**Lines and Dynamic are mutually exclusive.** Enabling one automatically disables and restores the other.

## Important Dynamic limitation

Radium can only react to changes that the server actually sends to the client.

If a server continues sending a rounded value such as `2.1B` while the true server-side balance changes underneath it, Radium cannot see the hidden change until the server-sent text changes.

## Opening Radium

Press **Z** in-game.

If **Mod Menu 18.x** is installed, Radium also exposes its configuration screen through Mod Menu. Mod Menu is optional.

## Requirements

- Minecraft **26.1.2**
- Fabric Loader **0.19.2+**
- Fabric API
- Java **25**
- Mod Menu **18.x** *(optional)*

## Installation

1. Install Fabric Loader and Fabric API.
2. Place the Radium `.jar` in your Minecraft `mods` folder.
3. Launch Minecraft.
4. Press **Z** to open Radium.

## Building from source

On Windows:

```bat
gradlew.bat build
```

On Linux/macOS:

```bash
./gradlew build
```

Built files will appear in `build/libs/`.

## License

Radium is released under the **MIT License**.

You are free to use, modify, redistribute, fork, and build on the project under the terms of that license.

## Author

**br0q**
