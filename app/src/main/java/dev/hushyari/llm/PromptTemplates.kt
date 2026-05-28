package dev.hushyari.llm

data class PromptTemplate(
    val name: String,
    val gamePackage: String = "",
    val systemPrompt: String = "",
    val actionPromptSuffix: String = "",
    val uiKnowledge: String = "",
    val strategies: List<String> = emptyList(),
    val commonElements: Map<String, String> = emptyMap(),
    val skills: Map<String, String> = emptyMap(),
)

object PromptTemplates {

    val SYSTEM_PROMPT_BASE = """
You are HushYari, an AI game-playing agent that controls an Android device to play mobile
games autonomously. You observe the screen through accessibility tree elements and OCR text,
then decide which action to take.

You have access to tools (functions):
- tap(x, y): Tap at coordinates
- swipe(x1, y1, x2, y2, duration_ms): Swipe gesture
- long_press(x, y): Long press
- type_text(text): Type into focused field
- scroll(direction, amount): Scroll
- open_app(package): Launch an app
- go_home(): Return to home screen
- go_back(): Press back button
- wait_for(condition, timeout_ms): Wait until condition met
- find_element(query): Find UI element
- get_screen_info(): Get current screen summary
- take_screenshot(): Capture current screen

Safety rules:
- Never spend premium currency (gems, diamonds, etc.) without explicit permission
- Never attack allies or friendly players
- Never delete or sell rare items
- Stop if a payment/password screen appears
- If the same action fails 3 times, try a different approach
- If the game crashes, try to reopen it

Response format:
Respond with a JSON object:
{"action": "tool_name", "params": {"param_name": "value"}, "reasoning": "why you chose this"}

For multi-step plans:
{"plan": ["step1", "step2", ...], "reasoning": "overall strategy"}

When done:
{"action": "done", "reasoning": "task completed successfully"}

Always include your reasoning. Be precise with coordinates.
If you are unsure about an element's location, use find_element first.
Prefer exact coordinates from the accessibility tree over guessing.
If an action fails, try an alternative approach.
""".trimIndent()

    val GAME_SPECIFIC_PROMPTS: Map<String, PromptTemplate> = mapOf(
        "com.supercell.clashofclans" to PromptTemplate(
            name = "Clash of Clans",
            gamePackage = "com.supercell.clashofclans",
            uiKnowledge = """
Clash of Clans has a village view with buildings arranged on a grid.
The shop button is at bottom-right.
The attack button is at bottom-left.
The clan button is on the left side.
The builder base is accessible via bottom-left boat icon.
Resources (gold, elixir, dark elixir) are displayed at top of screen.
Gems counter is at top-right.
""".trimIndent(),
            strategies = listOf(
                "Keep builders busy at all times",
                "Upgrade town hall only when defenses are maxed",
                "Collect resources from collectors regularly",
                "Donate troops to clan members for XP",
                "Attack in clan wars when available",
            ),
        ),

        "com.supercell.clashroyale" to PromptTemplate(
            name = "Clash Royale",
            gamePackage = "com.supercell.clashroyale",
            uiKnowledge = """
Clash Royale has a main screen with Battle button, card collection, and shop.
During battle, elixir bar is at bottom, cards are at bottom.
Opponent towers are across the field.
Elixir regenerates over time during battle.
""".trimIndent(),
            strategies = listOf(
                "Wait for full elixir before deploying cards",
                "Counter opponent's cards with appropriate counters",
                "Open chests when slots are available",
            ),
        ),

        "com.supercell.brawlstars" to PromptTemplate(
            name = "Brawl Stars",
            gamePackage = "com.supercell.brawlstars",
            uiKnowledge = """
Brawl Stars has a main screen with Brawl button, Brawlers, and Shop.
During battle, virtual joystick on left, attack on right, super on center-right.
Trophy road and quests on main screen.
""".trimIndent(),
            strategies = listOf(
                "Complete daily quests",
                "Open Brawl Boxes when available",
                "Select brawler before entering match",
            ),
        ),

        "com.miHoYo.GenshinImpact" to PromptTemplate(
            name = "Genshin Impact",
            gamePackage = "com.miHoYo.GenshinImpact",
            uiKnowledge = """
Genshin Impact UI has virtual joystick on lower-left for movement.
Attack, Skill, Burst buttons on lower-right.
Character HP bars on right side.
Map accessible via top-left icon.
Party setup via character icon top-right.
""".trimIndent(),
            strategies = listOf(
                "Use elemental reactions for combat efficiency",
                "Collect domain rewards daily",
                "Spend resin before capping",
            ),
        ),
    )

    suspend fun loadFromAssets(
        androidContext: android.content.Context,
    ): Map<String, PromptTemplate> {
        val templates = mutableMapOf<String, PromptTemplate>()
        try {
            val assetFiles = androidContext.assets.list("games") ?: emptyArray()
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            for (filename in assetFiles) {
                if (!filename.endsWith(".json")) continue
                try {
                    val content = androidContext.assets.open("games/$filename")
                        .bufferedReader().readText()
                    val template = json.decodeFromString<PromptTemplate>(content)
                    templates[template.gamePackage] = template
                } catch (e: Exception) {
                    timber.log.Timber.w("Failed to load prompt template $filename: ${e.message}")
                }
            }
        } catch (_: Exception) { }
        return GAME_SPECIFIC_PROMPTS + templates
    }

    fun forGame(gamePackage: String): PromptTemplate? =
        GAME_SPECIFIC_PROMPTS[gamePackage]
}
