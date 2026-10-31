# Deephall

Deephall is a settlement-building game about a small dwarven colony in the
mountains, being reworked for Android phones. A PC release may follow later.

It is a fork of [Mountaincore](https://github.com/rossturner/mountaincore) by
Rocket Jump Technology, which was released as open source under the MIT license.
Deephall is an independent project and is not affiliated with Rocket Jump
Technology.

## What is different

- Pacing made for a phone: newcomers arrive one at a time, and raids start early
- A trade road: caravans and raiders use the same way in, so a sealed
  settlement has consequences
- A rewritten Russian translation
- Many fixes for settlers, kitchens, pathfinding and performance
- A larger interface for small screens

## Status

In development, with no public release yet. On Android the game currently runs
through our own launcher, DeepDroid. A standalone version is planned.
## Credits

Many of the gameplay and performance fixes in this fork come from Augustin ([gusasas](https://github.com/gusasas)) and the `bugfixes` branch of [MountainCorePatches.](https://github.com/gusasas/MountainCorePatches./tree/bugfixes), used under the MIT license.
## Setup

Download and extract Adoptium JDK releases from https://adoptium.net/temurin/releases 
to release_tools/jdks for the packing process (packr.jar) to use. 


## Build / Framework

This game is written in Java (currently version 17) using [LibGDX](https://libgdx.com/wiki/)

LibGDX uses Gradle as a build tool, so you'll need to run `gradlew build` at least once (though not for general development).

The `core` module contains the main game code and assets, the `desktop` module is the desktop launcher and platform-specific binaries.

### Desktop Module

The source contains two classes with runnable main methods, `DesktopLauncher` and `RunTexturePacker`.
**Both of these classes expect to be run where the `assets` directory exists, i.e. they need to be run with `./core` as the working directory.**


`RunTexturePacker` reads the source asset files in (from current working directory) `./mods/base` and packages them into `./assets`.
This process has already been run and this repository contains the output files, it only needs to be re-run when there is a change to asset files.

`DesktopLauncher` is the game launcher. Run this with `./core` as the working directory.

### Building for release

To package for release, you need to download AdoptOpen JDK releases from https://adoptopenjdk.net/releases.html for the packing process (packr.jar) to use. You will likely need to change the packr config files from OpenJDK17U-jdk_x64_windows_hotspot_17.0.4_8 to whatever version you have downloaded.
