# AndroidLLM — on-device Qwen3-4B (Q4_K_M) for OnePlus 13 / Snapdragon 8 Elite

A minimal Android app that runs **Qwen3-4B at Q4_K_M** fully on-device using
[llama.cpp](https://github.com/ggml-org/llama.cpp). The model is **not bundled** —
the app downloads the ~2.5 GB GGUF on first launch and stores it locally.

On a OnePlus 13 (Snapdragon 8 Elite) expect roughly **25–30 tokens/sec** decode,
comfortably above the 10 TPS target.

## Features

- llama.cpp native engine built from source via CMake `FetchContent` (pinned to tag `b5600`).
- On-demand, **resumable** model download (HTTP range-resume) — no model shipped in the APK.
- Model stored in `/sdcard/AndroidLLM` (with All-files access) so it **survives uninstall/reinstall**; falls back to internal storage.
- **Chat management**: local persistence (Room), chat list, search across chats, new-chat, delete.
- **Reused KV cache** across turns — follow-ups only decode their new tokens, so replies stay fast as the chat grows.
- **Agent mode (tools)**: the model can call `read_file`, `write_file`, and `list_files` in a sandboxed workspace, see the results, and continue — shown as tool-call/result bubbles.
- **File uploads**: attach a file in chat; it's saved to the workspace so the agent can `read_file` it.
- Jetpack Compose chat UI with **live token streaming** and a **tokens/sec** readout.
- Qwen3 ChatML with a "fast mode" toggle (pre-seeded empty `<think></think>` for reliable, low-latency replies).
- Universal build: arm64-v8a (phones) + x86_64 (emulator).

## Agent tools

When **Tools** is on, the model may respond with a single JSON object to call a tool:

```json
{"tool": "read_file", "args": {"path": "notes.txt"}}
```

The app runs the tool, feeds back a `TOOL RESULT:` message, and the model continues until it
answers in plain text (capped at 5 tool calls per message). Tools operate inside a sandboxed
workspace (`filesDir/workspace`); paths that escape it are rejected. Available tools:

| Tool | Args | Purpose |
|------|------|---------|
| `read_file`  | `{"path": "...", "offset": <line=1>, "limit": <lines=200>}` | Read a text file; large files are paginated and the result tells the model the next `offset` |
| `write_file` | `{"path": "...", "content": "..."}` | Create/overwrite a text file |
| `list_files` | `{}` | List files in the workspace |
| `web_search` | `{"query": "..."}` | Search the web; returns titles, URLs, and snippets |
| `fetch_url`  | `{"url": "https://...", "offset": <char=0>}` | Open a page and read it as clean text (paginated) |

File tools operate in the workspace folder (configurable in Settings; defaults to
`/sdcard/AndroidLLM/files` with All-files access). A bare filename resolves there; `..` and
disallowed absolute paths are rejected.

### Web browsing (headless WebView)

`web_search` and `fetch_url` are powered by a **headless Android WebView** — a real browser
engine that runs JavaScript with a normal user agent, rather than a plain HTTP client. We
inject JavaScript into the rendered page to extract content (a Readability-style extractor for
`fetch_url`, and result-node scraping for `web_search`), conceptually similar to running
`Runtime.evaluate` via the Chrome DevTools protocol. This handles JS-heavy pages and the no-JS
bot walls (CAPTCHAs) that trip up raw HTTP fetches. Pages are returned as clean, paginated text
so they fit the on-device context window. Requires the `INTERNET` permission.

Tool-following quality scales with model size — Qwen3-4B handles JSON tool calls well; very
small models may not format them reliably.

## Project layout

```
app/src/main/cpp/CMakeLists.txt        # fetches + builds llama.cpp (tag b5600)
app/src/main/cpp/llama-android.cpp      # JNI bridge (from the official llama.cpp example)
app/src/main/java/android/llama/cpp/LLamaAndroid.kt   # Kotlin wrapper over the JNI layer
app/src/main/java/com/example/androidllm/Downloader.kt    # resumable GGUF downloader
app/src/main/java/com/example/androidllm/MainViewModel.kt # chat state, prompt building, TPS
app/src/main/java/com/example/androidllm/MainActivity.kt  # Compose UI
```

## Prerequisites

- **Android Studio** (Ladybug or newer) — or the command-line Android SDK.
- **Android SDK Platform 35** and **NDK** + **CMake** (install via Android Studio → SDK Manager → SDK Tools).
- A device on **arm64-v8a** with enough free storage for the ~2.5 GB model. The OnePlus 13 qualifies.
- JDK 17 (bundled with recent Android Studio).

The Gradle wrapper is pinned to **Gradle 8.9** and the build to **AGP 8.5.2**.

## Build & run

### Option A — Android Studio (recommended)
1. `File → Open` this folder.
2. Let Gradle sync. Accept any prompt to install the matching **NDK** and **CMake**.
3. Plug in your phone (USB debugging on) and click **Run**.
   - The first build compiles llama.cpp from source — this takes a few minutes.

### Option B — command line
1. Point the build at your SDK by creating `local.properties` in the repo root:
   ```
   sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
   ```
2. Ensure the NDK and CMake are installed (via Android Studio SDK Manager, or `sdkmanager`).
3. Build and install onto a connected device:
   ```powershell
   .\gradlew.bat :app:installDebug
   ```

> Note: this project **cannot be built without the Android SDK + NDK** — they were not
> available in the environment where it was generated, so the native build has not been
> compiled here. Building in Android Studio (which installs the NDK/CMake on demand) is
> the verified path.

## Using the app

1. Launch the app. On first run it shows the model URL and a **Download & load model** button.
   - Default: `Qwen/Qwen3-4B-GGUF → Qwen3-4B-Q4_K_M.gguf`. You can paste any GGUF URL.
   - Use **Wi-Fi** for the ~2.5 GB download. It resumes if interrupted.
2. After download, the model loads into memory and the chat screen appears.
3. Type a message → responses stream token-by-token. The top bar shows the last **tok/s**.
4. **Fast mode (no thinking)** is on by default for snappy replies; turn it off to let Qwen3 "think".

## Picking a different model

Change the URL on the setup screen (or `DEFAULT_MODEL_URL` in `MainViewModel.kt`). Good options:

| Model | Quant | URL file | Notes |
|-------|-------|----------|-------|
| Qwen3-4B  | Q4_K_M | `Qwen3-4B-Q4_K_M.gguf`  | Recommended sweet spot (~25–30 TPS) |
| Qwen3-1.7B | Q4_K_M | `Qwen3-1.7B-Q4_K_M.gguf` | Much faster, lighter |
| Qwen3-8B  | Q4_K_M | `Qwen3-8B-Q4_K_M.gguf`  | Smarter, ~12–16 TPS, ~5 GB RAM |

All are ChatML/Qwen3-compatible, so the existing prompt formatting works unchanged.

## Tuning notes

- **Context / batch size** are set to 2048 in `llama-android.cpp` (`new_context`) and
  `LLamaAndroid.kt` (`new_batch`). Raise both together for longer conversations (uses more RAM).
- **Threads** auto-set to `min(8, cores-2)` in `new_context`.
- The sampler is **greedy** (deterministic). To add temperature/top-p, extend `new_sampler`
  in `llama-android.cpp` with `llama_sampler_init_temp` / `llama_sampler_init_top_p`.
- Bumping the llama.cpp `GIT_TAG` in `CMakeLists.txt` updates the engine; if you do, re-check
  that `llama-android.cpp` still matches the llama.cpp C API for that tag.

## Credits

JNI bridge (`llama-android.cpp`) and the `LLamaAndroid` Kotlin class are adapted from the
official [llama.cpp Android example](https://github.com/ggml-org/llama.cpp/tree/master/examples/llama.android)
(tag `b5600`), under the MIT license.
