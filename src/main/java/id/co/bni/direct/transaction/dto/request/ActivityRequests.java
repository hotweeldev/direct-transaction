package id.co.bni.direct.transaction.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Request bodies of Aktivitas Transaksi (FE contract §7). */
public final class ActivityRequests {

    private ActivityRequests() {
    }

    /**
     * POST /transactions/search - mirrors the Cari/Pencarian drawer.
     *
     * <p>{@code searchType} DATE_RANGE needs {@code dateRange}; REFERENCE needs
     * {@code referenceNo}. {@code transactionType} is the menu code
     * ({@code MNU_GCME_050200}) or one of the screen aliases the service knows
     * ({@code TRANSFER_BNI}). {@code status} is BERHASIL / GAGAL / DIPROSES. Cross-field
     * rules (which of the two keys is required, from &lt;= to) are enforced in the service
     * so the answer names the field, like the rest of this module.
     */
    public record ActivitySearchRequest(
            @NotBlank String userId,
            @NotBlank @Pattern(regexp = "DATE_RANGE|REFERENCE") String searchType,
            @Valid DateRange dateRange,
            String transactionType,
            @Pattern(regexp = "BERHASIL|GAGAL|DIPROSES") String status,
            String sourceAccountNo,
            String beneficiaryName,
            @Valid MoneyFilter amountFrom,
            @Valid MoneyFilter amountTo,
            @Pattern(regexp = "\\d{6,20}") String referenceNo,
            @Min(1) Integer page,
            @Min(1) @Max(100) Integer size) {

        public int pageOrDefault() {
            return page == null ? 1 : page;
        }

        public int sizeOrDefault() {
            return size == null ? 10 : size;
        }
    }

    public record DateRange(@NotNull LocalDate start, @NotNull LocalDate end) {
    }

    /** Amount bound; currency is informational (every row is IDR today). */
    public record MoneyFilter(@NotNull @Positive BigDecimal amount, String currency) {
    }
}
