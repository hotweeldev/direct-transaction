package id.co.bni.direct.transaction.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Refuses to start on the two configurations in which RBAC enforcement cannot do its job,
 * and warns on the one where it merely will not be reached. Faithful copy of
 * direct-admin's check of the same name (per-repo copies are the house pattern).
 *
 * <p><b>Why boot and not the first request.</b> With
 * {@code app.umas.authorization.enabled=true} and {@code app.umas.auth.enabled=false}
 * there is no verified subject on any request, so every annotated endpoint denies —
 * correctly, fail-closed, but the whole service is then a 403 machine. Discovering that
 * from a support ticket is worse than discovering it from a stack trace at deploy time,
 * and the alternative — waving requests through when there is no subject — is exactly the
 * fail-open path §7 forbids. Local development is unaffected: enforcement defaults off,
 * which is the sanctioned off switch, and turning it on locally simply requires turning
 * bearer validation on too.
 *
 * <p>The scope check is the same argument. The corporate users this service serves hold
 * {@code POSTLOGIN} tokens; a {@code required-scope} of {@code BANK_STAFF} rejects every
 * one of them at the filter with 403 {@code FORBIDDEN_SCOPE} before enforcement is ever
 * consulted.
 */
public class UmasEnforcementStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(UmasEnforcementStartupCheck.class);

    /** The scope a corporate user's token actually carries. */
    static final String CORPORATE_SCOPE = "POSTLOGIN";

    public UmasEnforcementStartupCheck(UmasAuthorizationProperties authorization, UmasAuthProperties auth) {
        if (!authorization.isEnabled()) {
            if (auth.isEnabled() && !CORPORATE_SCOPE.equals(auth.getRequiredScope())) {
                log.warn("app.umas.auth.required-scope={} but this service's callers are corporate "
                                + "users holding {} tokens - every one of them will be "
                                + "rejected with FORBIDDEN_SCOPE.",
                        auth.getRequiredScope(), CORPORATE_SCOPE);
            }
            return;
        }
        if (!auth.isEnabled()) {
            throw new IllegalStateException(
                    "app.umas.authorization.enabled=true requires app.umas.auth.enabled=true: "
                            + "without bearer validation no request carries a verified subject, so "
                            + "every guarded endpoint would deny. Turn bearer validation on, or turn "
                            + "enforcement off - never let an unverified caller through.");
        }
        if (!CORPORATE_SCOPE.equals(auth.getRequiredScope())) {
            throw new IllegalStateException(
                    "app.umas.authorization.enabled=true with app.umas.auth.required-scope="
                            + auth.getRequiredScope() + ": corporate users hold "
                            + CORPORATE_SCOPE + " tokens, so the bearer filter would reject every "
                            + "caller with FORBIDDEN_SCOPE before enforcement is reached.");
        }
    }
}
