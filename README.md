
# LocalTavern

LocalTavern is a standalone, privacy-focused LLM interface designed for Windows, Linux, and Android. It aims to provide a premium "product" experience—a native application rather than a browser wrapper—prioritizing local control, high-fidelity UI, and zero-dependency operation.

## The Goal

The project exists to solve the "service complexity" problem. Most LLM interfaces require complex environments (Python/Node.js) or cloud accounts. LocalTavern is built to be a simple, native binary that you run locally, keeping your data on your hardware and your configurations synced across your own devices.

## **THIS IS WORK IN PROGRESS**
*The following description is the ideal outcome but might not represent the current state of the project.*

## Core Principles

- **Privacy First:** Zero telemetry, zero metrics, and no mandatory cloud accounts. All chat history and API keys are stored locally.

- **Zero-Dependency:** Shipped as native binaries. You don't need to install a specific runtime or manage environments to use it.

- **Symmetry:** Full feature parity across desktop and mobile versions.

- **Local Sync:** Encrypted P2P differential synchronization via WiFi or USB—no intermediary servers required.


## Key Features

- **Multi-Backend Support:**

    - ***Commercial:*** Native integration for OpenAI, Gemini, Anthropic, Mistral, DeepSeek, OpenRouter, and more.

    - ***Local Inference:*** Connect directly to Ollama, LM Studio, KoboldCPP, or any OpenAI-compatible local endpoint.

- **Character & Persona Management:** * Full support for SillyTavern-compatible PNG metadata cards.

    - Multiple user personas with custom avatars and system prompts.

    - Local "Lorebook" (World Info) support: trigger-key matching against chat history with automatic prompt injection (or explicit `{{lorebook}}` placement), plus a built-in entry editor.

- **Advanced Control:** Granular parameter sliders (Temperature, Top-P, Presence Penalty, etc.).

    - Real-time cost estimation based on active model pricing and prompt length, with per-message and per-session estimates and per-model price overrides.

    - Automatic reasoning mode for supported models (DeepSeek-R1, O1, Anthropic extended thinking): chain-of-thought is captured and shown in a collapsible section; Auto/On/Off override per connection.

- **UI/UX:** A refined interface utilizing glassmorphism, fluid animations, and a consistent 12-16pt rounded aesthetic.
## Technical Stack

**Framework:** Compose Multiplatform (Kotlin)

**Database:** SQLDelight (Type-safe, cross-platform SQL)

**Networking:** Ktor

**Image Loading:** Coil3

**Encryption:** API keys are encrypted at rest. Android uses the AndroidKeyStore (non-exportable AES key), iOS uses a Keychain-held EC key with ECIES/AES-GCM, and the desktop build supports an opt-in passphrase (PBKDF2 + AES-256-GCM). The database itself remains plain SQLite; only secrets are protected.

**P2P Sync:** Devices pair over the local network with a 6-digit PIN (shown by the hosting device). Each device runs an embedded Ktor server; sync exchanges are encrypted end-to-end with forward-secret per-exchange channel keys (static X25519 for authentication + fresh ephemeral X25519 per exchange, HKDF-SHA256 → AES-256-GCM), merged last-writer-wins per row (tombstones included). LWW timestamps use hybrid logical clocks — each device stamps rows with `max(wall clock, last-observed + 1)` and advances its counter with every received timestamp — so edits are never lost to wall-clock drift between devices. Pairing is PIN-authenticated without sending the PIN over the wire (HMAC proof, rate-limited), and both devices display a mutual key fingerprint for out-of-band MITM verification. The sync identity key can be rotated, which invalidates all pairings. LAN discovery (UDP broadcast) lists nearby devices and auto-updates peer addresses after DHCP changes. Characters, personas, chats, messages and API profiles all sync; auto-sync runs on app start and after pairing. No intermediary servers.


## Disclaimer

**This is a vibecoded project.** 
LocalTavern is built primarily to satisfy my own specific use-case and workflow. It is opinionated and developed at the speed of my own needs. While I aim for it to be a stable and useful tool for anyone who values privacy and native performance, it is provided "as-is."

*Everyone is free to open issues and share feedback to improve the project.* 
- **PR are no available until further notice**
