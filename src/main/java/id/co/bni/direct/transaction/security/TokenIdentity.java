package id.co.bni.direct.transaction.security;

import id.co.bni.direct.transaction.config.UmasBearerAuthFilter;
import id.co.bni.direct.transaction.exception.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The body-field leg of identity binding. The wire shapes carry a {@code userId} inside
 * the JSON body (OTP challenge, submit, approve, reject) because the FE was built against
 * those exact field names; they stay accepted for shape compatibility, but once bearer
 * validation is on they may no longer name somebody else. A JSON body cannot be read in
 * {@link IdentityBindingInterceptor} without consuming the stream, so the check runs in
 * the controller, on the already-deserialized value.
 *
 * <p>With {@code app.umas.auth.enabled=false} no verified subject exists and the claimed
 * id is trusted as sent — exactly the pre-UMAS behavior, same as everywhere else in this
 * stack.
 */
public final class TokenIdentity {

    private TokenIdentity() {
    }

    /**
     * 403 (opaque, via {@link ForbiddenException}) when a body-supplied {@code userId}
     * contradicts the verified token identity. Case-insensitive, matching the URL and
     * header comparisons in the rest of the binding.
     */
    public static void requireSameUser(HttpServletRequest request, String claimedUserId) {
        Object subject = request.getAttribute(UmasBearerAuthFilter.ATTR_STAFF_ID);
        if (subject == null || subject.toString().isBlank()) {
            return; // bearer validation off - claimed id trusted, as before UMAS
        }
        if (claimedUserId != null && !claimedUserId.isBlank()
                && !claimedUserId.equalsIgnoreCase(subject.toString())) {
            throw new ForbiddenException("Body userId=" + claimedUserId
                    + " contradicts the token identity " + subject);
        }
    }
}
