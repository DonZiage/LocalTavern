
# LocalTavern

LocalTavern is a standalone, privacy-focused LLM interface designed for Windows, Linux, and Android. It aims to provide a premium "product" experience—a native application rather than a browser wrapper—prioritizing local control, high-fidelity UI, and zero-dependency operation.

## The Goal

The project exists to solve the "service complexity" problem. Most LLM interfaces require complex environments (Python/Node.js) or cloud accounts. LocalTavern is built to be a simple, native binary that you run locally, keeping your data on your hardware and your configurations synced across your own devices.

## **THIS IS WORK IN PROGRESS**
*The following description is the ideal outcome but might not represent the current state of the project*

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

    - Local "Lorebook" (World Info) support for character-specific context triggers.

- **Advanced Control:** Granular parameter sliders (Temperature, Top-P, Presence Penalty, etc.).

    - Real-time cost estimation based on active model pricing and prompt length.

    - Automatic reasoning mode for supported models (DeepSeek-R1, O1).

- **UI/UX:** A refined interface utilizing glassmorphism, fluid animations, and a consistent 12-16pt rounded aesthetic.
## Technical Stack

**Framework:** Compose Multiplatform (Kotlin)

**Database:** SQLDelight (Type-safe, cross-platform SQL)

**Networking:** Ktor

**Image Loading:** Coil3

**Encryption:** SQLCipher / Platform-native KeyStore


## Disclaimer

**This is a vibecoded project.** 
LocalTavern is built primarily to satisfy my own specific use-case and workflow. It is opinionated and developed at the speed of my own needs. While I aim for it to be a stable and useful tool for anyone who values privacy and native performance, it is provided "as-is."

*Everyone is free to contribute, open issues, or fork the project if they find it useful.*
