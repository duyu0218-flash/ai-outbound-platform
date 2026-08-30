from __future__ import annotations

import asyncio
import json
from dataclasses import dataclass
from typing import AsyncIterator
from urllib.parse import unquote


class EslError(RuntimeError):
    """Raised when FreeSWITCH rejects an Event Socket command."""


@dataclass(slots=True)
class EslFrame:
    headers: dict[str, str]
    body: bytes = b""

    @property
    def content_type(self) -> str:
        return self.headers.get("Content-Type", "").lower()

    def json(self) -> dict[str, object]:
        if not self.body:
            return {}
        value = json.loads(self.body.decode("utf-8", errors="replace"))
        if not isinstance(value, dict):
            raise EslError("FreeSWITCH event payload is not an object")
        return value


async def read_frame(reader: asyncio.StreamReader) -> EslFrame:
    headers: dict[str, str] = {}
    while True:
        line = await reader.readline()
        if not line:
            if headers:
                raise EslError("FreeSWITCH closed the Event Socket mid-frame")
            raise EOFError("FreeSWITCH closed the Event Socket")
        stripped = line.rstrip(b"\r\n")
        if not stripped:
            break
        key, separator, value = stripped.partition(b":")
        if not separator:
            continue
        headers[key.decode("utf-8", errors="replace")] = unquote(
            value.lstrip().decode("utf-8", errors="replace")
        )
    raw_length = headers.get("Content-Length", "0")
    try:
        content_length = int(raw_length)
    except ValueError as exc:
        raise EslError(f"invalid FreeSWITCH Content-Length: {raw_length}") from exc
    body = await reader.readexactly(content_length) if content_length else b""
    return EslFrame(headers=headers, body=body)


class EslConnection:
    def __init__(self, host: str, port: int, password: str, timeout_sec: float):
        self.host = host
        self.port = port
        self.password = password
        self.timeout_sec = timeout_sec
        self.reader: asyncio.StreamReader | None = None
        self.writer: asyncio.StreamWriter | None = None

    async def connect(self) -> None:
        self.reader, self.writer = await asyncio.wait_for(
            asyncio.open_connection(self.host, self.port),
            timeout=self.timeout_sec,
        )
        greeting = await asyncio.wait_for(read_frame(self.reader), timeout=self.timeout_sec)
        if greeting.content_type != "auth/request":
            await self.close()
            raise EslError(f"unexpected FreeSWITCH greeting: {greeting.content_type or 'unknown'}")
        await self.send(f"auth {self.password}")
        reply = await asyncio.wait_for(read_frame(self.reader), timeout=self.timeout_sec)
        if not reply.headers.get("Reply-Text", "").startswith("+OK"):
            await self.close()
            raise EslError("FreeSWITCH Event Socket authentication failed")

    async def send(self, command: str) -> None:
        if self.writer is None:
            raise EslError("FreeSWITCH Event Socket is not connected")
        if "\r" in command or "\n" in command:
            raise EslError("FreeSWITCH command contains a newline")
        self.writer.write(command.encode("utf-8") + b"\n\n")
        await asyncio.wait_for(self.writer.drain(), timeout=self.timeout_sec)

    async def command(self, command: str) -> EslFrame:
        await self.send(command)
        if self.reader is None:
            raise EslError("FreeSWITCH Event Socket is not connected")
        return await asyncio.wait_for(read_frame(self.reader), timeout=self.timeout_sec)

    async def close(self) -> None:
        if self.writer is None:
            return
        self.writer.close()
        try:
            await self.writer.wait_closed()
        except (ConnectionError, OSError):
            pass
        self.writer = None
        self.reader = None


class EslClient:
    def __init__(self, host: str, port: int, password: str, timeout_sec: float = 5.0):
        self.host = host
        self.port = port
        self.password = password
        self.timeout_sec = timeout_sec

    def _connection(self) -> EslConnection:
        return EslConnection(self.host, self.port, self.password, self.timeout_sec)

    async def api(self, command: str) -> str:
        connection = self._connection()
        try:
            await connection.connect()
            frame = await connection.command(f"api {command}")
            body = frame.body.decode("utf-8", errors="replace").strip()
            reply = body or frame.headers.get("Reply-Text", "")
            if reply.startswith("-ERR"):
                raise EslError(reply)
            return reply
        finally:
            await connection.close()

    async def bgapi(self, command: str) -> str:
        connection = self._connection()
        try:
            await connection.connect()
            frame = await connection.command(f"bgapi {command}")
            reply = frame.headers.get("Reply-Text", "")
            if not reply.startswith("+OK"):
                raise EslError(reply or "FreeSWITCH rejected bgapi command")
            marker = "Job-UUID:"
            return reply.split(marker, 1)[1].strip() if marker in reply else ""
        finally:
            await connection.close()

    async def events(self, names: tuple[str, ...]) -> AsyncIterator[dict[str, object]]:
        connection = self._connection()
        try:
            await connection.connect()
            reply = await connection.command(f"event json {' '.join(names)}")
            if not reply.headers.get("Reply-Text", "").startswith("+OK"):
                raise EslError("FreeSWITCH rejected event subscription")
            if connection.reader is None:
                raise EslError("FreeSWITCH Event Socket is not connected")
            while True:
                frame = await read_frame(connection.reader)
                if frame.content_type == "text/event-json":
                    yield frame.json()
                elif frame.content_type == "text/disconnect-notice":
                    raise EslError("FreeSWITCH disconnected the event listener")
        finally:
            await connection.close()
