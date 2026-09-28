COMPOSE = docker compose -f infrastructure/compose.yml --env-file .env
GRADLE  = cd backend && ./gradlew
DEV_SUBNET = 172.28.88.0/24

GREEN  = \033[0;32m
YELLOW = \033[0;33m
CYAN   = \033[0;36m
RESET  = \033[0m

.PHONY: dev dev-clean debug debug-clean test test-unit test-graphql test-integration test-e2e \
	coverage format format-check build status logs down clean help restart config doctor firewall-check \
	seed-test-data

help:
	@echo "$(CYAN)Available commands:$(RESET)"
	@echo "  $(GREEN)make dev$(RESET)             🚀 start docker containers and run the backend"
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
	@echo "  $(GREEN)make doctor$(RESET)          🩺 diagnose the local dev infrastructure (env, network, firewall, ports)"
	@echo "  $(GREEN)make firewall-check$(RESET)  🔥 verify (without changing) the host firewall rule oauth-service needs"
	@echo "  $(GREEN)make seed-test-data$(RESET)  🌱 wipe and repopulate the dev database with test data"

dev:
	@test -f .env || { echo "$(YELLOW)✗ .env not found — run: cp .env.example .env$(RESET)"; exit 1; }
	@$(COMPOSE) config >/dev/null || { echo "$(YELLOW)✗ docker compose config failed — check .env for missing values above$(RESET)"; exit 1; }
	@echo "$(CYAN)🐳 starting docker containers...$(RESET)"
	@$(COMPOSE) up -d
	@echo "$(YELLOW)⚠ Spring is not started by this target — start it now (e.g. from your IDE, or in another terminal: cd backend && ./gradlew bootRun)$(RESET)"
	@echo "$(CYAN)🔌 checking oauth-service -> host Spring:8080 (best-effort, 15s)...$(RESET)"
	@for i in $$(seq 1 5); do \
		$(COMPOSE) exec -T oauth-service python3 -c "import socket; socket.create_connection(('host.docker.internal', 8080), timeout=2)" 2>/dev/null && { echo "$(GREEN)✓ oauth-service can reach host:8080$(RESET)"; break; }; \
		[ "$$i" = 5 ] && echo "$(YELLOW)⚠ oauth-service cannot reach host:8080 yet — normal if Spring just started; run 'make doctor' once it's up$(RESET)"; \
		sleep 3; \
	done
	@echo "$(GREEN)🚀 starting backend...$(RESET)"
	@$(GRADLE) bootRun

dev-clean:
	@echo "$(YELLOW)🧹 clean start requested — removing existing containers and volumes...$(RESET)"
	@$(COMPOSE) down -v
	@$(MAKE) dev

debug:
	@echo "$(CYAN)🐳 starting docker containers...$(RESET)"
	@$(COMPOSE) up -d
	@echo "$(YELLOW)🐞 starting backend in debug mode (port 5005)...$(RESET)"
	@$(GRADLE) bootRun --debug-jvm

debug-clean:
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
	@echo "$(CYAN)🔥 checking firewall rule for $(DEV_SUBNET) -> host:8080...$(RESET)"
	@sudo -n iptables -C ufw-user-input -p tcp -s $(DEV_SUBNET) --dport 8080 -j ACCEPT 2>/dev/null \
		&& echo "$(GREEN)✓ rule present$(RESET)" \
		|| echo "$(YELLOW)✗ rule missing or sudo needs a password — run: sudo ufw allow from $(DEV_SUBNET) to any port 8080 proto tcp comment 'bytecore-dev: oauth-service -> Spring'$(RESET)"

seed-test-data:
	@echo "$(CYAN)🌱 seeding dev database with test data...$(RESET)"
	@$(GRADLE) testData -PdbName=retail -PdbUser=retail -PdbPassword=retail