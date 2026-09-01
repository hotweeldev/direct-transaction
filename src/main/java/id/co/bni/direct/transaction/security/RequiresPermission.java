package id.co.bni.direct.transaction.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the UMAS {@code resource:action} a handler needs. Faithful copy of
 * direct-admin's annotation of the same name (per-repo copies are the house pattern);
 * the local counterpart of {@code @GatewayPolicy} in {@code foundation-gateway}, which
 * this Maven module cannot depend on.
 *
 * <p>Sits on the controller class or the handler method — a method-level declaration wins
 * over the class it is in. It lives next to the code it protects on purpose: a resource
 * code kept in a path-to-resource table drifts the first time somebody renames a mapping.
 *
 * <p><b>The resource code must already be seeded in UMAS.</b> A code with no
 * {@code permission_resource}/{@code permission} row denies every caller with
 * {@code UNKNOWN_RESOURCE} — the seeded catalog is the authority, not this annotation.
 * The corporate catalog is the unprefixed nav keys of
 * {@code direct-fe-shared/src/navConfig.ts}, seeded under tenant {@code bni-direct}
 * (V13/V20 in direct-umas). The two this service uses are {@code transfer-bni} (the
 * Transfer ke BNI menu) and {@code task-pending} (the pending-task inbox), both ACCESS.
 *
 * <p>{@code action} defaults to {@code ACCESS} because {@code ACCESS} is the only action
 * seeded for these codes (§3 of the integration guide — granularity is a seeding
 * decision). Naming a CRUD verb here that has no permission row does not tighten
 * anything; it denies everyone.
 *
 * <p>An endpoint <b>without</b> this annotation is not authorized at all — it is left
 * exactly as it is today. Adding the annotation is what opts an endpoint in.
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface RequiresPermission {

    /** Seeded UMAS resource code, e.g. {@code transfer-bni}. Unprefixed: it is the nav key. */
    String resource();

    /**
     * Other resources that ALSO admit the caller, with the same action. Any one of them is
     * enough; they are not required together. Deliberately per-METHOD, never on a
     * controller class: it widens exactly one reachable thing.
     */
    String[] alsoAllow() default {};

    /** Seeded action on that resource. Only {@code ACCESS} exists for the nav keys today. */
    String action() default "ACCESS";
}
