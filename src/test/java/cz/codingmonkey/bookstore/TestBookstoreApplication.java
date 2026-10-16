package cz.codingmonkey.bookstore;

import org.springframework.boot.SpringApplication;

/**
 * Runs the application locally against a throwaway PostgreSQL container, with demo data: {@code ./gradlew bootTestRun}.
 */
public class TestBookstoreApplication {

    public static void main(String[] args) {
        SpringApplication.from(BookstoreApplication::main)
                .with(TestcontainersConfiguration.class)
                .withAdditionalProfiles("demo") // fill the empty database with demo books and authors
                .run(args);
    }
}
