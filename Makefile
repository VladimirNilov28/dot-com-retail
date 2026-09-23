COMPOSE = docker compose -f infrastructure/compose.yml --env-file .env
GRADLE  = cd backend && ./gradlew

GREEN  = \033[0;32m
YELLOW = \033[0;33m
CYAN   = \033[0;36m
RESET  = \033[0m

.PHONY: dev debug test test-unit test-graphql test-integration test-e2e \
	coverage format format-check build status logs down help

help:
	@echo "$(CYAN)Available commands:$(RESET)"
	@echo "  $(GREEN)make dev$(RESET)             🚀 start docker containers and run the backend"
	@echo "  $(GREEN)make debug$(RESET)           🐞 start docker containers and run the backend with a debug port open (5005)"
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

dev:
	@echo "$(CYAN)🐳 starting docker containers...$(RESET)"
	@$(COMPOSE) up -d
	@echo "$(GREEN)🚀 starting backend...$(RESET)"
	@$(GRADLE) bootRun

debug:
	@echo "$(CYAN)🐳 starting docker containers...$(RESET)"
	@$(COMPOSE) up -d
	@echo "$(YELLOW)🐞 starting backend in debug mode (port 5005)...$(RESET)"
	@$(GRADLE) bootRun --debug-jvm

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