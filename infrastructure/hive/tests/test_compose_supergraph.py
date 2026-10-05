from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "compose-supergraph.sh"


class ComposeSupergraphTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / "scripts").mkdir()
        shutil.copy2(SCRIPT, self.root / "scripts" / SCRIPT.name)
        (self.root / "supergraph-config.yaml").write_text("subgraphs: {}\n")
        self.bin = self.root / "bin"
        self.bin.mkdir()
        rover = self.bin / "rover"
        rover.write_text("#!/bin/sh\nprintf 'composed schema\\n'\n")
        rover.chmod(0o755)
        self.requests = []
        self.token_status = 200
        self.graphql_status = 200
        self.graphql_body = {"data": {"_service": {"sdl": "type Query { example: String }"}}}
        test = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                test.requests.append((self.path, body, self.headers.get("Authorization")))
                if self.path == "/token":
                    status = test.token_status
                    response = (
                        {"access_token": "test-access-token"}
                        if status == 200
                        else {"error": "second_factor_required"}
                    )
                else:
                    status, response = test.graphql_status, test.graphql_body
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps(response).encode())

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.close_server)

    def close_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)

    def run_script(self, token=None):
        environment = os.environ.copy()
        environment.pop("HIVE_BEARER_TOKEN", None)
        base = f"http://127.0.0.1:{self.server.server_port}"
        environment.update(
            PATH=f"{self.bin}:{environment['PATH']}",
            OAUTH_TOKEN_URL=base + "/token",
            SPRING_GRAPHQL_URL=base + "/graphql",
            DEV_EMAIL="compose-test@example.com",
            DEV_PASSWORD="test-password",
        )
        if token is not None:
            environment["HIVE_BEARER_TOKEN"] = token
        return subprocess.run(
            ["bash", str(self.root / "scripts" / SCRIPT.name)],
            env=environment,
            capture_output=True,
            text=True,
            timeout=15,
        )

    def assert_no_credentials(self, result):
        output = result.stdout + result.stderr
        for credential in ("aal2-test-token", "test-access-token", "test-password"):
            self.assertNotIn(credential, output)
        for filename in ("retail.graphql", "supergraph.graphql"):
            path = self.root / filename
            if path.exists():
                self.assertNotIn("test-token", path.read_text())

    def test_uses_supplied_bearer_without_password_login(self):
        self.token_status = 403
        result = self.run_script("aal2-test-token")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([request[0] for request in self.requests], ["/graphql"])
        self.assertEqual(self.requests[0][2], "Bearer aal2-test-token")
        self.assertEqual((self.root / "supergraph.graphql").read_text(), "composed schema\n")
        self.assert_no_credentials(result)

    def test_preserves_non_enrolled_password_fallback(self):
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([request[0] for request in self.requests], ["/token", "/graphql"])
        self.assertEqual(self.requests[0][1]["password"], "test-password")
        self.assertEqual(self.requests[1][2], "Bearer test-access-token")
        self.assert_no_credentials(result)

    def test_second_factor_failure_has_actionable_instruction(self):
        self.token_status = 403
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("HIVE_BEARER_TOKEN", result.stderr)
        self.assertIn("AAL2", result.stderr)
        self.assertFalse((self.root / "supergraph.graphql").exists())
        self.assert_no_credentials(result)

    def test_invalid_supplied_token_does_not_fall_back_or_replace_schema(self):
        self.graphql_status = 401
        (self.root / "retail.graphql").write_text("previous retail\n")
        (self.root / "supergraph.graphql").write_text("previous supergraph\n")
        result = self.run_script("aal2-test-token")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual([request[0] for request in self.requests], ["/graphql"])
        self.assertEqual((self.root / "retail.graphql").read_text(), "previous retail\n")
        self.assertEqual((self.root / "supergraph.graphql").read_text(), "previous supergraph\n")
        self.assert_no_credentials(result)

    def test_graphql_error_does_not_replace_schema(self):
        self.graphql_body = {"errors": [{"message": "Access denied"}]}
        (self.root / "retail.graphql").write_text("previous retail\n")
        result = self.run_script("aal2-test-token")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.root / "retail.graphql").read_text(), "previous retail\n")
        self.assert_no_credentials(result)

    def test_normalizes_versioned_snapshots(self):
        self.graphql_body = {
            "data": {"_service": {"sdl": "type Query {\n  example: String  \n}\n"}}
        }
        (self.bin / "rover").write_text("#!/bin/sh\nprintf 'composed schema\\n\\n'\n")
        result = self.run_script("aal2-test-token")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(
            (self.root / "retail.graphql").read_text(),
            "type Query {\n  example: String\n}\n",
        )
        self.assertEqual((self.root / "supergraph.graphql").read_text(), "composed schema\n")


if __name__ == "__main__":
    unittest.main()
