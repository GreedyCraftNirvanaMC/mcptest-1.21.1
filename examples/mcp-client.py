#!/usr/bin/env python3
"""Make one MCP request to a local MCP Test client using only Python's standard library."""

import argparse
import base64
import binascii
import http.client
import json
import re
import sys
from pathlib import Path


class McpError(Exception):
    """A connection, protocol, or tool failure safe to print without credentials."""


class MinecraftClient:
    def __init__(self, config_path):
        try:
            config = json.loads(config_path.read_text(encoding="utf-8-sig"))
        except (OSError, ValueError) as error:
            raise McpError("Cannot read the MCP Test configuration: {}".format(config_path)) from error
        if not isinstance(config, dict):
            raise McpError("The configuration must be a JSON object")
        self.port = config.get("port", 8765)
        self.token = config.get("bearerToken")
        timeout = config.get("requestTimeoutSeconds", 30)
        if type(self.port) is not int or not 1024 <= self.port <= 65535:
            raise McpError("Invalid port in configuration")
        if not isinstance(self.token, str) or not re.fullmatch(r"[A-Za-z0-9_-]{32,256}", self.token):
            raise McpError("Missing or invalid bearerToken; start Minecraft to generate its configuration")
        if type(timeout) is not int or not 1 <= timeout <= 120:
            raise McpError("Invalid requestTimeoutSeconds in configuration")
        if config.get("enabled", True) is not True:
            raise McpError("The bridge is disabled in this configuration")
        self.timeout = timeout + 10
        self.session = None
        self.version = "2025-11-25"
        self.next_id = 1

    def _http(self, method, payload=None):
        headers = {
            "Authorization": "Bearer " + self.token,
            "Accept": "application/json, text/event-stream",
            "Content-Type": "application/json",
        }
        if self.session is not None:
            headers["Mcp-Session-Id"] = self.session
            headers["Mcp-Protocol-Version"] = self.version
        body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
        # Direct connection avoids proxies and redirects carrying local credentials elsewhere.
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=self.timeout)
        try:
            connection.request(method, "/mcp", body=body, headers=headers)
            response = connection.getresponse()
            session = response.getheader("Mcp-Session-Id")
            if session:
                self.session = session
            raw = response.read(64 * 1024 * 1024 + 1)
            if len(raw) > 64 * 1024 * 1024:
                raise McpError("Response exceeds the helper's 64 MiB limit")
            try:
                document = json.loads(raw) if raw else None
            except (ValueError, UnicodeDecodeError) as error:
                raise McpError("The local endpoint returned invalid JSON") from error
            if response.status >= 400:
                error = document.get("error") if isinstance(document, dict) else None
                detail = error.get("message", "") if isinstance(error, dict) else ""
                raise McpError("HTTP {}: {}".format(response.status, detail or response.reason))
            return document
        except (OSError, http.client.HTTPException) as error:
            raise McpError("Cannot reach the MCP Test client at 127.0.0.1:{} ({})".format(
                self.port, type(error).__name__)) from error
        finally:
            connection.close()

    def request(self, method, params):
        request_id = self.next_id
        self.next_id += 1
        document = self._http("POST", {
            "jsonrpc": "2.0", "id": request_id, "method": method, "params": params,
        })
        if document is None:
            raise McpError("The request ended without a result (it may have been cancelled)")
        if not isinstance(document, dict) or document.get("jsonrpc") != "2.0" or document.get("id") != request_id:
            raise McpError("Invalid JSON-RPC response envelope")
        if "error" in document:
            error = document["error"]
            if not isinstance(error, dict):
                raise McpError("Malformed JSON-RPC error")
            raise McpError("JSON-RPC {}: {}".format(error.get("code"), error.get("message")))
        if not isinstance(document.get("result"), dict):
            raise McpError("JSON-RPC response has no result object")
        return document["result"]

    def initialize(self):
        result = self.request("initialize", {
            "protocolVersion": self.version,
            "capabilities": {},
            "clientInfo": {"name": "mcptest-python-example", "version": "1.0.0"},
        })
        negotiated = result.get("protocolVersion")
        if negotiated not in {"2025-11-25", "2025-06-18"} or not self.session:
            raise McpError("The server did not negotiate a supported session")
        self.version = negotiated
        self._http("POST", {"jsonrpc": "2.0", "method": "notifications/initialized"})

    def close(self):
        if self.session is not None:
            try:
                self._http("DELETE")
            finally:
                self.session = None


def show_result(result):
    if "structuredContent" in result:
        print(json.dumps(result["structuredContent"], ensure_ascii=False, indent=2))
    elif isinstance(result.get("content"), list):
        for content in result["content"]:
            if content.get("type") == "text":
                print(content.get("text", ""))
    else:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    for content in result.get("content", []):
        if content.get("type") == "image":
            print("Received {} image content.".format(content.get("mimeType", "unknown")), file=sys.stderr)


def save_screenshot(result, destination):
    image = next((item for item in result.get("content", [])
                  if item.get("type") == "image" and item.get("mimeType") == "image/png"), None)
    if image is None:
        raise McpError("The requested tool returned no PNG image")
    try:
        data = base64.b64decode(image.get("data", ""), validate=True)
    except (ValueError, binascii.Error) as error:
        raise McpError("The screenshot contains invalid base64") from error
    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
        raise McpError("The screenshot does not contain a PNG file")
    try:
        with destination.open("xb") as output:
            output.write(data)
    except FileExistsError as error:
        raise McpError("Screenshot file already exists: {}".format(destination)) from error
    except OSError as error:
        raise McpError("Cannot save screenshot: {}".format(destination)) from error
    print("Saved screenshot to {}".format(destination.resolve()), file=sys.stderr)


def main():
    # Preserve Chinese player/item text when output is redirected on Windows.
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=Path("run/config/mcptest.json"),
                        help="Minecraft's config/mcptest.json (default: run/config/mcptest.json)")
    selection = parser.add_mutually_exclusive_group()
    selection.add_argument("--list", action="store_true", help="Print the tool catalog")
    selection.add_argument("--tool", default="minecraft_status", help="Tool name (default: minecraft_status)")
    parser.add_argument("--args", default="{}", help="Tool arguments as a JSON object")
    parser.add_argument("--screenshot", type=Path, help="Save PNG image content to a new file")
    options = parser.parse_args()
    try:
        arguments = json.loads(options.args)
    except ValueError:
        parser.error("--args must be valid JSON")
    if not isinstance(arguments, dict):
        parser.error("--args must be a JSON object")
    if options.list and options.screenshot:
        parser.error("--screenshot requires a tool call, not --list")

    client = None
    exit_code = 0
    try:
        client = MinecraftClient(options.config)
        client.initialize()
        result = client.request("tools/list", {}) if options.list else client.request(
            "tools/call", {"name": options.tool, "arguments": arguments})
        show_result(result)
        if result.get("isError"):
            exit_code = 1
        elif options.screenshot:
            save_screenshot(result, options.screenshot)
    except KeyboardInterrupt:
        print("Interrupted; closing the MCP session to cancel pending work.", file=sys.stderr)
        exit_code = 130
    except McpError as error:
        print(str(error), file=sys.stderr)
        exit_code = 1
    finally:
        if client is not None:
            try:
                client.close()
            except (McpError, KeyboardInterrupt):
                print("Could not close the MCP session; pending work remains subject to server timeouts.",
                      file=sys.stderr)
                if exit_code == 0:
                    exit_code = 1
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
