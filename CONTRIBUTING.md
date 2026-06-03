# Contributing to KotlinSurvivors

Thank you for your interest in contributing! This document explains how to get started.

---

## Code of Conduct

Be respectful, constructive, and inclusive. We follow the [Contributor Covenant](https://www.contributor-covenant.org/).

---

## How to Contribute

### Reporting Bugs

1. Search existing [issues](https://github.com/yourusername/KotlinSurvivors/issues).
2. If none found, open a new issue with:
   - Device model and Android version
   - Steps to reproduce
   - Expected vs actual behavior
   - Logcat output if available

### Suggesting Features

Open an issue with the `[Feature Request]` prefix and describe:
- What problem it solves
- How it fits the game's design
- Any implementation ideas

### Submitting Pull Requests

1. Fork the repo and create your branch from `main`:
   ```bash
   git checkout -b feature/your-feature-name
   ```

2. Follow the code style (see below).

3. Write/update tests where applicable.

4. Ensure the project builds cleanly:
   ```bash
   ./gradlew assembleDebug
   ./gradlew test
   ```

5. Commit with a clear message:
   ```
   feat: add chain lightning weapon type
   fix: resolve enemy stacking near world edges
   perf: reduce allocations in CollisionSystem
   ```

6. Open a Pull Request against `main` with a description of your changes.

---

## Code Style

- **Language**: Kotlin only. No Java.
- **Formatting**: Follow Kotlin official style guide. Use `ktlint` if possible.
- **Architecture**: Respect Clean Architecture layers — domain never imports data or presentation.
- **ECS**: New entity types go in `EntityFactory`. New logic goes in dedicated Systems.
- **No experimental APIs** unless strictly necessary and annotated.
- **Comments**: Document public APIs with KDoc. Complex algorithms get inline comments.

---

## Project Structure

```
app/src/main/kotlin/com/kotlinsurvivors/
├── core/                  # Shared infrastructure (DB, DI, theme)
├── engine/                # Game loop, ECS, rendering, input
│   ├── ecs/               # World, EntityFactory, Components, Systems
│   ├── rendering/         # GameRenderer (Canvas)
│   ├── input/             # VirtualJoystick
│   └── spatial/           # SpatialGrid
└── features/              # Feature modules
    ├── game/              # Main gameplay
    ├── menu/              # Main menu
    ├── shop/              # Permanent upgrades
    └── achievements/      # Achievement display
```

---

## Areas Welcoming Contributions

- 🗡️ New weapon types
- 👹 New enemy types and boss behaviors
- 🎵 Sound effect integration (AudioTrack / SoundPool)
- 🌍 Localization (translations)
- ♿ Accessibility improvements
- 🧪 Unit tests for ECS systems
- ⚡ Performance optimizations
- 🎨 Visual effects improvements

---

## License

By contributing, you agree your contributions will be licensed under the MIT License.
