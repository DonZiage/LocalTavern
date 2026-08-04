# LocalTavern

> **Status: Work in Progress** — the description below is the ideal outcome and may not fully represent the current state.

LocalTavern is a standalone, privacy-focused LLM interface that runs as a **native application** on desktop (Windows, Linux, macOS) and mobile (Android, iOS). It is not a browser wrapper and it is not a web service — it is a single binary you run locally, with your data staying on your hardware.

The project exists to solve the "service complexity" problem: most LLM interfaces require Python/Node.js environments or cloud accounts. LocalTavern is a simple, native binary — zero dependency, zero telemetry, and your configuration synced across **your own** devices over **your own** network.

- **License:** GPL-3.0
- **Current version:** 0.6.0
- **Backend:** Kotlin 2.3.21, Compose Multiplatform 1.10.3

---

## Core Principles

| Principle | What it means |
|---|---|
| **Privacy first** | Zero telemetry, zero metrics, no mandatory cloud accounts. Chat history and API keys live only on your devices. |
| **Zero dependency** | Shipped as native binaries. No runtime to install, no environment to manage. |
| **Symmetry** | Full feature parity between desktop and mobile versions. |
| **Local sync** | Encrypted P2P differential synchronization over your local network — no intermediary servers. |

---

## Quick Start

**Requirements:** JDK 17+, Android SDK (Android builds), Xcode (iOS builds).

```bash
# Run the desktop app (Windows/Linux/macOS JVM)
./gradlew :composeApp:run

# Build desktop installers (MSI, EXE, Deb, RPM)
./gradlew :composeApp:packageDistributionForCurrentOS

# Build an Android debug APK
./gradlew :composeApp:assembleDebug

# Run the test suite (desktop JVM)
./gradlew :composeApp:desktopTest

# Build the iOS framework (requires macOS)
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
```

---

## Features

### Model Connections

- **14 commercial providers:** OpenAI, Anthropic, Gemini, Mistral, DeepSeek, OpenRouter, xAI, TogetherAI, Fireworks AI, Perplexity, Cohere, AI21, DreamGen, Mancer. Anthropic speaks its native `/messages` dialect (including extended thinking); everything else uses the OpenAI-compatible API.
- **Local inference:** Ollama, LM Studio, KoboldCPP, llama.cpp, TabbyAPI, Oobabooga, vLLM, or any OpenAI-compatible local endpoint.
- **Connection profiles:** multiple API profiles with per-connection parameters, live connection probing (distinguishes auth failures from unreachable endpoints), and a fuzzy-searchable model picker.
- **Real-time cost estimation** — live cloud prices are fetched automatically from OpenRouter's public models endpoint at startup (bundled offline catalog as fallback), so estimates always track current list prices. Shown as a "Max prompt cost" readout, an in-flight estimate, and stored per message; cost info is hidden entirely for local inference models.
- **Reasoning mode** for supported models (DeepSeek-R1, OpenAI o-series, Anthropic extended thinking) with Auto/On/Off override per connection. Chain-of-thought is captured, shown in a collapsible section, and syncs across devices: OpenAI o-series gets `reasoning_effort`, Anthropic gets a thinking budget plus the beta header, R1-style models stream `reasoning_content`.

### Characters & Personas

- Full support for **SillyTavern-compatible PNG metadata cards** (v2 spec, including `ccv3`/`chara` chunks) and legacy **TavernAI v1 JSON cards**.
- Multiple user personas with custom avatars and system prompts; token-counted editors for every field.
- **Lorebook (World Info):** trigger-key matching against chat history with automatic prompt injection (or explicit `{{lorebook}}` placement), plus a built-in entry editor (primary/secondary/constant keys, case-sensitive matching).
- Alternate greetings with per-character management, character export back to PNG/JSON cards, and a built-in "Assistant" fallback character for plain chat.

### Chat Controls

- Granular parameter sliders (Temperature, Top-P, Top-K, Presence/Frequency Penalty), context and response limits with presets, per-connection timeouts.
- **System prompt blocks:** layered, reorderable prompt blocks with macros (`{{char}}`, `{{user}}`, `{{description}}`, `{{chat_history}}`, `{{lorebook}}`, `{{lastMessage}}`, and more), including built-in defaults and custom blocks.
- **Streaming with graceful degradation:** truncated streams are recovered via a non-streaming fallback; image-carrying requests retry without images if rejected.
- **Message actions:** edit, copy, add image, delete, branch (start a new chat from any message, with "Go to Parent Chat"), regenerate, and swipe/arrow-key variation cycling.

### Images

- Attach up to 4 images per message.
- Images are EXIF-normalized, downscaled to ≤1024px and ≤1.5MB with a JPEG quality ladder before storage.
- Gallery view and fullscreen viewer included.

### Interface

- Refined UI with glassmorphism, fluid animations, and a consistent 12–16pt rounded aesthetic.
- Desktop: collapsible side panels. Mobile: animated drawers.
- Dark/light theme toggle with a circular-reveal transition.

---

## Security Model

**At rest** — API keys are encrypted. The database itself is plain SQLite; only secrets are protected.

| Platform | Mechanism |
|---|---|
| Android | AndroidKeyStore (non-exportable AES key) + **device-unlock gate** (the app asks for the phone's PIN/password/fingerprint via the OS prompt before opening) |
| iOS | Keychain-held EC key with ECIES/AES-GCM + **device-unlock gate** (Face ID / Touch ID / device passcode before opening) |
| Desktop | **Mandatory passphrase gate** (PBKDF2-SHA256 @ 600k + AES-256-GCM): a passphrase must be set before the main UI is shown, and re-entered after every launch or idle auto-lock. Wrong guesses are rate-limited (5 per session, then the gate locks until restart); the derived key lives only in process memory. Removing protection is an explicit opt-out. New passphrases must meet a strength policy: at least 8 characters with uppercase, lowercase, a number and a symbol; a number may never be used twice or placed consecutively (12, 98, 09); letters may not repeat in sequence or mirror (aa, aba). The passphrase is never stored — a password manager is recommended for generating and keeping it. |

Mobile devices without any unlock method (no PIN, password or fingerprint) open directly — nothing can be prompted with — and the first launch shows a one-time recommendation to set a screen lock. Like every local credential, the unlock prompt protects data **at rest** (stolen phone, idle unlocked session) but cannot stop malware running in the same user context.

**In transit (P2P sync)** — no intermediary servers:

- Devices pair over the local network with a **6-digit PIN** (shown by the hosting device). Each device runs an embedded Ktor server.
- Exchanges are encrypted end-to-end with **forward-secret per-exchange channel keys**: static X25519 for authentication + a fresh ephemeral X25519 per exchange, HKDF-SHA256 → AES-256-GCM. Every exchange carries a fresh **exchange ID bound into the AEAD associated data**, so captured requests cannot be replayed (the server rejects repeated IDs).
- Merged **last-writer-wins per row** (tombstones included). LWW timestamps use **hybrid logical clocks** — each device stamps rows with `max(wall clock, last-observed + 1)` — so edits are never lost to wall-clock drift.
- **Delta cursors run on per-row monotone sync sequences** (device-local counters, re-stamped on every applied incoming row), so a row arriving late from a lagging peer — however low its timestamp — is always forwardable and never silently skipped. Cursors are announcements: they advance exactly, and a cutoff that exceeds the peer's actual sequence space (a device that restored an older backup) is clamped so the peer re-sends everything once instead of skipping its re-stamped rows forever (idempotent LWW). Upgrading from an older release resets peer cursors and re-syncs everything once.
- **Message images travel out of band:** rows carry only content-addressed SHA-256 refs; the referenced blobs are pulled from the peer in 512 KB chunks over an authenticated `/blob/fetch` endpoint (progress shown in the sync UI). Sync exchanges stay small even with image-heavy histories. Blob refs are validated as canonical SHA-256 hex keys before any filesystem access, and fetch addresses are format-validated (no scheme/path/SSRF vectors).
- PIN pairing is authenticated with an **HMAC proof over a PBKDF2-stretched PIN** (200k iterations, the per-attempt nonce as salt — the PIN never travels over the wire, and a captured proof cannot be brute-forced offline) and rate-limited to 5 attempts, with per-session nonce replay rejection; both devices display a mutual key fingerprint for out-of-band MITM verification, and **sync with a newly paired device is refused until that fingerprint is confirmed on both sides**.
- The sync identity key can be rotated, which invalidates all pairings.
- **LAN discovery** (UDP broadcast) lists nearby devices and auto-updates peer addresses after DHCP changes — not available on iOS, where you pair by manual address.
- Characters, personas, chats, messages, and API profiles all sync. Auto-sync runs on app start and after pairing.
- **Overwrites are not silent.** LWW keeps a per-peer ledger of the last version each device announced per row. When a peer's newer version replaces a local edit that the peer had never seen (a genuine concurrent edit), the event is recorded and surfaced in the sync settings ("N edits overwritten by paired devices") with the row type, device and time, dismissible per row or all at once. The records are per-device observational history, never synced. Convergence and echoes (a peer sending this device's own version back) are not reported; ties resolved by the deterministic device-id tie-break are not reported either.

---

## Technical Stack

| Layer | Choice |
|---|---|
| Framework | Compose Multiplatform (Kotlin 2.3.x, Compose 1.10.x, Material3) |
| Database | SQLDelight (type-safe, cross-platform SQL, schema v14) |
| Networking | Ktor 3.x (client for API calls; embedded CIO server + UDP discovery for sync) |
| Image loading | Coil3 |
| Serialization | kotlinx-serialization (JSON), kotlinx-datetime, kotlinx-coroutines |
| Cryptography | dev.whyoleg.cryptography (X25519, AES-256-GCM, HKDF, ChaCha20) with platform providers: AndroidKeyStore/BouncyCastle (Android), JDK (desktop), CryptoKit/CommonCrypto (iOS) |

### Dependency notes

Every pin is a current stable except one, deliberately: `org.jetbrains.compose.material3:material3` is `1.10.0-alpha05`, the companion line to Compose Multiplatform 1.10.x — JetBrains versions material3 independently, and no *stable* 1.10 material3 exists (stable lines are 1.8/1.9; 1.11/1.12 are alpha/beta). Downgrading to 1.9.0 or jumping to the 1.11 alpha each carries its own compatibility risk, so the alpha companion stays. Everything else — Kotlin 2.3.21, Compose 1.10.3, Ktor 3.4.x, SQLDelight 2.3.2, `dev.whyoleg.cryptography` 0.6.0 (the latest release), Coil 3.4, CameraX 1.4 — is the current stable release of its line.

### Supported Platforms

| Platform | Notes |
|---|---|
| Windows / Linux / macOS | JVM desktop app |
| Android | minSdk 24, targetSdk 36 |
| iOS | x64 / arm64 / simulator-arm64 |

---

## Project Structure

```
composeApp/
├── src/
│   ├── commonMain/      Shared code: UI, controllers, data layer, sync, utils
│   │   └── chat/donzi/localtavern/
│   │       ├── controller/      AppContainer (DI), AppState, ChatController, GenerationRunner
│   │       ├── data/            Repositories (SQLDelight), network, pricing, security, sync
│   │       ├── domain/          Plain data models
│   │       ├── ui/
│   │       │   ├── chat/        Chat UI: components + ChatScreenState (send flow, actions)
│   │       │   ├── characters/  Characters/personas UI + CharactersPanelState (editor, exports)
│   │       │   ├── settings/    API connections, parameters, security, pricing, prompt blocks
│   │       │   ├── sync/        Pairing and sync dialogs
│   │       │   ├── common/      Shared components (image viewer, carousel, toggles, bubbles)
│   │       │   ├── layout/      MainScreen + coordinator state (active chat/character)
│   │       │   └── theme/       Material theme and theme transition
│   │       └── utils/           Tokenizer, context builder, markdown, character cards, images
│   ├── androidMain/     Android platform implementations (KeyStore, image picker, …)
│   ├── desktopMain/     JVM platform implementations (driver, secret storage, …)
│   ├── iosMain/         iOS platform implementations (Keychain, CryptoKit, …)
│   ├── commonTest/      Cross-platform tests
│   └── desktopTest/     JVM-only tests (sync protocol, crypto, UI state + Compose UI)
├── iosApp/              Xcode host app for iOS
└── build.gradle.kts     Multiplatform build config (targets, SQLDelight, packaging)
```

---

## Where Your Data Lives

| Platform | Location |
|---|---|
| Desktop | `~/.localtavern/` (SQLite database, image blob store, passphrase-protection blob, sync identity) |
| Android | App-private directory |
| iOS | App Documents folder |
| Character exports | `Downloads/LocalTavern/ExportedCharacters` (Android) or app Documents folder (iOS) |

---

## Data Layer

**SQLite is accessed through two dispatchers, not one.** The JVM driver opens a separate SQLite connection per thread, so concurrent write transactions on different connections would collide with SQLITE_BUSY; all writes therefore funnel through a single-threaded dispatcher (one connection, never contended). Pure reads — chat timelines, character lists, sync delta collection — run on a small pool of extra connections, which is safe because WAL readers never block the writer and every connection carries `busy_timeout`. The routing rule: transactions, writes and read-modify-write flows stay on the write connection; standalone SELECTs use the read pool. iOS keeps everything serialized (its native driver manages its own single reader connection). This is verified by `DatabaseConcurrencyTest`, which hammers concurrent readers against a live writer and asserts no SQLITE_BUSY, no stalls and no lost writes.

## Testing

`./gradlew :composeApp:desktopTest` runs 360+ tests covering the sync protocol (two real devices pairing and converging over localhost), the crypto (including the **BouncyCastle provider Android uses on-device** — X25519, HKDF, AES-GCM, PBKDF2 — exercised through `SyncCryptoBouncyCastleTest` without an emulator), QR round-trips (decoded with an independent zxing reader), fresh-database creation through the driver factory, the conflict ledger, LWW merge, delta batching, malformed-envelope fuzzing (truncations, byte corruption, structural damage and extreme-value stamps must never crash or pollute the database), the passphrase/secret gates, and screen-level Compose smoke tests. Device-only paths — AndroidKeyStore, iOS Keychain, biometric prompts — cannot run on the JVM and are verified manually on hardware.

## Known Limitations

- **Message images live in a platform blob store** (content-addressed files, never in SQLite). They travel out of band during sync: rows carry SHA-256 refs and the bytes are pulled chunked from the peer. A blob the peer cannot serve shows a "pending sync" placeholder (re-attempted after an app restart); a blob fetch that fails mid-transfer is retried on the next sync.
- **The legacy `imageData` column remains in the schema** (unused, emptied by the startup extraction pass). It will be dropped by a future migration once the blob offload has been live for at least one release — a device jumping straight past the extraction would lose its inline images, so the drop is deliberately deferred.
- **The tokenizer is a heuristic, not a true BPE tokenizer:** it estimates ~4 characters per token for Latin text and 1 token per CJK character. Accurate enough for context budgeting, cost estimates, and editor token counters, but it will not match a model's exact tokenizer.
- **Desktop API keys are passphrase-encrypted by default** — a passphrase setup screen blocks the main UI on first launch (and after the passphrase is explicitly removed). The passphrase is never stored; a forgotten passphrase means encrypted keys are unrecoverable by design. Like every passphrase scheme, it protects keys **at rest** (stolen disk, idle unlocked session via auto-lock) but cannot stop malware running as the same user on an unlocked machine.
- **The hand-rolled markdown renderer is deliberately conservative:** no raw HTML, links render as non-clickable labels, and inline spans are strict-format only — malformed LLM output degrades to plain text instead of misrendering.

---

## Disclaimer

**This is a vibecoded project.** LocalTavern is built primarily to satisfy my own specific use-case and workflow. It is opinionated and developed at the speed of my own needs. While I aim for it to be a stable and useful tool for anyone who values privacy and native performance, it is provided **"as-is."**

Everyone is free to open issues and share feedback to improve the project. **Pull requests are not available until further notice.**
