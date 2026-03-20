# Repository Guidelines

## Project Structure & Module Organization
- `src/main/java`: Two top-level packages:
    - `ca.uhn.fhir.jpa.starter` — upstream HAPI FHIR JPA Starter (Spring Boot entry point `Application`, server configs, MDM, MCP, CDS Hooks).
    - `de.gematik.isik.mockserver` — ISiK-specific logic organised into subpackages: `async`, `helper`, `interceptor`, `operation`, `provider`, `refv`.
- `src/main/resources`: `application.yaml`, search parameter bundles (`conformance/`), example FHIR resources (`example-resources/`), gematik Referenzvalidator plugins (`plugins/`), and profile/plugin mapping JSON files.
- `src/main/webapp`: HAPI Testpage overlay shipped for the default UI.
- `src/test/java` & `src/test/resources`: JUnit 5 unit tests mirror the `de.gematik.isik.mockserver` subpackages; integration tests (`*IT.java`) live in the same package. Test fixtures are under `src/test/resources/fhir-examples/` and `src/test/resources/integration-tests/` (each with `valid/` and `invalid/` sub-dirs).
- `custom/`: Branded web UI assets (`welcome.html`, `about.html`, `logo.jpg`) copied into the Docker image.
- `charts/`, `docker-compose.yml`, `configs/`: Deployment templates for Helm, Docker Compose (with PostgreSQL), and Tomcat/server overrides.
- `Dockerfile`: Reference container build; emits `gematik1/isik-mock-server`.

## Build, Test, and Development Commands
- `mvn clean install`: Compile, run Surefire + Failsafe, and emit `target/isik-mock-server.war`.
- `mvn spring-boot:run`: Start the server on port 8080 (the `boot` profile is active by default).
- `mvn clean package spring-boot:repackage && java -jar target/isik-mock-server.war`: Build and exercise the bootable WAR.
- `docker compose up -d`: Launch ISiK Mock Server + PostgreSQL using the local Dockerfile.
- `docker run -p 8080:8080 gematik1/isik-mock-server:latest`: Run the pre-built image with in-memory H2.
- Speed up startup during development by leaving `example-fhir-resources.validation.enabled` at its default `false` (skips Referenzvalidator on example resources; startup time might vary).

## Coding Style & Naming Conventions
- Target Java 21, four-space indents, alphabetized imports, no wildcards.
- ISiK-specific code goes under `de.gematik.isik.mockserver`; upstream HAPI customisations stay under `ca.uhn.fhir.jpa.starter`. Mirror packages in tests.
- Spotless enforces **Google Java Format** on `de.gematik` code (and `ResourceLoader.java`). Run `mvn spotless:apply` before committing to auto-format.
- Apache 2.0 license headers are managed by `license-maven-plugin` on the same scope; run `mvn license:update-file-header` if adding new files under `de.gematik`.
- Prefer descriptive class suffixes (`*Provider`, `*Interceptor`, `*Operation`, `*Handler`, `*Config`) and constructor injection with `final` collaborators.
- YAML keys stay kebab-case; JSON fixtures use lower_snake_case filenames.
- Register new interceptors/providers in `application.yaml` under `hapi.fhir.custom-interceptor-classes` / `hapi.fhir.custom-provider-classes`.

## Testing Guidelines
- `mvn test`: Runs JUnit Jupiter unit suites (e.g., `FhirValidationHandlerTest`, `AppointmentBookHandlerTest`, `PluginMappingResolverTest`).
- `mvn verify`: Adds integration coverage through Failsafe with the default H2 datasource; if you pivot to PostgreSQL, run `mvn install -DskipTests` until fixtures are updated.
- Store integration suites as `*IT.java` so Failsafe detects them (e.g., `FhirValidationIT`, `AppointmentBookOperationIT`, `IsikMockServerIT`). Colocate datasets in `src/test/resources`.
- Leverage Testcontainers and HAPI FHIR test utilities already declared in `pom.xml`.

## Commit & Pull Request Guidelines
- Follow repository precedent: imperative summary, optional scope (`Feature/mcp`), and linked issue `(#123)` when applicable.
- Keep commits narrowly scoped and include config or fixture updates with the code they support.
- PRs should describe runtime impact (profiles, ports, env vars), reference issues, and include UI screenshots when behaviour changes.
- Run `mvn verify` or the relevant Docker workflow before review; note any skipped checks and how to reproduce the result.

## Security & Configuration Tips
- Do not commit secrets; stash overrides under `configs/` and explain required env vars in the PR.
- When enabling external services, update `src/main/resources/application.yaml` plus sample overrides and mention connection expectations for reviewers.

## Review Focus Areas
- **Correctness**: Does the code do what it claims? Are edge cases handled? Are the FHIR Implementation Guides and profiles correctly handled?
    - **Basis**: https://simplifier.net/guide/isik-basis-stufe-5?version=current
    - **Medikation**: https://simplifier.net/guide/isik-medikation-stufe-5?version=current
    - **Terminplanung**: https://simplifier.net/guide/isik-terminplanung-stufe-5?version=current
    - **Vitalparameter**: https://simplifier.net/guide/isik-vitalparameter-stufe-5?version=current
    - **Dokumentenaustausch**: https://simplifier.net/guide/isik-dokumentenaustausch-stufe-5?version=current
- **Readability**: Is the code easy to understand? Are method and variable names descriptive?
- **Maintainability**: Is the code modular? Are there clear separations of concern?
- **Performance**: Are there any obvious inefficiencies? Could this cause issues under load?
