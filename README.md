# 🐷 Piggselle

A private, offline-first AI assistant for Android, tuned for the **Pixel 9 Pro** and built for
**GrapheneOS**. It is a fork of [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery)
(Apache 2.0) that turns the agent chat into a personal assistant with:

- **Persona**: editable instructions, a name for you, several personas to switch between.
- **Memory**: the model *proposes* lasting facts about you; nothing is saved until you approve,
  edit or drop it. Memories are AES-GCM encrypted with an Android Keystore key.
- **Filtered web search** through **your own SearXNG**: queries are scrubbed of personal data and
  shown to you before they leave the phone (auto mode optional; health topics always ask).
- **On-device inference** with LiteRT-LM on the Tensor G4 GPU (Gemma 4 E4B recommended), with
  automatic CPU fallback.

Once a model is downloaded, the app works fully offline.

## Privacy model

| What | Where it goes |
| --- | --- |
| Chats, memories, personas, network log | Encrypted on the phone. Excluded from cloud backup and device transfer. |
| Web search query | Only the (scrubbed, approved) query text, to the SearXNG URL you set. No cookies, neutral User-Agent. |
| Search results | Shown to the model as untrusted text. Pages are never fetched. |
| Model downloads | Model files from Hugging Face (`huggingface.co`, `*.hf.co`). Toggleable. |
| Telemetry | None. Firebase, Google Analytics, FCM, ML Kit/AICore, MediaPipe and the MCP client were removed. CI fails the build if telemetry packages reappear. |

How it's enforced:

1. **NetworkGate**: every request in the app goes through one class that checks a purpose
   (web search, model download, catalog), the offline switch, and a host allowlist. Each redirect
   hop is checked again, and tokens are never forwarded to another host. Every attempt, allowed or
   blocked, lands in an encrypted log you can view under *Privacy*.
2. **Process fence**: a default `ProxySelector` sends any HTTP(S) connection that no enabled
   purpose allows to a dead local proxy. That covers code that doesn't go through the gate (for
   example library uploaders).
3. **QueryScrubber**: removes your private terms (names, places…), emails, phone numbers, IBANs,
   card numbers, IPs, coordinates, dates, street addresses, postcodes, long numbers and
   first-person wording ("my", "ich", …). It flags health terms (German and English) so you
   always see those queries.
4. **Rendering**: markdown images become plain links, so model output can't trigger hidden
   requests.
5. **GrapheneOS**: for belt and braces, revoke Piggselle's *Network* permission whenever you
   don't need search or downloads.

The scrubber is a pattern-based safety net, not a guarantee. That's why confirmation is the
default.

## Install

**Obtainium** (recommended): add `https://github.com/der-ort/Piggselle`. Releases are built and
signed by GitHub Actions. Until release signing is set up (see below), builds are published as
pre-releases, so turn on *Include prereleases* in Obtainium.

To publish an update, bump `versionName` and `versionCode` in `Android/src/app/build.gradle.kts`
and push to `main`. CI creates the `v<versionName>` release once per version.

Or download the APK from [Releases](https://github.com/der-ort/Piggselle/releases) and check
the `.sha256` file.

## First run

1. Open **Piggselle** on the home screen, pick **Gemma-4-E4B-it**, download it (≈3.7 GB) and keep
   the accelerator on **GPU**.
2. Tap the persona chip to set what it should call you, and edit the instructions if you like.
3. Optional: tap **Local → Privacy & network** to set your SearXNG URL and switch on web search.
4. Optional: add *Never send these words* (your name, street, employer…).

### SearXNG requirements

The instance must allow JSON output. In `settings.yml`:

```yaml
search:
  formats:
    - html
    - json
```

`https://` works for any host. Plain `http://` is accepted only for LAN or Tailscale addresses
(`10.x`, `192.168.x`, `100.64.0.0/10`, `*.ts.net`, …). If your instance runs the limiter, allow
your tailnet IP or disable the limiter for it. Use **Test with "weather"** in the app to check.

## Building

GitHub Actions builds every push (`.github/workflows/piggselle.yml`): unit tests, release APK,
privacy checks on the APK (permissions, no telemetry classes), and a GitHub Release for `v*` tags.

### Release signing (needed for updates)

Android only installs an update if it's signed with the same key. Create a key once, on your
own machine:

```sh
keytool -genkeypair -v -keystore piggselle.jks -alias piggselle \
  -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Piggselle"
base64 -w0 piggselle.jks > piggselle.jks.b64
```

Add these repository secrets (*Settings → Secrets and variables → Actions*):

| Secret | Value |
| --- | --- |
| `PIGGSELLE_KEYSTORE_BASE64` | contents of `piggselle.jks.b64` |
| `PIGGSELLE_KEYSTORE_PASSWORD` | keystore password |
| `PIGGSELLE_KEY_ALIAS` | `piggselle` |
| `PIGGSELLE_KEY_PASSWORD` | key password (same as keystore password unless you chose another) |

Back up `piggselle.jks` and its password somewhere safe. Without the secrets, CI signs with a
throwaway debug key: fine for testing, but every build then needs a reinstall.

Local build: `cd Android/src && ./gradlew :app:assembleRelease` (JDK 21, Android SDK 37).

## Code map

New code lives in `Android/src/app/src/main/java/com/google/ai/edge/gallery/assistant/`:

- `net/NetworkGate.kt`, `net/HostPolicy.kt`: network policy, fence and log
- `privacy/QueryScrubber.kt`, `privacy/MarkdownSanitizer.kt`
- `search/SearxngClient.kt`, `search/SearchResults.kt`
- `tools/AssistantTools.kt`: `web_search` and `suggest_memory` tools for the model
- `data/`, `storage/SecureStore.kt`: personas, memories, settings (encrypted)
- `ui/`: assistant screen, persona/memory/privacy sheets, search confirmation
- `customtasks/agentchat/AssistantTaskModule.kt`: wires it into the app

## License

Apache 2.0, like upstream. See `LICENSE` and `NOTICE`. Not affiliated with Google.
The original README is kept as `UPSTREAM_README.md`.
