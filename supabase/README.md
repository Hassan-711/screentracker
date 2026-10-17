# ScreenTrack — Supabase Backend Setup

This folder contains the complete, production-grade PostgreSQL database schema, Row-Level Security (RLS) policies, and secure server-side stored procedures for **ScreenTrack**.

## Setup Instructions

1. **Create a Supabase Project**:
   - Go to [Supabase Console](https://supabase.com/dashboard) and create a new project.
   - Note your **Project URL** and **anon public key** from `Settings` -> `API`.

2. **Run Migrations**:
   - Navigate to the **SQL Editor** in your Supabase dashboard.
   - First, run the contents of `migrations/20261010000000_screentrack_schema.sql` to create all tables, indexes, and RLS policies.
   - Second, run the contents of `functions/pairing_functions.sql` to create the pairing workflow RPC functions (`generate_pairing_code`, `submit_pairing_request`, `respond_to_pairing_request`, `revoke_viewer_access`).

3. **Enable Realtime**:
   - Go to `Database` -> `Replication` in your Supabase dashboard.
   - Confirm that the `device_sync_status`, `daily_screen_time_summaries`, `device_sharing_requests`, and `authorized_device_viewers` tables are in the `supabase_realtime` publication (this is pre-configured in the migration script).

4. **Environment Variables**:
   - In the Android app (`local.properties` or `gradle.properties`):
     ```properties
     SUPABASE_URL=https://your-project-id.supabase.co
     SUPABASE_ANON_KEY=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
     ```
   - In the Next.js / Web remote viewer dashboard (`.env.local`):
     ```bash
     VITE_SUPABASE_URL=https://your-project-id.supabase.co
     VITE_SUPABASE_ANON_KEY=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
     ```

## Security Principles

- **Single-use pairing codes**: Stored in `pairing_codes` with 10-minute expiry (`INTERVAL '10 minutes'`). Codes cannot be reused once approved or expired.
- **Server-side validation**: `submit_pairing_request` enforces rate-limiting (max 5 failed attempts per 15 minutes), checks code expiration, prevents self-pairing, and verifies caller identity.
- **No service-role keys**: The Android app only uses the public anonymous key (`anon_key`) and the user's authenticated JWT session.
- **RLS Enforced**: Authorized viewers can only read device screen time if a corresponding row with `status = 'active'` exists in `authorized_device_viewers`. Viewers can never modify data or view unshared devices.
