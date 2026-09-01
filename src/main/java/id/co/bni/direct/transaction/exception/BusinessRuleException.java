package id.co.bni.direct.transaction.exception;

/**
 * A workflow rule said no. Answered as 422 with a machine code the FE switches on and a
 * readable Indonesian message it can show as-is.
 *
 * <p>The codes are a FIXED contract with the FE (built in parallel). Submit: NOT_MAKER,
 * SOURCE_ACCT_FORBIDDEN, BANK_LIMIT, COMPANY_LIMIT, GROUP_LIMIT, ACCOUNT_LIMIT,
 * MAKER_SCHEME_LIMIT, OTP_INVALID, NO_MATRIX, NO_ELIGIBLE_APPROVER, NO_ELIGIBLE_RELEASER.
 * Approve/reject: TASK_NOT_ACTIONABLE, NOT_ELIGIBLE, ALREADY_ACTED, OTP_INVALID. Do not
 * rename them.
 */
public class BusinessRuleException extends RuntimeException {

    private final String code;

    public BusinessRuleException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
