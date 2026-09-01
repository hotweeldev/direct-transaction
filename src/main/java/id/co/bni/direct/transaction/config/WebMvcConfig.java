package id.co.bni.direct.transaction.config;

import java.text.ParseException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

import id.co.bni.direct.transaction.security.IdentityBindingInterceptor;
import id.co.bni.direct.transaction.security.PermissionEnforcementInterceptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.Formatter;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Case-insensitive URL matching and lenient date parsing, matching spine-backoffice.
 *
 * <p>Spring MVC matches paths case-sensitively; the MFEs call these routes with mixed
 * casing because the .NET services they grew up against did not care. Keeping the two
 * services consistent is worth more than Spring's default here.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ObjectProvider<IdentityBindingInterceptor> identityBinding;
    private final ObjectProvider<PermissionEnforcementInterceptor> permissionEnforcement;

    public WebMvcConfig(ObjectProvider<IdentityBindingInterceptor> identityBinding,
                        ObjectProvider<PermissionEnforcementInterceptor> permissionEnforcement) {
        this.identityBinding = identityBinding;
        this.permissionEnforcement = permissionEnforcement;
    }

    /**
     * The UMAS guards, when their flags put the beans on the context ({@code
     * ObjectProvider} because both default off — nothing registered, handler chain
     * unchanged; same wiring shape as direct-admin's {@code WebMvcConfig}). Identity
     * binding first: whether the caller IS who the request names is decided before what
     * they are allowed to do is asked.
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        identityBinding.ifAvailable(interceptor -> registry.addInterceptor(interceptor).order(0));
        permissionEnforcement.ifAvailable(interceptor -> registry.addInterceptor(interceptor).order(1));
    }

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        PathPatternParser parser = new PathPatternParser();
        parser.setCaseSensitive(false);
        configurer.setPatternParser(parser);
    }

    /**
     * Accepts a full local date-time, a bare date, or an offset date-time. Spring's
     * default rejects the last two, which breaks query parameters sent as
     * {@code "2026-08-20"}.
     */
    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addFormatter(new LenientLocalDateTimeFormatter());
    }

    private static final class LenientLocalDateTimeFormatter implements Formatter<LocalDateTime> {

        @Override
        public LocalDateTime parse(String text, Locale locale) throws ParseException {
            String value = text.trim();
            try {
                return LocalDateTime.parse(value);
            } catch (DateTimeParseException ignored) {
                // fall through
            }
            try {
                return LocalDate.parse(value).atStartOfDay();
            } catch (DateTimeParseException ignored) {
                // fall through
            }
            try {
                return OffsetDateTime.parse(value).toLocalDateTime();
            } catch (DateTimeParseException ex) {
                throw new ParseException("Unparseable date: " + text, 0);
            }
        }

        @Override
        public String print(LocalDateTime object, Locale locale) {
            return object.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
    }
}
