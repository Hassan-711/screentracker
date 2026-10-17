# ScreenTrack v2.0 — Lightweight Android Live Screen-Time Sharing & Multi-Phone Remote Viewer

ScreenTrack is a lightweight, native Android Studio application (**Kotlin**, **Jetpack Compose**, **Material 3**, **UsageStatsManager**, **WorkManager**) paired with **Supabase** for live screen-time sharing across **2 or more phones**.

---

## What Was Fixed & Upgraded in v2.0

1. **Fixed Second-Launch Crash (`100% Crash-Free Startup & Background Sync`)**:
   - Removed the heavyweight `supabase-kt` + `ktor` multiplatform dependency chain that caused `SharedPreferences` / initialization crashes after first launch and bloated the APK.
   - Replaced it with a lightweight, zero-crash native `HttpURLConnection` REST client with automatic JWT token refresh.
   - Added missing semantic color constants (`AccentEmerald`, `AccentRose`, `AccentAmber`, `AccentBlue`) in `Color.kt`.
   - Stabilized `NavHost` `startDestination` across lifecycle `ON_RESUME` events and guarded all `LinearProgressIndicator` progress values against `Float.NaN`.
   - Added Android 14/15 (`targetSdk 35`) `ForegroundService` safety guards (`START_NOT_STICKY` + `POST_NOTIFICATIONS` runtime check).

2. **Fixed Digital Wellbeing Screen-Time Mismatch (`UsageStatsRepository.kt`)**:
   - **Activity-Level Tracking (`packageName` + `className`)**: In Android, when navigating between two screens (`Activity A` -> `Activity B`) inside the same app (e.g., opening a chat in WhatsApp or Reels in Instagram), `Activity B` logs `ACTIVITY_RESUMED` *before* `Activity A` logs `ACTIVITY_PAUSED` (~100–300ms later). The old code only tracked `packageName`, so `Activity A`'s delayed `ACTIVITY_PAUSED` prematurely terminated the app's active session! v2.0 tracks `(packageName, className)` so internal screen transitions never cut off ongoing usage.
   - **Eliminated UTC Bucket Bleed-Over**: Removed the buggy merge with `queryUsageStats(INTERVAL_DAILY)` which was pulling yesterday's UTC usage bucket into today's screen time and doubling 7-day totals.
   - **Midnight (`00:00`) Rollover Support**: Looks back 2 hours prior to midnight and clamps ongoing sessions to `00:00:00` local time.
   - **Excluded System Launchers, SystemUI & Keyboards**: Added `QUERY_ALL_PACKAGES` and `<queries>` in `AndroidManifest.xml` to dynamically detect and exclude the default Home Launcher, `com.android.systemui`, lockscreen, and keyboards—matching Android's built-in Digital Wellbeing.

3. **Added Dedicated Multi-Phone Remote Viewer Inside the Android App (`RemoteMonitorScreen.kt`)**:
   - **Person B** can now monitor **2 or more phones of Person A** directly inside the Android app under the **Remote View** tab.
   - Supports **separate 6-digit pairing codes** for each of Person A's phones.
   - Displays a **Combined All-Phones Overview Card** (showing total screen time across all of Person A's phones + live status of each phone at a glance) AND **individual phone switcher chips** (`Phone 1`, `Phone 2`, etc.).
   - Shows **Live Active App right now**, active session duration, battery percentage, **Today's App Usage Breakdown**, and **7-Day History** (tap any day bar to see that day's apps) for each remote phone.

4. **Fixed Supabase Repeat-Sync Upsert Bug & Added Automatic 7-Day Cleanup**:
   - Added explicit `?on_conflict=device_id,date` and `?on_conflict=device_id,date,package_name` batch upserts so 10-second live syncs never fail with duplicate-key errors.
   - Added automatic **7-Day History Cleanup** (`cleanup_old_screen_time_data()` SQL trigger + RPC) that automatically deletes usage records older than 7 days to keep your free Supabase database and Android app fast and lightweight forever.

---

## Step-by-Step Guide: How to Connect Supabase (Takes 3 Minutes)

### Step 1: Create a Free Supabase Project
1. Go to [https://supabase.com](https://supabase.com) and sign in.
2. Click **New Project**, choose any name (e.g., `ScreenTrack`), set a database password, and select the region closest to you (e.g., **Mumbai `ap-south-1`**).

### Step 2: Run the One-Click SQL Setup Script
1. In your Supabase Dashboard left sidebar, click **SQL Editor** -> **New Query**.
2. Open the file `supabase/FULL_SUPABASE_SETUP.sql` from this project, copy its entire contents, paste it into the Supabase SQL Editor, and click **Run**.
   *(This creates all tables, Row-Level Security policies, 6-digit pairing code functions, and the automatic >7-day history cleanup trigger).*

### Step 3: Disable "Confirm Email" for Instant Sign-Up (Recommended)
1. In your Supabase Dashboard left sidebar, go to **Authentication** -> **Providers** -> **Email**.
2. Toggle **OFF** `"Confirm email"` and click **Save**.
   *(This allows Person A and Person B to register and sign in immediately from the Android app without waiting for verification emails).*

### Step 4: Copy Your Supabase URL & Anon Key
1. In your Supabase Dashboard left sidebar, go to **Project Settings (gear icon)** -> **API**.
2. Copy two values:
   - **Project URL** (looks like `https://abcdefghijklm.supabase.co`)
   - **Project API Keys -> `anon` `public` key** (looks like `eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...`)

### Step 5: Connect the Android App to Supabase (Two Easy Ways)
* **Option A (Before building APK in Android Studio)**:
  Create a file named `local.properties` inside the `android/` folder and add:
  ```properties
  SUPABASE_URL=https://YOUR_PROJECT_ID.supabase.co
  SUPABASE_ANON_KEY=YOUR_SUPABASE_ANON_PUBLIC_KEY
  ```
* **Option B (Directly inside the installed APK on your phone)**:
  Open the app on your phone -> go to the **Settings** tab -> tap **Configure** under **1. Supabase Backend Connection** -> paste your Project URL and Anon Key -> tap **Save**.

---

## How to Pair Person A's 2 Phones with Person B's Phone

1. **On Person A's Phone 1**:
   - Install and open the app, grant **Usage Access**, and sign in (under **Settings -> Sign In**).
   - On the **My Phone** tab, turn ON **Live 10-Second Broadcast Mode**.
   - Go to the **Share Code** tab -> tap the edit icon next to **This Phone's Label** and name it `"Phone 1 (Primary)"`.
   - Tap **Generate 6-Digit Pairing Code** (e.g., `7K2M9X`).

2. **On Person A's Phone 2**:
   - Install and open the app, grant **Usage Access**, and sign in.
   - On the **My Phone** tab, turn ON **Live 10-Second Broadcast Mode**.
   - Go to the **Share Code** tab -> rename the phone to `"Phone 2 (Secondary)"`.
   - Tap **Generate 6-Digit Pairing Code** (e.g., `9P4R2W`).

3. **On Person B's Phone (Remote Viewer)**:
   - Install the same APK and sign in with Person B's account.
   - Open the **Remote View** tab -> tap **Pair Phone** in the top-right corner.
   - Enter Phone 1's 6-digit code (`7K2M9X`) -> tap **Send Pairing Request**.
   - Enter Phone 2's 6-digit code (`9P4R2W`) -> tap **Send Pairing Request**.

4. **Approve on Person A's Phones**:
   - On Person A's Phone 1 and Phone 2, under the **Share Code** tab, tap the green **Checkmark (Approve)** button next to Person B's request.
   - Person B's **Remote View** tab will immediately show **both phones live** (combined screen time, live active app on each phone, today's app breakdown, and 7-day history)!

---

## Building the APK in Android Studio

1. Open **Android Studio** -> click **Open** -> select the **`android`** folder inside this zip.
2. Wait for Gradle sync to finish.
3. Click **Build -> Build Bundle(s) / APK(s) -> Build APK(s)** (or run `./gradlew assembleDebug` inside the `android/` folder).
4. The generated APK will be located at:
   `android/app/build/outputs/apk/debug/app-debug.apk`
