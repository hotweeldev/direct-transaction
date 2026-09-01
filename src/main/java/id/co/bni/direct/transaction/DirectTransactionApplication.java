package id.co.bni.direct.transaction;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Corporate transaction workflow engine for BNI Direct. */
@SpringBootApplication
public class DirectTransactionApplication {

    public static void main(String[] args) {
        SpringApplication.run(DirectTransactionApplication.class, args);
    }
}
