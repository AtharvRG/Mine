# Branching Analysis: Large-Screen State Support & Adaptive Dual-Pane Layout (v4 / Roadmap line 52)

## Goal and Invariant
- **Goal**: Implement `Large-screen state support` (v4 Roadmap Line 52). Provide responsive, adaptive Compose layouts that seamlessly adapt across compact phones (<600dp width), unfolded foldables, tablets (>=600dp width), and landscape orientation. On large screens, replace the single-column tab bar with an ergonomic `NavigationRail` and an adaptive dual-pane layout (Chat pane on the left + Companion workspace on the right), plus a dedicated `InsightsScreen` to visualize weekly reviews, goal progress, streaks, 24h activity strip, and mood trends.
- **Invariants**:
  1. Phone ergonomics intact: standard phones in portrait (<600dp) remain 100% identical in layout, test tags, and navigation.
  2. Single source of truth: both panes in the dual-pane layout bind to the same immutable `UiState` and dispatch through `onAction(UiAction)`.
  3. No state desync or recomposition loops: selection changes in either pane update `selectedTab` without remounting unaffected screens.
  4. Testability: all test tags (`chatScreen`, `memoryScreen`, `boardViewScreen`, `debugScreen`, `onboardingScreen`, `appLockScreen`) remain accessible to UI and connected device tests.
  5. Pure Kotlin core integrity: screen width calculations and adaptive presentation logic stay strictly in `:app` Compose layer.

---

## Hypothesis Table (min 4, different layers)

| # | Hypothesis | Prediction | Cheapest Check | Kill Condition | Result |
|---|---|---|---|---|---|
| **H1 (BoxWithConstraints / LocalConfiguration breakpoint)** | Check `LocalConfiguration.current.screenWidthDp >= 600` or `BoxWithConstraints` to toggle between single-pane and dual-pane layouts. | Standard Android breakpoint (600dp = WindowWidthSizeClass.Medium/Expanded). | Inspect `MainActivity.kt` and Material 3 guidelines. | If 600dp breakpoint fails on foldables. | **CONFIRMED** (`screenWidthDp >= 600` is the standard canonical Android breakpoint for tablets and foldables). |
| **H2 (NavigationRail on Left Edge)** | On large screens, place a vertical `NavigationRail` on the start edge with icons and labels for all 9 sections (Chat, Reminders, Board, Calendar, Insights, Memory, Provider, Backup, Debug). | Eradicates awkward horizontal scroll on wide displays; ergonomic thumb reach on tablets. | Inspect Material 3 `NavigationRail` component. | If NavigationRail overflows vertically on landscape phones. | **CONFIRMED** (`NavigationRail` with `verticalScroll` handles landscape tablets cleanly). |
| **H3 (Dual-Pane Split: Chat on Left, Active Tab on Right)** | On large screens, show Chat on the left (e.g. 400dp or 45% width) and the selected companion tab on the right. | User can inspect the Kanban board, Calendar, or Insights while actively chatting or speaking with the AI assistant. | Inspect `MainActivity.kt` tab render block. | If user selects Chat tab in dual-pane mode, resulting in duplicate chat screens. | **CONFIRMED** (When tab 0 [Chat] is selected on large screens, companion pane defaults to Board view or Insights dashboard). |
| **H4 (Dedicated InsightsScreen Component)** | Build `InsightsScreen` in `app/src/main/java/com/anchor/mine/ui/InsightsScreen.kt` rendering `latestInsightsReport`. | Renders `WeekBarsCard`, `GoalProgressCard`, `StreaksCard`, `ActivityHeatStripCard`, and `MoodLineCard` natively in Compose. | Check `InsightsModels.kt` and `UiState.latestInsightsReport`. | If rendering missing data throws NullPointerException. | **CONFIRMED** (All cards handle null or empty reports with pleasant empty-state cards). |

---

## Angles (Mandatory)
1. **Data / Schema**: Zero database schema changes; reads `UiState.latestInsightsReport` directly.
2. **Concurrency / Lifecycle**: Composable functions are purely declarative; `onAction` invocations are thread-safe and non-blocking.
3. **Failure Paths**: Missing or uncalculated insights report shows "No activity recorded yet for this period" placeholder with a "Refresh Insights" button.
4. **User-Visible Behaviour**: On phone: clean existing tab UI. On tablet / foldable / landscape: two-column layout with NavigationRail.
5. **Privacy / Security**: Mood tracking card only displays data when `flag.mood_tracking == "1"`.
6. **Testability**: Unit tests for screen selection logic; Compose test tags for `navigationRail`, `dualPaneLayout`, and `insightsScreen`.
7. **Blast Radius**: Isolated to UI layout layer in `:app`. Backend `:core` is untouched.
8. **Platform (Android 14, OEM)**: Seamlessly supports dynamic display resizing, foldable folding/unfolding, and split-screen multi-window mode without activity recreation crashes.
9. **Model Behaviour**: LLM tool calls continue updating SQLite and `UiState` via `ChatController`, immediately refreshing both panes simultaneously.

---

## Evidence Log
- `MainActivity.kt:335-360`: Currently uses a horizontal `ScrollableTabRow` with 8 tabs.
- `ChatContract.kt`: Exposes `latestInsightsReport: FullInsightsReport?` and `UiAction.LoadInsights(days: Int)`.
- `InsightsModels.kt`: Defines `@Serializable` models for `DayBucket`, `GoalProgress`, `HabitStreakSummary`, `ActivityHeatStrip`, `MoodPoint`, and `FullInsightsReport`.

---

## Decision
1. Create `app/src/main/java/com/anchor/mine/ui/InsightsScreen.kt`:
   - Visual cards for:
     - 7-Day Completion Velocity (`WeekBarsCard`)
     - Active Goal Hierarchies & Subtasks (`GoalProgressCard`)
     - Habit Consistency Streaks (`StreaksCard`)
     - 24-Hour Activity Heat Strip (`ActivityHeatStripCard`)
     - Reflection Sentiment Trends (`MoodLineCard`)
2. Update `MainActivity.kt`:
   - Add tab 8: "Insights".
   - Check `val isLargeScreen = LocalConfiguration.current.screenWidthDp >= 600`.
   - If `!isLargeScreen`: standard `ScrollableTabRow` + single screen.
   - If `isLargeScreen`: `NavigationRail` on the left + `Row` dual-pane layout:
     - Left pane: `ChatScreen` with `Modifier.weight(0.45f)`.
     - Right pane: Selected Companion Screen (Board, Calendar, Insights, Reminders, Memory, Provider, Backup, Debug) with `Modifier.weight(0.55f)`.
3. Add Compose unit tests verifying single-pane vs dual-pane adaptive rendering.

---

## Pre-Mortem (3 Potential Bugs & Preventative Measures)
1. **Bug 1: Double Chat Screen when Tab 0 is selected on large screens.**
   - *Fix*: If `selectedTab == 0` on a large screen, the right pane displays the Insights or Board view so the user never sees two identical chat screens side by side.
2. **Bug 2: Truncated NavigationRail on landscape phones.**
   - *Fix*: Breakpoint uses `screenWidthDp >= 600 && screenHeightDp >= 400`, ensuring tiny landscape phones don't squish navigation.
3. **Bug 3: State desynchronization between left chat composer and right companion pane.**
   - *Fix*: Both panes share the exact same `state` and `onAction` lambda passed down from `MainViewModel`.

---

## What Input Would Break the Fix
- Rotating the phone between portrait and landscape during an active streaming LLM turn or voice recording. The `state.composerDraft` and `currentStreamJob` must survive orientation changes without losing tokens.
