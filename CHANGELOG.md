# Changelog

All notable changes to KotlinSurvivors will be documented in this file.
Format based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

---

## [Unreleased]

### Planned
- Multiplayer support (co-op)
- Additional boss types
- Character selection screen
- Cloud save via Google Play Games
- Additional weapon types
- Map biomes / themed environments
- Controller support

---

## [1.0.0] - 2024-01-01

### Added
- Core ECS game engine with fixed-timestep loop
- 10 distinct weapon types (Magic Wand, Knife, Cross, Fire Wand, Axe, Lightning, Garlic, Santa Water, Whip, King Bible)
- 6 enemy types (Basic, Fast, Tank, Ranged, Exploder, Swarm)
- 3 boss types (Shadow, Golem, Necromancer) with multi-phase AI
- Progressive wave spawning system
- Experience and level-up system with 3-choice upgrade selection
- Permanent upgrade shop with 10 upgrade categories
- 14 achievements
- Local leaderboard (best run by time and kills)
- Virtual joystick control
- Full HUD (HP, XP, timer, kills, coins)
- Pause menu, Game Over screen, Level-up overlay
- Animated main menu with particle background
- Object pooling and spatial partitioning for performance
- Material Design 3 dark theme
- Room database for persistence
- Hilt dependency injection
- Clean Architecture + MVVM + Repository Pattern
- Compatible with Android 8.0+ (API 26)
- Landscape-only, full immersive mode
