
# LocalTavern

LocalTavern is a standalone, privacy-focused LLM interface for Windows, Linux, macOS (JVM), Android, and iOS. It aims to provide a premium "product" experience—a native application rather than a browser wrapper—prioritizing local control, high-fidelity UI, and zero-dependency operation.

## The Goal

The project exists to solve the "service complexity" problem. Most LLM interfaces require complex environments (Python/Node.js) or cloud accounts. LocalTavern is built to be a simple, native binary that you run locally, keeping your data on your hardware and your configurations synced across your own devices.

## **THIS IS WORK IN PROGRESS**
*The following description is the ideal outcome but might not represent the current state of the project.*

## Core Principles

- **Privacy First:** Zero telemetry, zero metrics, and no mandatory cloud accounts. All chat history and API keys are stored locally.

- **Zero-Dependency:** Shipped as native binaries. You don't need to install a specific runtime or manage environments to use it.

- **Symmetry:** Full feature parity across desktop and mobile versions.

- **Local Sync:** Encrypted P2P differential synchronization over your local network—no intermediary servers required.

## Key Features

- **Multi-Backend Support:**

    - ***Commercial:*** Native integration for OpenAI, Anthropic, Gemini, Mistral, DeepSeek, OpenRouter, xAI, TogetherAI, Fireworks AI, Perplexity, Cohere, AI21, DreamGen, and Mancer. Anthropic uses its native `/messages` dialect (including extended thinking); everything else speaks OpenAI-compatible API.

    - ***Local Inference:*** Connect directly to Ollama, LM Studio, KoboldCPP, llama.cpp, TabbyAPI, Oobabooga, vLLM, or any OpenAI-compatible local endpoint.

    - ***Connection Profiles:*** Multiple API profiles with per-connection parameters, live connection probing (distinguishes auth failures from unreachable endpoints), and fuzzy-searchable model picker.

- **Character & Persona Management:** Full support for SillyTavern-compatible PNG metadata cards (v2 spec, including `ccv3`/`chara` chunks) and legacy TavernAI v1 JSON cards.

    - Multiple user personas with custom avatars and system prompts; token-counted editors for every field.

    - Local "Lorebook" (World Info) support: trigger-key matching against chat history with automatic prompt injection (or explicit `{{lorebook}}` placement), plus a built-in entry editor (primary/secondary/constant keys, case-sensitive matching).

    - Alternate greetings with per-character management, character export back to PNG/JSON cards, and a built-in "Assistant" fallback character for plain chat.

- **Advanced Chat Controls:**

    - Granular parameter sliders (Temperature, Top-P, Top-K, Presence/Frequency Penalty), context and response limits with presets, and per-connection timeouts.

    - **System prompt blocks:** layered, reorderable prompt blocks with macros (`{{char}}`, `{{user}}`, `{{description}}`, `{{chat_history}}`, `{{lorebook}}`, `{{lastMessage}}`, and more), including built-in defaults and custom blocks.

    - Real-time cost estimation from a bundled per-model price catalog (with per-model user overrides), shown as an in-flight estimate and stored per message.

    - **Reasoning mode** for supported models (DeepSeek-R1, OpenAI o-series, Anthropic extended thinking): Auto/On/Off override per connection; chain-of-thought is captured and shown in a collapsible section and syncs across devices. OpenAI o-series get `reasoning_effort`, Anthropic gets a thinking budget plus the beta header, R1-style models stream `reasoning_content`.

    - Streaming with graceful degradation: truncated streams are recovered via a non-streaming fallback, image-carrying requests retry without images if rejected.

- **UI/UX:** A refined interface utilizing glassmorphism, fluid animations, and a consistent 12-16pt rounded aesthetic. Desktop features collapsible side panels; mobile uses animated drawers. Dark/light theme toggle with a circular-reveal transition.

    - Message actions: edit, copy, add image, delete, branch (start a new chat from any message, with "Go to Parent Chat"), regenerate, and swipe/arrow-key variation cycling.

    - Image support: attach up to 4 images per message; images are EXIF-normalized, downscaled to ≤1024px and ≤1.5MB with a JPEG quality ladder before storage; gallery view and fullscreen viewer included.

## Technical Stack

**Framework:** Compose Multiplatform (Kotlin 2.3.x, Compose 1.10.x, Material3)

**Database:** SQLDelight (Type-safe, cross-platform SQL, schema v5)

**Networking:** Ktor 3.x (client for API calls, embedded CIO server + UDP discovery for sync)

**Image Loading:** Coil3

**Serialization:** kotlinx-serialization (JSON), kotlinx-datetime, kotlinx-coroutines

**Cryptography:** dev.whyoleg.cryptography (X25519, AES-256-GCM, HKDF, ChaCha20) with platform providers: AndroidKeyStore/BouncyCastle on Android, JDK on desktop, CryptoKit/CommonCrypto on iOS

**Encryption:** API keys are encrypted at rest. Android uses the AndroidKeyStore (non-exportable AES key), iOS uses a Keychain-held EC key with ECIES/AES-GCM, and the desktop build supports an opt-in passphrase (PBKDF2 + AES-256-GCM). The database itself remains plain SQLite; only secrets are protected.

**P2P Sync:** Devices pair over the local network with a 6-digit PIN (shown by the hosting device). Each device runs an embedded Ktor server; sync exchanges are encrypted end-to-end with forward-secret per-exchange channel keys (static X25519 for authentication + fresh ephemeral X25519 per exchange, HKDF-SHA256 → AES-256-GCM), merged last-writer-wins per row (tombstones included). LWW timestamps use hybrid logical clocks — each device stamps rows with `max(wall clock, last-observed + 1)` and advances its counter with every received timestamp — so edits are never lost to wall-clock drift between devices. Pairing is PIN-authenticated without sending the PIN over the wire (HMAC proof, rate-limited to 5 attempts), and both devices display a mutual key fingerprint for out-of-band MITM verification. The sync identity key can be rotated, which invalidates all pairings. LAN discovery (UDP broadcast) lists nearby devices and auto-updates peer addresses after DHCP changes (not available on iOS — pair by manual address). Characters, personas, chats, messages and API profiles all sync; auto-sync runs on app start and after pairing. No intermediary servers.

## Building & Running

Requirements: JDK 17+, Android SDK (for Android builds), Xcode (for iOS builds).

```bash
# Desktop (Windows/Linux/macOS JVM)
./gradlew :composeApp:run

# Desktop distributables (MSI, EXE, Deb, RPM)
./gradlew :composeApp:packageDistributionForCurrentOS

# Android debug APK
./gradlew :composeApp:assembleDebug

# Tests (desktop JVM)
./gradlew :composeApp:desktopTest

# iOS framework (requires macOS)
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
```

Desktop data lives in `~/.localtavern/` (SQLite database, passphrase-protection blob, sync identity). Android and iOS store data in their app-private directories; character exports go to `Downloads/LocalTavern/ExportedCharacters` (Android) or the app Documents folder (iOS).

## Disclaimer

**This is a vibecoded project.**
LocalTavern is built primarily to satisfy my own specific use-case and workflow. It is opinionated and developed at the speed of my own needs. While I aim for it to be a stable and useful tool for anyone who values privacy and native performance, it is provided "as-is."

*Everyone is free to open issues and share feedback to improve the project.*
- **PR are no available until further notice**

## Known Limitations

- **Images live in the SQLite database as BLOBs.** Message images and avatars are stored inline in `MessageEntity`/`CharacterEntity` rows and travel inside the encrypted sync envelope. They are bounded at ingest (sanitized to ≤1024px and ≤1.5MB each with a quality ladder), so per-image cost is controlled, but a long image-heavy history grows the database file and produces large (tens of MB) sync exchanges until the cursor drains. A file-based store would require a schema migration, sync file transfer, and platform storage — not implemented.

- **The tokenizer is a heuristic, not a true BPE tokenizer:** it estimates ~4 characters per token for Latin text and 1 token per CJK character. It is accurate enough for context budgeting, cost estimates, and editor token counters, but it will not match a model's exact tokenizer.

- **The hand-rolled markdown renderer is deliberately conservative:** no raw HTML, links render as non-clickable labels, and inline spans are strict-format only, so malformed LLM output degrades to plain text instead of misrendering.
