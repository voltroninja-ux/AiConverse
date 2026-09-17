# AiConverse

A group voice chat between you and two free-tier AI models (Gemini + an OpenRouter free
model). Both AIs hear each other and you, talk to each other by name, have individualized
TTS voices, and can be interrupted mid-sentence (barge-in) if you enable it in-app.

## What's already done for you
- Full Android Studio project (Compose UI, Kotlin, Gradle scripts) — nothing to write.
- Two-AI orchestration with natural, non-rigid turn-taking (`ConversationManager.kt`).
- Individualized voices per AI via on-device TextToSpeech (`VoiceOutputManager.kt`).
- Continuous speech recognition + voice barge-in support (`SpeechInputManager.kt`).
- In-app Settings screen to paste your API keys — **no code editing or rebuilding needed**
  to add/change keys later (`SettingsStore.kt`, encrypted on-device storage).
- App icon, manifest permissions, network config — all set.

## What you still need to do (unavoidable — these require your own accounts / a build machine)

### 1. Get two free API keys (~5 minutes, $0)
- **Gemini**: go to https://aistudio.google.com → "Get API key" → copy it.
- **OpenRouter**: go to https://openrouter.ai → sign up → API Keys → create one → copy it.
  Also check https://openrouter.ai/models?max_price=0 for the current list of `:free`
  models — the project defaults to `meta-llama/llama-3.1-8b-instruct:free`, which you can
  change right in the app's Settings screen if that model is ever retired.

### 2. Open and build the project
1. Install [Android Studio](https://developer.android.com/studio) (free) if you don't have it.
2. Open Android Studio → **Open** → select this `AiConverse` folder.
3. Let it sync (Android Studio will auto-download the Gradle wrapper and all dependencies
   the first time — this needs internet access once).
4. Plug in an Android phone (USB debugging on) or start an emulator.
5. Click the green **Run ▶** button. This installs and launches the app.

### 3. To get a standalone `.apk` file to share/install elsewhere
In Android Studio: **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
The finished file will be at `app/build/outputs/apk/debug/app-debug.apk` — copy that
anywhere and install it on any Android device (enable "Install unknown apps" for the
source you use).

### 4. First run
- Grant microphone permission when asked.
- Paste your two API keys into Settings (shown automatically on first launch).
- Tap the mic, say something — both AIs will reply and may reply to each other too.

## Notes on the design choices
- **Cost**: this is built for $0/month. Gemini's free tier and OpenRouter's `:free` models
  have rate limits (a handful of requests per minute) — if a reply comes back as
  "rate-limited," just wait a few seconds and try again. If you later want the *actual*
  Claude or ChatGPT instead of a free open model, swap `OpenRouterParticipant` for a
  paid-key participant using the same `AiParticipant` interface — the rest of the app
  doesn't change.
- **Individualized voices**: each AI is assigned a distinct installed TTS voice where the
  device has more than one, and distinct pitch/rate always, so they're recognizable even
  on devices with only one voice installed.
- **Natural conversation flow**: turn order isn't fixed ping-pong — the app picks whichever
  AI didn't just speak (with an occasional same-AI follow-up, like a person adding "oh,
  and..."), caps how long the AIs will talk before yielding back to you, and both AIs are
  prompted to keep replies short, react to each other by name, and skip robotic phrasing.
- **Barge-in caveat**: true interruption while AI audio plays has no hardware echo
  cancellation here, so it works best with headphones/earbuds. On a phone's open speaker it
  can occasionally mishear its own TTS as user speech. If that's annoying, turn off the
  "Allow interrupting by voice" switch in the app and just tap the mic button when the AIs
  are talking — that stops playback immediately too.

## Project structure
```
app/src/main/java/com/aiconverse/app/
  MainActivity.kt              — Compose UI, screen flow, wiring
  ConversationManager.kt       — turn-taking / natural pacing logic
  SettingsStore.kt             — encrypted on-device API key storage
  ai/
    AiParticipant.kt           — shared interface + transcript model
    GeminiParticipant.kt       — Gemini free-tier API calls
    OpenRouterParticipant.kt   — OpenRouter free-model API calls
  voice/
    SpeechInputManager.kt      — mic input, continuous + barge-in modes
    VoiceOutputManager.kt      — TTS output, per-speaker voice profiles
```

## If something doesn't compile
This was hand-written and reviewed carefully but not compiled in a live Android SDK
environment. If Android Studio flags an error on first sync, it's most likely one of:
- A dependency version bump (Android Studio usually offers an auto-fix / suggests the
  correct version).
- The OpenRouter free model id in Settings having been renamed/retired — swap it for a
  current one from https://openrouter.ai/models?max_price=0.
