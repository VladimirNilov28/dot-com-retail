# Local Setup

This guide walks through setting up the ByteCore e-commerce application for local development.

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 21 | Required by the backend toolchain |
| Node.js | 22+ | Required for Angular 22 / TypeScript 6 |
| pnpm | 11.11.0 | Frontend package manager |
| Docker | Latest | PostgreSQL container + Testcontainers |
| Git | Latest | Version control |

## Project Structure

```
dot-com-retail/
├── backend/          # Spring Boot 4.0 (WebFlux, GraphQL, R2DBC, Kafka)
├── frontend/         # Angular 22 (Tailwind CSS 4, SSR via Express)
├── docs/             # Project documentation
├── scripts/          # Utility scripts
├── infrastructure/   # Docker, Kubernetes, Jenkins, Nginx configs
└── .github/          # GitHub Actions / CI
```

## Backend

The backend is a reactive Spring Boot 4.0 application written in Java 21, using Gradle as the build system.

### Key dependencies

- **Spring Boot 4.0.7** (WebFlux, Security, OAuth2 Client, Actuator)
- **PostgreSQL** via R2DBC (reactive database access)
- **Flyway** for database migrations
- **GraphQL** via Netflix DGS Framework 11.1.0
- **Apache Kafka** for messaging
- **SpringDoc OpenAPI** for REST API documentation

### Setup

```bash
cd backend
./gradlew build
```

This runs the full build pipeline: formatting (Spotless), compilation, tests (JUnit 5 + Testcontainers), coverage (JaCoCo), and static analysis (SonarQube).

### Database

A `compose.yaml` at `backend/compose.yaml` defines the PostgreSQL service:

| Setting | Value |
|---|---|
| Database | `mydatabase` |
| Username | `myuser` |
| Password | `secret` |

Spring Boot's `spring-boot-docker-compose` dev module auto-starts the container when you run the application. Alternatively, start it manually:

```bash
docker compose -f backend/compose.yaml up -d
```

### Configuration

Application properties live in `backend/src/main/resources/application.yaml`. As the project grows, you'll add configuration for the database connection, Kafka, OAuth2, and other services. Create environment-specific profiles under `application-{profile}.yaml`.

### Common commands

| Command | What it does |
|---|---|
| `./gradlew bootRun` | Start the backend server |
| `./gradlew build` | Full build (format, compile, test, coverage) |
| `./gradlew test` | Run unit and integration tests |
| `./gradlew jacocoTestReport` | Generate coverage report |
| `./gradlew spotlessApply` | Auto-format Java source |
| `./gradlew dependencyCheckAnalyze` | Check dependencies for known CVEs |

### Testing

Tests use JUnit 5 with Testcontainers. The `TestcontainersConfiguration` class spins up disposable PostgreSQL and Kafka containers during integration tests — no shared state, no cleanup needed. Test classes should use the `@Import(TestcontainersConfiguration.class)` annotation.

```bash
# Run all tests
./gradlew test

# Run a specific test class
./gradlew test --tests "ee.bytecore.backend.YourTestClass"
```

## Frontend

The frontend is an Angular 22 application with Tailwind CSS 4 and SSR support.

### Key dependencies

- **Angular 22** with SSR (Angular SSG/SSR packages)
- **Tailwind CSS 4.x** via PostCSS
- **Vitest** for unit testing
- **Express.js** for the SSR server

### Setup

```bash
cd frontend
pnpm install
```

### Common commands

| Command | What it does |
|---|---|
| `pnpm start` | Dev server on `http://localhost:4200/` |
| `pnpm build` | Production build (output to `dist/`) |
| `pnpm test` | Run unit tests via Vitest |
| `pnpm watch` | Watch mode with dev config |
| `pnpm serve:ssr:frontend` | Serve SSR production build on port 4000 |

## Running the Full Stack

1. **Start Docker** and ensure the PostgreSQL container is running:
   ```bash
   docker compose -f backend/compose.yaml up -d
   ```

2. **Start the backend** (in one terminal):
   ```bash
   cd backend
   ./gradlew bootRun
   ```

3. **Start the frontend** (in another terminal):
   ```bash
   cd frontend
   pnpm start
   ```

4. Open `http://localhost:4200/` in your browser.

For SSR mode, build and serve instead:
```bash
cd frontend
pnpm build
pnpm serve:ssr:frontend
# Open http://localhost:4000/
```

## Development Workflow

### Code style

- **Backend**: Google Java Format via Spotless. Run `./gradlew spotlessApply` before committing.
- **Frontend**: Prettier. Format on save is recommended — configure your editor to use the project's `.prettierrc`.

### Before committing

```bash
# Backend
cd backend && ./gradlew build

# Frontend
cd frontend && pnpm test && pnpm build
```

### Environment variables

No `.env.example` exists yet. As the project matures, create one at the project root (`.gitignore` already excludes `.env*` files except `.env.example`). Typical variables to expect:

- `DATABASE_URL` — PostgreSQL connection string
- `KAFKA_BOOTSTRAP_SERVERS` — Kafka broker addresses
- `OAUTH2_CLIENT_ID` / `OAUTH2_CLIENT_SECRET` — OAuth2 credentials

## Troubleshooting

### Docker isn't running

The backend will fail to start if Docker isn't available (the `spring-boot-docker-compose` module tries to start the PostgreSQL container). Start Docker and try again.

### Port conflicts

| Port | Service |
|---|---|
| 4200 | Angular dev server |
| 4000 | SSR Express server |
| 5432 | PostgreSQL |
| 9092 | Kafka (via Testcontainers) |

### Testcontainers issues

If integration tests fail with connection errors, ensure Docker is running and your user has permission to access the Docker socket. On Linux:

```bash
sudo usermod -aG docker $USER
# Log out and back in for the group change to take effect
```

### pnpm version mismatch

If you see warnings about pnpm version, install the exact version:

```bash
corepack enable
corepack prepare pnpm@11.11.0 --activate
```
