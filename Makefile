.DEFAULT_GOAL := help
SHELL := /bin/bash

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

env: ## Create .env from the example if missing
	@test -f .env || (cp .env.example .env && echo "created .env")

up: env ## Build and start the whole stack
	docker compose up -d --build
	@echo "waiting for Kibana (this takes 2-3 minutes on a cold start)..."
	@until curl -s http://localhost:5601/api/status | grep -q '"level":"available"'; do printf '.'; sleep 5; done; echo
	@$(MAKE) kibana-setup
	@echo
	@echo "  app       http://localhost:8080"
	@echo "  kibana    http://localhost:5601   (elastic / see .env)"
	@echo "  es        http://localhost:9200"
	@echo
	@echo "Next: make traffic && make smoke"

down: ## Stop the stack, keep data
	docker compose down

clean: ## Stop and delete all volumes (fresh start)
	docker compose down -v

kibana-setup: ## Create data views and alerting rules
	@set -a; source .env; set +a; \
	KIBANA_HOST=http://localhost:5601 ./observability/kibana/setup-kibana.sh

traffic: ## Generate 2 minutes of traffic
	./scripts/load-generator.sh 120 5

smoke: ## Verify the pipeline end to end
	@set -a; source .env; set +a; ./scripts/smoke-test.sh

chaos: ## Run a chaos scenario: make chaos SCENARIO=error-burst
	./scripts/chaos.sh $(SCENARIO)

logs: ## Tail app logs
	docker compose logs -f app

ps: ## Show stack status
	docker compose ps

test: ## Run unit tests
	mvn -B test

rebuild-app: ## Rebuild only the app container
	docker compose up -d --build app
