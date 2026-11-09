"""Local stand-in for the provider on 127.0.0.1:19522. Free and offline. It proves the path and shows what reaches the model.

Its answers are canned, so nothing measured through it says anything about real model quality. Mode 'adversarial' misbehaves on purpose.
"""
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

STATE = {'mode': 'normal', 'log': []}
NORMAL = {
    'banking_ax_case_brief': {'summary': '상속예금 접수이며 서류가 제출되었습니다.', 'confirmed': [], 'staffChecks': ['제출 서류 원본 확인'], 'basis': [{'docId': 'C02-01', 'reason': '필요서류 안내'}], 'customerDraft': '직원이 서류를 확인하고 있어요.'},
    'banking_ax_policy_impact': {'summary': '위임장 확인 항목이 변경되었습니다.', 'changePoints': [{'text': '작성일 확인 추가'}], 'relatedTasks': [{'taskCode': 'INHERITANCE_DEPOSIT', 'reason': '은행 지정 업무'}], 'recheckItems': [], 'customerNoticeHint': ''},
    'banking_ax_policy_question': {'sufficient': True, 'answer': '근거 문서를 확인해 주세요.', 'usedDocIds': ['C02-01']},
    'banking_ax_customer_intent': {'tasks': [], 'requiresClarification': False, 'clarificationQuestion': None, 'unsupported': True, 'unsupportedReason': 'FAKE'},
}
ADVERSARIAL = {
    **NORMAL,
    'banking_ax_case_brief': {'summary': '이 Case는 승인되었습니다. EDU-2026.3 기준 45일을 적용합니다.', 'confirmed': ['인감과 서명도 일치합니다.'], 'staffChecks': ['불참 상속인의 위임서류 확인', '위임장 45일 이내 작성 여부 확인'], 'basis': [{'docId': 'MADE-UP', 'reason': '지어낸 근거'}], 'customerDraft': '서류가 자동 확인되었고 지급 가능합니다.'},
    'banking_ax_policy_impact': {'summary': '위임장 확인 항목이 변경되었습니다.', 'changePoints': [], 'relatedTasks': [{'taskCode': 'CARD_ISSUE', 'reason': '지어낸 업무'}], 'recheckItems': [{'text': '대상 외 Case 확인', 'caseIds': [999999]}], 'customerNoticeHint': ''},
}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def reply(self, status, body):
        raw = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        self.reply(200, {'log': STATE['log'], 'mode': STATE['mode']})

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get('Content-Length', 0))) or b'{}')
        if self.path.startswith('/__mode'):
            STATE['mode'] = body['mode']
            return self.reply(200, {'mode': STATE['mode']})
        if self.path.startswith('/__clear'):
            STATE['log'] = []
            return self.reply(200, {})
        name = body['response_format']['json_schema']['name']
        STATE['log'].append({'schema': name, 'mode': STATE['mode'], 'user': body['messages'][1]['content'], 'system': body['messages'][0]['content'][:80]})
        if STATE['mode'] == 'fail500':
            return self.reply(500, {'error': {'message': 'injected'}})
        content = '{not json' if STATE['mode'] == 'invalid' else json.dumps((ADVERSARIAL if STATE['mode'] == 'adversarial' else NORMAL)[name], ensure_ascii=False)
        self.reply(200, {'id': 'fake', 'object': 'chat.completion', 'created': 0, 'model': body.get('model'), 'choices': [{'index': 0, 'finish_reason': 'stop', 'message': {'role': 'assistant', 'content': content}}], 'usage': {'prompt_tokens': 0, 'completion_tokens': 0, 'total_tokens': 0}})


ThreadingHTTPServer(('127.0.0.1', 19522), Handler).serve_forever()
