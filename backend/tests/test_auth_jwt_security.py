"""Keep JWT error handling and algorithm checks intact after security upgrades."""

import base64
import hashlib
import hmac

import jwt
import pytest
from fastapi import HTTPException

from app.services import auth


# Synthetic signing material, with enough bytes for both test algorithms.
TEST_KEY = "jwt-security-regression-" + "x" * 64


@pytest.fixture(autouse=True)
def isolated_jwt_settings(monkeypatch):
    monkeypatch.setattr(auth.settings, "jwt_secret", TEST_KEY)
    monkeypatch.setattr(auth.settings, "jwt_algorithm", "HS256")


def assert_invalid(token):
    with pytest.raises(HTTPException) as exc:
        auth.parse_token(token)
    assert exc.value.status_code == 401


def test_valid_access_token_round_trip():
    token = auth.create_access_token("synthetic-user", 7, "agent", 42, 3)
    payload = auth.parse_token(token)
    assert {k: payload[k] for k in ("sub", "tenant_id", "role", "uid", "tv")} == {
        "sub": "synthetic-user", "tenant_id": 7, "role": "agent", "uid": 42, "tv": 3,
    }


def test_expired_token_is_unauthorized():
    assert_invalid(jwt.encode({"sub": "synthetic-user", "exp": -1}, TEST_KEY, algorithm="HS256"))


def test_wrong_signature_is_unauthorized():
    token = jwt.encode({"sub": "synthetic-user"}, "wrong-test-key-" + "z" * 64, algorithm="HS256")
    assert_invalid(token)


def test_unapproved_algorithm_is_unauthorized():
    assert_invalid(jwt.encode({"sub": "synthetic-user"}, TEST_KEY, algorithm="HS384"))


def test_unsigned_token_is_unauthorized():
    assert_invalid(jwt.encode({"sub": "synthetic-user"}, None, algorithm="none"))


@pytest.mark.parametrize("claim", ["exp", "nbf", "iat"])
@pytest.mark.parametrize("value", [None, [], {}])
def test_malformed_numeric_claim_is_unauthorized(claim, value):
    token = jwt.encode({"sub": "synthetic-user", claim: value}, TEST_KEY, algorithm="HS256")
    assert_invalid(token)


def test_deeply_nested_payload_is_unauthorized():
    def segment(value):
        return base64.urlsafe_b64encode(value).rstrip(b"=")

    # Sign valid JSON without using a recursive Python object encoder. Decoding
    # exceeds the parser recursion limit and must produce 401 instead of 500.
    payload = b'{"sub":"synthetic-user","nested":' + b"[" * 10000 + b"0" + b"]" * 10000 + b"}"
    signing_input = segment(b'{"alg":"HS256","typ":"JWT"}') + b"." + segment(payload)
    signature = hmac.new(TEST_KEY.encode(), signing_input, hashlib.sha256).digest()
    assert_invalid((signing_input + b"." + segment(signature)).decode())
