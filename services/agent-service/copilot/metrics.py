"""Low-cardinality operational metrics; never label metrics with customer data."""

import logging

from prometheus_client import Counter, Gauge

logger = logging.getLogger("copilot")
JOBS = Counter("copilot_agent_operations_total", "Agent operation results", ["operation", "state"])
ONLINE = Gauge(
    "copilot_connector_online", "Whether the configured connector is authenticated and online"
)
