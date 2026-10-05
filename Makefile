COMPOSE = docker compose -f infrastructure/compose.yml --env-file .env
COMPOSE_DEBUG = $(COMPOSE) -f infrastructure/compose.debug.yml
GRADLE  = cd backend && ./gradlew
WAIT_TIMEOUT ?= 180

GREEN  = \033[0;32m
YELLOW = \033[0;33m
CYAN   = \033[0;36m
RESET  = \033[0m

.PHONY: dev front-dev dev-clean debug debug-clean test test-unit test-graphql test-integration test-e2e \
	coverage format format-check build status logs down clean help restart config doctor firewall-check \
	seed-test-data check-env

help:
	@echo "$(CYAN)Available commands:$(RESET)"
	@echo "  $(GREEN)make dev$(RESET)             🚀 build/start the Docker stack including Spring and wait for readiness"
	@echo "  $(GREEN)make front-dev$(RESET)       start the Next.js frontend on http://localhost:3000"
	@echo "  $(GREEN)make dev-clean$(RESET)       🚀🧹 clean start: tear down containers/volumes first, then start docker containers and run the backend"
	@echo "  $(GREEN)make debug$(RESET)           🐞 start docker containers and run the backend with a debug port open (5005)"
	@echo "  $(GREEN)make debug-clean$(RESET)     🐞🧹 clean start, then start the backend with a debug port open (5005)"
	@echo "  $(GREEN)make test$(RESET)            ✅ run all backend tests"
	@echo "  $(GREEN)make test-unit$(RESET)       ✅ run tests tagged 'unit'"
	@echo "  $(GREEN)make test-graphql$(RESET)    ✅ run tests tagged 'graphql'"
	@echo "  $(GREEN)make test-integration$(RESET) ✅ run tests tagged 'integration'"
	@echo "  $(GREEN)make test-e2e$(RESET)        ✅ run tests tagged 'e2e'"
	@echo "  $(GREEN)make coverage$(RESET)        📈 generate test coverage report"
	@echo "  $(GREEN)make format$(RESET)          🎨 format code (spotlessApply)"
	@echo "  $(GREEN)make format-check$(RESET)    🔍 check code formatting (spotlessCheck)"
	@echo "  $(GREEN)make build$(RESET)           📦 build the application"
	@echo "  $(GREEN)make status$(RESET)          📊 show status of docker containers"
	@echo "  $(GREEN)make logs$(RESET)            📜 follow docker container logs"
	@echo "  $(GREEN)make down$(RESET)            🛑 stop docker containers"
	@echo "  $(GREEN)make restart$(RESET)         🔁 restart docker containers (e.g. to re-run oauth-service bootstrap)"
	@echo "  $(GREEN)make clean$(RESET)           🧹 stop docker containers and delete their volumes (fresh Postgres/Hydra/Kratos state)"
	@echo "  $(GREEN)make config$(RESET)          🔎 print the effective (resolved) docker compose config"
	@echo "  $(GREEN)make doctor$(RESET)          🩺 diagnose the local dev stack (env, network, ports, health)"
	@echo "  $(GREEN)make firewall-check$(RESET)  ℹ️ legacy target: no Docker-to-host Spring rule is needed"
	@echo "  $(GREEN)make seed-test-data$(RESET)  🌱 wipe and repopulate the dev database with test data"

check-env:
	@test -f .env || { echo "$(YELLOW)✗ .env not found — run: cp .env.example .env$(RESET)"; exit 1; }
	@$(COMPOSE) config >/dev/null || { echo "$(YELLOW)✗ docker compose config failed — check .env for missing values above$(RESET)"; exit 1; }

dev: check-env
	@echo "$(CYAN)🐳 building and starting the stack, including Spring...$(RESET)"
	@$(COMPOSE) up -d --build --wait --wait-timeout $(WAIT_TIMEOUT)
	@echo "$(GREEN)✓ stack ready; backend: http://localhost:8080 — logs: make logs$(RESET)"

front-dev:
	@echo "$(CYAN)Starting the frontend on http://localhost:3000...$(RESET)"
	@cd frontend && bun dev

dev-clean: check-env
	@echo "$(YELLOW)🧹 clean start requested — removing existing containers and volumes...$(RESET)"
	@$(COMPOSE) down -v
	@$(MAKE) dev

debug: check-env
	@echo "$(CYAN)🐞 starting the stack with Spring remote debugging on 127.0.0.1:5005...$(RESET)"
	@$(COMPOSE_DEBUG) up -d --build --wait --wait-timeout $(WAIT_TIMEOUT)

debug-clean: check-env
	@echo "$(YELLOW)🧹 clean start requested — removing existing containers and volumes...$(RESET)"
	@$(COMPOSE) down -v
	@$(MAKE) debug

test:
	@echo "$(CYAN)✅ running backend tests...$(RESET)"
	@$(GRADLE) test

test-unit:
	@echo "$(CYAN)✅ running unit tests...$(RESET)"
	@$(GRADLE) test -Pgroup=unit

test-graphql:
	@echo "$(CYAN)✅ running graphql tests...$(RESET)"
	@$(GRADLE) test -Pgroup=graphql

test-integration:
	@echo "$(CYAN)✅ running integration tests...$(RESET)"
	@$(GRADLE) test -Pgroup=integration

test-e2e:
	@echo "$(CYAN)✅ running e2e tests...$(RESET)"
	@$(GRADLE) test -Pgroup=e2e

coverage:
	@echo "$(CYAN)📈 generating coverage report...$(RESET)"
	@$(GRADLE) jacocoTestReport

format:
	@echo "$(CYAN)🎨 formatting code...$(RESET)"
	@$(GRADLE) spotlessApply

format-check:
	@echo "$(CYAN)🔍 checking code formatting...$(RESET)"
	@$(GRADLE) spotlessCheck

build:
	@echo "$(CYAN)📦 building application...$(RESET)"
	@$(GRADLE) build

status:
	@echo "$(CYAN)📊 docker container status:$(RESET)"
	@$(COMPOSE) ps

logs:
	@echo "$(CYAN)📜 following docker container logs...$(RESET)"
	@$(COMPOSE) logs -f

down:
	@echo "$(YELLOW)🛑 stopping docker containers...$(RESET)"
	@$(COMPOSE) down

clean:
	@echo "$(YELLOW)🧹 stopping docker containers and deleting volumes...$(RESET)"
	@$(COMPOSE) down -v

restart:
	@echo "$(CYAN)🔁 restarting docker containers...$(RESET)"
	@$(COMPOSE) restart

config:
	@$(COMPOSE) config

doctor:
	@./infrastructure/scripts/dev-doctor.sh

firewall-check:
	@echo "$(CYAN)Spring and oauth-service now share the Compose network; no Docker-to-host firewall rule is required.$(RESET)"

seed-test-data:
	@echo "$(CYAN)🌱 seeding dev database with test data...$(RESET)"
	@$(GRADLE) testData -PdbName=retail -PdbUser=retail -PdbPassword=retail