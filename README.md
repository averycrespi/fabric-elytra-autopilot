# Elytra AutoPilot

**This mod requires [Fabric API](https://www.curseforge.com/minecraft/mc-mods/fabric-api).**

***

This is a fork of TheMegax's [Elytra Autopilot mod](https://github.com/TheMegax/fabric-elytra-autopilot), which itself is a fork of Simonlourson's client [auto-flight mod](https://www.curseforge.com/minecraft/mc-mods/elytra-auto-flight). I created this fork for my own personal use.

Changes from upstream:
- Added `elytraHotswap` toggle to automatically swap out elytra when low on durability
- Removed buggy `riskyLanding` toggle
- Removed support for Minecraft 1.17 and Spanish/Mandarin translations

## How to use

Press the assigned key (default `R`) while flying high enough to enable the autopilot. While the autopilot is enabled, the mod will modify your pitch between going up and down, resulting in a net altitude gain.

## Commands

## Fly to target
> Syntax: `/flyto X Z`

While flying, use this command to automatically fly to the target coordinates. If `autoLanding` is enabled, the mod will automatically land after reaching the target.

## Takeoff
> Syntax: `/takeoff` or `/takeoff X Z`

While having an elytra equipped and fireworks in either hand, this command will make you fly upwards and then activate auto flight. If coordinates are provided, it will then automatically fly to the target coordinates.

## Configuration

- `autoLanding`: Automatically land after reaching the target coordinates.
- `elytraHotswap`: Automatically swap elytra when low on durability.
- `poweredFlight`: Automatically use rockets while flying for faster travel. (WIP)