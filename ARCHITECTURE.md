# HushYari — Full Architecture & Development Plan

## Overview

**هۆشیاری (HushYari)** is an Android app that uses LLMs to play video games.
The name means "clever play" / "awareness" in Kurdish Sorani.

**One sentence**: An on-device AI agent that sees your game screen, thinks
strategically, and plays autonomously — game-agnostic, blazing fast.

---

## Table of Contents

1. [Research: What We Learned From Existing Projects](#1-research-what-we-learned)
2. [Core Architecture](#2-core-architecture)
3. [Technology Stack](#3-technology-stack)
4. [System Design](#4-system-design)
5. [Module Breakdown](#5-module-breakdown)
6. [Agent Loop](#6-agent-loop)
7. [Game-Specific Skill System](#7-game-skill-system)
8. [Performance Architecture](#8-performance)
9. [LLM Strategy](#9-llm-strategy)
10. [Accessibility Service Design](#10-accessibility-service)
11. [Screen Capture Pipeline](#11-screen-capture)
12. [Input Injection](#12-input-injection)
13. [Phase Roadmap](#13-phase-roadmap)
14. [Detailed Implementation Plan](#14-implementation-plan)
15. [Directory Structure](#15-directory-structure)

---

## 1. Research: What We Learned From Existing Projects

After analyzing every major open-source Android automation and game-agent project,
here is what each taught us.

### PokeClaw (875 stars, Kotlin)

**What they did right:**
- On-device LLM execution via LiteRT-LM (Gemma 4) — no cloud needed
- AccessibilityService for UI tree reading and gesture injection
- Tools + Skills architecture (21 generic tools, reusable skills)
- Foreground service keeps agent alive during tasks
- External automation API (Tasker/MacroDroid integration)
- Floating pill for status display and stop control
- MMKV for fast persistent storage
- 13 core safety rules built into tool execution
- Exported activity entrypoint for external automation (Android 14+ background limits safe)

**What we apply to HushYari:**
- The **Tools + Skills** pattern is the right abstraction for game actions
- Foreground service + floating overlay = mandatory for long-running game sessions
- AccessibilityService is the most reliable input method (no root needed)
- Hybrid local+cloud LLM strategy: local for fast reactions, cloud for strategy
- External automation API lets users trigger "farm for 2 hours" from Tasker/MacroDroid

### Roubao (2.2k stars, Kotlin)

**What they did right:**
- Agent architecture: Manager → Executor → ActionReflector → Notetaker
  (directly ported from MobileAgent-v3 research paper)
- Shizuku for system-level control (screencap, input tap/swipe)
- Delegation mode (fast path via DeepLink to AI-capable apps)
- GUI automation mode (traditional screenshot → analyze → act loop)
- Material 3 UI with excellent design
- Multi-VLM support (Qwen, GPT-4V, Claude, local via Ollama)
- API Key AES-256-GCM encrypted storage
- v2.0 planning: AccessibilityService hybrid mode

**What we apply to HushYari:**
- Manager/Executor/Reflector/Notetaker multi-agent decomposition is **essential**
  for complex game strategies (not just single-step app automation)
- Shizuku is excellent for screen capture speed (raw `screencap` is faster than
  AccessibilityService screenshots)
- Delegation pattern: when confidence is high, skip LLM and execute directly
- Hybrid mode: coordinator-based click when possible, coordinate-based fallback

### 4x-game-agent (10 stars, Python)

**What they did right:**
- **5-layer hybrid architecture** — this is the most important insight:
  - Layer 1: Local Perception (OCR + pixel classify, <100ms, FREE)
  - Layer 2: State Machine (screen detection + popup handling, FREE)
  - Layer 3: World Model (persistent state + timer predictions, FREE)
  - Layer 4: Workflows (scripted tap sequences with verification, FREE)
  - Layer 5: Strategic AI (LLM vision review every 30 min, ~$0.004/call)
- **99% cheaper** than pure LLM approach ($0.01/hr vs $1.00/hr)
- World Model tracks timers, resource state, building levels
- Self-improving via reflection log and template capture
- Config-driven (YAML) per-game configuration
- Web dashboard for monitoring

**What we apply to HushYari:**
- **The 5-layer model is the foundation of HushYari.** This is THE insight.
  Most rival agents burn API credits on every frame. We don't.
- The World Model concept is critical for game playing — track what's happening
  in the game world persistently, not just react to screenshots
- Workflow engine: pre-scripted sequences for repetitive actions (farming, gathering)
- Only escalate to LLM when the deterministic path fails or strategy changes
- Per-game configuration files (YAML/JSON) for game-specific knowledge

### ClickClickClick (694 stars, Python)

**What they did right:**
- **Planner + Finder** separation — distinct LLM roles:
  - Planner: "what should I do?" (strategic reasoning)
  - Finder: "where is the button?" (UI element localization)
- Supports Ollama local models + cloud models
- Image quality compression for faster processing
- Both CLI and REST API interfaces
- Android + macOS support

**What we apply to HushYari:**
- Planner/Finder separation is more cost-effective and reliable than
  a single "do everything" prompt
- Use cheap/small models for Finder (element detection), reserve smart
  models for Planner (strategy)
- Image compression pipeline to reduce bandwidth/latency when using cloud LLMs

### Key Technical Insight from All Projects

The biggest performance problem in LLM-based game automation is **LLM latency**.
A single vision-LLM call takes 1-5 seconds. A game needs decisions in 50-200ms.

**The solution (proven by 4x-game-agent):** Use the LLM for what it's good at
(strategic reasoning, planning, novel situations) and use deterministic, local
methods for what happens 95% of the time (recognizing known screens, executing
known sequences, detecting popups).

---

## 2. Core Architecture

HushYari uses a **6-layer event-driven architecture**:

```
┌──────────────────────────────────────────────────────────────┐
│  LAYER 6: STRATEGIC AI                                       │
│  ┌────────────────────────────────────────────────────────┐ │
│  │ Cloud VLM (Gemini/GPT-4o/Claude) - every 30-120s      │ │
│  │ Long-term strategy, novel situations, fallback          │ │
│  │ Cost: ~$0.01-0.05/hr                                    │ │
│  └────────────────────────────────────────────────────────┘ │
├──────────────────────────────────────────────────────────────┤
│  LAYER 5: TACTICAL AI                                        │
│  ┌────────────────────────────────────────────────────────┐ │
│  │ On-device LLM (Gemma 4 via LiteRT-LM) - every 1-5s    │ │
│  │ Quick decisions, element finding, simple choices        │ │
│  │ Cost: FREE (on-device)                                   │ │
│  └────────────────────────────────────────────────────────┘ │
├──────────────────────────────────────────────────────────────┤
│  LAYER 4: SKILL EXECUTION ENGINE                            │
│  ┌────────────────────────────────────────────────────────┐ │
│  │ Reusable game skill workflows (FREE)                   │ │
│  │ "Farm resources" / "Complete quest" / "Defend base"    │ │
│  │ Deterministic tap sequences + verification             │ │
│  └────────────────────────────────────────────────────────┘ │
├──────────────────────────────────────────────────────────────┤
│  LAYER 3: GAME WORLD MODEL                                   │
│  ┌────────────────────────────────────────────────────────┐ │
│  │ Persistent state tracking (FREE)                        │ │
│  │ Resources, timers, health, enemies, position, inventory │ │
│  │ Predictions: "builder finishes in 2min 30s"            │ │
│  └────────────────────────────────────────────────────────┘ │
├──────────────────────────────────────────────────────────────┤
│  LAYER 2: GAME STATE MACHINE                                 │
│  ┌────────────────────────────────────────────────────────┐ │
│  │ Screen detection + popup handling (FREE)                │ │
│  │ "You are on main screen" / "Popup detected: dismiss"   │ │
│  │ Context-aware navigation                                │ │
│  └────────────────────────────────────────────────────────┘ │
├──────────────────────────────────────────────────────────────┤
│  LAYER 1: FAST PERCEPTION                                    │
│  ┌────────────────────────────────────────────────────────┐ │
│  │ Hybrid screen reading (10-50ms, FREE)                  │ │
│  │ AccessibilityService UI tree + MediaProjection capture │ │
│  │ + template matching + pixel classify + OCR             │ │
│  └────────────────────────────────────────────────────────┘ │
├──────────────────────────────────────────────────────────────┤
│  FOUNDATION: TOOLS LAYER                                     │
│  ┌────────────────────────────────────────────────────────┐ │
│  │ Generic game-agnostic primitives                        │ │
│  │ tap | swipe | long_press | drag | multi_touch |         │ │
│  │ type_text | press_key | screenshot | read_ui_tree |     │ │
│  │ find_element | wait_for | scroll | pinch_zoom           │ │
│  └────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
```

### Event Flow (how a single game action happens)

```
1. Game frame arrives (via MediaProjection or AccessibilityService)
       │
2. Layer 1 — Fast Perception
   ├── Accessibility UI tree → "Button(id=attack_btn, bounds=..."
   ├── Template matching → "Attack button at (840, 1920)"
   └── OCR → "Gold: 1,234 | Gems: 56"
       │
3. Layer 2 — State Machine
   ├── Identify current screen: "BattleScreen"
   └── Check for popups: none detected
       │
4. Layer 3 — World Model
   ├── Update: still in battle, HP=78%
   └── Predict: enemy will attack in ~3s
       │
5. Layer 4 — Skill Engine (pre-scripted path)
   ├── Skill "AutoBattle" running
   ├── Current step 3 of 5: tap attack button
   └── Execute: tap(840, 1920)
       │
6. Verify: did the tap work? (Layer 1 check)
   ├── YES → continue skill
   └── NO  → escalate to Layer 5/6
       │
7. Escalation path (if deterministic fails):
   ├── Layer 5: On-device LLM analyzes screen
   │   "Attack button not found, check if battle ended"
   └── Layer 6: If still failing, Cloud VLM full screen analysis
       "Battle has ended, return to map and heal"
```

---

## 3. Technology Stack

### Language & Framework
- **Kotlin 2.0+** — primary language (same as PokeClaw and Roubao)
- **Jetpack Compose** — UI framework (Material 3)
- **Kotlin Coroutines + Flow** — async and reactive state management
- **Hilt/Dagger** — dependency injection

### Android APIs (the power layer)
| API | Purpose | Why |
|-----|---------|-----|
| **AccessibilityService** | Read UI tree, inject gestures | No root needed, works everywhere |
| **MediaProjection** | High-speed screen capture | 10-30ms per frame (vs 100ms+ for screencap) |
| **NotificationListenerService** | Monitor game notifications | Detect game events (attack, resource full) |
| **Foreground Service** | Keep agent alive | Android kills background services |
| **WindowManager (Overlay)** | Floating control panel | Show/hide, stop, status display |
| **Shizuku (optional)** | System-level commands | Raw `screencap`, `input`, `am` commands |

### On-Device AI
- **MediaPipe LLM Inference** (formerly LiteRT-LM) — run Gemma 4 or other models
- **TFLite** — on-device game element detection (object detection models)
- **ML Kit** — Google's on-device text recognition (OCR)

### Cloud AI (optional, for strategic reasoning)
- **Gemini 2.5 Flash** — best cost/speed for vision tasks
- **GPT-4o** — strongest strategic reasoning
- **Claude Sonnet 4** — best for complex multi-step planning
- OpenRouter / custom endpoints for flexibility

### Storage & Communication
- **MMKV** — fast key-value storage (PokeClaw uses this, 10x faster than SharedPreferences)
- **Room DB** — game configurations, skill libraries, history
- **OkHttp + Retrofit** — API calls
- **Protobuf** — efficient tool/agent protocol messages

### Performance
- **Coil** — image loading
- **RenderScript/Vulkan** — GPU-accelerated image processing
- **NDK (C++ via JNI)** — performance-critical pixel operations and template matching

---

## 4. System Design

### 4.1 Agent Loop (core execution engine)

```
┌─────────────────────────────────────────────────────────┐
│                    AGENT LOOP                            │
│                                                         │
│  ┌─────────┐   ┌──────────┐   ┌───────────────┐        │
│  │PERCEIVE │──▶│  THINK   │──▶│     ACT       │──┐     │
│  │ screen  │   │ Manager  │   │ Executor      │  │     │
│  │ + tree  │   │ Planner  │   │ Tools layer   │  │     │
│  └─────────┘   └──────────┘   └───────────────┘  │     │
│       ▲                                           │     │
│       │         ┌──────────────┐                  │     │
│       └─────────│  REFLECT     │◀─────────────────┘     │
│                 │ ActionReflec │                        │
│                 │ + Notetaker  │                        │
│                 └──────────────┘                        │
│                                                         │
│  ┌──────────────────────────────────────────────────┐   │
│  │              ESCALATION LADDER                    │   │
│  │                                                  │   │
│  │  Level 0: Cached action (0ms)                    │   │
│  │  Level 1: Deterministic skill step (1-5ms)       │   │
│  │  Level 2: State machine rule (5-20ms)            │   │
│  │  Level 3: Local OCR/template match (10-50ms)     │   │
│  │  Level 4: On-device LLM (100-2000ms)             │   │
│  │  Level 5: Cloud LLM (1000-5000ms)                │   │
│  │                                                  │   │
│  │  Always start at lowest level, escalate on fail  │   │
│  └──────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

### 4.2 Multi-Agent System (MAS)

For complex games, multiple specialized agents run concurrently:

| Agent | Role | Runs |
|-------|------|------|
| **Manager** | Task decomposition, priority ordering | Every 5-30s |
| **Executor** | Execute actions, verify results | Every 100-500ms |
| **Reflector** | Evaluate action success/failure | After each action |
| **Notetaker** | Record important observations | After significant events |
| **Watcher** | Monitor for urgent events (attack, low HP) | Every 50-200ms |
| **Strategist** | Long-term strategy adjustments | Every 30-120s |
| **Popuphandler** | Detect and dismiss popups/ads | Every 500ms |

### 4.3 Data Flow

```
┌─────────────────┐     ┌──────────────────┐
│ Game App         │     │  HushYari App    │
│ (on screen)      │     │  (service)       │
├─────────────────┤     ├──────────────────┤
│                 │     │                   │
│  [Game Frame]───┼──1──▶ AccessibilitySvc │
│                 │     │       │           │
│                 │     │   ┌───▼──────┐    │
│                 │     │   │Perception│    │
│                 │     │   │Pipeline  │    │
│                 │     │   └───┬──────┘    │
│                 │     │       │           │
│                 │     │   ┌───▼──────┐    │
│                 │     │   │State     │    │
│                 │     │   │Machine   │    │
│                 │     │   └───┬──────┘    │
│                 │     │       │           │
│                 │     │   ┌───▼──────┐    │
│                 │     │   │World     │    │
│                 │     │   │Model     │    │
│                 │     │   └───┬──────┘    │
│                 │     │       │           │
│                 │     │   ┌───▼──────┐    │
│                 │     │   │Skill     │    │
│                 │     │   │Engine    │    │
│                 │     │   └───┬──────┘    │
│                 │     │       │           │
│                 │     │   ┌───▼──────────┐│
│   [Tap/Swipe]◀──┼──2──┼───│Gesture       ││
│                 │     │   │Dispatcher    ││
│                 │     │   └──────────────┘│
└─────────────────┘     └──────────────────┘
```

1. Screen state flows in via AccessibilityService's UI tree + occasional
   MediaProjection screenshot for visual analysis
2. Actions flow out via AccessibilityService's `dispatchGesture()` or
   Shizuku's shell commands

### 4.4 Concurrency Model

```
┌──────────────────────────────────────────────────────┐
│                   THREAD MODEL                        │
│                                                      │
│  Main Thread (UI)                                     │
│  ├── Compose UI state observation                    │
│  └── Overlay view management                         │
│                                                      │
│  Perception Thread (high priority)                    │
│  ├── Accessibility event processing                  │
│  ├── UI tree parsing                                 │
│  └── Screen state extraction (10-50ms budget)        │
│                                                      │
│  Agent Thread (medium priority)                       │
│  ├── State machine transitions                       │
│  ├── World model updates                             │
│  ├── Skill execution                                 │
│  └── Tool dispatch                                   │
│                                                      │
│  LLM Thread (low priority, coroutine)                 │
│  ├── On-device model inference                       │
│  ├── Cloud API calls                                 │
│  └── Response parsing                                │
│                                                      │
│  Watcher Thread (high priority, periodic)             │
│  ├── Popup detection (every 500ms)                   │
│  ├── Emergency condition check (every 200ms)          │
│  └── Timer/alarm management                          │
│                                                      │
│  I/O Dispatcher (coroutine pool)                      │
│  ├── Storage (MMKV, Room)                            │
│  ├── Network (API calls, model download)             │
│  └── Image encoding/compression                      │
└──────────────────────────────────────────────────────┘
```

---

## 5. Module Breakdown

### Module Map

```
app/
├── agent/                 # Multi-agent orchestration
│   ├── AgentLoop.kt       # Main perception-think-act-reflect loop
│   ├── Manager.kt         # Task decomposition agent
│   ├── Executor.kt        # Action execution agent
│   ├── Reflector.kt       # Success/failure evaluation agent
│   ├── Notetaker.kt       # Observation recording agent
│   ├── Watcher.kt         # Urgent event monitoring agent
│   └── Strategist.kt      # Long-term planning agent
│
├── perception/            # Layer 1: Fast screen reading
│   ├── PerceptionPipeline.kt  # Orchestrates all perception
│   ├── AccessibilityReader.kt # UI tree extraction
│   ├── ScreenCapture.kt       # MediaProjection screenshot
│   ├── TemplateMatcher.kt     # OpenCV-like template matching
│   ├── PixelClassifier.kt     # Fast pixel-based screen ID
│   ├── OcrEngine.kt           # On-device text reading
│   └── ElementFinder.kt       # "Where is element X on screen?"
│
├── statemachine/          # Layer 2: Game state management
│   ├── ScreenClassifier.kt    # "Which screen am I on?"
│   ├── PopupHandler.kt        # Detect and dismiss popups
│   ├── StateTransition.kt     # Screen-to-screen navigation
│   └── GameFSM.kt             # Per-game finite state machine
│
├── worldmodel/            # Layer 3: Persistent game knowledge
│   ├── WorldState.kt          # Live game state holder
│   ├── ResourceTracker.kt     # Gold, gems, energy, etc.
│   ├── TimerEngine.kt         # Building/research/event timers
│   ├── PositionTracker.kt     # Map position, base location
│   ├── CombatState.kt         # HP, enemies, battle status
│   └── InventoryTracker.kt    # Items, heroes, troops
│
├── skills/                # Layer 4: Game skill library
│   ├── Skill.kt               # Skill interface
│   ├── SkillRegistry.kt       # Game → skills mapping
│   ├── SkillEngine.kt         # Execute skill workflows
│   ├── SkillVerifier.kt       # Check if skill step succeeded
│   └── builtin/               # Built-in game skills
│       ├── generic/           # Universal skills (any game)
│       │   ├── DismissPopup.kt
│       │   ├── WaitForScreen.kt
│       │   ├── CollectRewards.kt
│       │   └── HandleConnection.kt
│       ├── strategy/          # 4X strategy game skills
│       │   ├── FarmResources.kt
│       │   ├── TrainTroops.kt
│       │   ├── UpgradeBuilding.kt
│       │   └── AllianceHelp.kt
│       ├── rpg/               # RPG game skills
│       │   ├── AutoBattle.kt
│       │   ├── NavigateMap.kt
│       │   └── CompleteQuest.kt
│       └── puzzle/            # Puzzle game skills
│           ├── ScanBoard.kt
│           └── FindMatch.kt
│
├── tools/                 # Foundation: Generic primitives
│   ├── Tool.kt                # Tool interface
│   ├── ToolManager.kt         # Tool registry and safety
│   ├── GestureTool.kt         # tap, swipe, long_press, drag
│   ├── MultiTouchTool.kt      # pinch, multi-finger gestures
│   ├── InputTool.kt           # type_text, press_key
│   ├── ScreenTool.kt          # screenshot, read_ui_tree
│   ├── FindTool.kt            # find_element, find_text
│   ├── WaitTool.kt            # wait_for, wait_until
│   └── AppTool.kt             # open_app, close_app, go_home
│
├── llm/                   # LLM integration
│   ├── LlmClient.kt           # Interface for all LLM backends
│   ├── LocalLlmClient.kt      # On-device Gemma via MediaPipe
│   ├── CloudLlmClient.kt      # Gemini/GPT/Claude API
│   ├── ModelManager.kt        # Download, cache, lifecycle
│   ├── PromptEngine.kt        # Prompt construction
│   ├── PromptTemplates.kt     # Game-specific prompt templates
│   └── ResponseParser.kt      # Parse LLM → structured actions
│
├── controller/            # Device control layer
│   ├── DeviceController.kt    # Unified control interface
│   ├── AccessibilityController.kt  # Via AccessibilityService
│   ├── ShizukuController.kt        # Via Shizuku (optional)
│   └── GestureDispatcher.kt        # Route to best provider
│
├── service/               # Android services
│   ├── HushyariAccessibilityService.kt  # Core accessibility
│   ├── HushyariForegroundService.kt     # Keep alive
│   ├── ScreenCaptureService.kt          # MediaProjection
│   ├── NotificationMonitorService.kt    # Game notifications
│   └── OverlayService.kt                # Floating controls
│
├── ui/                    # Jetpack Compose UI
│   ├── MainActivity.kt
│   ├── screens/
│   │   ├── HomeScreen.kt         # Dashboard + game launcher
│   │   ├── GameConfigScreen.kt   # Per-game settings
│   │   ├── SkillEditorScreen.kt  # Create/edit skills
│   │   ├── HistoryScreen.kt      # Past sessions/logs
│   │   ├── SettingsScreen.kt     # App configuration
│   │   └── ModelManagerScreen.kt # LLM model management
│   ├── components/
│   │   ├── FloatingPill.kt       # Overlay control pill
│   │   ├── StatusBar.kt          # Game status display
│   │   ├── SkillCard.kt          # Skill selection UI
│   │   └── LogViewer.kt          # Real-time log view
│   └── theme/
│       └── Theme.kt              # Material 3 theming
│
├── data/                  # Data layer
│   ├── local/
│   │   ├── HushyariDatabase.kt   # Room database
│   │   ├── GameConfigDao.kt
│   │   ├── SkillDao.kt
│   │   ├── SessionLogDao.kt
│   │   └── PreferencesManager.kt # MMKV-backed
│   └── model/
│       ├── GameConfig.kt         # Per-game configuration
│       ├── SkillDef.kt           # Skill definition
│       ├── SessionLog.kt         # Session history
│       └── AgentConfig.kt        # Agent settings
│
├── gameconfig/            # Built-in game configurations
│   ├── GameConfigProvider.kt
│   ├── templates/               # Community-contributed
│   │   ├── clash_of_clans.json
│   │   ├── clash_royale.json
│   │   ├── rise_of_kingdoms.json
│   │   ├── brawl_stars.json
│   │   ├── pokemon_go.json
│   │   └── genshin_impact.json
│   └── GameConfigSchema.kt
│
├── di/                    # Dependency injection
│   └── AppModule.kt
│
├── app/                   # Application class
│   └── HushyariApp.kt
│
└── native/                # C++ NDK modules
    ├── template_match.cpp      # Fast CV template matching
    ├── pixel_ops.cpp           # Pixel-level operations
    └── image_compress.cpp      # JPEG/WebP compression
```

---

## 6. Agent Loop — Detailed Design

The agent loop is the heart of HushYari. It must be fast, interruptible, and
escalation-aware.

```kotlin
// Simplified pseudocode of the Agent Loop
class AgentLoop(
    private val perception: PerceptionPipeline,
    private val stateMachine: GameFSM,
    private val worldModel: WorldState,
    private val skillEngine: SkillEngine,
    private val manager: Manager,
    private val executor: Executor,
    private val reflector: Reflector,
    private val watcher: Watcher,
    private val strategist: Strategist,
) {
    suspend fun run(task: GameTask): Flow<AgentEvent> = flow {
        var attempts = 0
        while (task.isActive && attempts < task.maxAttempts) {
            // 1. PERCEIVE — fast, always runs
            val screen = perception.capture()
            emit(AgentEvent.ScreenCaptured(screen))

            // 2. CLASSIFY — which game screen are we on?
            val classification = stateMachine.classify(screen)
            emit(AgentEvent.ScreenClassified(classification))

            // 3. HANDLE POPUPS — must do this before anything else
            if (classification.isPopup) {
                skillEngine.execute(Skills.DISMISS_POPUP)
                continue
            }

            // 4. UPDATE WORLD MODEL
            worldModel.update(screen, classification)

            // 5. CHECK WATCHER — urgent conditions?
            val urgentAction = watcher.check(screen, worldModel)
            if (urgentAction != null) {
                executor.execute(urgentAction)
                continue
            }

            // 6. SKILL EXECUTION — try deterministic path
            if (skillEngine.hasActiveSkill) {
                val result = skillEngine.executeNextStep()
                when (result) {
                    SkillResult.SUCCESS -> continue
                    SkillResult.COMPLETED -> {
                        emit(AgentEvent.SkillCompleted(skillEngine.activeSkill))
                        break
                    }
                    SkillResult.STUCK -> {
                        // Escalate: ask on-device LLM for help
                    }
                }
            }

            // 7. LLM PATH — when deterministic fails
            val llmResponse = when {
                // Simple UI navigation? Use local LLM
                classification.isSimpleUIDecision ->
                    localLlmClient.analyze(screen, task)
                // Strategic decision? Use cloud LLM
                classification.needsStrategy ->
                    cloudLlmClient.analyze(screen, worldModel, task)
                else -> null
            }

            if (llmResponse != null) {
                val reflection = reflector.evaluate(llmResponse.action, screen)
                if (reflection.isSuccess) {
                    executor.execute(llmResponse.action)
                } else {
                    attempts++
                }
            }

            emit(AgentEvent.LoopIteration(attempts))
        }
    }
}
```

### Escalation Ladder (latency vs intelligence tradeoff)

| Level | Method | Latency | Use When |
|-------|--------|---------|----------|
| 0 | Cached action | 0ms | Same action as last 100ms ago |
| 1 | Skill step | 1-5ms | Active skill has next step defined |
| 2 | FSM rule | 5-20ms | Known screen, deterministic transition |
| 3 | Template match | 10-50ms | Need to find known element visually |
| 4 | Local LLM | 100-3000ms | Simple decision, known game context |
| 5 | Cloud LLM | 1000-5000ms | Complex strategy, novel situation |

---

## 7. Game-Specific Skill System

Skills are the most important abstraction in HushYari. They enable game-agnostic
LLM reasoning while keeping 95% of actions deterministic and fast.

### Skill Definition Format (YAML/JSON)

```json
{
  "id": "farm_resources_strategy",
  "name": "Farm Resources",
  "game_type": ["4x_strategy"],
  "description": "Continuously gather resources from map tiles",
  "prerequisites": ["main_screen"],
  "steps": [
    {
      "id": "open_map",
      "tool": "find_and_tap",
      "target": { "content_desc": "World Map" },
      "fallback": { "coordinates": [0.9, 0.1] },
      "timeout_ms": 5000
    },
    {
      "id": "find_resource_tile",
      "tool": "find_element",
      "target": { "template": "resource_tile.png" },
      "timeout_ms": 3000
    },
    {
      "id": "march_to_tile",
      "tool": "tap",
      "target": "$find_resource_tile.result.coordinates",
      "wait_after_ms": 1000
    },
    {
      "id": "confirm_march",
      "tool": "find_and_tap",
      "target": { "text": "March" },
      "fallback": { "text": "Go", "content_desc": "Confirm" },
      "timeout_ms": 3000
    }
  ],
  "completion_condition": {
    "type": "world_model",
    "path": "resources.current >= threshold"
  },
  "retry_policy": {
    "max_retries": 3,
    "on_failure": "escalate_to_llm"
  }
}
```

### Skill Types

1. **Sequential Skill**: Fixed sequence of steps (farming, upgrading)
2. **Conditional Skill**: Steps depend on world model state (if HP < 50%, heal)
3. **Looped Skill**: Repeat until condition met (train troops until queue full)
4. **Reactive Skill**: Wait for event, then execute (auto-retaliate on attack)
5. **LLM-Guided Skill**: Use LLM for each step decision (exploration, complex UI)

### Community Skill Marketplace

- Skills are stored as JSON/YAML files
- Community can contribute game configs and skills
- Built-in library for popular games
- Import/export via file or URL
- Skills are versioned and rated

---

## 8. Performance Architecture

### The Speed Problem

A game running at 30 FPS updates every 33ms. An LLM call takes 1000-5000ms.
This is a **30-150x mismatch**. HushYari must bridge this gap.

### Solution: Three Time Horizons

```
Time Budget    │ Action Type                 │ Tech
───────────────┼─────────────────────────────┼─────────────────────
   1-10ms      │ Cached/repeated actions      │ Skill engine cache
  10-50ms      │ Pixel classify responses     │ NDK + template match
  50-200ms     │ UI tree based navigation     │ AccessibilityService
 200-2000ms    │ Simple game decisions        │ On-device LLM (MediaPipe)
2000-5000ms    │ Strategic planning           │ Cloud LLM (Gemini)
5000-30000ms   │ Complex multi-step analysis  │ Cloud LLM (Claude/GPT-4o)
```

### Screen Capture Optimization

| Method | Speed | Quality | Requires |
|--------|-------|---------|----------|
| AccessibilityService.getWindowRoots() | ~5ms | UI tree only | Accessibility |
| MediaProjection (Surface) | ~16ms (60fps) | Full screen | User consent |
| screencap (Shizuku) | ~200ms | PNG file | Shizuku |
| screencap (ADB) | ~500ms | PNG file | ADB connection |

**Our approach**: Use AccessibilityService UI tree as primary (~5ms).
Use MediaProjection for visual analysis when needed (~16ms).
Never use ADB/screencap for real-time (too slow).

### Template Matching Acceleration (NDK)

```cpp
// C++ native code for fast template matching
// Runs on GPU via OpenCL when available
// Falls back to CPU SIMD (NEON on ARM64)

extern "C" JNIEXPORT jfloatArray JNICALL
Java_dev_hushyari_perception_TemplateMatcher_nativeMatch(
    JNIEnv* env, jobject,
    jbyteArray screen, jint screenW, jint screenH,
    jbyteArray templateImg, jint templateW, jint templateH,
    jfloat threshold
) {
    // Convert to cv::Mat
    // Run matchTemplate with TM_CCOEFF_NORMED
    // Return top matches with positions
    // Use OpenCL if available, NEON SIMD otherwise
}
```

### Memory Budget

| Component | RAM Budget |
|-----------|-----------|
| App + UI | ~50 MB |
| Accessibility Service | ~10 MB |
| On-device LLM (Gemma 4 E2B) | ~2 GB |
| Template cache (in-memory) | ~50 MB |
| World Model state | ~10 MB |
| Image buffers | ~100 MB |
| **Total (with local LLM)** | **~2.5 GB** |
| **Total (cloud only)** | **~200 MB** |

---

## 9. LLM Strategy

### Model Selection by Task

| Task | Preferred Model | Why |
|------|----------------|-----|
| Find button/element | Local Gemma 4 (lite) | Fast, private, simple |
| Identify current screen | Local Gemma 4 (lite) | Deterministic classification |
| Simple navigation | Local Gemma 4 | Follow predefined paths |
| Read game text | Local ML Kit OCR | Fast, free, offline |
| Complex strategy | Cloud Gemini 2.5 Flash | Best cost/speed ratio |
| Multi-step planning | Cloud GPT-4o / Claude | Strongest reasoning |
| Novel situation | Cloud GPT-4o / Claude | Most flexible |

### Prompt Architecture

```kotlin
data class GamePrompt(
    val systemPrompt: String,     // Game context, rules, goals
    val worldState: WorldState,   // Current resources, position, timers
    val screenDescription: String,// Text description of current screen
    val task: String,             // What to do now
    val actionHistory: List<ActionResult>, // Recent actions and results
    val visualContext: ByteArray? // Optional screenshot for VLM
) {
    fun toLlmPrompt(): List<Message> {
        return listOf(
            Message.system(systemPrompt),
            Message.system("World State: ${worldState.toCompactString()}"),
            Message.user("Screen: $screenDescription"),
            Message.user("Task: $task"),
            Message.user("Recent actions: ${actionHistory.last(5)}"),
            // Append image as separate content part if VLM supports it
        )
    }
}
```

### On-Device vs Cloud Decision Tree

```
Is this frame time-critical?
├── YES → Use cached/skill/FSM result (0-50ms)
└── NO → Is this a simple classification?
    ├── YES → Use local LLM (100-2000ms, free)
    └── NO → Does this need strategic reasoning?
        ├── YES → Is cost acceptable?
        │   ├── YES → Use cloud LLM (2000-5000ms, ~$0.001-0.01)
        │   └── NO  → Use local LLM with simpler prompt
        └── NO  → Use local LLM
```

### Cost Optimization

For 24/7 game automation:

| Strategy | Cost/Day | Cost/Month |
|----------|----------|------------|
| Pure cloud LLM (every frame) | ~$24 | ~$720 |
| Hybrid (Strategist every 2min) | ~$0.48 | ~$14.60 |
| Optimized (Strategist every 5min, skill fallback) | ~$0.20 | ~$5.84 |
| Local-only mode | $0 | $0 |

---

## 10. Accessibility Service Design

This is the most critical Android component. Everything flows through it.

```kotlin
class HushyariAccessibilityService : AccessibilityService() {

    // Flow: raw events → parsed actions for the agent loop
    private val _screenEvents = MutableSharedFlow<AccessibilityScreenEvent>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST  // Always latest
    )
    val screenEvents: SharedFlow<AccessibilityScreenEvent> = _screenEvents

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Types we care about:
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                // Parse immediately on this thread (high priority)
                val screenEvent = parseScreenState()
                _screenEvents.tryEmit(screenEvent)
            }
        }
    }

    private fun parseScreenState(): AccessibilityScreenEvent {
        val root = rootInActiveWindow ?: return AccessibilityScreenEvent.Empty

        val elements = mutableListOf<UIElement>()
        extractElements(root, elements, depth = 0)

        return AccessibilityScreenEvent(
            packageName = root.packageName?.toString() ?: "",
            windowTitle = root.contentDescription?.toString() ?: "",
            elements = elements,
            timestamp = SystemClock.elapsedRealtime(),
            isGameScreen = classification.isGame(elements)
        )
    }

    // Extract full UI tree (recursive, depth-limited to 50)
    private fun extractElements(
        node: AccessibilityNodeInfo,
        elements: MutableList<UIElement>,
        depth: Int
    ) {
        if (depth > 50) return  // Safety limit

        if (node.isVisibleToUser) {
            elements.add(UIElement(
                className = node.className?.toString() ?: "",
                text = node.text?.toString() ?: "",
                contentDescription = node.contentDescription?.toString() ?: "",
                resourceId = node.viewIdResourceName ?: "",
                bounds = Rect().apply { node.getBoundsInScreen(this) },
                isClickable = node.isClickable,
                isScrollable = node.isScrollable,
                isEditable = node.isEditable,
                isChecked = node.isChecked,
                depth = depth,
                childCount = node.childCount
            ))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            extractElements(child, elements, depth + 1)
            child.recycle()
        }
    }

    // Gesture injection — our output channel
    fun dispatchTap(x: Float, y: Float) {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(
                Path().apply { moveTo(x, y) },
                0,
                50  // 50ms duration = tap
            ))
            .build()
        dispatchGesture(gesture, null, null)
    }

    fun dispatchSwipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long = 300
    ) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, null, null)
    }

    fun dispatchLongPress(x: Float, y: Float, durationMs: Long = 800) {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(
                Path().apply { moveTo(x, y) },
                0,
                durationMs
            ))
            .build()
        dispatchGesture(gesture, null, null)
    }

    override fun onInterrupt() {
        // Service is being interrupted (e.g., accessibility turned off)
        // Notify agent loop to pause
    }
}
```

### UI Element Model

```kotlin
data class UIElement(
    val className: String,
    val text: String,
    val contentDescription: String,
    val resourceId: String,
    val bounds: Rect,
    val isClickable: Boolean,
    val isScrollable: Boolean,
    val isEditable: Boolean,
    val isChecked: Boolean,
    val depth: Int,
    val childCount: Int
) {
    val centerX: Float get() = bounds.exactCenterX()
    val centerY: Float get() = bounds.exactCenterY()
    val isButton: Boolean get() = isClickable && className.contains("Button")
    val isTextInput: Boolean get() = isEditable && className.contains("Edit")
    val isImage: Boolean get() = className.contains("Image") && !isClickable
    val isContainer: Boolean get() = childCount > 0 && !isClickable

    fun matches(query: ElementQuery): Boolean {
        query.text?.let { if (text != it && contentDescription != it) return false }
        query.contentDesc?.let { if (contentDescription != it) return false }
        query.resourceId?.let { if (resourceId != it) return false }
        query.isClickable?.let { if (isClickable != it) return false }
        query.isScrollable?.let { if (isScrollable != it) return false }
        return true
    }
}
```

---

## 11. Screen Capture Pipeline

For games, we need BOTH the UI tree (structure) AND visual capture (appearance).

### Dual-Mode Capture

```kotlin
class PerceptionPipeline(
    private val accessibilityReader: AccessibilityReader,
    private val screenCapture: ScreenCapture,
    private val templateMatcher: TemplateMatcher,
    private val ocrEngine: OcrEngine,
    private val pixelClassifier: PixelClassifier,
) {
    suspend fun capture(mode: CaptureMode = CaptureMode.FAST): ScreenState {
        return when (mode) {
            CaptureMode.FAST -> {
                // UI tree only (~5ms) — good enough for navigation
                val tree = accessibilityReader.readUITree()
                ScreenState(tree = tree, bitmap = null)
            }
            CaptureMode.VISUAL -> {
                // UI tree + screenshot (~20ms) — needed for element finding
                coroutineScope {
                    val tree = async { accessibilityReader.readUITree() }
                    val bitmap = async { screenCapture.capture() }
                    ScreenState(tree = tree.await(), bitmap = bitmap.await())
                }
            }
            CaptureMode.FULL -> {
                // UI tree + screenshot + OCR + template matching (~50ms)
                coroutineScope {
                    val tree = async { accessibilityReader.readUITree() }
                    val bitmap = async { screenCapture.capture() }
                    val bmp = bitmap.await()
                    val ocr = async { ocrEngine.readText(bmp!!) }
                    val templates = async {
                        templateMatcher.matchActive(bmp)
                    }
                    ScreenState(
                        tree = tree.await(),
                        bitmap = bmp,
                        ocrText = ocr.await(),
                        templateMatches = templates.await()
                    )
                }
            }
        }
    }
}
```

### MediaProjection Capture (for visual analysis)

```kotlin
class ScreenCaptureService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null

    fun startCapture(intent: Intent, resultCode: Int) {
        val projection = (getSystemService(MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager)
            .getMediaProjection(resultCode, intent)

        mediaProjection = projection

        val metrics = resources.displayMetrics
        imageReader = ImageReader.newInstance(
            metrics.widthPixels,
            metrics.heightPixels,
            PixelFormat.RGBA_8888,
            2  // Max 2 images in buffer
        )

        projection.createVirtualDisplay(
            "HushyariCapture",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, null
        )
    }

    fun capture(): Bitmap? {
        val image = imageReader?.acquireLatestImage() ?: return null
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        // Crop to actual size
        val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        image.close()

        return cropped
    }
}
```

---

## 12. Input Injection

HushYari uses a priority-ordered input dispatch system:

```kotlin
class GestureDispatcher(
    private val accessibilityController: AccessibilityController,
    private val shizukuController: ShizukuController?,
) {
    enum class Provider { ACCESSIBILITY, SHIZUKU }

    private var activeProvider: Provider = Provider.ACCESSIBILITY

    suspend fun tap(x: Float, y: Float, delayMs: Long = 50) {
        when (activeProvider) {
            Provider.ACCESSIBILITY -> {
                accessibilityController.dispatchTap(x, y)
            }
            Provider.SHIZUKU -> {
                shizukuController!!.exec("input tap $x $y")
            }
        }
        delay(delayMs)
    }

    suspend fun swipe(from: Point, to: Point, durationMs: Long = 300) {
        when (activeProvider) {
            Provider.ACCESSIBILITY -> {
                accessibilityController.dispatchSwipe(
                    from.x, from.y, to.x, to.y, durationMs
                )
            }
            Provider.SHIZUKU -> {
                shizukuController!!.exec(
                    "input swipe ${from.x} ${from.y} ${to.x} ${to.y} $durationMs"
                )
            }
        }
        delay(durationMs + 20)
    }

    // Multi-touch for game actions (pinch zoom, two-finger drag)
    suspend fun multiTouch(vararg points: List<Point>, durationMs: Long = 100) {
        // AccessibilityService supports multi-stroke gestures
        val builder = GestureDescription.Builder()
        for (pointList in points) {
            val path = Path()
            pointList.forEachIndexed { i, p ->
                if (i == 0) path.moveTo(p.x, p.y)
                else path.lineTo(p.x, p.y)
            }
            builder.addStroke(
                GestureDescription.StrokeDescription(path, 0, durationMs)
            )
        }
        accessibilityController.dispatchGesture(builder.build())
    }

    // Fallback: if accessibility fails, try Shizuku
    fun setProvider(provider: Provider) {
        activeProvider = provider
    }
}
```

---

## 13. Phase Roadmap

### Phase 0: Foundation (Weeks 1-2)
- [ ] Initialize Android project (Kotlin, Compose, Hilt)
- [ ] Implement AccessibilityService skeleton (read UI tree, inject gestures)
- [ ] Implement basic Tool layer (tap, swipe, long_press, type)
- [ ] Implement foreground service (keep alive)
- [ ] Implement floating pill overlay (start/stop)
- [ ] Test on real device with a simple game

### Phase 1: Perception Pipeline (Weeks 3-4)
- [ ] Accessibility UI tree reader with full element parsing
- [ ] MediaProjection screen capture service
- [ ] NDK template matching (C++ via JNI)
- [ ] ML Kit OCR integration
- [ ] Pixel classifier (fast screen identification)
- [ ] Element finder (locate UI elements by text, id, description)
- [ ] Benchmark: achieve <50ms perception pipeline

### Phase 2: State Machine & World Model (Weeks 5-6)
- [ ] Screen classifier (identify game screens)
- [ ] Popup handler (detect and dismiss ads/popups)
- [ ] Game FSM framework (definable state machines)
- [ ] World model (resources, timers, health, position tracking)
- [ ] Timer engine (predict building/research completion)
- [ ] Define config format for 2 reference games

### Phase 3: Skill Engine (Weeks 7-8)
- [ ] Skill interface and registry
- [ ] Skill engine (execute sequential/conditional/looped skills)
- [ ] Skill verifier (confirm each step)
- [ ] Built-in generic skills (dismiss popup, handle connection loss)
- [ ] Skill editor UI (create skills visually)
- [ ] First game-specific skills for reference game

### Phase 4: LLM Integration (Weeks 9-10)
- [ ] LLM client interface
- [ ] Cloud LLM client (Gemini, OpenAI, Claude)
- [ ] Prompt engine with game-specific templates
- [ ] Response parser (LLM output → structured action)
- [ ] Escalation logic (when to call LLM vs use skill)
- [ ] Local LLM: MediaPipe (Gemma 4) integration
- [ ] Model download and management UI

### Phase 5: Agent System (Weeks 11-12)
- [ ] Manager agent (task decomposition)
- [ ] Executor agent (action dispatching)
- [ ] Reflector agent (success evaluation)
- [ ] Notetaker agent (observation recording)
- [ ] Watcher agent (urgent condition monitoring)
- [ ] Main agent loop (perceive → think → act → reflect)
- [ ] Multi-agent coordination

### Phase 6: UI & UX (Weeks 13-14)
- [ ] Main dashboard (game launcher, status)
- [ ] Game configuration screens
- [ ] Skill editor/browser
- [ ] Live log viewer
- [ ] Session history
- [ ] Settings (API keys, model selection, permissions)
- [ ] Onboarding flow (permissions, first game setup)
- [ ] Floating pill enhancements (status, quick actions)

### Phase 7: Game Support (Weeks 15-16)
- [ ] Clash of Clans game config + skills
- [ ] Clash Royale game config + skills
- [ ] Rise of Kingdoms game config + skills
- [ ] Game config template system
- [ ] Community game config import/export
- [ ] Game-specific screen classifiers
- [ ] Game-specific skills for each target game

### Phase 8: Polish & Performance (Weeks 17-18)
- [ ] Performance optimization (benchmark-driven)
- [ ] Battery optimization (lazy capture, adaptive polling)
- [ ] Error recovery (connection loss, app restart, popup cascade)
- [ ] Safety guardrails (don't spend gems, don't attack allies)
- [ ] External automation API (Tasker/MacroDroid integration)
- [ ] Crash reporting and logging
- [ ] Accessibility service reconnect hardening

### Phase 9: Release (Weeks 19-20)
- [ ] Testing on 5+ Android devices across OEMs
- [ ] GitHub Actions CI/CD (emulator matrix)
- [ ] Release build signing
- [ ] F-Droid listing
- [ ] Documentation (user guide, skill creation guide)
- [ ] Demo video production
- [ ] Play Store listing (if policy allows accessibility-based apps)

---

## 14. Detailed Implementation Plan

### Week 1: Project Setup + Accessibility Foundation

```kotlin
// Goal: App that can read screen and inject a tap

// File: HushyariAccessibilityService.kt
// - Register in AndroidManifest.xml with accessibility_service_config.xml
// - Implement onAccessibilityEvent() to capture window changes
// - Implement onServiceConnected() for initialization
// - Have dispatchGesture() working for tap/swipe

// File: accessibility_service_config.xml
// - Define what events to listen for (WINDOW_STATE_CHANGED, etc.)
// - Define feedback type (GENERIC)

// Test: Install app, enable accessibility, tap a button
```

### Week 2: Tool Layer + Foreground Service

```kotlin
// Goal: 10 generic tools working + app stays alive

// File: Tool.kt
interface Tool {
    val name: String
    val description: String
    suspend fun execute(params: Map<String, Any>): ToolResult
}

// File: HushyariForegroundService.kt
// - type: foregroundServiceType="specialUse" (Android 14+)
// - Notification with "HushYari is active" + Stop button
// - Keep agent loop alive even when screen is off (optional)

// Implement: tap, swipe, long_press, drag, multi_touch,
//           type_text, press_key, open_app, go_home, scroll
```

### Week 3-4: Perception

```kotlin
// Goal: See and understand the game screen in <50ms

// File: AccessibilityReader.kt
// - Parse full UI tree from AccessibilityNodeInfo
// - Extract clickable elements, text fields, scrollable areas
// - Return structured UIElement list
// - Cache last tree for 50ms to avoid repeated parsing

// File: ScreenCapture.kt (MediaProjection)
// - Request MediaProjection permission (system dialog)
// - Create VirtualDisplay linked to ImageReader
// - Provide capture() method returning Bitmap
// - Handle orientation changes

// File: TemplateMatcher.kt (NDK bridge)
// - Load template images (PNG from assets)
// - Match against screen bitmap
// - Return (x, y, confidence) for top N matches
// - Cache results per template

// File: OcrEngine.kt
// - Use ML Kit Text Recognition
// - Read game text (resources, timers, quest descriptions)
// - Return structured text blocks with positions
```

### Week 5-6: State Machine + World Model

```kotlin
// Goal: Know which screen we're on and what's happening

// File: ScreenClassifier.kt
// - Rule-based classification using UI tree + pixel checks
// - Priority-ordered check list per game
// - Confidence scoring
// - Example: "BATTLE_SCREEN" if HP bar element exists

// File: PopupHandler.kt
// - Periodically scan for popup indicators
// - Common patterns: close buttons in corners, full-screen overlays
// - Auto-dismiss with confidence threshold
// - Track "already tried this popup" to avoid loops

// File: WorldState.kt
// - MutableStateFlow containing all tracked game variables
// - Resources: gold, gems, food, wood, etc.
// - Timers: building, research, training, healing
// - Combat: HP, enemies, shield status
// - Position: map coordinates, base location
// - All persisted to MMKV for crash recovery
```

### Week 7-8: Skill Engine

```kotlin
// Goal: Execute pre-defined workflows deterministically

// File: Skill.kt
@Serializable
data class Skill(
    val id: String,
    val name: String,
    val gameType: List<String>,
    val description: String,
    val prerequisites: List<String>,  // Screen names
    val steps: List<SkillStep>,
    val completionCheck: CompletionCheck,
    val retryPolicy: RetryPolicy
)

@Serializable
data class SkillStep {
    val tool: String  // Tool name
    val target: TargetSpec  // What to interact with
    val fallback: TargetSpec?  // Alternative if primary fails
    val timeoutMs: Long
    val waitAfterMs: Long
}

// File: SkillEngine.kt
// - Load active skills from registry
// - Execute steps sequentially
// - Verify each step via perception pipeline
// - Auto-escalate to LLM on repeated failure
// - Support concurrent skills (watcher skills run alongside main)
```

### Week 9-10: LLM Integration

```kotlin
// Goal: Brain for complex decisions

// File: CloudLlmClient.kt
// - Gemini 2.5 Flash (default, best value)
// - GPT-4o (fallback for complex strategy)
// - Claude Sonnet 4 (fallback for planning)
// - Structured output via JSON mode / tool calling
// - Image compression before upload (save bandwidth)
// - Retry with exponential backoff

// File: LocalLlmClient.kt
// - MediaPipe LLM Inference with Gemma 4 E2B
// - Download model on first use (~2.6 GB)
// - Conversation management (multi-turn context)
// - Tool calling support
// - Fallback to CPU if GPU not available
// - Performance monitoring (token/s, memory usage)

// File: PromptEngine.kt
// - Assemble prompts from: system prompt + world state + screen + task + history
// - Game-specific prompt templates
// - Safety constraints baked into system prompt
// - Token budget management (trim history to fit context window)
```

### Week 11-12: Agent System

```kotlin
// Goal: Complete multi-agent loop running autonomously

// File: AgentLoop.kt
// - Perceive → Think → Act → Reflect cycle
// - Escalation ladder (cached → skill → FSM → local LLM → cloud LLM)
// - Error recovery and self-correction
// - StateFlow emission for UI observation
// - Graceful stop and restart

// File: Manager.kt
// - Break high-level goal into sub-tasks
// - Prioritize sub-tasks based on urgency and dependencies
// - "Goal: max out base → Tasks: upgrade TH, upgrade walls, train troops, collect resources"
// - Re-plan when world state changes significantly

// File: Watcher.kt
// - Periodic checks: every 200ms for combat, every 500ms for popups
// - Triggers: HP < 30% → heal, base under attack → return home
// - Non-blocking: runs in parallel with main agent loop
```

### Week 13-14: UI

```kotlin
// Goal: Beautiful, functional user interface

// Material 3 design with dark/light themes
// Navigation: Home | Games | Skills | History | Settings

// HomeScreen.kt:
//   - "Start Playing" button with game selector
//   - Active session status (what's happening right now)
//   - Quick skill launcher (one-tap common actions)
//   - Agent log (live scrolling feed)

// GameConfigScreen.kt:
//   - Select game → load/modify config
//   - Screen classifier test ("what screen is this?")
//   - Element inspector (tap element → see properties)
//   - Coordinate picker (overlay crosshair)

// SkillEditorScreen.kt:
//   - Record mode: perform actions → automatically generate skill
//   - Manual mode: add steps, configure targets
//   - Test mode: run skill step by step
//   - Import/export JSON

// SettingsScreen.kt:
//   - API keys (AES-256-GCM encrypted)
//   - Model selection (local vs cloud)
//   - Performance settings (capture mode, intervals)
//   - Safety rules (spending limits, action blacklists)
//   - External automation (Tasker/MacroDroid)
```

### Week 15-16: Game Support

```kotlin
// Goal: Work with popular games out of the box

// Built-in game configurations:
// - Clash of Clans (strategy)
// - Clash Royale (real-time strategy)
// - Rise of Kingdoms (4X strategy)
// - Brawl Stars (action)

// Each game config includes:
// - Screen identification rules
// - Known UI element positions
// - Common popup patterns
// - Resource tracking definitions
// - Skill library

// Game config template system:
// - Users create configs for any game
// - Share via community marketplace
// - Versioned configs (game updates may break them)
```

### Week 17-18: Performance + Polish

```kotlin
// Goal: Fast, reliable, efficient

// Performance:
// - Profile every component: perception, FSM, skills, LLM calls
// - Optimize hot paths (reduce allocations, use primitives)
// - NDK for pixel operations and template matching
// - Adaptive capture rate (60fps when visual, 10fps when tree-only)
// - Image compression pipeline for cloud LLM calls

// Battery:
// - Coroutine-based scheduler (inactive during idle screens)
// - Coalesce accessibility events (process at most every 50ms)
// - MediaProjection: capture only when needed for visual analysis
// - On-device LLM: sleep when not in use, fast wake-up

// Error Recovery:
// - App crash → foreground service auto-restarts
// - Game crash → detect, re-launch game, resume skill
// - Network loss → queue cloud LLM requests, continue with local
// - Popup cascade → exponential backoff on dismiss attempts
// - Stuck detection → if same screen for 30s, escalate to LLM
```

### Week 19-20: Release

```kotlin
// Goal: Ship it

// Testing matrix:
// - Android 10 through 15
// - Pixel, Samsung, Xiaomi, OnePlus devices
// - Real game testing (hours-long sessions)
// - Edge cases: rotation, split-screen, notifications, calls

// CI/CD:
// - GitHub Actions: build, lint, test
// - Android emulator matrix (API 26-35)
// - Automated smoke tests (install, launch, basic tap)
// - Release APK signing

// Distribution:
// - GitHub Releases (APK + changelog)
// - F-Droid (if accessibility service policy allows)
// - Website with documentation
```

---

## 15. Directory Structure

```
HushYari/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/dev/hushyari/
│       │   │   ├── HushyariApp.kt
│       │   │   ├── MainActivity.kt
│       │   │   ├── agent/
│       │   │   │   ├── AgentLoop.kt
│       │   │   │   ├── Manager.kt
│       │   │   │   ├── Executor.kt
│       │   │   │   ├── Reflector.kt
│       │   │   │   ├── Notetaker.kt
│       │   │   │   ├── Watcher.kt
│       │   │   │   └── Strategist.kt
│       │   │   ├── perception/
│       │   │   │   ├── PerceptionPipeline.kt
│       │   │   │   ├── AccessibilityReader.kt
│       │   │   │   ├── ScreenCapture.kt
│       │   │   │   ├── TemplateMatcher.kt
│       │   │   │   ├── PixelClassifier.kt
│       │   │   │   ├── OcrEngine.kt
│       │   │   │   └── ElementFinder.kt
│       │   │   ├── statemachine/
│       │   │   │   ├── ScreenClassifier.kt
│       │   │   │   ├── PopupHandler.kt
│       │   │   │   ├── StateTransition.kt
│       │   │   │   └── GameFSM.kt
│       │   │   ├── worldmodel/
│       │   │   │   ├── WorldState.kt
│       │   │   │   ├── ResourceTracker.kt
│       │   │   │   ├── TimerEngine.kt
│       │   │   │   ├── PositionTracker.kt
│       │   │   │   ├── CombatState.kt
│       │   │   │   └── InventoryTracker.kt
│       │   │   ├── skills/
│       │   │   │   ├── Skill.kt
│       │   │   │   ├── SkillRegistry.kt
│       │   │   │   ├── SkillEngine.kt
│       │   │   │   ├── SkillVerifier.kt
│       │   │   │   └── builtin/
│       │   │   │       ├── GenericSkills.kt
│       │   │   │       └── GameSkills.kt
│       │   │   ├── tools/
│       │   │   │   ├── Tool.kt
│       │   │   │   ├── ToolManager.kt
│       │   │   │   ├── GestureTool.kt
│       │   │   │   ├── InputTool.kt
│       │   │   │   ├── ScreenTool.kt
│       │   │   │   ├── FindTool.kt
│       │   │   │   └── WaitTool.kt
│       │   │   ├── llm/
│       │   │   │   ├── LlmClient.kt
│       │   │   │   ├── CloudLlmClient.kt
│       │   │   │   ├── LocalLlmClient.kt
│       │   │   │   ├── ModelManager.kt
│       │   │   │   ├── PromptEngine.kt
│       │   │   │   └── ResponseParser.kt
│       │   │   ├── controller/
│       │   │   │   ├── DeviceController.kt
│       │   │   │   ├── AccessibilityController.kt
│       │   │   │   ├── ShizukuController.kt
│       │   │   │   └── GestureDispatcher.kt
│       │   │   ├── service/
│       │   │   │   ├── HushyariAccessibilityService.kt
│       │   │   │   ├── HushyariForegroundService.kt
│       │   │   │   ├── ScreenCaptureService.kt
│       │   │   │   ├── NotificationMonitorService.kt
│       │   │   │   └── OverlayService.kt
│       │   │   ├── ui/
│       │   │   │   ├── screens/
│       │   │   │   ├── components/
│       │   │   │   └── theme/
│       │   │   ├── data/
│       │   │   │   ├── local/
│       │   │   │   └── model/
│       │   │   ├── gameconfig/
│       │   │   │   ├── GameConfigProvider.kt
│       │   │   │   └── templates/
│       │   │   └── di/
│       │   │       └── AppModule.kt
│       │   ├── cpp/  # NDK native code
│       │   │   ├── template_match.cpp
│       │   │   ├── pixel_ops.cpp
│       │   │   └── CMakeLists.txt
│       │   └── res/
│       │       ├── xml/
│       │       │   └── accessibility_service_config.xml
│       │       └── values/
│       └── test/
│           └── java/dev/hushyari/
├── docs/
│   ├── ARCHITECTURE.md
│   ├── USER_GUIDE.md
│   ├── SKILL_CREATION.md
│   └── CONTRIBUTING.md
├── gradle/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
├── LICENSE
└── README.md
```

---

## Key Design Decisions

### Why Kotlin (not Python, not Flutter)?

- **Performance**: Kotlin compiles to native Android bytecode. Python agents require PyTorch/Mobile runtime overhead.
- **API access**: Full Android API access (AccessibilityService, MediaProjection, etc.)
- **Industry trend**: Both PokeClaw and Roubao are Kotlin. The winning Android agent projects use Kotlin.
- **NDK compatibility**: JNI bridge to C++ for performance-critical operations.

### Why AccessibilityService (not just Shizuku)?

- **No setup required**: User just enables accessibility (same as any accessibility app)
- Shizuku requires wireless debugging or ADB connection (tech barrier)
- AccessibilityService gestures work on ALL Android versions 7+
- Shizuku can be an optional accelerator for power users

### Why Hybrid AI (not just cloud, not just local)?

- **Cloud-only**: Expensive at scale ($720/month for 24/7 use), requires internet, latency
- **Local-only**: Limited by phone hardware, smaller models, less capable
- **Hybrid**: Free for 95% of actions, cloud for intelligence when needed

### Why Game-Specific Configs (not "universal AI")?

- Games have wildly different UI patterns
- Pre-configured screen classifiers are 100x faster than LLM "figure it out"
- Community can contribute for any game
- The LLM handles edge cases; configs handle the routine

---

*HushYari — "the game plays itself" — هۆشیاری*
