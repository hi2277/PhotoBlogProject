import re
import socket
from pathlib import Path

HOST = "127.0.0.1"
PORT = 8001

output_dir = Path(__file__).resolve().parent / "request"
output_dir.mkdir(exist_ok=True)

with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((HOST, PORT))
    server.listen(5)

    print(f"Listening on http://{HOST}:{PORT}")
    print("Waiting for Android request...")

    while True:
        client, address = server.accept()

        with client:
            client.settimeout(10)
            data = b""

            try:
                while b"\r\n\r\n" not in data:
                    chunk = client.recv(4096)
                    if not chunk:
                        break
                    data += chunk

                if not data:
                    continue

                headers = data.split(b"\r\n\r\n", 1)[0]
                text = headers.decode("iso-8859-1")

                # Hide the token in the report evidence.
                text = re.sub(
                    r"(?im)^Authorization:[^\r\n]*",
                    "Authorization: Token [hidden]",
                    text,
                )

                path = output_dir / "client_request.txt"
                path.write_text(text + "\n", encoding="utf-8")
                print(f"Request saved: {path}")

                body = b"[]"
                response = (
                    b"HTTP/1.1 200 OK\r\n"
                    b"Content-Type: application/json\r\n"
                    b"Content-Length: 2\r\n"
                    b"Connection: close\r\n"
                    b"\r\n"
                    + body
                )
                client.sendall(response)

            except (OSError, TimeoutError) as error:
                print(f"Connection error: {error}")