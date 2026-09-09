# payauth
#
# Everything here assumes Docker is running. Nothing assumes a local Postgres,
# Redis, Kafka, JDK or k6 installation beyond a JDK 21 for `make test`.

SHELL := /bin/bash
COMPOSE := docker compose
MVN := ./mvnw
BASE_URL ?= http://localhost:8080
# k6 runs in a container, so it reaches the app through the host gateway rather
# than through localhost, which inside that container is the container itself.
LOAD_TEST_URL ?= http://host.docker.internal:8080

.DEFAULT_GOAL := help

.PHONY: help
help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

.PHONY: up
up: ## Build and start Postgres, Redis, Kafka and the app
	$(COMPOSE) up -d --build
	@echo "waiting for the service to report healthy..."
	@for i in $$(seq 1 90); do \
		if curl -sf $(BASE_URL)/actuator/health | grep -q '"status":"UP"'; then \
			echo "up: $(BASE_URL)"; exit 0; \
		fi; sleep 2; \
	done; \
	echo "service did not become healthy; see 'make logs'"; exit 1

.PHONY: down
down: ## Stop everything and remove volumes
	$(COMPOSE) down -v

.PHONY: logs
logs: ## Tail the application log
	$(COMPOSE) logs -f app

.PHONY: infra
infra: ## Start only the dependencies, for running the app from an IDE
	$(COMPOSE) up -d postgres redis kafka

.PHONY: build
build: ## Compile and package
	$(MVN) -B clean package -DskipTests

.PHONY: test
test: ## Run the full test suite (needs Docker for Testcontainers)
	$(MVN) -B verify

.PHONY: smoke
smoke: ## Prove idempotency with curl: 201 then 200 on replay
	@KEY=demo-$$(date +%s); \
	BODY='{"cardToken":"tok_4111111111111111","amountMinor":249900,"currency":"INR","merchantId":"mrc_acme_travel","countryCode":"IN"}'; \
	echo "--- first call ---"; \
	curl -s -i -X POST $(BASE_URL)/v1/authorizations \
		-H 'Content-Type: application/json' -H "Idempotency-Key: $$KEY" -d "$$BODY" \
		| sed -n '1p;/^{/p'; \
	echo "--- replay, same key, same body ---"; \
	curl -s -i -X POST $(BASE_URL)/v1/authorizations \
		-H 'Content-Type: application/json' -H "Idempotency-Key: $$KEY" -d "$$BODY" \
		| sed -n '1p;/^{/p'; \
	echo "--- same key, different body ---"; \
	curl -s -i -X POST $(BASE_URL)/v1/authorizations \
		-H 'Content-Type: application/json' -H "Idempotency-Key: $$KEY" \
		-d '{"cardToken":"tok_4111111111111111","amountMinor":999900,"currency":"INR","merchantId":"mrc_acme_travel","countryCode":"IN"}' \
		| sed -n '1p;/^{/p'

.PHONY: load-test
load-test: ## Run the k6 load test against a running stack
	@mkdir -p load-test/results
	docker run --rm -i \
		--add-host=host.docker.internal:host-gateway \
		-v "$(PWD)/load-test:/scripts" \
		-e BASE_URL=$(LOAD_TEST_URL) \
		-e VUS=$${VUS:-50} \
		-e DURATION=$${DURATION:-2m} \
		grafana/k6:latest run /scripts/authorizations.js

.PHONY: clean
clean: ## Remove build output
	$(MVN) -B clean
