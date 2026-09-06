"""Platform-owned service destinations and DNS-pinned customer callbacks."""
from __future__ import annotations

import asyncio
import ipaddress
import socket
from urllib.parse import urlsplit

import httpx

from ..config import get_settings


def normalized_endpoint(value: str) -> str:
    if any(ord(c) < 33 or ord(c) == 127 for c in value):
        raise ValueError('invalid service endpoint')
    parsed = urlsplit(value)
    if parsed.scheme not in {'http', 'https'} or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError('service endpoint must be an absolute HTTP URL without credentials')
    if parsed.query or parsed.fragment:
        raise ValueError('service endpoint cannot contain a query or fragment')
    port = parsed.port or (443 if parsed.scheme == 'https' else 80)
    host = parsed.hostname.lower().rstrip('.')
    host = f'[{host}]' if ':' in host else host
    return f'{parsed.scheme}://{host}:{port}{parsed.path.rstrip("/")}'


def require_platform_endpoint(value: str, approved: str, service: str) -> str:
    if not approved or normalized_endpoint(value) != normalized_endpoint(approved):
        raise ValueError(f'{service} endpoint is managed by the platform')
    # Use the platform spelling; never interpolate the tenant's input into a URL.
    return approved.rstrip('/')


def callback_origin(value: str) -> str:
    parsed = urlsplit(value)
    if parsed.username or parsed.password or parsed.fragment or not parsed.hostname:
        raise ValueError('invalid callback destination')
    return normalized_endpoint(f'{parsed.scheme}://{parsed.netloc}')


def validate_callback_destination(value: str) -> str:
    settings = get_settings()
    origin = callback_origin(value)
    allowed = {normalized_endpoint(v.strip()) for v in settings.business_callback_allowed_origins.split(',') if v.strip()}
    if origin not in allowed:
        raise ValueError('callback origin is not registered by the platform')
    if settings.env.lower() in {'prod', 'production'} and not value.startswith('https://'):
        raise ValueError('production callbacks require HTTPS')
    return origin


class CallbackTransport(httpx.AsyncHTTPTransport):
    """Resolve once, reject non-public IPs, connect to that IP with original TLS SNI.

    Network proxies and redirects are disabled by the caller. Exact private
    origins require a separate platform-controlled exception, never tenant input.
    """
    async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
        origin = validate_callback_destination(str(request.url))
        settings = get_settings()
        private = {normalized_endpoint(v.strip()) for v in settings.business_callback_private_origins.split(',') if v.strip()}
        host = request.url.host
        port = request.url.port or (443 if request.url.scheme == 'https' else 80)
        addresses = await asyncio.wait_for(asyncio.get_running_loop().getaddrinfo(host, port, type=socket.SOCK_STREAM), 3)
        ips = list(dict.fromkeys(item[4][0] for item in addresses))
        if not ips or (origin not in private and any(not ipaddress.ip_address(ip).is_global for ip in ips)):
            raise ValueError('callback DNS resolved to an unauthorized network')
        request.headers['Host'] = request.url.netloc.decode('ascii')
        request.extensions['sni_hostname'] = host
        request.url = request.url.copy_with(host=ips[0])
        return await super().handle_async_request(request)
