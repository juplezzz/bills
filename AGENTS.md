# AGENTS.md

Bills — snap a receipt, let a vision model read it, fix it up, say who ate what, and get an
exact per-person split plus a short Indonesian recap to share.

Kotlin Multiplatform + Compose Multiplatform. Android is the only shipping target today; the
iOS app is still the KMP wizard template. `README.md` is the public overview; this file is the
source of truth for how the code works.

## Modules

| Module        | Targets                                  | What lives there                                                                                                                                                                                                                                                             |
|---------------|------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `androidApp`  | Android                                  | Thin shell: `BillsApplication` owns the one `BillsStore` (Android SQLite driver); `MainActivity` wires the `AiProvider` chain (keys from `BuildConfig`), the store, `AndroidReceiptCapture`, the share intent and the reduced-motion setting into `App(...)`.                |
| `sharedUI`    | **Android only** (no iOS target yet)     | `App.kt`, `BillsViewModel`, screens in `ui/screen/`, theme, shared components. `androidMain` holds the CameraX/gallery capture.                                                                                                                                              |
| `sharedLogic` | Android, `iosArm64`, `iosSimulatorArm64` | `model/` (Receipt, Person, Expense, SavedSplit, Category), `split/` (SplitCalculator), `spend/` (SpendCalculator, CategoryGuesser), `ai/` (ReceiptScanner, AiProvider, OpenAI-style wire types), `db/` (BillsStore over SQLDelight; schema in `src/commonMain/sqldelight/`). |
| `iosApp`      | iOS                                      | SwiftUI template showing `Greeting()` from `SharedLogic`. Not wired to `sharedUI`.                                                                                                                                                                                           |

`Greeting.kt`, `GreetingUtil.kt`, `Platform*.kt` and the `example()` tests are template leftovers
kept alive only by `iosApp`.

## Commands

```bash
./gradlew :androidApp:assembleDebug                      # build the app
./gradlew :androidApp:assembleRelease                    # R8-minified, unsigned (see Build wiring)
./gradlew :sharedLogic:testAndroidHostTest               # the real tests (commonTest, on JVM)
./gradlew :sharedLogic:testAndroidHostTest --tests "com.disheveled.bills.split.SplitCalculatorTest"
./gradlew :sharedLogic:iosSimulatorArm64Test             # same commonTest on Kotlin/Native
./gradlew :sharedUI:testAndroidHostTest                  # UI copy helpers (dates, money, spend text)
```

- JVM toolchain comes from `gradle/gradle-daemon-jvm.properties` (JDK 21); bytecode targets JVM 11.
- Configuration cache and build cache are on. Read files/env in build scripts through
  `providers` (see how `androidApp/build.gradle.kts` reads `local.properties`), never directly.
- iOS: open `iosApp/` in Xcode; its build phase runs `:sharedLogic:embedAndSignAppleFrameworkForXcode`.

## Setup

- `GEMINI_API_KEY`, `MISTRAL_API_KEY`, `GROQ_API_KEY` and `NVIDIA_API_KEY` go in `local.properties` (or env vars of
  the same names for CI). Any one is enough; a provider without a key is skipped, and none at all
  builds fine and shows up in-app as `FailureReason.MissingApiKey`. Never commit or print them.
- `BuildConfig` exists only in `androidApp`, so the keys are passed inward as
  `App(aiProviders = listOf(AiProvider.gemini(…), …))`.

## Flow

Two tabs under a bottom nav: Pengeluaran (`Screen.Spend`, where the app opens) and Split
(`Screen.Splits`). The scan/split flow is `Camera → Scanning → Review → People → Assign → Summary`,
plus `Failed` from any scan; Pengeluaran adds `ExpenseEditor` and `Recap`. All navigation is one
`Screen` sealed interface in `BillsViewModel.kt` and one `when` in `App.kt`.

- `BillsViewModel` owns navigation and the split flow; `SpendViewModel` owns the Pengeluaran tab
  (month on screen, its `MonthlyReport`, the editor's `ExpenseDraft`, the recap note). Opening the
  editor is two calls: `spendVm.startNew()`/`edit()` to load the draft, then `vm.goTo(ExpenseEditor)`.

- A flow returns to the tab it started from: `goTo()` records the last tab root shown in
  `BillsState.origin`, and closing, cancelling or finishing a flow goes to `origin.root`.
- `Screen.backDestination(origin)` must match the on-screen back arrow of each screen. Back from
  Split goes to Pengeluaran; back from Pengeluaran leaves the app. There is exactly
  one `NavigationBackHandler` (in `App.kt`); don't add per-screen ones — binding a second handler
  to the same state throws at runtime. Use `androidx.navigationevent`, not CMP's deprecated `BackHandler`.
- A long receipt can go in up to `ReceiptScanner.maxPhotos` overlapping photos (3 today). Each
  camera shot waits in `BillsState.pages` until "Baca"; gallery picks are read straight away;
  closing the camera drops the pages. With only providers that read one photo, `maxPhotos` is 1
  and the camera reads on the shutter as it always did.
- `goTo()` cancels the in-flight scan when leaving `Scanning`. Scanning → Review is done inside the
  scan itself, never through `goTo()`.
- Finished splits are saved through `BillsStore`; the Split tab's history list reads from it. Computing a
  split again after editing re-saves under the same id (`BillsState.savedSplit`).
- Review's main button follows the tab the scan came from: "Aku bayar sendiri" from Pengeluaran,
  "Lanjut, pilih orangnya" from Split. Paying alone opens `ExpenseEditor(back = Review)` with the
  receipt total locked; saving ends the flow on Pengeluaran (`startOver(Tab.Spend)`).
- The user's share of a split is an `Expense` with `source = Split` and `splitId`. `BillsState.shares`
  (live, from the store) is the only record of whether a share is logged — not
  `SavedSplit.selfPersonId`, which outlives a deleted share. `logShare` also stores the person's
  name as "me" (`settings.self_name`); a later split matches it case-insensitively and saves in
  one tap, otherwise "Yang mana kamu?" asks. Editing the split in the same flow updates the share.
- `ExpenseEditor` and `SavedSummary` carry their own `back` screen, since both open from more than
  one place; `backDestination` returns it.

## Invariants — don't break these

**Money**
- Every amount is `Long` in minor units (rupiah, no subunit). Never `Double`/`Float`.
- Splits must sum to the input exactly. Use `splitEvenly` / `allocate` (largest-remainder,
  deterministic tie-break by index) in `SplitCalculator.kt`; don't hand-roll division.
- Tax, service and discount are allocated proportionally to each person's item subtotal.
- An item with no assignment entry is shared by everyone; clearing the last sharer hands it back
  to everyone, never to nobody.
- A receipt that doesn't tie out is surfaced (`SplitResult.discrepancy`), never silently "fixed".
- `formatMoney` is hand-written because `NumberFormat` doesn't exist in common code.

**AI (`ai/ReceiptScanner.kt`)**
- The model only extracts and writes prose. All arithmetic stays in `SplitCalculator` and
  `SpendCalculator`.
- Both prose notes are written from facts the app computed: the split recap (`summarize`) from
  `splitFacts`, the monthly note (`monthNote`) from `MonthFacts`. `noteIsGrounded` drops a note
  that quotes any number not in its facts — a missing note is fine, a wrong figure isn't. Change
  a note's facts and its prompt together; `ai/NoteGroundingTest.kt` keeps a real bad recap as a case.
- Providers are OpenAI-compatible chat completions, tried in order: Gemini 3.6 Flash, then
  Gemini 3.5 Flash Lite on the same key (free-tier quota is per model, 20 vs 500 a day), then
  Mistral (free Experiment plan, Ministral 14B), then Groq, then NVIDIA NIM. Each `AiProvider` carries its own model, sampling and thinking control
  (`reasoning_budget` vs `reasoning_effort`); add one there, never by special-casing in
  `ReceiptScanner`. The parts of a long receipt go to the model in ONE request
  (`scanRequest`): the model lines them up and lists the overlap once, which merging separate
  reads can't do, since two identical lines can be two real orders. `AiProvider.maxImages` is
  measured on that, not on the API accepting the images: both Geminis and Ministral 14B read three
  exactly; NIM and Groq take three but listed the overlap twice or broke, so they read single
  photos only. Only provider-side failures (network, 429, 5xx, rejected key) fall through to
  the next — an unreadable receipt does not. Wire types are hand-rolled in `ChatWire.kt` because
  `sharedLogic` compiles for iOS — no JVM-only SDKs in `commonMain`.
- `strict` JSON schema: every object needs `additionalProperties: false` and all properties in
  `required`. Changing `ReceiptDto` means changing `receiptSchema` and the prompt together.
- Read only `message.content` (schema-bound), never `reasoning_content`; strip leaked `</think>`.
  The prose notes are schema-bound too (`{"note": "..."}`, read by `noteFrom`): with reasoning on,
  the budget cuts the model mid-thought and free-text content carried the rest of the thinking.
- `ReceiptDto` mirrors the API (snake_case, no ids); `toDomain()` mints ids and fills fallbacks.
  Keep the domain model free of API shape.
- Model choice, sampling values, reasoning budgets, retry and timeout values carry measured
  rationale in their comments — read it before changing them.

**Storage (`db/`, `*.sq`)**
- Once a build with a table has been installed, schema changes go in `.sqm` migrations. Editing a
  `CREATE TABLE` in place crashes existing installs on first query.
- SQL has to run on SQLite 3.22 (API 29): use `INSERT OR REPLACE`, not `ON CONFLICT … DO UPDATE` (3.24).
- Enums are stored by a stable key — `Category.key`, the `ExpenseSource` mapping in `BillsStore` —
  never by constant name. `Category` serializes by key for the same reason.
- `splits.data` is JSON keyed by the property names of `Receipt`, `ReceiptItem` and `Person`:
  renaming one orphans every saved split. `BillsStoreTest.aSplitSavedByThisVersionStillOpens` pins it.
- Rows are soft-deleted (`deleted_at`) so a later sync can tell a deletion from a missing row.

**Build wiring that fails only at runtime**
- Ktor engine per platform: `ktor-client-okhttp` in `androidMain`, `ktor-client-darwin` in
  `iosMain`. Dropping darwin still links, then crashes on first `HttpClient()`.
- `androidx.camera:camera-camera2` is `runtimeOnly` and required — CameraX binding fails without it.
- Modules use the AGP 9 KMP library plugin (`com.android.kotlin.multiplatform.library`): Android
  config lives in `kotlin { android { … } }`, host tests via `withHostTest`.
- Release runs R8 with resource shrinking. The libraries' own rules cover everything today, and
  a missing rule still builds fine: after adding a dependency, reflection or a serializer looked
  up by name, install the release build and exercise a scan and the recap note (Ktor, OkHttp's
  ServiceLoader engine, kotlinx.serialization), the camera and a saved split. The release APK is
  unsigned; to try it, sign it with the debug keystore (`apksigner sign --ks ~/.android/debug.keystore`)
  and `adb install -r` it over the debug app.

## UI conventions

- Platform capabilities are injected interfaces (`ReceiptCapture`, `CameraSession`), not
  `expect`/`actual`. iOS adds an implementation without touching `commonMain`.
- The design system is **Nota** (https://claude.ai/artifact/ABiHjVJwKivfwxYqi6ZZNG; screens in the
  "Bills Redesign" canvas, https://claude.ai/artifact/FWaJnmL3QUwHFx3P9PsyWL). Colors and type in
  `ui/theme/BillsTheme.kt` are copied from its tokens — don't re-pick values by eye. Use
  `MaterialTheme.colorScheme` (Nota's README maps each token to a role), `BillsTheme.colors` for
  tokens Material has no slot for (category tints, AI `marker`, `ok`), and `MoneyLarge/Medium/Small`
  for amounts. Corners follow Nota's xs 2 / s 4 / m 8 / l 12; lines are 1dp except focus, stamps
  and selected chips (2dp).
- Fonts are IBM Plex Sans + Mono as static TTFs in `sharedUI/src/commonMain/composeResources/font/`
  (one file per weight, never variable fonts), with their OFL licence in `composeResources/files/`.
  Font family swaps happen in `BillsTheme.kt` only.
- No `material-icons` dependency: every glyph is a `NotaGlyph` — the design system's 24-unit SVG
  path data, copied from the canvas — drawn by `Glyph()` in `ui/Glyphs.kt`.
- Reuse the Nota components: `PrimaryButton`, `SecondaryButton`, `PlainButton`, `SurfaceCard` (a
  receipt: ink hairline, torn zigzag bottom), `Avatar` (neutral, never coloured per person),
  `NotaSheet`, `ScreenTopBar`, `BottomBar`, `BottomNav`, `EmptyState`, `AiNote`, `ReconLine`,
  the fields and chips in `SpendComponents.kt`.
- No ripple. Every tappable thing except a text field uses `Modifier.pressable` (`ui/Press.kt`),
  placed first in its modifier chain: `Press.Small` shrinks to 96%, `Press.Large` (cards) to 98%,
  `Press.Row` (rows inside a receipt) turns paper-sunk. With the system's "remove animations"
  on (`reducedMotion`, from `MainActivity`), presses dim instead of moving. The feedback is an
  `IndicationNodeFactory` that `clickable` builds on the first touch and animates in the draw
  phase — don't move it back to composed state or `animate*AsState`: it runs for every button
  and every lazy row.
- Drawing allocates nothing per frame: build `Path`, `PathEffect` and `Stroke` in `drawWithCache`,
  since a `drawBehind` or `Canvas` block re-runs on every draw. Every lazy item gets a
  `contentType`, and lists that grow with use (split history, the month log) are lazy.
- Insets: `App.kt` pads horizontal `safeDrawing` only; each screen handles top/bottom with
  `Modifier.topBarInsets()` / `bottomBarInsets()` (Scanning goes full-bleed on purpose). Tab roots
  skip `bottomBarInsets()`: `BottomNav` owns the bottom inset.
- Edge-to-edge with `isNavigationBarContrastEnforced = false` in `MainActivity` — keep it.
- User-facing copy and the AI recap are casual Indonesian. Code, identifiers and comments are English.

## Code style

- `kotlin.code.style=official`. Declare new screens/helpers `internal` unless another module needs them.
- Comments explain *why* — measured numbers, platform quirks, what breaks otherwise. Match that
  density; don't add comments that restate the code.
- Dependencies go through `gradle/libs.versions.toml`; add a short comment there when a
  dependency's reason isn't obvious (see the camera and navigationevent entries).

## Verifying changes

- Touched `split/`, `model/` or `spend/` → add/adjust a case in the matching test under
  `sharedLogic/src/commonTest/` (`split/SplitCalculatorTest.kt`, `spend/…`) and run `:sharedLogic:testAndroidHostTest`.
  `ReceiptDto` mapping or `receiptSchema` → `ai/ReceiptDtoTest.kt`, which also checks the schema stays strict.
  `MonthFacts` → `spend/MonthFactsTest.kt` pins the exact text the model sees.
- UI copy helpers (`SpendText.kt`, `Dates.kt`, `Money.kt`) → `sharedUI` commonTest, run with
  `:sharedUI:testAndroidHostTest`.
- Touched `db/` or a `.sq` file → `db/BillsStoreTest.kt`. It runs on an in-memory database on both
  JVM (JDBC driver) and iOS (native driver) via `testDriver()`.
- Touched anything that compiles for iOS (`sharedLogic/commonMain`, `iosMain`) → also compile it
  for Native (`:sharedLogic:iosSimulatorArm64Test` covers that).
- UI has no automated tests. Build `:androidApp:assembleDebug` and check on a device/emulator;
  scanning needs at least one real API key, the manual-entry path doesn't.
