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
