# AndroidLLM — Killer Feature Specifications

A combined roadmap of on-device AI features that exploit the structural advantages of local SLM inference: **zero marginal token cost, always-on availability, and access to data users would never send to a cloud.**

**Feasibility legend:** 🟢 Straightforward with public Android APIs · 🟡 Possible but with platform friction (permissions, background limits, OEM quirks) · 🔴 Requires workarounds or has policy risk

## Hard constraint: light models only

Every spec below assumes **SLM-class models on phone hardware** and is budgeted accordingly. Reference device tiers (validate all numbers on real hardware — treat figures in this doc as targets to confirm, not guarantees):

| Tier | Example device | RAM | Text model | Typical decode speed (Q4) |
|---|---|---|---|---|
| **Low** | Pixel 6a / SD 695-class, 6 GB | 6 GB | Qwen3-0.6B / 1.7B Q4 | ~8–15 tok/s |
| **Mid** | Pixel 8 / SD 7 Gen-class, 8 GB | 8 GB | Qwen3-1.7B Q4 | ~15–25 tok/s |
| **High** | S24/Pixel 9 Pro / SD 8 Gen 3, 12 GB+ | 12 GB+ | Qwen3-4B Q4 | ~20–35 tok/s |
| **Flagship (primary reference device)** | **OnePlus 13 / SD 8 Elite, 12–24 GB** | 12–24 GB | Qwen3-4B Q4 default; 7–8B Q4 viable as opt-in | ~30–50 tok/s (4B); validate |

**OnePlus 13 notes (primary dev device):**
- **Headroom:** SD 8 Elite's Oryon CPU + Adreno 830 makes it one of the fastest llama.cpp phones available; the 16/24 GB variants can comfortably hold a 7–8B Q4 model — worth exposing as an opt-in "max quality" tier rather than the default, so specs stay honest for the broader fleet.
- **NPU path:** Hexagon NPU is the ideal target for the vision encoder (Feature 7) and embeddings via ONNX Runtime QNN EP; benchmark QNN vs CPU explicitly — the wins are large but op-coverage gaps cause silent CPU fallbacks. The harness must report *which* delegate actually ran.
- **Caution — don't calibrate on it:** latency budgets in this doc are gated on **mid tier**, not on the OnePlus 13. If it's your daily driver, everything will feel fast; the CI device matrix keeps you honest about the Pixel-8-class experience.
- **OxygenOS battery management is aggressive** (ColorOS lineage): it's a perfect candidate for the "aggressive-OEM device" slot in every soak test below — notification listener survival, alarm reliability, and WorkManager job execution all need verifying with OnePlus's battery optimization set to its *default*, not "unrestricted."

Global budgets that every feature must respect:
- **Latency:** time-to-first-token (TTFT) < 1.5 s for interactive features on mid tier; background jobs have no TTFT requirement but must finish within `WorkManager`/wakeup windows.
- **Memory:** model + KV cache + app ≤ 60% of device RAM; the app must survive `onTrimMemory` by unloading the model.
- **Battery:** each always-on feature ≤ 1.5%/day attributable drain (measured via Batterystats/`adb bugreport` diffing over 24 h soak tests); heavy indexing only while charging + idle.
- **Thermals:** sustained inference must not trigger thermal throttling status ≥ `THERMAL_STATUS_SEVERE` (`PowerManager.getCurrentThermalStatus`); batch jobs back off on `MODERATE`.
- **Output structure:** all classification/extraction uses grammar-constrained decoding (llama.cpp GBNF / JSON schema) — this is what makes 1–2 B models reliable enough to ship.

**Quality methodology used throughout:** each feature ships with (a) a frozen **golden dataset** (labeled, device-independent) run in CI against the exact quantized artifact — quantization changes must re-pass; (b) **on-device latency tests** on one physical device per tier (e.g., Firebase Test Lab or a local device farm); (c) an **attribution/hallucination check** for any generative output — generated claims must trace to a source item (notification, SMS, memory) or the test fails; and (d) an opt-in, on-device-only **thumbs-down telemetry counter** (no content uploaded) to catch quality regressions in the field.

---

## 1. Notification Intelligence & Triage

### Concept
The assistant reads the notification stream, filters noise, surfaces what matters, and produces on-demand or scheduled digests ("What did I miss in the last 3 hours?"). Optionally drafts replies for messaging notifications.

### User stories
- "Summarize my notifications since lunch" → 3-line digest grouped by importance.
- Silent hours: everything is muted, but the model breaks through only for genuinely urgent items (boss, family, delivery at the door).
- Long group-chat threads collapsed into one-line summaries.

### Functional spec
- **Ingestion:** Persistent listener captures notification title, text, sub-text, app package, timestamp, and `MessagingStyle` conversation data. Store in a local Room DB with a rolling retention window (e.g., 72 h, user-configurable).
- **Classification pass:** A lightweight prompt (or fine-tuned head) tags each notification: `urgent / actionable / informational / noise`, plus entity extraction (sender, deadline, amount).
- **Digest generation:** On demand, or on schedule via your existing scheduled-prompts engine. Output grouped by category with deep-links back to the source notification (`PendingIntent` from the original notification can be fired to open it).
- **Smart reply:** For notifications exposing a `RemoteInput` (most messengers), generate 2–3 reply drafts; user taps to send — reply is injected via the notification's own `RemoteInput`, so no per-app integration is needed.

### Android implementation
- `NotificationListenerService` — the core API. Requires the user to grant **Notification Access** in system settings (one-time, well-understood flow; apps like Pushbullet/BuzzKill use it).
- Reply injection: `Notification.Action` + `RemoteInput.addResultsToIntent()` → `actionIntent.send()`.
- Battery: batch inference — queue notifications and classify in bursts every N minutes rather than per-notification, or only classify on digest request.

### Feasibility: 🟢
Fully supported public API. Main risks: OEM battery killers (Xiaomi/Oppo) killing the listener service — mitigate with foreground service + user guidance; and Play Store review requiring a clear disclosure for notification access (allowed when core to functionality).

### Model requirements
Classification and summarization work well even at 1–2 B params. Structured-output (JSON tags) prompting is enough; no fine-tune required for v1.

### Performance budget & quality testing
- Classification is short-prompt/short-output: budget ≤ 400 prompt tokens and ≤ 40 output tokens per notification → ≤ 3 s per item on low tier; batching every 15 min keeps daily inference under ~2 min of compute.
- Digest generation: ≤ 2,000 prompt tokens (pre-truncated per-app), ≤ 250 output tokens → ≤ 20 s on mid tier, acceptable for an on-demand action with streaming UI.
- **Golden dataset:** ≥ 500 real-world notifications (anonymized/synthetic mix) labeled `urgent/actionable/informational/noise` across ≥ 30 apps and 3 languages you target. CI gate: macro-F1 and per-class recall on `urgent`.
- **Hallucination gate:** every sentence in a digest must map to ≥ 1 source notification ID (the model is forced to emit source IDs; a post-check verifies them).

### Definition of done
- [ ] `urgent` recall ≥ 0.90 and noise precision ≥ 0.85 on the golden set, on the shipping quant, on all three tiers' model variants.
- [ ] JSON schema-valid output ≥ 99.5% of classifications (grammar decoding makes the remainder retry-once, then fall back to `informational`).
- [ ] Digest contains zero unattributed claims across the 100-case digest test suite.
- [ ] 24 h soak with 300 synthetic notifications/day: attributable battery drain ≤ 1.5%, listener service alive ≥ 99% of the period on Pixel + OnePlus 13 (OxygenOS default battery settings — no "unrestricted" cheat).
- [ ] Smart-reply send works via `RemoteInput` on WhatsApp, Telegram, Signal, Messages, Slack (top-5 matrix).
- [ ] Notification access disclosure flow passes Play pre-review checklist; all data verifiably never leaves device (network audit with mitmproxy shows zero egress from the feature).

---

## 2. Ambient Memory ("Second Brain")

### Concept
Everything the user shares to the app, dictates, or clips is automatically embedded and indexed. Weeks later, natural-language recall just works: "What was that restaurant Priya recommended?"

### User stories
- Share an article, a maps link, a screenshot of a wifi password → auto-ingested, no filing required.
- "What did I save about visa requirements?" → answer with source snippets.
- Proactive resurfacing: when the user is composing a related query, the assistant volunteers a past memory.

### Functional spec
- **Capture surfaces:** Android share sheet (`ACTION_SEND` for text/URL/image), a quick-capture tile in Quick Settings, voice notes via the existing Whisper pipeline, and (opt-in) clipboard capture while the app is foregrounded.
- **Pipeline:** capture → normalize (URL → fetched readable text via on-device readability parse; image → OCR via ML Kit Text Recognition, fully on-device) → chunk → embed → store in the existing RAG index with metadata (source app, timestamp, type).
- **Recall:** hybrid retrieval (BM25 + vector) feeding the chat context; answers must cite which memory they came from, with a tap-through to the original item.
- **Lifecycle:** user-visible memory browser with delete/pin; everything exportable (JSON/markdown) to reinforce the "you own it" promise.

### Android implementation
- Share sheet: `intent-filter` for `ACTION_SEND` / `ACTION_SEND_MULTIPLE` — trivial.
- OCR: ML Kit on-device Text Recognition (no network).
- Embeddings: small embedding model (e.g., a 30–100 MB MiniLM/GTE-class model) via ONNX Runtime or llama.cpp embedding mode — fast even on mid-range SoCs.
- Clipboard: heavily restricted since Android 10 (background clipboard reading is blocked) — only offer foreground capture or an explicit "paste into memory" action.

### Feasibility: 🟢
No dangerous permissions needed for the core loop. The only constrained piece is clipboard (by design). This is mostly product/UX work on top of your existing RAG.

### Performance budget & quality testing
- Ingestion: share-to-indexed ≤ 3 s for text/URL, ≤ 6 s for an image (OCR + embed) on mid tier; embedding a 512-token chunk with a MiniLM-class model ≤ 50 ms on CPU.
- Recall query: retrieval (hybrid BM25 + vector over ≤ 50 k chunks) ≤ 300 ms; end-to-end answer TTFT ≤ 1.5 s on mid tier.
- **Golden dataset:** build a retrieval eval of ≥ 200 (query → correct memory) pairs including paraphrase queries, cross-lingual queries if supported, and 50 adversarial "not in memory" queries. Track recall@5 and the false-answer rate on the not-in-memory set.
- **Index integrity test:** ingest → kill app → reopen → query must still hit (WAL/DB durability), and delete-from-memory must remove the item from retrieval within one query cycle.

### Definition of done
- [ ] Recall@5 ≥ 0.90 on the golden retrieval set; on not-in-memory queries the assistant says it has nothing ≥ 95% of the time (no fabricated memories).
- [ ] Every answer displays tappable source memory chips; tapping opens the original item.
- [ ] Index of 10,000 memories: query latency ≤ 300 ms and DB size ≤ 150 MB on low tier.
- [ ] Share-sheet ingestion works for text, URL, single image, multi-image from 5 common apps (Chrome, Photos, WhatsApp, Gmail, Maps).
- [ ] Full export (markdown + JSON) and full wipe both verified; wipe leaves zero residual vectors (query returns nothing).

---

## 3. Voice Morning Briefing (Scheduled + TTS)

### Concept
A fully offline daily audio briefing: today's calendar, weather (cached), unread/notification summary, reminders — generated by the SLM and spoken aloud. The "airplane-mode demo" feature.

### User stories
- 7:45 AM: phone speaks a 60-second brief while the user makes coffee.
- "Brief me" via voice at any time for an ad-hoc version.
- Post-meeting micro-brief: "your next event is in 20 minutes, here's the context."

### Functional spec
- **Composer:** scheduled prompt gathers: calendar events (next 24 h), reminders due, notification digest (from Feature 1), and any cached data (weather fetched opportunistically when online, used offline).
- **Script style:** conversational, < 150 words, front-loads the single most important item.
- **Voice out:** on-device TTS. Options: system `TextToSpeech` (zero cost, robotic on some devices) → upgrade path to bundled neural TTS (Piper or Kokoro-82M compiled for Android; ~50–300 MB, near-human quality, fully offline).
- **Trigger surface:** exact alarm at chosen time; also expose as a home-screen widget and an Assistant shortcut.

### Android implementation
- Scheduling: `AlarmManager.setExactAndAllowWhileIdle()` (needs `SCHEDULE_EXACT_ALARM` permission on Android 12+) — your scheduled-prompt system likely has this already.
- Calendar: `CalendarContract` with `READ_CALENDAR`.
- Audio: `TextToSpeech` API or Piper via JNI; route through `AudioAttributes.USAGE_ASSISTANT`.
- Doze: exact alarms fire in Doze; keep the generation job short (< 30 s inference budget) so it completes within the wakeup window, or pre-generate the night before and only synthesize in the morning.

### Feasibility: 🟢
Everything is public API. The main engineering lift is bundling a good neural TTS; Piper on Android is a proven path.

### Performance budget & quality testing
- Generation: brief script ≤ 200 output tokens. Pre-generate the text the night before (charging window); at alarm time only synthesize + speak → audio starts ≤ 3 s after the alarm fires, even on low tier.
- TTS: Piper-class models synthesize faster than real time on phone CPUs (typical RTF 0.2–0.5); verify RTF ≤ 0.7 on low tier so a 60 s brief synthesizes in ≤ 45 s streamed (start playback while synthesizing).
- **Quality testing:** (a) factual harness — 50 synthetic days (known calendar/reminders/notifications) → script must mention 100% of "critical" items (next meeting, overdue reminder) and invent nothing (attribution check as in Feature 1); (b) length discipline — 95% of scripts within 90–170 words; (c) TTS smoke tests for numbers, dates, currencies, names (classic TTS failure points) reviewed by ear per release.
- **Reliability testing:** alarm-fire success across Doze (`adb shell dumpsys deviceidle force-idle`), reboot (persist schedules), and OEM battery-saver profiles.

### Definition of done
- [ ] 30-day simulated schedule: brief fires within ±60 s of target time ≥ 99% of days on Pixel and OnePlus 13 (OxygenOS defaults), including after reboot.
- [ ] Zero invented events/reminders across the 50-day factual harness; 100% critical-item coverage.
- [ ] Audio begins ≤ 3 s post-alarm on low tier; full offline run passes with airplane mode on (the demo scenario is a literal CI-adjacent manual test).
- [ ] Playback respects DND and audio focus (ducks/pauses for calls), routes correctly to BT headphones.
- [ ] Widget and voice invocation ("brief me") produce an ad-hoc brief ≤ 10 s end-to-end on mid tier.

---

## 4. Screen-Aware Assistant ("What am I looking at?")

### Concept
Invoke the model on any screen in any app: summarize this article, explain this error, extract this address, translate this conversation. Turns AndroidLLM into a layer over the whole phone.

### User stories
- Reading a dense terms-of-service page → long-press gesture → "explain the sketchy parts."
- Foreign-language chat → instant offline translation overlay.
- Error dialog in any app → "what does this mean and how do I fix it?"

### Functional spec
- **Invocation:** three tiers, increasing power/friction:
  1. **Share sheet / "Select text → Process"** (`ACTION_PROCESS_TEXT`) — zero special permissions, works today.
  2. **Screenshot intake:** user screenshots + shares to app → on-device OCR → analysis. Also zero special permissions.
  3. **AccessibilityService overlay:** floating button or gesture reads the current screen's view hierarchy (`AccessibilityNodeInfo` tree) — richest, no screenshot step.
- **Output:** bottom-sheet overlay with the answer + contextual actions (copy, save to memory, create reminder, add contact via `ContactsContract` intents).

### Android implementation
- Tier 1–2: `ACTION_PROCESS_TEXT` intent filter + ML Kit OCR. Trivial.
- Tier 3: `AccessibilityService` with `canRetrieveWindowContent`. Overlay needs `SYSTEM_ALERT_WINDOW`.
- Alternative to Tier 3: `MediaProjection` (screen capture) — but it shows a persistent capture notification and a consent dialog, which feels heavy for this use.

### Feasibility: 🟡
Tiers 1–2 are 🟢 and should ship first. Tier 3 works technically, but **Google Play policy on AccessibilityService is strict**: you must declare it, justify it as core functionality, and show a prominent disclosure. Apps do get approved for assistant-type use, but expect review friction. Sideload/F-Droid builds have no such constraint — a reasonable strategy is full power in the GitHub build, Tier 1–2 in the Play build.

### Model requirements
This is the feature that most benefits from a small **vision-language model** (e.g., Qwen2.5-VL-3B, SmolVLM) for screenshot understanding beyond OCR-able text. OCR + text-only SLM covers 80% of cases as v1. Note VLM cost honestly: a 2–3 B VLM's image prefill can take 5–15 s on mid tier — gate it to high tier and keep OCR+text as the default path everywhere.

### Performance budget & quality testing
- Tier 1 (`ACTION_PROCESS_TEXT`): selected text ≤ 2,000 tokens after truncation; TTFT ≤ 1.5 s, streamed answer.
- Tier 2 (screenshot): ML Kit OCR ≤ 500 ms/screen; OCR → answer TTFT ≤ 2.5 s on mid tier.
- Tier 3 (accessibility tree): tree serialization ≤ 200 ms; cap serialized tree at ~1,500 tokens with visibility-based pruning.
- **Golden dataset:** ≥ 300 screenshots across app categories (articles, chats, settings screens, error dialogs, foreign-language content) with labeled expected extractions (addresses, dates, error identifiers) and reference summaries. Score extraction with exact/fuzzy match; score summaries with a rubric-based human pass per release (SLM summaries are too small to trust auto-judging alone).
- **Overlay UX test:** invoke on 20 top apps; overlay must never block the underlying app's input after dismissal, and must not appear over secure (FLAG_SECURE) screens.

### Definition of done
- [ ] Extraction accuracy ≥ 90% on structured targets (addresses, phone numbers, dates, amounts) in the golden set via constrained JSON output.
- [ ] "Explain this error" answers rated helpful ≥ 80% in a 50-case human-rubric pass.
- [ ] Contextual actions verified: copy, save-to-memory (Feature 2), create reminder, add contact — each lands in the right destination in an instrumented test.
- [ ] Tier 3 build passes Play's accessibility declaration flow **or** is cleanly compiled out of the Play flavor (build-variant test proves the Play APK contains no AccessibilityService).
- [ ] No content from FLAG_SECURE surfaces is ever captured (banking-app test matrix).
- [ ] Latency budgets above hold on mid tier across the 300-screenshot set (p90, not just median).

---

## 5. Call & Voicemail Intelligence

### Concept
Local transcription and summarization of voicemails; post-call action-item extraction; content-based scam flagging. Phone audio is the most privacy-radioactive data on the device — the strongest possible "local-only" story.

### User stories
- Voicemail arrives → one-line summary in a notification: "Dentist: reschedule Thursday's appointment."
- After a call (opt-in): "You agreed to send the invoice by Friday → create reminder?"
- Unknown number's voicemail flagged: "Likely scam — claims to be your bank, asks for OTP."

### Functional spec
- **Voicemail path:** ingest audio files shared/exported from the carrier's visual-voicemail app, or (where available) act as the visual voicemail client. Whisper transcribes → SLM summarizes + classifies (scam/legit, action needed) → structured card with callback / reminder actions.
- **Call path (consent-gated):** Android does **not** allow third-party apps to record call audio on modern versions. The compliant design is: user manually starts a recording via speakerphone + mic in explicit "meeting/call notes" mode, or imports recordings made by the OEM dialer. Everything downstream (transcribe → summarize → extract commitments) is identical.
- **Screening path:** `CallScreeningService` lets the app classify incoming calls by number and reject/label them; combine with the voicemail-content classifier for a two-layer scam shield.

### Android implementation
- `CallScreeningService` (role: `ROLE_CALL_SCREENING`) — public, Play-compliant.
- Voicemail: `VoicemailContract` (carrier-dependent) or file import — pragmatic v1 is import/share.
- Call recording: blocked at the platform level (Android 10+ mic restrictions; Play policy bans accessibility-based recording). Do not attempt; design around it.

### Feasibility: 🟡
Transcription/summarization/screening: 🟢. True automatic call recording: 🔴 (platform-blocked) — the spec above deliberately routes around it. Legal note: recording-consent laws vary by jurisdiction; the manual, user-initiated design keeps consent in the user's hands.

### Performance budget & quality testing
- ASR: whisper.cpp `base`/`small` quantized. Target real-time factor ≤ 0.5 on mid tier (a 60 s voicemail transcribes in ≤ 30 s); use `tiny`/`base` on low tier. Voicemails are short — this runs on-demand, not always-on.
- Summarization: transcript ≤ 1,500 tokens → one-line summary ≤ 30 tokens; ≤ 5 s on mid tier.
- **ASR benchmark:** WER on a phone-audio test set (compressed 8 kHz-style audio, accents, background noise — *not* clean LibriSpeech). Build ~2 h of representative voicemail-like audio; gate per model/quant: WER ≤ 15% mid tier (`small`), ≤ 22% low tier (`base`). Summaries are tested downstream of *real* ASR output, errors included — that's what ships.
- **Scam classifier eval:** ≥ 300 labeled transcripts (scam scripts are well-documented publicly; synthesize variants). Track scam recall and false-positive rate on legitimate bank/clinic calls — false accusations are the worse failure.

### Definition of done
- [ ] Voicemail import → summary card ≤ 45 s for a 60 s message on low tier, fully offline.
- [ ] Summary factuality: 95% of summaries contain no claim absent from the transcript (100-case human-checked set).
- [ ] Scam flagging: recall ≥ 0.85 with false-positive rate ≤ 3% on the labeled set; flag copy is hedged ("looks like a scam") and never auto-blocks based on content alone.
- [ ] Commitment extraction ("send invoice by Friday") produces a correct reminder draft (right task, right date) ≥ 85% on a 100-case set; user always confirms before creation.
- [ ] `CallScreeningService` role acquisition, rejection, and labeling verified on Android 12–15; graceful no-op when another app holds the role.
- [ ] Zero call-audio capture code paths exist in any build flavor (static check in CI).

---

## 6. SMS Auto-Pilot (Bills, Deliveries, OTPs)

### Concept
Parse incoming SMS locally into a structured life-feed: deliveries, bill due dates, bank alerts, appointments — with one-tap reminders. Regex apps break on messy formats; an SLM generalizes, and bank SMS must never leave the device.

### User stories
- "Electricity bill ₹2,340 due July 8" → card with **Remind me July 7** button.
- Delivery timeline assembled across couriers into one tracker.
- Monthly spend summary inferred from bank debit alerts — computed entirely on-device.

### Functional spec
- **Ingestion:** read incoming SMS, filter to transactional senders (sender-ID heuristics + model classification), extract to a typed schema: `{type, merchant, amount, due_date, tracking_id, ...}` via constrained JSON decoding (llama.cpp grammars make this reliable even on small models).
- **Feed UI:** chronological cards by category; each card offers actions: reminder (your scheduler), calendar event, mark paid.
- **Digest hooks:** feeds Feature 1's digests and Feature 3's briefing ("your package arrives today").

### Android implementation
- `READ_SMS` / `RECEIVE_SMS` permissions, or `SmsRetriever`-style approaches for OTP only.
- **The real constraint is Google Play policy**, not the OS: SMS permissions are restricted to apps whose *core* function requires them, with a permissions declaration form. Assistant apps have been approved, but it's the single biggest policy risk in this list. Fallbacks: (a) `NotificationListenerService` reads SMS *notifications* instead — no SMS permission needed, captures most content; (b) full SMS power in the sideloaded/F-Droid build.

### Feasibility: 🟡
Technically 🟢 (old, stable APIs; SLM + grammar-constrained JSON is very reliable). Distribution on Play: 🟡→🔴 depending on review. The notification-listener fallback makes a compliant v1 shippable regardless.

### Performance budget & quality testing
- Per-SMS extraction: ≤ 250 prompt tokens, ≤ 60 output tokens (typed JSON) → ≤ 4 s on low tier; SMS volume is low enough to process on arrival without batching.
- **Golden dataset (the heart of this feature):** ≥ 1,000 real-format transactional SMS spanning banks, couriers, utilities, telecoms across your target markets (formats vary wildly by country — India vs US vs EU sender conventions differ completely). Label the full schema. This dataset is the moat; grow it continuously from opt-in user corrections (stored locally, exported only as anonymized templates with explicit consent — or not at all).
- **Metrics:** field-level precision/recall per type; the critical ones are `amount` and `due_date` (a wrong bill amount or date is worse than a miss — tune for precision on those fields).
- **Adversarial set:** phishing SMS mimicking banks must be classified `suspicious`, never rendered as a legitimate bill card with a payment nudge.

### Definition of done
- [ ] Field extraction: `amount` precision ≥ 0.98, `due_date` precision ≥ 0.97, overall record F1 ≥ 0.92 on the golden set with the low-tier model.
- [ ] Schema-valid JSON ≥ 99.5% (grammar-constrained); malformed retries once then drops to an "unparsed" bucket visible to the user, never a wrong card.
- [ ] Phishing set: 0 phishing messages rendered as actionable bill/payment cards; ≥ 90% flagged suspicious.
- [ ] Reminder/calendar actions land correctly (instrumented tests through your scheduler and `CalendarContract`).
- [ ] Notification-listener fallback achieves ≥ 85% of the SMS-permission build's recall on the same golden set (measured, documented — this justifies the Play flavor).
- [ ] Network audit: zero egress of SMS-derived data in any flavor; monthly spend summaries computed only in the encrypted local store.

---

## 7. Semantic Gallery & File Search

### Concept
"The whiteboard photo from last week." "Screenshot of the wifi password." Natural-language search over photos, screenshots, and documents — indexed entirely on-device.

### User stories
- Find any screenshot by what it *says* (OCR index) or *shows* (visual embedding).
- "Photos of receipts from June" → grouped results, one tap to total the amounts (Feature 6 synergy).
- Documents in Downloads searchable by meaning, not filename.

### Functional spec
- **Indexer:** background job walks `MediaStore` (images) and user-granted folders (SAF tree URIs). Per item: OCR text (ML Kit) + visual embedding (CLIP-class model, ~100–400 MB, ONNX/NNAPI-accelerated) + EXIF metadata (time, place if present). Store vectors in the RAG index; incremental via `MediaStore` change notifications.
- **Query:** text query → text-encoder embedding → cosine search over both OCR-text vectors and image vectors; merge and rank. Results open in a grid with the matching region highlighted where OCR matched.
- **Budgeting:** index only while charging + idle (`WorkManager` constraints); target < 1 s per image on a mid-range NPU; first-run backfill communicated honestly ("indexing 4,200 photos overnight").

### Android implementation
- `READ_MEDIA_IMAGES` (13+) or the Android 14 partial-access photo picker flow.
- `WorkManager` with `requiresCharging + requiresDeviceIdle` for the backfill.
- Inference: ONNX Runtime with NNAPI/QNN delegate for the vision encoder — this is the compute-heaviest feature in the list; quantized MobileCLIP-class models keep it tractable.

### Feasibility: 🟢/🟡
All public APIs, real precedent (this is essentially local Google Photos search). The risk is purely performance engineering on low-end devices — solve with model tiering (OCR-only index on weak hardware, +visual embeddings on capable hardware).

### Performance budget & quality testing
- Indexing throughput: target ≥ 1 image/s on mid tier with NNAPI/QNN delegate (MobileCLIP-S-class, INT8), ≥ 0.3 image/s CPU-only on low tier (OCR-only mode there). A 10 k-photo library backfills in one or two overnight charging sessions — set that expectation in UI.
- Query latency: text-encoder embed + ANN search over 50 k vectors ≤ 400 ms; results grid interactive ≤ 1 s.
- Index footprint: ≤ 4 KB/image (quantized vectors + OCR text) → ~40 MB per 10 k photos.
- **Retrieval benchmark:** internal eval of ≥ 300 (query → correct image set) pairs over a fixed test library: screenshots-by-content ("wifi password"), objects ("whiteboard"), scenes, receipts, plus 50 no-answer queries. Metric: recall@10 and nDCG@10, reported separately for OCR-hit vs pure-visual queries (expect visual to be weaker; that's the tier-2 upsell).
- **Thermal test:** full overnight backfill must stay below `THERMAL_STATUS_SEVERE` and pause/resume cleanly when unplugged mid-run.

### Definition of done
- [ ] Recall@10 ≥ 0.85 on OCR-anchored queries (all tiers) and ≥ 0.70 on pure-visual queries (mid/high tiers) on the fixed benchmark library.
- [ ] Incremental indexing: a new photo is searchable within 15 min while charging, or at next charge otherwise (MediaStore observer test).
- [ ] Backfill of 10 k photos completes within 2 charging nights on mid tier with zero ANRs and battery impact only during charge.
- [ ] Revoking photo permission (or Android 14 partial access changes) prunes the index correspondingly — verified, no orphaned vectors of revoked content.
- [ ] Search works in airplane mode over the full index.
- [ ] Low-tier devices get OCR-only mode automatically, with honest UI ("visual search needs a more powerful device").

---

## 8. Auto-Diary / Continuous Journaling

### Concept
Because local inference is free, a nightly on-device job composes a private daily log from the day's exhaust: calendar events, dictated notes, saved memories, (opt-in) significant places. Over months this becomes an irreplaceable archive — maximal lock-in, zero cloud cost, impossible to offer server-side at this privacy level.

### User stories
- Every night at 11 PM, a one-paragraph entry appears: where you were, who you met (from calendar), what you saved, what you said you'd do.
- "What was I doing the week before the Goa trip?" → narrative recall over the diary index.
- Weekly/monthly auto-retrospectives: recurring themes, open loops, mood trajectory (if the user dictates feelings).

### Functional spec
- **Sources (each individually toggleable):** calendar (`CalendarContract`), the app's own memories/notes/voice captures, notification digest highlights (Feature 1), SMS feed events (Feature 6), and location visits via geofenced "significant places" the user defines — *not* continuous tracking.
- **Composer:** nightly `WorkManager` job (charging + idle) assembles the day's structured facts → SLM writes the entry in a user-chosen voice (neutral log / warm journal / bullet ops-review). Facts and prose are both stored; prose can be regenerated.
- **Privacy posture:** diary DB encrypted at rest (SQLCipher / EncryptedFile keyed via Android Keystore + optional biometric unlock), never included in any export/share unless explicitly chosen, indexed into RAG only inside the encrypted store.

### Android implementation
- `WorkManager` periodic job; all sources are APIs already used by other features — this is primarily a composition layer.
- Location (optional): `ACCESS_BACKGROUND_LOCATION` is heavily gated on Play — prefer the geofence-on-user-defined-places design (`GeofencingClient`), which is both more private and easier to justify, or skip location in v1.
- Keystore-backed encryption: standard Jetpack Security patterns.

### Feasibility: 🟢
Almost entirely built from the other features' plumbing plus a scheduler and an encrypted store. The only 🟡 element is background location — designed here as optional and geofence-based.

### Performance budget & quality testing
- Nightly job: assemble facts (≤ 1,500 prompt tokens after summarizing sources) → entry ≤ 250 tokens. Runs while charging + idle, so latency is a non-issue; budget ≤ 90 s total on low tier including retrieval and indexing of the entry.
- **Factuality harness:** 60 synthetic days with known ground-truth facts → every entry statement must trace to a source fact (same attribution mechanism as Features 1/3); zero invented people, places, or commitments. This is *the* quality bar — an auto-diary that confabulates is worse than none.
- **Style regression:** 3 voice presets each tested against a rubric (length, tense consistency, no repeated stock phrases across consecutive days — SLMs love reusing openers; test for n-gram overlap between consecutive entries).
- **Longitudinal retrieval:** after generating 180 synthetic days, "what was I doing the week before X" queries must retrieve the correct date range (recall ≥ 0.85 on a 50-query set).

### Definition of done
- [ ] Zero unattributed claims across the 60-day factual harness; missing-data days produce a graceful short entry, never filler fiction.
- [ ] Opener/phrase diversity: < 30% 5-gram overlap between any two consecutive entries per voice preset.
- [ ] Diary DB encrypted at rest (verified: file pulled via adb from a rooted test device is unreadable); optional biometric gate works; diary excluded from Android auto-backup (`android:allowBackup` rules audited).
- [ ] Each source toggle independently verified: disabling a source removes it from the next entry; retroactive delete of a source item offers entry regeneration.
- [ ] Nightly job success rate ≥ 95% over a 30-day device soak (charging overnight), with catch-up generation for missed nights.
- [ ] Geofence-based places (if enabled) never records raw location traces — only named-place enter/exit events (code + data audit).

---

## Cross-cutting notes

**Model tiering.** Every feature above works at 1.7–4 B params. Recommended stack: one instruction model (Qwen3-1.7B/4B tiered by RAM), one embedding model (~50 MB), OCR via ML Kit, optional vision encoder (MobileCLIP-class) and optional VLM for Feature 4, neural TTS (Piper) for Feature 3. Auto-select by device RAM at onboarding; never show users a model-picker first.

**A shared benchmarking harness, built once.** Don't test per-feature ad hoc. Build one internal harness with three layers: (1) **model-level** — a CLI/instrumented runner that reports prefill tok/s, decode tok/s, TTFT, peak RSS, and energy (via `BatteryManager` counters) for each model×quant×device; run it whenever you bump llama.cpp, a model, or a quant, and keep a tracked results table in the repo. (2) **task-level** — the golden datasets above, runnable both on desktop (fast iteration, same GGUF) and on-device (truth). Desktop and on-device scores must agree within a tolerance, or the delegate/quant is suspect. (3) **system-level** — 24 h soak tests on physical devices measuring battery, memory, service survival, and thermal status. Gate releases on all three. Also maintain a public `BENCHMARKS.md` — for this app's audience, published honest numbers *are* marketing.

**Battery discipline.** The unifying pattern: *capture cheaply in real time, infer in batches, do heavy work only while charging.* `WorkManager` constraints + grammar-constrained short generations keep all of this invisible on the battery graph.

**Play Store vs. sideload strategy.** Features 1, 2, 3, 7, 8 are fully Play-safe. Features 4 (accessibility tier) and 6 (SMS permission) are where policy bites — ship the compliant fallback on Play and the full-power version via GitHub/F-Droid, which also reinforces the open, user-owned brand.

**The compounding effect.** These features feed each other: SMS feed → briefing → diary; memories → screen-aware answers; notification digest → triage → diary. The moat isn't any single feature — it's the private, local index that grows richer every day and can never be replicated by an app the user doesn't trust with the data.
