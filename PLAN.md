# HushYari — Implementation Plan

## Complete File Inventory & Build Order

Every file needed to build HushYari from scratch. Organized by build phase.
Files marked with 🧠 use mechanics from research projects.

---

## PHASE 0: Build System (files: 5)

```
build.gradle.kts                    # Root build config
settings.gradle.kts                 # Module settings
gradle.properties                   # Gradle props
gradle/libs.versions.toml           # Version catalog
app/build.gradle.kts                # App module build config
app/proguard-rules.pro              # ProGuard rules
```

---

## PHASE 1: Foundation — Domain + Manifest (files: 8)

```
app/src/main/AndroidManifest.xml
app/src/main/res/xml/accessibility_service_config.xml
app/src/main/res/values/strings.xml
app/src/main/res/values/themes.xml
app/src/main/java/dev/hushyari/
  ├── HushyariApp.kt               # Application class
  ├── di/AppModule.kt              # Hilt DI module
  ├── data/model/
  │   ├── UIElement.kt             # Accessibility element model
  │   ├── ScreenState.kt           # Captured screen state
  │   ├── GameTask.kt              # User-given task model
  │   ├── WorldState.kt            # Game world state model
  │   ├── Skill.kt                 # Skill definition model
  │   ├── ToolResult.kt            # Tool execution result
  │   └── AgentEvent.kt            # Agent loop events
```

---

## PHASE 2: Tool Layer (files: 12) 🧠 PokeClaw + Roubao

```
app/src/main/java/dev/hushyari/tools/
  ├── Tool.kt                      # Tool interface
  ├── ToolManager.kt               # Registry + safety rules
  ├── ToolSafety.kt               # 13 safety rules (🧠 PokeClaw)
  ├── GestureTool.kt              # tap, swipe, long_press, drag
  ├── ScrollTool.kt               # scroll up/down/left/right
  ├── InputTool.kt                # type_text, press_key, paste
  ├── AppTool.kt                  # open_app, go_home, go_back
  ├── ScreenTool.kt               # take_screenshot, get_screen_info
  ├── FindTool.kt                 # find_element, find_text, find_image
  ├── WaitTool.kt                 # wait_for, wait_until, sleep
  ├── ClipboardTool.kt            # copy, paste clipboard
  └── VoiceTool.kt                # speech input (optional)
```

---

## PHASE 3: Accessibility Service (files: 4) 🧠 PokeClaw + Roubao

```
app/src/main/java/dev/hushyari/service/
  ├── HushyariAccessibilityService.kt   # Core service
  ├── AccessibilityTreeParser.kt        # UI tree extraction
  └── AccessibilityGestureDispatcher.kt # Gesture injection

app/src/main/java/dev/hushyari/perception/
  └── AccessibilityReader.kt            # Read + cache UI state
```

---

## PHASE 4: Perception Pipeline (files: 7) 🧠 4x-game-agent + ClickClickClick

```
app/src/main/java/dev/hushyari/perception/
  ├── PerceptionPipeline.kt         # Orchestrator (🧠 4x-agent Layer 1)
  ├── AccessibilityReader.kt        # UI tree extractor
  ├── ScreenCapture.kt              # MediaProjection capture service
  ├── TemplateMatcher.kt            # Image template matching (NDK bridge)
  ├── PixelClassifier.kt            # Fast pixel-based screen ID
  ├── OcrEngine.kt                  # ML Kit text recognition
  └── ElementFinder.kt              # Multi-strategy element finding
```

---

## PHASE 5: Controller Layer (files: 4) 🧠 Roubao + PokeClaw

```
app/src/main/java/dev/hushyari/controller/
  ├── DeviceController.kt            # Interface
  ├── AccessibilityController.kt     # Via AccessibilityService
  ├── ShizukuController.kt           # Via Shizuku (🧠 Roubao)
  └── GestureDispatcher.kt           # Priority-ordered dispatch
```

---

## PHASE 6: LLM Layer (files: 10) 🧠 PokeClaw + Roubao + ClickClickClick

```
app/src/main/java/dev/hushyari/llm/
  ├── LlmClient.kt                  # Interface
  ├── CloudLlmClient.kt             # Gemini/GPT/Claude API (🧠 Roubao)
  ├── LocalLlmClient.kt             # MediaPipe Gemma (🧠 PokeClaw)
  ├── ModelManager.kt               # Download + lifecycle (🧠 PokeClaw)
  ├── PromptEngine.kt               # Prompt assembly
  ├── PromptTemplates.kt            # Game-specific templates
  ├── ResponseParser.kt             # LLM output -> structured action
  ├── ChatHistory.kt                # Multi-turn conversation memory
  ├── ApiKeyManager.kt              # AES-256-GCM encrypted (🧠 Roubao)
  └── LlmConfig.kt                  # Model configuration data class
```

---

## PHASE 7: State Machine + World Model (files: 8) 🧠 4x-game-agent

```
app/src/main/java/dev/hushyari/statemachine/
  ├── ScreenClassifier.kt            # Which screen are we on? (🧠 Layer 2)
  ├── PopupHandler.kt                # Detect + dismiss popups
  ├── GameFSM.kt                     # Game finite state machine
  └── StateTransition.kt             # Screen navigation rules

app/src/main/java/dev/hushyari/worldmodel/
  ├── WorldStateManager.kt           # Live game state holder (🧠 Layer 3)
  ├── ResourceTracker.kt             # Gold, gems, energy tracking
  ├── TimerEngine.kt                 # Building/research timer predictions
  └── PositionTracker.kt             # Map position, base location
```

---

## PHASE 8: Skill Engine (files: 8) 🧠 PokeClaw + 4x-game-agent

```
app/src/main/java/dev/hushyari/skills/
  ├── Skill.kt                       # Skill data model
  ├── SkillRegistry.kt              # Manage skill library
  ├── SkillEngine.kt                # Execute skill workflows (🧠 Layer 4)
  ├── SkillVerifier.kt              # Confirm each step succeeded
  ├── SkillLoader.kt                # Load skills from JSON/YAML
  └── builtin/
      ├── GenericSkills.kt          # Universal skills
      ├── StrategyGameSkills.kt     # 4X strategy game skills
      └── RpgGameSkills.kt         # RPG game skills
```

---

## PHASE 9: Agent System (files: 8) 🧠 Roubao + ClickClickClick

```
app/src/main/java/dev/hushyari/agent/
  ├── AgentLoop.kt                  # Main perception-think-act loop
  ├── Manager.kt                    # Task decomposition (🧠 Roubao)
  ├── Executor.kt                   # Action execution
  ├── Reflector.kt                  # Success/failure evaluation (🧠 Roubao)
  ├── Notetaker.kt                  # Important observations
  ├── Watcher.kt                    # Urgent condition monitor
  ├── Strategist.kt                 # Long-term planning
  └── Planner.kt                    # Step-by-step planning (🧠 ClickClickClick)
```

---

## PHASE 10: Services (files: 5) 🧠 PokeClaw

```
app/src/main/java/dev/hushyari/service/
  ├── HushyariForegroundService.kt   # Keep agent alive (🧠 PokeClaw)
  ├── ScreenCaptureService.kt        # MediaProjection capture
  ├── NotificationMonitorService.kt  # Game notifications (🧠 PokeClaw)
  ├── OverlayService.kt              # Floating controls
  └── ExternalAutomationReceiver.kt  # Tasker/MacroDroid API (🧠 PokeClaw)
```

---

## PHASE 11: UI Layer (files: 14) 🧠 Roubao + PokeClaw

```
app/src/main/java/dev/hushyari/ui/
  ├── MainActivity.kt
  ├── navigation/NavGraph.kt
  ├── theme/Theme.kt
  ├── theme/Color.kt
  ├── theme/Type.kt
  ├── screens/
  │   ├── HomeScreen.kt             # Dashboard + game launcher
  │   ├── GameScreen.kt             # Active game session view
  │   ├── SkillEditorScreen.kt      # Create/edit skills
  │   ├── GameConfigScreen.kt       # Per-game settings
  │   ├── HistoryScreen.kt          # Session history/logs
  │   ├── SettingsScreen.kt         # App configuration
  │   └── ModelManagerScreen.kt     # LLM model management
  ├── components/
  │   ├── FloatingPill.kt           # Overlay control
  │   ├── StatusBar.kt              # Game session status
  │   ├── SkillCard.kt              # Skill display card
  │   ├── LogViewer.kt              # Real-time log
  │   ├── PermissionGate.kt         # Permission request UI
  │   └── GameSelector.kt           # Game picker dialog
  └── viewmodel/
      ├── HomeViewModel.kt
      ├── GameViewModel.kt
      ├── SettingsViewModel.kt
      └── SkillViewModel.kt
```

---

## PHASE 12: Data Layer (files: 6) 🧠 PokeClaw

```
app/src/main/java/dev/hushyari/data/
  ├── local/
  │   ├── HushyariDatabase.kt       # Room database
  │   ├── GameConfigDao.kt
  │   ├── SkillDao.kt
  │   ├── SessionLogDao.kt
  │   └── PreferencesManager.kt    # MMKV-backed preferences
  └── repository/
      ├── GameRepository.kt
      ├── SkillRepository.kt
      └── SessionRepository.kt
```

---

## PHASE 13: Game Configs (files: 6) 🧠 4x-game-agent

```
app/src/main/assets/games/
  ├── game_config_schema.json
  ├── clash_of_clans.json
  ├── clash_royale.json
  ├── rise_of_kingdoms.json
  ├── brawl_stars.json
  └── _template.json
```

---

## PHASE 14: Native NDK (files: 4) 🧠 4x-game-agent

```
app/src/main/cpp/
  ├── template_match.cpp             # OpenCV template matching
  ├── pixel_ops.cpp                  # Pixel operations
  ├── image_compress.cpp             # JPEG/WebP compression
  └── CMakeLists.txt                 # CMake build
```

---

## PHASE 15: Tests (files: 10+)

```
app/src/test/java/dev/hushyari/
  ├── tools/ToolManagerTest.kt
  ├── tools/GestureToolTest.kt
  ├── perception/ElementFinderTest.kt
  ├── statemachine/ScreenClassifierTest.kt
  ├── statemachine/PopupHandlerTest.kt
  ├── worldmodel/ResourceTrackerTest.kt
  ├── skills/SkillEngineTest.kt
  ├── agent/ManagerTest.kt
  ├── agent/AgentLoopTest.kt
  └── llm/PromptEngineTest.kt
```

---

## TOTAL: ~110 files across 15 phases

## Build Order: Foundation First

Each phase depends only on previous phases. This ensures every layer is
compilable and testable before the next layer is built.

### Dependency Graph

```
Phase 0 (Build)
  └─> Phase 1 (Domain + DI)
        ├─> Phase 2 (Tools)
        │     ├─> Phase 3 (Accessibility)
        │     │     └─> Phase 5 (Controller)
        │     │           └─> Phase 8 (Services)
        │     ├─> Phase 4 (Perception)
        │     │     └─> Phase 7 (State/World)
        │     │           └─> Phase 8 (Skills)
        │     │                 └─> Phase 9 (Agents)
        │     │                       └─> Phase 11 (UI)
        │     └─> Phase 6 (LLM)
        │           └─> Phase 9 (Agents)
        └─> Phase 12 (Data)
              └─> Phase 11 (UI)

Phase 13 (Game Configs) — standalone, loaded at runtime
Phase 14 (NDK) — optional, enhances Perception
Phase 15 (Tests) — parallel with all phases
```

---

## Key Design Decisions Based on Research

| Decision | Source | Why |
|----------|--------|-----|
| Kotlin + Compose | PokeClaw, Roubao | Both successful projects use this. Full Android API access. |
| Tools + Skills architecture | PokeClaw, Roubao | Proven pattern: 21 tools → unlimited skills. Generic + extensible. |
| Multi-agent decomposition | Roubao, MobileAgent-v3 | Manager/Executor/Reflector/Notetaker is battle-tested in research. |
| 6-layer escalation ladder | 4x-game-agent | 99% cost reduction. Deterministic first, LLM as fallback. |
| Planner + Finder separation | ClickClickClick | Cheaper models for finding, smarter models for planning. |
| AccessibilityService primary | PokeClaw, Roubao | No root required. Works on all Android 7+. Most reliable. |
| Shizuku optional | Roubao | Power users get raw speed. Normal users use Accessibility. |
| Hybrid local + cloud LLM | PokeClaw | Free for routine, cloud for hard problems. Best of both. |
| MMKV + Room | PokeClaw | 10x faster than SharedPreferences. Room for structured data. |
| 13 safety rules | PokeClaw | Payment detection, sensitive screen avoidance, action limits. |
| External automation API | PokeClaw | Tasker/MacroDroid integration. Users trigger AI from automation apps. |
| Foreground service + pill | PokeClaw | Keep alive + user visibility. Android kills background services. |
| AES-256-GCM key storage | Roubao | API keys must be encrypted. Industry standard. |
| NDK for perf-critical ops | 4x-game-agent | Template matching, pixel ops need C++/SIMD speed. |
| Per-game JSON configs | 4x-game-agent | Game-specific knowledge without code changes. Community-contributable. |

---

## Mechanics Integration Map

Every important mechanic from the 4 research projects and where it appears in HushYari:

### From PokeClaw:
1. ✅ Tools + Skills architecture → `tools/` + `skills/` modules
2. ✅ AccessibilityService → `service/HushyariAccessibilityService.kt`
3. ✅ Foreground service → `service/HushyariForegroundService.kt`
4. ✅ Floating pill overlay → `ui/components/FloatingPill.kt`
5. ✅ MMKV storage → `data/local/PreferencesManager.kt`
6. ✅ 13 safety rules → `tools/ToolSafety.kt`
7. ✅ External automation API → `service/ExternalAutomationReceiver.kt`
8. ✅ Local LLM (MediaPipe) → `llm/LocalLlmClient.kt`
9. ✅ Model download manager → `llm/ModelManager.kt`
10. ✅ Notification monitoring → `service/NotificationMonitorService.kt`
11. ✅ Conversation persistence → `llm/ChatHistory.kt`
12. ✅ QA checklist → `QA_CHECKLIST.md`
13. ✅ OEM compatibility → `docs/OEM_COMPATIBILITY.md`

### From Roubao:
1. ✅ Multi-agent decomposition → `agent/Manager|Executor|Reflector|Notetaker.kt`
2. ✅ Shizuku controller → `controller/ShizukuController.kt`
3. ✅ Material 3 UI → `ui/theme/` + Compose screens
4. ✅ Multi-VLM support → `llm/CloudLlmClient.kt` (Gemini, GPT-4o, Claude)
5. ✅ AES-256-GCM encryption → `llm/ApiKeyManager.kt`
6. ✅ Delegation vs GUI modes → `skills/SkillEngine.kt` (delegation path + agent path)
7. ✅ Smart app search → `tools/AppTool.kt`
8. ✅ DeepLink delegation → `tools/AppTool.kt` (open_app with deep link)

### From 4x-game-agent:
1. ✅ 5-layer architecture → 6 layers in HushYari (10-14 below)
2. ✅ Local perception → `perception/AccessibilityReader.kt`
3. ✅ State machine → `statemachine/GameFSM.kt`
4. ✅ World model → `worldmodel/WorldStateManager.kt`
5. ✅ Workflow engine → `skills/SkillEngine.kt`
6. ✅ Strategic AI → `llm/CloudLlmClient.kt` (strategist role)
7. ✅ Self-improving reflection → `agent/Reflector.kt`
8. ✅ Per-game configs → `assets/games/*.json`
9. ✅ Template matching → `perception/TemplateMatcher.kt`
10. ✅ Pixel classification → `perception/PixelClassifier.kt`
11. ✅ OCR text reading → `perception/OcrEngine.kt`
12. ✅ Web dashboard → future phase

### From ClickClickClick:
1. ✅ Planner + Finder separation → `agent/Planner.kt` + `perception/ElementFinder.kt`
2. ✅ Image quality compression → `perception/ScreenCapture.kt` (quality param)
3. ✅ Multi-LLM support → `llm/CloudLlmClient.kt`
4. ✅ CLI interface → `tools/` + `agent/AgentLoop.kt` (callable from CLI)
5. ✅ REST API → `service/ExternalAutomationReceiver.kt` (HTTP endpoint optional)
6. ✅ Ollama support → `llm/CloudLlmClient.kt` (Ollama endpoint in config)

---

## Escalation Ladder Implementation

This is THE core mechanic from 4x-game-agent adapted for HushYari:

```
Level 0 (0ms)    → CachedActionCache        → Same action as last 100ms
Level 1 (1-5ms)  → SkillEngine.executeStep()  → Deterministic skill step
Level 2 (5-20ms) → GameFSM.transition()       → Known screen transition
Level 3 (10-50ms)→ ElementFinder.find()       → Template/OCR/pixel match
Level 4 (100-2s) → LocalLlmClient.analyze()   → Simple on-device decision
Level 5 (1-5s)   → CloudLlmClient.analyze()   → Strategic cloud reasoning
```

Every layer has a confidence score. The agent always tries the lowest layer
first and escalates only when confidence < threshold.

---

## Safety Rules (from PokeClaw, adapted for gaming)

1. Never spend premium currency without explicit permission
2. Never attack players marked as "ally" or "friend"
3. Never delete or sell rare items
4. Pause if payment screen detected
5. Pause if password/authentication screen detected
6. Respect daily action limits
7. Never send chat messages without explicit content
8. Stop if same action fails 3+ times consecutively
9. Detect and handle connection/game crash
10. Never change account settings
11. Respect configured play time limits
12. Emergency stop on hardware button press
13. Log every action for audit trail
