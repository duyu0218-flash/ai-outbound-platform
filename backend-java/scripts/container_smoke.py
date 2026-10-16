"""CI-only loopback smoke against a disposable Java mock container."""
import json
import http.client
import time
import urllib.request
import urllib.error

BASE = 'http://127.0.0.1:18823'
token = ''
def request(path, body=None, method=None):
    data = None if body is None else json.dumps(body).encode()
    r = urllib.request.Request(BASE+path, data=data, method=method,
          headers={'Content-Type':'application/json', 'Authorization':'Bearer '+token})
    with urllib.request.urlopen(r, timeout=5) as response:
        return json.load(response)

for attempt in range(60):
    try:
        token = request('/api/v1/auth/login', {'username':'admin','password':'12345678'})['access_token']
        break
    except (OSError, http.client.HTTPException, KeyError):
        if attempt == 59: raise
        time.sleep(1)
with urllib.request.urlopen(BASE+'/admin/login') as page:
    assert b'<html' in page.read().lower()
assert request('/healthz')['status']=='ok'
try:
    request('/readyz')
    raise AssertionError('release acceptance must default to disabled')
except urllib.error.HTTPError as error:
    assert error.code == 503
data=request('/api/v1/admin/settings/compliance')['data']
data.update(allowed_start_hour=0,allowed_end_hour=0)
request('/api/v1/admin/settings/compliance',{'data':data},'PUT')
contact=request('/api/v1/contacts',{'phone':'13900000088','name':'Container synthetic contact','consent_state':'consented'})
call=request('/api/v1/calls',{'contact_id':contact['id'],'mode':'human_only','max_attempts':1})
for attempt in range(60):
    if request('/api/v1/calls/'+call['id'])['status']=='completed': break
    if attempt==59: raise AssertionError('mock call did not complete')
    time.sleep(.5)
for attempt in range(30):
    if request('/api/v1/calls/'+call['id']+'/analysis') is not None: break
    if attempt==29: raise AssertionError('analysis did not persist')
    time.sleep(.5)
print('Java image: login, frontend, release gate, contact, mock dial, callback and analysis passed')
