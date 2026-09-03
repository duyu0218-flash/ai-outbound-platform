import socket
from pathlib import Path

password = Path('/run/secrets/voismart_esl_password').read_text().strip()
with socket.create_connection(('127.0.0.1', 8021), timeout=3) as sock:
    sock.settimeout(3)
    stream = sock.makefile('rb')

    def frame():
        headers = {}
        while (line := stream.readline().strip()):
            key, _, value = line.partition(b':')
            headers[key] = value.strip()
        return headers, stream.read(int(headers.get(b'Content-Length', b'0')))

    frame()
    sock.sendall(f'auth {password}\n\n'.encode())
    headers, _ = frame()
    assert headers.get(b'Reply-Text', b'').startswith(b'+OK')
    sock.sendall(b'api module_exists mod_openai_audio_stream\n\n')
    _, body = frame()
    assert body.strip() == b'true', 'VoiSmart module is not loaded'
