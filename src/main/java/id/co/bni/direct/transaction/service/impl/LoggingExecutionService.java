package id.co.bni.direct.transaction.service.impl;

import id.co.bni.direct.transaction.service.ExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The no-op half of the execution seam: logs and leaves the task at READY_TO_EXECUTE.
 * {@link CoreExecutionServiceImpl} is {@code @Primary}, so this bean never runs in the
 * application; it stays for tests that want an inert seam and as the documented shape of
 * "execution did not happen".
 */
@Service
public class LoggingExecutionService implements ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(LoggingExecutionService.class);

    @Override
    public ExecutionResult execute(String taskId) {
        log.info("Task {} is READY_TO_EXECUTE; logging seam active, no core execution.", taskId);
        return new ExecutionResult("READY_TO_EXECUTE", null);
    }
}
