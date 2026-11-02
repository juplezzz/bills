# Bills

Snap a receipt, let a vision model read it, fix it up, say who ate what, and get an exact
per-person split plus a short recap to share. A second tab tracks your own monthly spending,
including your share of every split.

Built with Kotlin Multiplatform and Compose Multiplatform. Android is the only shipping target;
the iOS app is still a placeholder.

## Features

- **Receipt scanning**: camera or gallery photo, read by a vision model into items, tax, service
  and discount. Everything is editable before you split it.
- **Exact splits**: assign items to people (unassigned items are shared by everyone). Tax, service
  and discount are allocated proportionally, and every share sums back to the receipt total down to
  the rupiah. A receipt that doesn't add up is flagged instead of silently corrected.
- **Shareable recap**: a short casual-Indonesian summary of who owes what, written by the model
  from figures the app computed. A note that quotes any number the app didn't compute is dropped.
- **Spending tracker (Pengeluaran)**: log expenses by hand or from a scan, see the month by
  category, and get a short monthly note.
- **Local-first**: splits and expenses are stored on the device with SQLite.

## How it works

- The model only extracts data and writes prose. All arithmetic stays in plain Kotlin
  (`SplitCalculator`, `SpendCalculator`), with money held as `Long` in minor units.
- AI providers are OpenAI-compatible chat completions with strict JSON schemas, tried in order:
  Gemini 3.6 Flash, then Gemini 3.5 Flash Lite, then Mistral, then Groq, then NVIDIA NIM. Only
  provider-side failures (network, rate limit, 5xx) fall through to the next one.

## Setup

Requirements: Android Studio with JDK 21, an Android device or emulator on API 29+.

Add at least one API key to `local.properties` (or as environment variables of the same name):

```properties
GEMINI_API_KEY=...
MISTRAL_API_KEY=...
GROQ_API_KEY=...
NVIDIA_API_KEY=...
```

Any one key is enough. Without keys the app still builds and manual entry works; scanning shows
a missing-key error.

## Build and test

```bash
./gradlew :androidApp:assembleDebug            # debug APK
./gradlew :sharedLogic:testAndroidHostTest     # split, spend, AI mapping and storage tests
./gradlew :sharedUI:testAndroidHostTest        # UI copy helpers
./gradlew :sharedLogic:iosSimulatorArm64Test   # shared logic on Kotlin/Native
```

## Project structure

| Module        | What lives there                                                              |
|---------------|-------------------------------------------------------------------------------|
| `androidApp`  | Android entry point: wires API keys, storage and camera into the shared app. |
| `sharedUI`    | Compose UI: screens, view models, the Nota design system, CameraX capture.   |
| `sharedLogic` | Models, split and spend math, AI scanning, SQLDelight storage.               |
| `iosApp`      | SwiftUI template, not wired to the shared UI yet.                            |

## Stack

Kotlin 2.4, Compose Multiplatform 1.12, Ktor, kotlinx.serialization, SQLDelight, CameraX,
AndroidX Navigation Event. Fonts: IBM Plex Sans and Plex Mono (OFL).
