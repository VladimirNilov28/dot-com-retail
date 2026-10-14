import json
import os
from pathlib import Path
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[2]


class BackendComposeTest(unittest.TestCase):
    def config(self, debug=False, **overrides):
        command = ["docker", "compose", "-f", str(ROOT / "infrastructure/compose.yml")]
        if debug:
            command += ["-f", str(ROOT / "infrastructure/compose.debug.yml")]
        command += ["--env-file", str(ROOT / ".env.example"), "config", "--format", "json"]
        environment = os.environ.copy()
        environment.update(overrides)
        result = subprocess.run(
            command, env=environment, capture_output=True, text=True, check=True, timeout=30
        )
        return json.loads(result.stdout)["services"]

    def test_backend_has_internal_dependencies_and_healthcheck(self):
        services = self.config()
        backend = services["backend"]
        self.assertEqual(backend["build"]["context"], str(ROOT / "backend"))
        for service in ("postgres", "kafka", "hydra", "kratos"):
            self.assertEqual(backend["depends_on"][service]["condition"], "service_healthy")
        self.assertIn(
            "http://127.0.0.1:8080/actuator/health/readiness", backend["healthcheck"]["test"]
        )
        self.assertEqual(backend["restart"], "unless-stopped")
        self.assertNotIn("volumes", backend)
        self.assertEqual(backend["ports"][0]["host_ip"], "127.0.0.1")

    def test_stale_host_urls_cannot_override_container_networking(self):
        services = self.config(
            DB_URL="jdbc:postgresql://127.0.0.1:5432/retail",
            KAFKA_BOOTSTRAP_SERVERS="localhost:9092",
            SPRING_INTERNAL_BASE_URL="http://host.docker.internal:8080",
            DB_USERNAME="compose-test-user",
            DB_PASSWORD="compose-test-password",
        )
        environment = services["backend"]["environment"]
        self.assertEqual(environment["SPRING_PROFILES_ACTIVE"], "dev")
        self.assertEqual(environment["SPRING_DATASOURCE_URL"], "jdbc:postgresql://postgres:5432/retail")
        self.assertEqual(environment["SPRING_DATASOURCE_USERNAME"], "compose-test-user")
        self.assertEqual(environment["SPRING_DATASOURCE_PASSWORD"], "compose-test-password")
        self.assertEqual(environment["SPRING_KAFKA_BOOTSTRAP_SERVERS"], "kafka:29092")
        self.assertEqual(environment["KRATOS_ADMIN_URL"], "http://kratos:4434")
        self.assertEqual(environment["HYDRA_ADMIN_URL"], "http://hydra:4445")
        self.assertEqual(
            environment["SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI"],
            "http://127.0.0.1:4444",
        )
        self.assertEqual(
            environment["SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI"],
            "http://hydra:4444/.well-known/jwks.json",
        )
        self.assertEqual(
            services["oauth-service"]["environment"]["SPRING_INTERNAL_BASE_URL"],
            "http://backend:8080",
        )

    def test_consumers_wait_for_backend_and_hive_snapshot_routes_internally(self):
        services = self.config()
        for name in ("oauth-service", "hive-router"):
            self.assertEqual(services[name]["depends_on"]["backend"]["condition"], "service_healthy")
            self.assertNotIn("extra_hosts", services[name])
        config = (ROOT / "infrastructure/hive/supergraph-config.yaml").read_text()
        snapshot = (ROOT / "infrastructure/hive/supergraph.graphql").read_text()
        self.assertIn("routing_url: http://backend:8080/graphql", config)
        self.assertIn('url: "http://backend:8080/graphql"', snapshot)

    def test_debug_is_opt_in_and_loopback_only(self):
        normal = self.config()["backend"]
        debug = self.config(debug=True)["backend"]
        self.assertNotIn("JAVA_TOOL_OPTIONS", normal["environment"])
        self.assertNotIn(5005, [port["target"] for port in normal["ports"]])
        self.assertIn("suspend=n", debug["environment"]["JAVA_TOOL_OPTIONS"])
        port, = [port for port in debug["ports"] if port["target"] == 5005]
        self.assertEqual(port["host_ip"], "127.0.0.1")

    def test_make_startup_uses_compose_without_host_spring_or_volume_reset(self):
        result = subprocess.run(
            ["make", "-n", "dev", "debug", "restart", "down"],
            cwd=ROOT, capture_output=True, text=True, check=True, timeout=10,
        )
        self.assertIn("up -d --build --wait --wait-timeout", result.stdout)
        self.assertIn("-f infrastructure/compose.debug.yml", result.stdout)
        self.assertNotIn("bootRun", result.stdout)
        self.assertNotIn("down -v", result.stdout)


if __name__ == "__main__":
    unittest.main()
