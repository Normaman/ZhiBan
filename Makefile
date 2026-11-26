.PHONY: check test test-agent test-core test-web evals test-e2e docker-cache docker-update

PYTHON ?= conda run --no-capture-output -n dl python
SERVICE ?= web

check:
	$(PYTHON) -m ruff check services/agent-service/copilot services/agent-service/tests scripts tools evals
	$(PYTHON) -m ruff format --check services/agent-service/copilot services/agent-service/tests scripts tools evals

test: test-agent test-core test-web evals

test-agent:
	$(PYTHON) -m pytest -q

test-core:
	mvn -f services/core-service/pom.xml test

test-web:
	npm --prefix apps/web run build

evals:
	$(PYTHON) evals/run.py
	$(PYTHON) evals/benchmark.py --validate

test-e2e:
	npm --prefix apps/web run test:e2e

docker-cache:
	bash scripts/docker-cache.sh clean

docker-update:
	bash scripts/docker-update.sh $(SERVICE)
