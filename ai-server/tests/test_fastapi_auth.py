"""The 2026-11-09 isolated run read STAFF_ONLY documents with no credential by sending audience=staff. Service identity and user identity stay separate."""
import base64
import hashlib
import hmac
import json
import os
import time
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient

SECRET = 'unit-test-jwt-secret-with-enough-entropy-0123456789'
ENV = {'APP_JWT_SECRET': SECRET, 'APP_JWT_ISSUER': 'banking-ax', 'STAFF_AGENT_GATEWAY_TOKEN': 'staff-svc', 'CUSTOMER_AGENT_INTERNAL_TOKEN': 'cust-svc', 'OPENAI_API_KEY': ''}
RESULT = {'chunk_id': 'c', 'doc_id': 'C01-03', 'title': 't', 'section': 's', 'text': 'x', 'score': .1, 'source_file': 'f', 'source_pages': [1], 'verified': True, 'context_text': 'x', 'context_chunk_ids': ['c'], 'context_sections': ['s']}
BODY = {'query': '합의 해지 효과', 'task_code': 'DEBT_ADJUSTMENT', 'top_k': 3}


def b64(raw):
    return base64.urlsafe_b64encode(raw).rstrip(b'=').decode()


def token(role='CUSTOMER', secret=SECRET, issuer='banking-ax', expires=3600, alg='HS256', sign=True):
    head = b64(json.dumps({'alg': alg}).encode())
    claims = {'iss': issuer, 'sub': '7', 'exp': int(time.time()) + expires}
    if role:
        claims['role'] = role
    body = b64(json.dumps(claims).encode())
    signature = b64(hmac.new(secret.encode(), f'{head}.{body}'.encode(), hashlib.sha256).digest()) if sign else ''
    return f'{head}.{body}.{signature}'


def bearer(*args, **kwargs):
    return {'Authorization': 'Bearer ' + token(*args, **kwargs)}


@pytest.fixture()
def client():
    with patch.dict(os.environ, ENV):
        from app.main import app
        yield TestClient(app)


def search(client, audience, headers=None):
    with patch('app.main._run_search', return_value=[RESULT]) as run:
        response = client.post('/api/v1/rag/search', json={**BODY, 'audience': audience}, headers=headers or {})
    return response, run


@pytest.mark.parametrize('audience', ['customer', 'staff'])
def test_no_credential_is_rejected_before_any_search(client, audience):
    response, run = search(client, audience)
    assert response.status_code == 401 and not run.called


def test_the_audience_field_does_not_grant_staff_access(client):
    response, run = search(client, 'staff', bearer('CUSTOMER'))
    assert response.status_code == 403 and not run.called


def test_a_customer_gets_customer_documents(client):
    response, run = search(client, 'customer', bearer('CUSTOMER'))
    assert response.status_code == 200 and run.call_args.args[0].audience == 'customer'


@pytest.mark.parametrize('role', ['EMPLOYEE', 'ADMIN'])
def test_staff_identity_comes_from_the_signed_role(client, role):
    assert search(client, 'staff', bearer(role))[0].status_code == 200


def test_the_staff_service_may_read_staff_documents(client):
    assert search(client, 'staff', {'X-Staff-Agent-Token': 'staff-svc'})[0].status_code == 200


def test_the_customer_service_cannot_read_staff_documents(client):
    assert search(client, 'staff', {'X-Customer-Agent-Token': 'cust-svc'})[0].status_code == 403


@pytest.mark.parametrize('bad', [
    {'secret': 'another-secret-with-enough-entropy-abcdefghij'}, {'issuer': 'someone-else'}, {'expires': -10}, {'alg': 'none', 'sign': False}, {'sign': False},
], ids=['wrong-key', 'wrong-issuer', 'expired', 'alg-none', 'unsigned'])
def test_an_invalid_token_is_rejected(client, bad):
    response, run = search(client, 'staff', bearer('EMPLOYEE', **bad))
    assert response.status_code == 401 and not run.called


def test_a_token_without_a_known_role_gets_nothing(client):
    assert search(client, 'customer', bearer(None))[0].status_code == 403
    assert search(client, 'customer', bearer('SOMETHING'))[0].status_code == 403


def test_a_wrong_service_token_is_rejected(client):
    assert search(client, 'staff', {'X-Staff-Agent-Token': 'nope'})[0].status_code == 401


def test_it_fails_closed_when_the_jwt_secret_is_not_configured():
    with patch.dict(os.environ, {**ENV, 'APP_JWT_SECRET': ''}):
        from app.main import app
        assert search(TestClient(app), 'customer', bearer('CUSTOMER'))[0].status_code == 401


@pytest.mark.parametrize('path,body', [('/api/v1/rag/answer', BODY), ('/api/v1/rag/task-guide', {'query': '상속예금 서류'}), ('/api/v1/customer-agent/classify', {'message': '대출 조회해줘'})])
def test_routes_that_reach_the_model_reject_no_credential(client, path, body):
    with patch('openai.resources.chat.completions.Completions.create') as provider:
        response = client.post(path, json=body)
    assert response.status_code == 401 and not provider.called


def test_staff_ranking_needs_the_service_token(client):
    body = {'candidateIds': [1], 'taskCode': 'INHERITANCE_DEPOSIT'}
    assert client.post('/api/v1/staff-ranking', json=body).status_code == 403
    assert client.post('/api/v1/staff-ranking', json=body, headers={'X-Staff-Agent-Token': 'staff-svc'}).status_code == 200


def test_classify_accepts_only_the_customer_service_token(client):
    assert client.post('/api/v1/customer-agent/classify', json={'message': '대출 조회해줘'}, headers={'X-Staff-Agent-Token': 'staff-svc'}).status_code == 401


def test_a_customer_token_cannot_reach_internal_agent_routes(client):
    response = client.post('/api/v1/staff-agent/case-brief', json={'facts': {}}, headers=bearer('CUSTOMER'))
    assert response.status_code == 403
