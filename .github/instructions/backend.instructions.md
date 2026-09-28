---
applyTo: "services/**/*.java,services/**/pom.xml,pom.xml"
---

# Backend instructions

- Preserve domain, application, API, and infrastructure boundaries.
- Keep domain code free of Spring dependencies where practical.
- Use Flyway for schema changes; never edit a released migration.
- Test repository behavior against PostgreSQL with Testcontainers.
- Add ArchUnit coverage for enforceable architectural boundaries.
- Use structured logs and propagate correlation context.
