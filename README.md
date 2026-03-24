# MAM Test — Intune SDK + CopilotStudioClient on Android

Tests the end-to-end flow:
**Intune MAM SDK enrollment → AAD token with MAM compliance → CopilotStudioClient (M365 Agents SDK) → Copilot Studio agent**

---

## Architecture

```
Android Native App (Kotlin · single Activity)
│
├── MSAL (brokered auth via Company Portal)
│   └── Handles MsalIntuneAppProtectionPolicyRequiredException
│       └── MAMComplianceManager.remediateCompliance()
│           └── Waits for ENROLLMENT_SUCCEEDED
│               └── Retries acquireTokenSilent() → token now passes CA gate
│
└── WebView (loads assets/index.html + chat.bundle.js)
    ├── window.__authToken injected by native after token ready
    ├── CopilotStudioClient (@microsoft/agents-copilotstudio-client)
    │   └── getTokenAsync: () => window.__authToken
    └── window.NativeBridge.onTokenExpired() → triggers native token refresh
```

**Why a WebView for the chat UI?**
The M365 Agents SDK `CopilotStudioClient` is a JavaScript/TypeScript library. Running it in a WebView is the simplest path that avoids a full React Native setup while keeping the native layer in control of authentication.

---

## Setup & Testing

Everything you need — from zero to a working chat on your device or emulator.

> **Before you start:** have these open in your browser:
> - `https://entra.microsoft.com` (Entra admin)
> - `https://intune.microsoft.com` (Intune admin)
> - `https://copilotstudio.microsoft.com` (Copilot Studio)
> - A Notepad file to save IDs as you go

---

### Part 1 — Copilot Studio
*Set up the agent the Android app will talk to*

**Step 1 — Create or open your agent**

- Go to `https://copilotstudio.microsoft.com` and sign in
- Open an existing agent or click **+ Create** → **New agent**
- Give it a name e.g. `MDM Test Agent`

**Step 2 — Enable Microsoft (AAD) authentication**

This is the most important step. The Android app gets a token from Entra ID and passes it to the agent — the agent must be configured to accept it.

- Inside your agent click **Settings** (top right gear icon)
- Click **Security** → **Authentication**
- Select **Authenticate with Microsoft**
- Click **Save**

After saving, two values appear on screen — copy both to Notepad:
- **App ID** (the bot's Entra app registration ID)
- **Tenant ID**

**Step 3 — Copy the Environment ID and Schema name**

- Still in **Settings** → click the **About** tab
- Copy and save:
  - **Environment ID** (a GUID like `abc12345-0000-...`)
  - **Schema name** (something like `cr123_mdmTestAgent`)

**Step 4 — Add a test topic**

So the agent has something to reply with when you test:

- Click **Topics** → **+ Add a topic** → **From blank**
- Name it `Hello`
- Add trigger phrases: `hi`, `hello`, `test`
- Add a **Message** node with text: `Hi! I am working. The MAM flow succeeded.`
- Click **Save**

**Step 5 — Publish the agent**

The agent must be published before the Android app can reach it.

- Click **Publish** (top right) → **Publish** again to confirm
- Wait ~1 minute

**Step 6 — Enable the Direct Line channel**

The M365 `CopilotStudioClient` connects via this channel.

- **Settings** → **Channels** → find **Mobile app** or **Direct Line** → make sure it is **enabled**

---

### Part 2 — Entra ID
*Give your Android app an identity so it can request tokens*

**Step 7 — Create an app registration**

- Go to `https://entra.microsoft.com` and sign in
- **Applications** → **App registrations** → **+ New registration**
- Name: `MDM Test App`
- Supported account types: `Accounts in this organizational directory only`
- Redirect URI: leave blank for now
- Click **Register**

**Step 8 — Copy the IDs**

On the registration overview page, copy to Notepad:
- **Application (client) ID**
- **Directory (tenant) ID**

**Step 9 — Add API permissions**

- **API permissions** → **+ Add a permission** → **APIs my organization uses**
- Search `Microsoft Mobile Application Management`
- Select **Delegated** → tick `DeviceManagementManagedApps.ReadWrite` → **Add permissions**
- Click **Grant admin consent for [your tenant]** → Yes

**Step 10 — Expose the bot's API scope**

The Android app requests a token for the bot. The bot's Entra app registration must expose that scope.

- **App registrations** → search for the **Bot App ID** from Step 2
- Click **Expose an API**
- If no Application ID URI is set, click **Add** → accept the default `api://<app-id>`
- Click **+ Add a scope**
  - Scope name: `user_impersonation`
  - Who can consent: **Admins and users**
  - Display name: `Access Copilot agent`
  - Click **Add scope**

**Step 11 — Link the scope to your MDM Test App**

- Go back to the **MDM Test App** registration (from Step 7)
- **API permissions** → **+ Add a permission** → **My APIs**
- Find the bot app → tick `user_impersonation` → **Add permissions**
- Click **Grant admin consent**

---

### Part 3 — Intune
*Tell Intune to protect your app*

**Step 12 — Create an App Protection Policy**

- Go to `https://intune.microsoft.com` and sign in
- **Apps** → **App protection policies** → **+ Create policy** → **Android**
- Name: `MDM Test Policy` → **Next**
- On the **Apps** page → **+ Select custom apps**
- Enter package name: `com.contoso.mdmtest` → Add → **Next**
- Leave defaults on remaining pages
- On **Assignments** → **+ Add groups** → add your test user or group
- **Next** → **Create**

> After creating the policy wait **15–30 minutes** before testing. Intune needs time to propagate the assignment.

**Step 13 — Relax policy requirements for testing (emulator only)**

If you are using an emulator, some access requirements may block it. Temporarily:

- Your policy → **Access requirements**
- **PIN for access** → Not required
- **Minimum OS version** → leave blank

Tighten these again before production.

---

### Part 4 — Intune MAM SDK access
*Get permission to download the SDK*

The MAM SDK is distributed via Microsoft's Azure DevOps Maven feed.

- Request access at: `https://github.com/microsoftconnect/ms-intune-app-sdk-android`
- Once approved, set these environment variables before building:

```bash
export INTUNE_MAVEN_USER="your-ado-username"
export INTUNE_MAVEN_TOKEN="your-ado-pat"
```

Alternatively, download the AAR from GitHub Releases and add it as a local file dependency in `app/build.gradle`.

---

### Part 5 — Configure and build the app

**Step 14 — Install Android Studio**

Download from `https://developer.android.com/studio` and install it.

**Step 15 — Open the project**

- Open Android Studio → **Open**
- Navigate to: `C:\Users\vineetkaul\OneDrive - Microsoft\POWER CAT\BFL\mdm-project`
- Wait for **"Gradle sync finished"** in the bottom bar

**Step 16 — Get your app's debug signature**

Open the **Terminal** tab at the bottom of Android Studio and run:

```bash
keytool -exportcert -alias androiddebugkey \
  -keystore "%USERPROFILE%\.android\debug.keystore" \
  -storepass android | openssl sha1 -binary | openssl base64
```

You'll get a short string like `abc123XYZ==` — copy it to Notepad.

**Step 17 — Fill in `msal_config.json`**

Open: `app > src > main > res > raw > msal_config.json`

```json
{
  "client_id": "← Application (client) ID from Step 8",
  "redirect_uri": "msauth://com.contoso.mdmtest/← signature from Step 16",
  "broker_redirect_uri_registered": true,
  "client_capabilities": ["ProtApp"],
  "account_mode": "SINGLE",
  "authorities": [{
    "type": "AAD",
    "audience": {
      "type": "AzureADMyOrg",
      "tenant_id": "← Directory (tenant) ID from Step 8"
    }
  }]
}
```

> `client_capabilities: ["ProtApp"]` is mandatory — without it Entra ID will not return the `protection_policy_required` sub-error and the CA flow never triggers.

**Step 18 — Fill in the Copilot scope**

Open: `app > src > main > java > com > contoso > mdmtest > MsalClient.kt`

```kotlin
val copilotScopes = listOf("api://← Bot App ID from Step 2/.default")
```

**Step 19 — Fill in the chat config**

Open: `webchat > src > chat.js`

```js
const COPILOT_CONFIG = {
    environmentId: "← Environment ID from Step 3",
    agentIdentifier: "← Schema name from Step 3",
    tenantId: "← Directory (tenant) ID from Step 8",
};
```

**Step 20 — Update AndroidManifest with the signature**

Open: `app > src > main > AndroidManifest.xml`

Find:
```xml
android:path="/YOUR_BASE64_ENCODED_PACKAGE_SIGNATURE"
```
Replace `YOUR_BASE64_ENCODED_PACKAGE_SIGNATURE` with your signature from Step 16. Keep the `/` before it.

**Step 21 — Add the redirect URI to Entra**

Back in Entra → **MDM Test App** registration → **Authentication**:
- **+ Add a platform** → **Android**
- Package name: `com.contoso.mdmtest`
- Signature hash: paste signature from Step 16
- Click **Configure**

**Step 22 — Build the JS chat bundle**

In Android Studio's Terminal tab:

```bash
cd webchat
npm install
npm run build
```

This creates `app/src/main/assets/chat.bundle.js`. You must do this before running the app — if the file is missing the WebView will be blank.

---

### Part 6a — Run on a physical device

**Step 23 — Enable USB debugging on the device**

- Connect the device to your PC via USB
- On the device: **Settings** → **About phone** → tap **Build number** 7 times
- **Settings** → **Developer options** → turn on **USB debugging**
- Tap **Allow** on the prompt that appears on the device

**Step 24 — Install Company Portal**

On the device:
- Open **Play Store** → search **Intune Company Portal** → Install
- Do not sign into it — just having it installed is enough

**Step 25 — Run the app**

- In Android Studio your device appears in the device dropdown at the top
- Click **▶ Run** — the app builds and installs automatically

---

### Part 6b — Run on an emulator (no physical device needed)

**Step 23 — Create a virtual device**

- Android Studio: **View** → **Tool Windows** → **Device Manager** → **+** → **Create Virtual Device**
- Hardware: **Pixel 6** → **Next**
- System image: pick one with **"Google Play"** in the Target column e.g. `API 34 — Google Play`
  - ✅ Google Play — correct
  - ❌ Google APIs / AOSP — Company Portal will not install properly
- If not downloaded, click the arrow to download it (~1–2 GB) → **Next** → **Finish**

**Step 24 — Boot the emulator**

In Device Manager click **▶** next to your new device. First boot takes 2–3 minutes.

**Step 25 — Sign into Google Play on the emulator**

- Open **Play Store** on the emulator
- Sign in with any personal Google account (for Play Store access only — not your tenant account)

**Step 26 — Install Company Portal**

- Play Store → search **Intune Company Portal** → Install
- Do not open or sign into it

**Step 27 — Run the app**

- The emulator appears in the device dropdown in Android Studio
- Click **▶ Run**

| Emulator limitation | What to do |
|---|---|
| Slower than a real device | Token flows may take a few extra seconds — just wait |
| Clock drift → token expiry errors | Emulator **Settings** → **Date & Time** → enable **Automatic date & time** |
| No biometrics | Set a PIN in emulator **Settings** → **Security** if policy requires it |

---

### Part 7 — Test the flow

When the app opens:

1. A Microsoft login screen appears → sign in with your test user
2. If the Intune policy is active, Company Portal opens briefly for remediation — let it complete
3. The chat screen appears — type `hi` and the agent should reply

**Happy path (no CA policy configured):**
```
Login → registerAccountForMAM() → ENROLLMENT_SUCCEEDED
→ token injected into WebView → chat starts
```

**CA policy path:**
```
Login → MsalIntuneAppProtectionPolicyRequiredException thrown
→ remediateCompliance() called
→ Company Portal shows remediation UI
→ ENROLLMENT_SUCCEEDED received
→ acquireTokenSilent() retried → token passes CA gate
→ token injected into WebView → chat starts
```

**If something goes wrong:** in Android Studio click the **Logcat** tab at the bottom and filter by `MdmTest` — every step in the app is logged there.

---

### Config checklist — all values in one place

| Value | Where to find it | Goes into |
|---|---|---|
| Application (client) ID | Entra → MDM Test App registration | `msal_config.json` client_id |
| Directory (tenant) ID | Entra → MDM Test App registration | `msal_config.json` + `chat.js` |
| Debug signature | `keytool` command (Step 16) | `msal_config.json` redirect_uri + `AndroidManifest.xml` |
| Bot App ID | Copilot Studio → Settings → Security → Authentication | `MsalClient.kt` copilotScopes |
| Environment ID | Copilot Studio → Settings → About | `chat.js` |
| Schema name | Copilot Studio → Settings → About | `chat.js` |

---

## Troubleshooting

### "enrollment id missing"

| Check | Where |
|---|---|
| `client_capabilities: ["ProtApp"]` in msal_config.json? | `res/raw/msal_config.json` |
| `broker_redirect_uri_registered: true`? | Same file |
| `DeviceManagementManagedApps.ReadWrite` granted in Entra? | Entra → App registrations → API permissions |
| App package name in Intune App Protection Policy? | Intune → App protection policies → Apps |
| Company Portal installed on device? | Device |
| `aadId` in `remediateCompliance()` = OID from exception? | `MainActivity.kt` `startMamRemediation()` — already correct in scaffold |
| Previous account unregistered on sign-out? | Call `MAMEnrollmentManager.getInstance().unregisterAccountForMAM(upn, aadId)` |

### `AUTHORIZATION_NEEDED` enrollment result

`MAMAuthCallback.acquireToken()` returned null. Check:
- Is the OID (`aadId`) from the exception matching the signed-in MSAL account?
- Is `MsalClient.getPca()` returning a non-null PCA at callback time?

### Chat UI stuck on "Waiting for authentication..."

The `authReady` event was not dispatched. Check logcat for "Token ready — injecting into WebView".

### `MsalIntuneAppProtectionPolicyRequiredException` never thrown

- Confirm `client_capabilities: ["ProtApp"]` is present in msal_config.json
- Confirm the CA policy is **On** (not Report-only) in Entra → Conditional Access
- Confirm the CA grant is **Require app protection policy**, not "Require compliant device"

### Policy propagation delay

If you just assigned the App Protection Policy, wait 15–30 minutes and retry. The Intune service must propagate the assignment before enrollment succeeds.

---

## Project structure

```
mdm-project/
├── build.gradle                    Root build — AGP + Kotlin + MAM plugin versions
├── settings.gradle                 Module list + Maven repos (including Intune feed)
├── gradle/wrapper/
│   └── gradle-wrapper.properties
├── app/
│   ├── build.gradle                App-level build
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   ├── index.html          Chat UI shell
│       │   └── chat.bundle.js      Built by webchat/ (run npm run build first)
│       ├── res/
│       │   ├── layout/activity_main.xml
│       │   └── raw/msal_config.json
│       └── java/com/contoso/mdmtest/
│           ├── App.kt              Application — MAM SDK init, auth callback registration
│           ├── MainActivity.kt     Auth flow + CA remediation + WebView token injection
│           ├── MsalClient.kt       MSAL PCA wrapper (shared between Activity + MAMAuthCallback)
│           ├── MAMAuthCallback.kt  MAM SDK token callback (synchronous, background thread)
│           └── TokenBridge.kt      JS→Native bridge (token refresh, logging)
└── webchat/
    ├── package.json                npm project — esbuild bundles into assets/
    └── src/
        └── chat.js                 CopilotStudioClient chat logic
```

---

## Key design decisions

| Decision | Reason |
|---|---|
| Single Activity, no Compose | Simplest testable structure — no nav graph, no state hoisting |
| WebView for chat UI | CopilotStudioClient is a JS library — WebView avoids React Native overhead |
| Token injected via `evaluateJavascript` | No persistent storage of token; fresh on every native re-acquisition |
| `acquireTokenSilent` retry only after `ENROLLMENT_SUCCEEDED` | Retrying earlier = enrollment ID not yet in broker store = CA still fails |
| `remediateCompliance()` params from exception, not hardcoded | OID/UPN mismatch is the #1 cause of "enrollment id missing" |
| `CountDownLatch` in MAMAuthCallback | Callback runs on background thread; MSAL silent acquisition is async — latch bridges the gap |

---

## References

- [Intune MAM SDK Android — Phase 2 (MSAL)](https://learn.microsoft.com/en-us/intune/intune-service/developer/app-sdk-android-phase2)
- [Intune MAM SDK Android — Phase 4 (Enrollment)](https://learn.microsoft.com/en-us/mem/intune/developer/app-sdk-android-phase4)
- [Intune MAM SDK Android — Phase 7 (App Protection CA)](https://learn.microsoft.com/en-us/intune/intune-service/developer/app-sdk-android-phase7)
- [M365 Agents SDK — CopilotStudioClient](https://github.com/microsoft/agents)
- [MAM SDK Android GitHub](https://github.com/microsoftconnect/ms-intune-app-sdk-android)
