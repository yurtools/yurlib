package org.yurlib.server;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

    private final JavaClasses classes = new ClassFileImporter().importPackages("org.yurlib.server");

    @Test
    void domainDoesNotDependOnFrameworkDeliveryOrInfrastructure() {
        noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "jakarta.persistence..", "..api..", "..infrastructure..")
                .check(classes);
    }

    @Test
    void applicationDoesNotDependOnFrameworkDeliveryOrInfrastructure() {
        noClasses()
                .that()
                .resideInAPackage("..application..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "jakarta.persistence..", "..api..", "..infrastructure..")
                .check(classes);
    }
}
