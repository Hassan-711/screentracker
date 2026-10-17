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

CREATE TABLE IF NOT EXISTS public.device_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id UUID NOT NULL REFERENCES public.registered_devices(id) ON DELETE CASCADE,
    sender_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    sender_email TEXT NOT NULL DEFAULT '',
    sender_role TEXT NOT NULL DEFAULT 'viewer',
    message_text TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now())
);

CREATE INDEX IF NOT EXISTS idx_device_messages_device_created 
ON public.device_messages(device_id, created_at DESC);

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

    -- 6. Delete old paired chat messages (> 7 days)
    DELETE FROM public.device_messages
    WHERE created_at < (timezone('utc'::text, now()) - INTERVAL '7 days');

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
ALTER TABLE public.device_messages ENABLE ROW LEVEL SECURITY;

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

-- 10. DEVICE MESSAGES POLICIES
DROP POLICY IF EXISTS "Authorized users can view messages for device" ON public.device_messages;
CREATE POLICY "Authorized users can view messages for device"
    ON public.device_messages FOR SELECT
    USING (public.is_authorized_viewer(device_id));

DROP POLICY IF EXISTS "Authorized users can insert messages for device" ON public.device_messages;
CREATE POLICY "Authorized users can insert messages for device"
    ON public.device_messages FOR INSERT
    WITH CHECK (
        auth.uid() = sender_id 
        AND public.is_authorized_viewer(device_id)
    );

-- ==============================================================================
-- ScreenTrack — Secure Multi-Device Pairing Workflow Stored Functions (RPC)
-- File: supabase/functions/pairing_functions.sql
-- ==============================================================================

-- 1. Helper to generate secure random 6-character code (alphanumeric uppercase, no 0, O, 1, I, L)
CREATE OR REPLACE FUNCTION public.generate_secure_random_code(length INT DEFAULT 6)
RETURNS TEXT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions, auth, pg_temp
AS $$
DECLARE
    chars TEXT := '23456789ABCDEFGHJKMNPQRSTUVWXYZ';
    result TEXT := '';
    i INT;
BEGIN
    FOR i IN 1..length LOOP
        result := result || substr(chars, floor(random() * length(chars))::int + 1, 1);
    END LOOP;
    RETURN result;
END;
$$;

-- 2. GENERATE PAIRING CODE (Called by Phone Owner on Phone 1, Phone 2, etc.)
CREATE OR REPLACE FUNCTION public.generate_pairing_code(p_device_id UUID)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth, pg_temp
AS $$
DECLARE
    v_owner_id UUID;
    v_code VARCHAR(8);
    v_expires_at TIMESTAMPTZ;
    v_new_id UUID;
BEGIN
    v_owner_id := auth.uid();
    IF v_owner_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    -- Verify caller is registered owner of this device
    IF NOT EXISTS (
        SELECT 1 FROM public.registered_devices 
        WHERE id = p_device_id AND owner_id = v_owner_id
    ) THEN
        RAISE EXCEPTION 'Device not found or caller is not device owner';
    END IF;

    -- Invalidate any existing unused pairing codes for this specific device
    UPDATE public.pairing_codes
    SET is_used = true
    WHERE device_id = p_device_id AND is_used = false;

    -- Generate a distinct 6-character code
    v_code := public.generate_secure_random_code(6);
    v_expires_at := timezone('utc'::text, now()) + INTERVAL '10 minutes';

    INSERT INTO public.pairing_codes (
        device_id,
        owner_id,
        code,
        expires_at,
        is_used,
        attempts_count
    ) VALUES (
        p_device_id,
        v_owner_id,
        v_code,
        v_expires_at,
        false,
        0
    ) RETURNING id INTO v_new_id;

    RETURN jsonb_build_object(
        'success', true,
        'pairing_code_id', v_new_id,
        'code', v_code,
        'expires_at', v_expires_at,
        'validity_seconds', 600
    );
END;
$$;

-- 3. SUBMIT PAIRING REQUEST (Called by Remote Viewer for each of Person A's phones)
CREATE OR REPLACE FUNCTION public.submit_pairing_request(p_code VARCHAR)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth, pg_temp
AS $$
DECLARE
    v_viewer_id UUID;
    v_viewer_email TEXT;
    v_clean_code VARCHAR(16);
    v_pairing RECORD;
    v_device RECORD;
    v_failed_attempts INT;
    v_request_id UUID;
BEGIN
    v_viewer_id := auth.uid();
    IF v_viewer_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    -- Get viewer email
    SELECT email INTO v_viewer_email FROM auth.users WHERE id = v_viewer_id;
    IF v_viewer_email IS NULL THEN
        SELECT email INTO v_viewer_email FROM public.user_profiles WHERE id = v_viewer_id;
    END IF;
    IF v_viewer_email IS NULL THEN
        v_viewer_email := 'viewer_' || substr(v_viewer_id::text, 1, 8) || '@screentrack.app';
    END IF;

    v_clean_code := upper(trim(p_code));

    -- Rate-limiting: Max 10 failed attempts in the last 15 minutes
    SELECT COUNT(*) INTO v_failed_attempts
    FROM public.pairing_attempts_log
    WHERE viewer_id = v_viewer_id
       AND success = false
       AND attempted_at > timezone('utc'::text, now()) - INTERVAL '15 minutes';

    IF v_failed_attempts >= 10 THEN
        RAISE EXCEPTION 'Rate limit exceeded: too many failed pairing attempts. Please wait 15 minutes.';
    END IF;

    -- Lookup pairing code
    SELECT * INTO v_pairing
    FROM public.pairing_codes
    WHERE code = v_clean_code;

    -- 1. Invalid Code Check
    IF v_pairing IS NULL THEN
        INSERT INTO public.pairing_attempts_log (viewer_id, attempted_code, success, reason)
        VALUES (v_viewer_id, v_clean_code, false, 'Invalid code');
        RAISE EXCEPTION 'Invalid pairing code. Please double-check the 6-digit code on the target phone.';
    END IF;

    -- 2. Code already used
    IF v_pairing.is_used THEN
        INSERT INTO public.pairing_attempts_log (viewer_id, attempted_code, success, reason)
        VALUES (v_viewer_id, v_clean_code, false, 'Code already used');
        RAISE EXCEPTION 'This pairing code has already been used. Generate a fresh code on the phone.';
    END IF;

    -- 3. Code expired check
    IF v_pairing.expires_at < timezone('utc'::text, now()) THEN
        UPDATE public.pairing_codes SET is_used = true WHERE id = v_pairing.id;
        INSERT INTO public.pairing_attempts_log (viewer_id, attempted_code, success, reason)
        VALUES (v_viewer_id, v_clean_code, false, 'Code expired');
        RAISE EXCEPTION 'This pairing code has expired (valid for 10 minutes). Generate a new one.';
    END IF;

    -- 4. Already authorized active viewer check for this specific device
    IF EXISTS (
        SELECT 1 FROM public.authorized_device_viewers
        WHERE device_id = v_pairing.device_id
          AND viewer_id = v_viewer_id
          AND status = 'active'
    ) THEN
        RAISE EXCEPTION 'You are already connected to this phone.';
    END IF;

    -- Get device info
    SELECT * INTO v_device FROM public.registered_devices WHERE id = v_pairing.device_id;

    -- 5. Duplicate pending request check
    SELECT id INTO v_request_id
    FROM public.device_sharing_requests
    WHERE device_id = v_pairing.device_id
      AND viewer_id = v_viewer_id
      AND status = 'pending';

    IF v_request_id IS NOT NULL THEN
        RETURN jsonb_build_object(
            'success', true,
            'status', 'pending',
            'request_id', v_request_id,
            'device_name', v_device.device_name,
            'message', 'Request already pending approval on ' || v_device.device_name || '.'
        );
    END IF;

    -- Record successful submission in log
    INSERT INTO public.pairing_attempts_log (viewer_id, attempted_code, success, reason)
    VALUES (v_viewer_id, v_clean_code, true, 'Valid pairing request');

    -- Insert pending request
    INSERT INTO public.device_sharing_requests (
        pairing_code_id,
        device_id,
        owner_id,
        viewer_id,
        viewer_email,
        status,
        requested_at
    ) VALUES (
        v_pairing.id,
        v_pairing.device_id,
        v_pairing.owner_id,
        v_viewer_id,
        v_viewer_email,
        'pending',
        timezone('utc'::text, now())
    ) RETURNING id INTO v_request_id;

    -- Increment attempts count on the code
    UPDATE public.pairing_codes
    SET attempts_count = attempts_count + 1
    WHERE id = v_pairing.id;

    RETURN jsonb_build_object(
        'success', true,
        'status', 'pending',
        'request_id', v_request_id,
        'device_name', v_device.device_name,
        'device_model', v_device.device_model,
        'message', 'Request sent to ' || v_device.device_name || '! Approve it in the Sharing tab on that phone.'
    );
END;
$$;

-- 4. RESPOND TO PAIRING REQUEST (Called by Phone Owner)
CREATE OR REPLACE FUNCTION public.respond_to_pairing_request(
    p_request_id UUID,
    p_approved BOOLEAN
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth, pg_temp
AS $$
DECLARE
    v_owner_id UUID;
    v_req RECORD;
BEGIN
    v_owner_id := auth.uid();
    IF v_owner_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    SELECT * INTO v_req
    FROM public.device_sharing_requests
    WHERE id = p_request_id AND owner_id = v_owner_id;

    IF v_req IS NULL THEN
        RAISE EXCEPTION 'Pairing request not found or unauthorized';
    END IF;

    IF v_req.status != 'pending' THEN
        RAISE EXCEPTION 'This request has already been handled (status: %)', v_req.status;
    END IF;

    IF p_approved THEN
        UPDATE public.device_sharing_requests
        SET status = 'approved',
            responded_at = timezone('utc'::text, now())
        WHERE id = p_request_id;

        IF v_req.pairing_code_id IS NOT NULL THEN
            UPDATE public.pairing_codes
            SET is_used = true
            WHERE id = v_req.pairing_code_id;

            UPDATE public.device_sharing_requests
            SET status = 'expired',
                responded_at = timezone('utc'::text, now())
            WHERE pairing_code_id = v_req.pairing_code_id
              AND id != p_request_id
              AND status = 'pending';
        END IF;

        INSERT INTO public.authorized_device_viewers (
            device_id,
            owner_id,
            viewer_id,
            viewer_email,
            status,
            granted_at,
            revoked_at
        ) VALUES (
            v_req.device_id,
            v_req.owner_id,
            v_req.viewer_id,
            v_req.viewer_email,
            'active',
            timezone('utc'::text, now()),
            NULL
        )
        ON CONFLICT (device_id, viewer_id)
        DO UPDATE SET
            status = 'active',
            granted_at = timezone('utc'::text, now()),
            revoked_at = NULL;

        RETURN jsonb_build_object(
            'success', true,
            'action', 'approved',
            'request_id', p_request_id,
            'viewer_email', v_req.viewer_email
        );
    ELSE
        UPDATE public.device_sharing_requests
        SET status = 'rejected',
            responded_at = timezone('utc'::text, now())
        WHERE id = p_request_id;

        RETURN jsonb_build_object(
            'success', true,
            'action', 'rejected',
            'request_id', p_request_id
        );
    END IF;
END;
$$;

-- 5. REVOKE VIEWER ACCESS (Called by Phone Owner)
CREATE OR REPLACE FUNCTION public.revoke_viewer_access(
    p_device_id UUID,
    p_viewer_id UUID
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth, pg_temp
AS $$
DECLARE
    v_owner_id UUID;
    v_count INT;
BEGIN
    v_owner_id := auth.uid();
    IF v_owner_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    UPDATE public.authorized_device_viewers
    SET status = 'revoked',
        revoked_at = timezone('utc'::text, now())
    WHERE device_id = p_device_id
      AND viewer_id = p_viewer_id
      AND owner_id = v_owner_id;

    GET DIAGNOSTICS v_count = ROW_COUNT;

    IF v_count = 0 THEN
        RAISE EXCEPTION 'Authorization record not found or caller is not device owner';
    END IF;

    RETURN jsonb_build_object(
        'success', true,
        'message', 'Viewer access revoked immediately'
    );
END;
$$;

-- 6. UNPAIR REMOTE DEVICE (Called by Remote Viewer Person B to remove tracked phone)
CREATE OR REPLACE FUNCTION public.unpair_remote_device(p_device_id UUID)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth, pg_temp
AS $$
DECLARE
    v_viewer_id UUID;
BEGIN
    v_viewer_id := auth.uid();
    IF v_viewer_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    DELETE FROM public.authorized_device_viewers
    WHERE device_id = p_device_id AND viewer_id = v_viewer_id;

    DELETE FROM public.device_sharing_requests
    WHERE device_id = p_device_id AND viewer_id = v_viewer_id;

    RETURN jsonb_build_object(
        'success', true,
        'message', 'Device un-paired successfully'
    );
END;
$$;

-- ==============================================================================
-- FUNCTION EXECUTE PRIVILEGES
-- ==============================================================================
REVOKE ALL ON FUNCTION public.generate_secure_random_code(INT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.generate_pairing_code(UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.submit_pairing_request(VARCHAR) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.respond_to_pairing_request(UUID, BOOLEAN) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.revoke_viewer_access(UUID, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.unpair_remote_device(UUID) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.generate_pairing_code(UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.submit_pairing_request(VARCHAR) TO authenticated;
GRANT EXECUTE ON FUNCTION public.respond_to_pairing_request(UUID, BOOLEAN) TO authenticated;
GRANT EXECUTE ON FUNCTION public.revoke_viewer_access(UUID, UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.unpair_remote_device(UUID) TO authenticated;
