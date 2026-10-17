-- ==============================================================================
-- ScreenTrack — Production Supabase Schema, RLS & 7-Day Auto-Cleanup
-- Migration: 20261010000000_screentrack_schema.sql
-- ==============================================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- 1. USER PROFILES
CREATE TABLE IF NOT EXISTS public.user_profiles (
    id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    email TEXT NOT NULL,
    display_name TEXT,
    avatar_url TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now()),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now())
);

-- 2. REGISTERED DEVICES (owned by the phone user; 1 owner can register 2+ phones)
CREATE TABLE IF NOT EXISTS public.registered_devices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    device_name TEXT NOT NULL,
    device_model TEXT NOT NULL,
    os_version TEXT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT true,
    sync_frequency_minutes INT NOT NULL DEFAULT 15,
    last_sync_at TIMESTAMPTZ DEFAULT timezone('utc'::text, now()),
    created_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now()),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now())
);

CREATE INDEX IF NOT EXISTS idx_registered_devices_owner ON public.registered_devices(owner_id);

-- 3. PAIRING CODES (Short-lived, single-use, rate-limited, per device)
CREATE TABLE IF NOT EXISTS public.pairing_codes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id UUID NOT NULL REFERENCES public.registered_devices(id) ON DELETE CASCADE,
    owner_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    code VARCHAR(8) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    is_used BOOLEAN NOT NULL DEFAULT false,
    attempts_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now())
);

CREATE INDEX IF NOT EXISTS idx_pairing_codes_code ON public.pairing_codes(code);
CREATE INDEX IF NOT EXISTS idx_pairing_codes_device ON public.pairing_codes(device_id);

-- 4. PAIRING ATTEMPTS AUDIT LOG (Prevents rate-limit bypasses on code guessing)
CREATE TABLE IF NOT EXISTS public.pairing_attempts_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    viewer_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    attempted_code VARCHAR(16) NOT NULL,
    success BOOLEAN NOT NULL DEFAULT false,
    reason TEXT,
    attempted_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now())
);

CREATE INDEX IF NOT EXISTS idx_pairing_attempts_viewer ON public.pairing_attempts_log(viewer_id, attempted_at DESC);

-- 5. DEVICE SHARING REQUESTS (Viewer attempts pairing -> Owner approves/rejects)
CREATE TABLE IF NOT EXISTS public.device_sharing_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pairing_code_id UUID REFERENCES public.pairing_codes(id) ON DELETE SET NULL,
    device_id UUID NOT NULL REFERENCES public.registered_devices(id) ON DELETE CASCADE,
    owner_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    viewer_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    viewer_email TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'rejected', 'expired')),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now()),
    responded_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_sharing_requests_owner ON public.device_sharing_requests(owner_id);
CREATE INDEX IF NOT EXISTS idx_sharing_requests_viewer ON public.device_sharing_requests(viewer_id);
CREATE INDEX IF NOT EXISTS idx_sharing_requests_device ON public.device_sharing_requests(device_id);

-- 6. AUTHORIZED DEVICE VIEWERS (Active permissions granted by owner; supports 2+ devices per viewer)
CREATE TABLE IF NOT EXISTS public.authorized_device_viewers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id UUID NOT NULL REFERENCES public.registered_devices(id) ON DELETE CASCADE,
    owner_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    viewer_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    viewer_email TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'revoked')),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now()),
    revoked_at TIMESTAMPTZ,
    CONSTRAINT unique_device_viewer UNIQUE (device_id, viewer_id)
);

CREATE INDEX IF NOT EXISTS idx_authorized_viewers_viewer ON public.authorized_device_viewers(viewer_id);
CREATE INDEX IF NOT EXISTS idx_authorized_viewers_device ON public.authorized_device_viewers(device_id);

-- 7. DAILY SCREEN-TIME SUMMARIES (Aggregated daily totals, rolling 7-day window)
CREATE TABLE IF NOT EXISTS public.daily_screen_time_summaries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id UUID NOT NULL REFERENCES public.registered_devices(id) ON DELETE CASCADE,
    date DATE NOT NULL,
    total_screen_time_ms BIGINT NOT NULL DEFAULT 0,
    most_used_app_package TEXT,
    most_used_app_name TEXT,
    app_count INT NOT NULL DEFAULT 0,
    sync_timestamp TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now()),
    created_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now()),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now()),
    CONSTRAINT unique_device_date UNIQUE (device_id, date)
);

CREATE INDEX IF NOT EXISTS idx_daily_summaries_device_date ON public.daily_screen_time_summaries(device_id, date DESC);

-- 8. APP USAGE SUMMARIES (Per-app breakdown per day, rolling 7-day window)
CREATE TABLE IF NOT EXISTS public.app_usage_summaries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    daily_summary_id UUID REFERENCES public.daily_screen_time_summaries(id) ON DELETE CASCADE,
    device_id UUID NOT NULL REFERENCES public.registered_devices(id) ON DELETE CASCADE,
    date DATE NOT NULL,
    package_name TEXT NOT NULL,
    app_name TEXT NOT NULL,
    usage_duration_ms BIGINT NOT NULL DEFAULT 0,
    usage_percentage NUMERIC(5,2) NOT NULL DEFAULT 0.00,
    last_time_used TIMESTAMPTZ,
    last_time_used_ms BIGINT DEFAULT 0,
    category TEXT DEFAULT 'Application',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now()),
    CONSTRAINT unique_device_date_pkg UNIQUE (device_id, date, package_name)
);

ALTER TABLE public.app_usage_summaries ADD COLUMN IF NOT EXISTS last_time_used_ms BIGINT DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_app_usage_device_date ON public.app_usage_summaries(device_id, date DESC);

-- 9. DEVICE SYNC STATUS (Live heartbeat & current foreground state)
CREATE TABLE IF NOT EXISTS public.device_sync_status (
    device_id UUID PRIMARY KEY REFERENCES public.registered_devices(id) ON DELETE CASCADE,
    current_foreground_app TEXT,
    current_foreground_package TEXT,
    last_observed_app TEXT,
    last_observed_package TEXT,
    current_session_duration_ms BIGINT DEFAULT 0,
    is_screen_on BOOLEAN DEFAULT true,
    battery_level INT DEFAULT 100,
    last_heartbeat_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now()),
    last_sync_success BOOLEAN NOT NULL DEFAULT true,
    last_sync_error TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now())
);

-- ==============================================================================
-- AUTOMATIC 7-DAY HISTORY RETENTION & CLEANUP
-- Automatically purges usage history older than 7 days to keep DB lightweight
-- ==============================================================================

CREATE OR REPLACE FUNCTION public.cleanup_old_screen_time_data()
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_deleted_daily INT := 0;
    v_deleted_apps INT := 0;
    v_deleted_codes INT := 0;
BEGIN
    -- 1. Delete app usage summaries older than 7 days
    DELETE FROM public.app_usage_summaries
    WHERE date < (CURRENT_DATE - INTERVAL '7 days');
    GET DIAGNOSTICS v_deleted_apps = ROW_COUNT;

    -- 2. Delete daily screen time summaries older than 7 days
    DELETE FROM public.daily_screen_time_summaries
    WHERE date < (CURRENT_DATE - INTERVAL '7 days');
    GET DIAGNOSTICS v_deleted_daily = ROW_COUNT;

    -- 3. Delete expired or used pairing codes older than 24 hours
    DELETE FROM public.pairing_codes
    WHERE expires_at < (timezone('utc'::text, now()) - INTERVAL '24 hours')
       OR (is_used = true AND created_at < (timezone('utc'::text, now()) - INTERVAL '24 hours'));
    GET DIAGNOSTICS v_deleted_codes = ROW_COUNT;

    -- 4. Delete old pairing attempt audit logs (> 7 days)
    DELETE FROM public.pairing_attempts_log
    WHERE attempted_at < (timezone('utc'::text, now()) - INTERVAL '7 days');

    -- 5. Delete handled/expired sharing requests (> 7 days)
    DELETE FROM public.device_sharing_requests
    WHERE status IN ('approved', 'rejected', 'expired')
      AND requested_at < (timezone('utc'::text, now()) - INTERVAL '7 days');

    RETURN jsonb_build_object(
        'success', true,
        'deleted_daily_summaries', v_deleted_daily,
        'deleted_app_summaries', v_deleted_apps,
        'deleted_old_codes', v_deleted_codes
    );
END;
$$;

CREATE OR REPLACE FUNCTION public.trigger_cleanup_old_screen_time_data()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    PERFORM public.cleanup_old_screen_time_data();
    RETURN NULL;
END;
$$;

DROP TRIGGER IF EXISTS trg_auto_cleanup_7_days ON public.daily_screen_time_summaries;
CREATE TRIGGER trg_auto_cleanup_7_days
    AFTER INSERT ON public.daily_screen_time_summaries
    FOR EACH STATEMENT
    EXECUTE FUNCTION public.trigger_cleanup_old_screen_time_data();

GRANT EXECUTE ON FUNCTION public.cleanup_old_screen_time_data() TO authenticated;

-- ==============================================================================
-- ROW-LEVEL SECURITY (RLS) POLICIES
-- ==============================================================================

ALTER TABLE public.user_profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.registered_devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.pairing_codes ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.pairing_attempts_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.device_sharing_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.authorized_device_viewers ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.daily_screen_time_summaries ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.app_usage_summaries ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.device_sync_status ENABLE ROW LEVEL SECURITY;

-- Helper function to check if auth.uid() is an active authorized viewer OR owner for a device
CREATE OR REPLACE FUNCTION public.is_authorized_viewer(p_device_id UUID)
RETURNS BOOLEAN
LANGUAGE sql
SECURITY DEFINER
STABLE
SET search_path = public, auth, pg_temp
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM public.authorized_device_viewers
        WHERE device_id = p_device_id
          AND viewer_id = auth.uid()
          AND status = 'active'
    ) OR EXISTS (
        SELECT 1
        FROM public.registered_devices
        WHERE id = p_device_id
          AND owner_id = auth.uid()
    );
$$;

-- 1. USER PROFILES POLICIES
DROP POLICY IF EXISTS "Users can view their own profile" ON public.user_profiles;
CREATE POLICY "Users can view their own profile"
    ON public.user_profiles FOR SELECT
    USING (auth.uid() = id);

DROP POLICY IF EXISTS "Users can update their own profile" ON public.user_profiles;
CREATE POLICY "Users can update their own profile"
    ON public.user_profiles FOR UPDATE
    USING (auth.uid() = id);

DROP POLICY IF EXISTS "Users can insert their own profile" ON public.user_profiles;
CREATE POLICY "Users can insert their own profile"
    ON public.user_profiles FOR INSERT
    WITH CHECK (auth.uid() = id);

CREATE OR REPLACE FUNCTION public.handle_new_user()
RETURNS trigger AS $$
BEGIN
  INSERT INTO public.user_profiles (id, email, display_name)
  VALUES (
    new.id, 
    new.email, 
    COALESCE(new.raw_user_meta_data->>'display_name', split_part(new.email, '@', 1))
  )
  ON CONFLICT (id) DO NOTHING;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, auth, pg_temp;

DROP TRIGGER IF EXISTS on_auth_user_created ON auth.users;
CREATE TRIGGER on_auth_user_created
  AFTER INSERT ON auth.users
  FOR EACH ROW EXECUTE FUNCTION public.handle_new_user();

-- 2. REGISTERED DEVICES POLICIES
DROP POLICY IF EXISTS "Owners can manage their devices" ON public.registered_devices;
CREATE POLICY "Owners can manage their devices"
    ON public.registered_devices FOR ALL
    USING (auth.uid() = owner_id);

DROP POLICY IF EXISTS "Authorized viewers can view device info" ON public.registered_devices;
CREATE POLICY "Authorized viewers can view device info"
    ON public.registered_devices FOR SELECT
    USING (public.is_authorized_viewer(id));

-- 3. PAIRING CODES POLICIES
DROP POLICY IF EXISTS "Only device owner can access pairing codes" ON public.pairing_codes;
CREATE POLICY "Only device owner can access pairing codes"
    ON public.pairing_codes FOR ALL
    USING (auth.uid() = owner_id);

-- 4. PAIRING ATTEMPTS LOG POLICIES
DROP POLICY IF EXISTS "Viewers can view their own attempt logs" ON public.pairing_attempts_log;
CREATE POLICY "Viewers can view their own attempt logs"
    ON public.pairing_attempts_log FOR SELECT
    USING (auth.uid() = viewer_id);

-- 5. DEVICE SHARING REQUESTS POLICIES
DROP POLICY IF EXISTS "Owners can view and respond to sharing requests" ON public.device_sharing_requests;
CREATE POLICY "Owners can view and respond to sharing requests"
    ON public.device_sharing_requests FOR ALL
    USING (auth.uid() = owner_id);

DROP POLICY IF EXISTS "Viewers can view requests they sent" ON public.device_sharing_requests;
CREATE POLICY "Viewers can view requests they sent"
    ON public.device_sharing_requests FOR SELECT
    USING (auth.uid() = viewer_id);

-- 6. AUTHORIZED DEVICE VIEWERS POLICIES
DROP POLICY IF EXISTS "Owners can view and manage authorized viewers" ON public.authorized_device_viewers;
CREATE POLICY "Owners can view and manage authorized viewers"
    ON public.authorized_device_viewers FOR ALL
    USING (auth.uid() = owner_id);

DROP POLICY IF EXISTS "Viewers can see their own authorization records" ON public.authorized_device_viewers;
CREATE POLICY "Viewers can see their own authorization records"
    ON public.authorized_device_viewers FOR SELECT
    USING (auth.uid() = viewer_id);

-- 7. DAILY SUMMARIES POLICIES
DROP POLICY IF EXISTS "Device owners have full access to daily summaries" ON public.daily_screen_time_summaries;
CREATE POLICY "Device owners have full access to daily summaries"
    ON public.daily_screen_time_summaries FOR ALL
    USING (
        EXISTS (
            SELECT 1 FROM public.registered_devices
            WHERE id = daily_screen_time_summaries.device_id
              AND owner_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "Authorized viewers can view daily summaries" ON public.daily_screen_time_summaries;
CREATE POLICY "Authorized viewers can view daily summaries"
    ON public.daily_screen_time_summaries FOR SELECT
    USING (public.is_authorized_viewer(device_id));

-- 8. APP USAGE SUMMARIES POLICIES
DROP POLICY IF EXISTS "Device owners have full access to app usage summaries" ON public.app_usage_summaries;
CREATE POLICY "Device owners have full access to app usage summaries"
    ON public.app_usage_summaries FOR ALL
    USING (
        EXISTS (
            SELECT 1 FROM public.registered_devices
            WHERE id = app_usage_summaries.device_id
              AND owner_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "Authorized viewers can view app usage summaries" ON public.app_usage_summaries;
CREATE POLICY "Authorized viewers can view app usage summaries"
    ON public.app_usage_summaries FOR SELECT
    USING (public.is_authorized_viewer(device_id));

-- 9. DEVICE SYNC STATUS POLICIES
DROP POLICY IF EXISTS "Device owners have full access to device sync status" ON public.device_sync_status;
CREATE POLICY "Device owners have full access to device sync status"
    ON public.device_sync_status FOR ALL
    USING (
        EXISTS (
            SELECT 1 FROM public.registered_devices
            WHERE id = device_sync_status.device_id
              AND owner_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "Authorized viewers can view device sync status" ON public.device_sync_status;
CREATE POLICY "Authorized viewers can view device sync status"
    ON public.device_sync_status FOR SELECT
    USING (public.is_authorized_viewer(device_id));
