package cz.codingmonkey.bookstore;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

class ModularityTests {

    ApplicationModules modules = ApplicationModules.of(BookstoreApplication.class);

    @Test
    void verifiesModularStructure() {
        modules.verify();
    }

    @Test
    void writesDocumentation() {
        // Generates C4/UML component diagrams and module canvases into build/spring-modulith-docs
        new Documenter(modules).writeDocumentation();
    }
}
