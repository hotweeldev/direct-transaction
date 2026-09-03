package id.co.bni.direct.transaction.controller;

import java.math.BigDecimal;

import id.co.bni.direct.transaction.dto.request.TransferRequests.VaInquiryRequest;
import id.co.bni.direct.transaction.dto.response.TransferResponses.VaInquiryResponse;
import id.co.bni.direct.transaction.exception.ForbiddenException;
import id.co.bni.direct.transaction.config.UmasBearerAuthFilter;
import id.co.bni.direct.transaction.service.TransferService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The VA inquiry endpoint (P3) at the controller boundary: the path company is the
 * authority, the body's userId is bound to the token identity exactly like the
 * interbank inquiry, and the service answer passes through unchanged. No MockMvc -
 * the guard stack (filter, interceptors) has its own tests; this checks the wiring.
 */
class TransferControllerTest {

    private TransferService transferService;
    private TransferController controller;
    private HttpServletRequest servletRequest;

    @BeforeEach
    void setUp() {
        transferService = mock(TransferService.class);
        controller = new TransferController(transferService);
        servletRequest = mock(HttpServletRequest.class);
    }

    @Test
    void vaInquiryPassesThePathCompanyAndTheBodyToTheService() {
        var request = new VaInquiryRequest("budi", "113179933", "8241002201234567");
        var answer = new VaInquiryResponse("8241002201234567", "PT TOKOPEDIA",
                new BigDecimal("150000"), "IDR", "INQ-123", BigDecimal.ZERO, "00", "OK");
        when(transferService.vaInquiry("CORP1", request)).thenReturn(answer);

        var response = controller.vaInquiry("CORP1", request, servletRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(answer);
        verify(transferService).vaInquiry(eq("CORP1"), eq(request));
    }

    @Test
    void vaInquiryRefusesABodyUserIdThatContradictsTheTokenIdentity() {
        when(servletRequest.getAttribute(UmasBearerAuthFilter.ATTR_STAFF_ID)).thenReturn("ani");
        var request = new VaInquiryRequest("budi", "113179933", "8241002201234567");

        assertThatThrownBy(() -> controller.vaInquiry("CORP1", request, servletRequest))
                .isInstanceOf(ForbiddenException.class);
        verify(transferService, never()).vaInquiry(eq("CORP1"), eq(request));
    }
}
