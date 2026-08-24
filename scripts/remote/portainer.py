"""O ÚNICO cliente HTTP do CLI: Docker API proxeada pelo Portainer + API do Portainer.

Só stdlib (o repo não tem dependências de runtime em Python). A chave viaja
exclusivamente no header `X-API-Key` -- nunca em argv (visível em `ps` para qualquer
usuário da máquina) nem em query string (vai para o log de acesso do Portainer).
"""

import json
import time
import urllib.error
import urllib.parse
import urllib.request

from util import AuthError, RemoteError, UnreachableError, demux, redact

RETRYABLE = (502, 503, 504)


class Client:
    def __init__(self, settings, verbose=False, dry_run=False):
        self.settings = settings
        self.verbose = verbose
        self.dry_run = dry_run
        self.docker_base = f"/api/endpoints/{settings.endpoint}/docker"
        self._secrets = None

    # --- transporte -----------------------------------------------------------

    def hide(self, text):
        if self._secrets is None:
            self._secrets = self.settings.secret_values()
        return redact(text, self._secrets)

    def request(self, method, path, body=None, raw=None, ctype="application/json", timeout=None):
        """Devolve (status, bytes). Erros HTTP não levantam: o chamador decide."""
        timeout = timeout or self.settings.timeout
        url = self.settings.url + path
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        attempts = 3 if method == "GET" else 1
        last = None
        for attempt in range(attempts):
            req = urllib.request.Request(url, data=data, method=method)
            # O header é a única cópia da chave em trânsito, e --verbose nunca
            # imprime headers justamente por isso.
            req.add_header("X-API-Key", self.settings.key)
            if data is not None:
                req.add_header("Content-Type", ctype)
            if self.verbose:
                print(f"  → {method} {path}")
            try:
                with urllib.request.urlopen(req, timeout=timeout) as resp:
                    return resp.status, resp.read()
            except urllib.error.HTTPError as exc:
                payload = exc.read()
                if exc.code in (401, 403):
                    raise AuthError(
                        "Portainer recusou a API key (HTTP %d). Gere outra em "
                        "Portainer → My account → Access tokens e atualize "
                        "scripts/remote.env." % exc.code
                    ) from None
                # Retry só em GET: repetir um POST que já pode ter criado o
                # container transformaria um 502 de gateway em dois containers.
                if exc.code in RETRYABLE and method == "GET" and attempt + 1 < attempts:
                    time.sleep(2 ** attempt)
                    last = (exc.code, payload)
                    continue
                return exc.code, payload
            except (urllib.error.URLError, TimeoutError, OSError) as exc:
                if method == "GET" and attempt + 1 < attempts:
                    time.sleep(2 ** attempt)
                    last = None
                    continue
                raise UnreachableError(
                    f"Portainer inalcançável em {self.settings.url}: {exc}"
                ) from None
        return last or (0, b"")

    def json_request(self, method, path, body=None, timeout=None):
        status, payload = self.request(method, path, body=body, timeout=timeout)
        if not payload:
            return status, None
        try:
            return status, json.loads(payload)
        except ValueError:
            return status, payload.decode("utf-8", "replace")

    # --- Docker API -----------------------------------------------------------

    def docker(self, method, path, **kwargs):
        if self.dry_run and method != "GET":
            print(f"  [dry-run] {method} docker{path}")
            return 0, b""
        return self.request(method, self.docker_base + path, **kwargs)

    def docker_json(self, method, path, body=None, timeout=None):
        if self.dry_run and method != "GET":
            print(f"  [dry-run] {method} docker{path}")
            return None
        status, data = self.json_request(method, self.docker_base + path, body, timeout)
        if status >= 400:
            return None
        return data

    def must(self, method, path, body=None, ok=(200, 201, 204, 304), timeout=None):
        """Mutação que precisa dar certo: falha com a mensagem do Docker, já redigida."""
        if self.dry_run and method != "GET":
            print(f"  [dry-run] {method} docker{path}")
            return None
        status, payload = self.request(method, self.docker_base + path, body=body, timeout=timeout)
        if status not in ok:
            text = self.hide(payload.decode("utf-8", "replace"))[:600]
            raise RemoteError(f"{method} {path} → HTTP {status}: {text}")
        try:
            return json.loads(payload) if payload else None
        except ValueError:
            return None

    # --- API do Portainer (stacks) -------------------------------------------

    def portainer_json(self, method, path, body=None, timeout=None):
        if self.dry_run and method != "GET":
            print(f"  [dry-run] {method} {path}")
            return 0, None
        return self.json_request(method, "/api" + path, body, timeout)

    # --- primitivas de container ---------------------------------------------

    def version(self):
        status, data = self.json_request("GET", self.docker_base + "/version")
        if status != 200 or not isinstance(data, dict):
            raise RemoteError(f"GET /version → HTTP {status}")
        return data

    def inspect(self, name):
        status, data = self.json_request("GET", self.docker_base + f"/containers/{name}/json")
        return data if status == 200 else None

    def exec_run(self, container, cmd, env=None, timeout=None):
        """docker exec -> (exit_code, texto). Segredo vai por Env, jamais em argv."""
        body = {"AttachStdout": True, "AttachStderr": True, "Tty": True, "Cmd": cmd}
        if env:
            body["Env"] = env
        created = self.must("POST", f"/containers/{container}/exec", body, ok=(200, 201))
        if created is None:  # dry-run
            return 0, ""
        exec_id = created["Id"]
        _, out = self.request(
            "POST",
            self.docker_base + f"/exec/{exec_id}/start",
            body={"Detach": False, "Tty": True},
            timeout=timeout,
        )
        _, state = self.json_request("GET", self.docker_base + f"/exec/{exec_id}/json")
        return (state or {}).get("ExitCode"), demux(out)

    def sh(self, container, script, env=None, timeout=None, shell="sh"):
        """`sh -lc` por padrão; `shell="bash"` para quem precisa de recurso de bash.

        O padrão continua `sh` de propósito -- é o que existe em qualquer imagem, e a
        maioria dos usos aqui é `cat`/`rm`/`printf`. Quem precisa de mais que POSIX pede
        explicitamente, e assume que a imagem daquele container tem bash.
        """
        return self.exec_run(container, [shell, "-lc", script], env=env, timeout=timeout)

    def logs(self, container, tail="200", since=0):
        status, payload = self.docker(
            "GET", f"/containers/{container}/logs?stdout=1&stderr=1&tail={tail}&since={since}"
        )
        if status != 200:
            raise RemoteError(f"logs de {container} → HTTP {status}")
        return demux(payload)

    def follow(self, container, since=0, out=print):
        """Segue o log até o container parar. Streaming linha a linha, sem bufferizar tudo."""
        path = (
            f"{self.docker_base}/containers/{container}/logs"
            f"?stdout=1&stderr=1&follow=1&since={since}"
        )
        req = urllib.request.Request(self.settings.url + path, method="GET")
        req.add_header("X-API-Key", self.settings.key)
        try:
            with urllib.request.urlopen(req, timeout=self.settings.timeout) as resp:
                for line in resp:
                    text = demux(line).rstrip("\n")
                    if text:
                        out(self.hide(text))
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            raise UnreachableError(f"stream de log interrompido: {exc}") from None

    def wait(self, container, timeout=None):
        status, data = self.json_request(
            "POST", self.docker_base + f"/containers/{container}/wait", timeout=timeout
        )
        if status != 200:
            raise RemoteError(f"wait {container} → HTTP {status}")
        return (data or {}).get("StatusCode", 1)

    def remove(self, name, force=True):
        self.docker("DELETE", f"/containers/{name}?force={1 if force else 0}&v=0")

    def create_and_start(self, name, spec):
        # Remove antes de criar: um container auxiliar órfão de uma execução
        # interrompida (Ctrl-C no meio do sync) segura o nome e todo run seguinte
        # morreria com "name already in use".
        self.remove(name)
        self.must("POST", f"/containers/create?name={name}", spec, ok=(200, 201))
        self.must("POST", f"/containers/{name}/start", ok=(204, 304))

    def start(self, name):
        self.must("POST", f"/containers/{name}/start", ok=(204, 304))

    def stop(self, name, seconds=120):
        self.must("POST", f"/containers/{name}/stop?t={seconds}", ok=(204, 304))

    def restart(self, name, seconds=120):
        self.must("POST", f"/containers/{name}/restart?t={seconds}", ok=(204, 304))

    def list_volumes(self):
        data = self.docker_json("GET", "/volumes") or {}
        return [v.get("Name") for v in (data.get("Volumes") or [])]

    def create_volume(self, name):
        """Idempotente. Tem de rodar ANTES de um deploy que declare o volume: os
        volumes deste stack são `external: true`, e o compose recusa o stack INTEIRO
        quando um deles não existe -- o que num scale-out significaria derrubar a rede
        para acrescentar um nó."""
        if name in (self.list_volumes() or []):
            return name
        self.must("POST", "/volumes/create", {"Name": name}, ok=(200, 201))
        return name

    def put_archive(self, container, path, blob):
        query = urllib.parse.urlencode({"path": path})
        status, payload = self.docker(
            "PUT",
            f"/containers/{container}/archive?{query}",
            raw=blob,
            ctype="application/x-tar",
        )
        if status not in (200, 0):
            text = self.hide(payload.decode("utf-8", "replace"))[:400]
            raise RemoteError(f"upload para {container}:{path} → HTTP {status}: {text}")
