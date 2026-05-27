# هۆشیاری — HushYari

**An ultra-fast Android AI agent that uses LLMs to play video games.**

*"هۆشیاری" (HushYari) means "clever play" / "awareness" in Kurdish Sorani.*

HushYari is an on-device AI agent that sees your game screen, thinks strategically, and plays autonomously. It's game-agnostic, blazing fast, and designed for the Android accessibility framework.

[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://www.android.com)
[![Min SDK](https://img.shields.io/badge/Min%20SDK-26-blue.svg)](https://developer.android.com/about/versions/oreo)
[![Languages](https://img.shields.io/badge/Languages-Kotlin%20%7C%20C%2B%2B-purple.svg)](#)
[![License](https://img.shields.io/badge/License-Apache%202.0-yellow.svg)](LICENSE)

---

## What It Does

```
    User: "Farm resources for the next 2 hours"
              │
    ┌─────────▼──────────────┐
    │  HushYari Agent Loop   │
    │                        │
    │  👁 Perceive (5ms)     │ → Read game screen via accessibility
    │  🧠 Think (1-20ms)     │ → FSM classifies screen, World Model tracks state
    │  🎮 Act (1-50ms)       │ → Execute skill step or LLM-directed action
    │  🔍 Reflect (5ms)      │ → Verify action succeeded
    │                        │
    │  Repeat × 1000s/hour   │
    └────────────────────────┘
```

---

## Why HushYari Is Different

| Feature | Typical Game Bot | HushYari |
|---------|-----------------|----------|
| Technology | Pixel color matching, hardcoded coordinates | **LLM-powered with hybrid fallback** |
| Speed | Fast but fragile (breaks on UI changes) | **Deterministic is fast (5ms), LLM is smart (when needed)** |
| Game Support | One bot per game | **Any game via config + skills** |
| Cost | Free (rule-based) or $$$ (pure LLM API) | **~$0.01-0.05/hr** (99% cheaper than pure LLM) |
| Setup | Root, ADB, PC required | **Just an Android app + accessibility permission** |
| Reliability | Breaks on popups, errors, disconnects | **Self-healing: popup detection, error recovery, auto-escalate** |

---

## Architecture (6-Layer Hybrid)

```
LAYER 6: STRATEGIC AI — Cloud VLM every 30-120s ($0.01/hr)
LAYER 5: TACTICAL AI  — On-device LLM every 1-5s (FREE)
LAYER 4: SKILL ENGINE — Scripted tap sequences (FREE)
LAYER 3: WORLD MODEL  — Resources, timers, state tracking (FREE)
LAYER 2: STATE MACHINE — Screen classification, popup handling (FREE)
LAYER 1: PERCEPTION   — Accessibility tree + MediaProjection (FREE)
```

95% of decisions stay at layers 1-4. Only escalate to LLM when needed.

[Full Architecture →](ARCHITECTURE.md)

---

## Game Support

HushYari is designed to work with any game through configurable skills and screen classifiers.

**Initial target games:**
- 🏰 Clash of Clans
- ⚔️ Clash Royale
- 👑 Rise of Kingdoms
- 🥊 Brawl Stars

**Game types that work best:**
- 4X Strategy (RoK, Evony, Lords Mobile)
- Base Builder (CoC, Boom Beach)
- RPG/Farming (Stardew Valley mobile)
- Any game with repetitive actions

**Not ideal for:**
- FPS/reflex games (too fast for LLM)
- Rhythm games (requires audio sync)
- Real-time PvP (need sub-100ms reactions)

---

## Tech Stack

- **Language**: Kotlin 2.0 + C++ (NDK for performance)
- **UI**: Jetpack Compose (Material 3)
- **DI**: Hilt
- **Storage**: Room DB + MMKV
- **AI (local)**: MediaPipe LLM Inference + Gemma 4
- **AI (cloud)**: Gemini 2.5 Flash / GPT-4o / Claude Sonnet 4
- **Device Control**: AccessibilityService + Shizuku (optional)
- **Screen Capture**: MediaProjection (60fps)
- **OCR**: ML Kit (on-device)
- **Vision**: NDK template matching + OpenCL GPU

---

## Development Roadmap

| Phase | Duration | Goal |
|-------|----------|------|
| 0: Foundation | Weeks 1-2 | Accessibility, tools, foreground service |
| 1: Perception | Weeks 3-4 | UI tree + MediaProjection + OCR |
| 2: State & World | Weeks 5-6 | Screen classifier, world model |
| 3: Skills | Weeks 7-8 | Skill engine + built-in game skills |
| 4: LLM | Weeks 9-10 | Cloud + local LLM integration |
| 5: Agents | Weeks 11-12 | Multi-agent loop + coordination |
| 6: UI | Weeks 13-14 | Compose UI + settings |
| 7: Games | Weeks 15-16 | Game configs for popular titles |
| 8: Polish | Weeks 17-18 | Performance, battery, reliability |
| 9: Release | Weeks 19-20 | Testing, signing, distribution |

[Detailed 20-Week Plan →](ARCHITECTURE.md#13-phase-roadmap)

---

## Project Status

🟡 **Phase 0 — Planning & Architecture**

Currently in the planning and architecture phase. Follow the repo for development updates.

---

## Contributing

HushYari is in early development. The most valuable contributions right now:

- **Game configs**: Create screen classifiers and skills for games you play
- **Testing**: Real device testing across different OEMs and Android versions
- **Skill library**: Build and share game skill workflows
- **Documentation**: Help write guides for creating game configs

See [ARCHITECTURE.md](ARCHITECTURE.md) for the full technical plan before contributing.

---

## Inspiration

Built after studying the architecture and lessons from:

- [PokeClaw](https://github.com/agents-io/PokeClaw) — On-device AI phone agent (Kotlin)
- [Roubao](https://github.com/Turbo1123/roubao) — VLM-based Android automation (Kotlin)
- [4x-game-agent](https://github.com/sonpiaz/4x-game-agent) — LLM game bot with 5-layer hybrid architecture (Python)
- [ClickClickClick](https://github.com/instavm/clickclickclick) — Planner+Finder+Executor architecture (Python)
- [Mobile-Agent-v3](https://github.com/X-PLUG/MobileAgent) — Multi-agent mobile GUI framework (Python)

HushYari takes the best ideas from each and builds them natively for Android as a dedicated game-playing agent.

---

## License

Apache 2.0 — see [LICENSE](LICENSE)

---

*هۆشیاری — "the game plays itself"*
