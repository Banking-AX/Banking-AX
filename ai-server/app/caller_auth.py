"""Who is calling FastAPI. Two separate questions, never mixed:

* Service identity: a shared token proves the caller is Spring's staff or customer gateway. Spring has already checked the user.
* User identity: a browser presents the access token Spring signed. Its role decides what that person may read.

The audience in a request body is only what the caller asks for; what they may have comes from here. Missing configuration fails closed.
"""
from __future__ import annotations

import base64
import binascii
import hashlib
import hmac
import json
import os
import time
from dataclasses import dataclass

from fastapi import Depends, Header, HTTPException

CUSTOMER, STAFF = 'customer', 'staff'
ROLES = {'CUSTOMER': CUSTOMER, 'EMPLOYEE': STAFF, 'ADMIN': STAFF}


@dataclass(frozen=True)
class Caller:
    kind: str  # SERVICE or USER
    audience: str
    subject: str | None = None


def _matches(given: str | None, name: str) -> bool:
    expected = os.getenv(name, '').strip()
    return bool(expected and given) and hmac.compare_digest(given, expected)


def _decode(part: str) -> bytes:
    return base64.urlsafe_b64decode(part + '=' * (-len(part) % 4))


def _claims(token: str) -> dict | None:
    """Same key material, algorithm, issuer and expiry as Spring's decoder (JwtKeyMaterial, HS256)."""
    secret = os.getenv('APP_JWT_SECRET', '').strip().encode('utf-8')
    if len(secret) < 32:
        return None
    try:
        head, body, signature = token.split('.')
        if json.loads(_decode(head)).get('alg') != 'HS256':
            return None
        expected = hmac.new(secret, f'{head}.{body}'.encode('ascii'), hashlib.sha256).digest()
        if not hmac.compare_digest(expected, _decode(signature)):
            return None
        claims = json.loads(_decode(body))
    except (ValueError, binascii.Error, UnicodeError):
        return None
    if not isinstance(claims, dict) or claims.get('iss') != os.getenv('APP_JWT_ISSUER', 'banking-ax'):
        return None
    expires = claims.get('exp')
    return claims if isinstance(expires, (int, float)) and expires > time.time() else None


def rag_caller(authorization: str | None = Header(default=None),
               x_staff_agent_token: str | None = Header(default=None),
               x_customer_agent_token: str | None = Header(default=None)) -> Caller:
    if _matches(x_staff_agent_token, 'STAFF_AGENT_GATEWAY_TOKEN'):
        return Caller('SERVICE', STAFF)
    if _matches(x_customer_agent_token, 'CUSTOMER_AGENT_INTERNAL_TOKEN'):
        return Caller('SERVICE', CUSTOMER)
    if authorization and authorization.startswith('Bearer '):
        claims = _claims(authorization[len('Bearer '):].strip())
        if claims is not None:
            audience = ROLES.get(claims.get('role'))
            if audience is None:
                raise HTTPException(403, 'CALLER_ROLE_NOT_ALLOWED')
            return Caller('USER', audience, str(claims.get('sub')))
    raise HTTPException(401, 'CALLER_AUTH_REQUIRED')


def require_audience(requested: str, caller: Caller) -> None:
    if requested == STAFF and caller.audience != STAFF:
        raise HTTPException(403, 'AUDIENCE_NOT_ALLOWED')


def require_staff_service(x_staff_agent_token: str | None = Header(default=None)) -> None:
    if not _matches(x_staff_agent_token, 'STAFF_AGENT_GATEWAY_TOKEN'):
        raise HTTPException(403, 'STAFF_AGENT_GATEWAY_FORBIDDEN')


__all__ = ['Caller', 'Depends', 'rag_caller', 'require_audience', 'require_staff_service']
