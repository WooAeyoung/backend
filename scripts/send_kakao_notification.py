import base64
import json
import os
import sys
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from nacl.public import PublicKey, SealedBox


def request_json(url: str, method: str = "GET", data: dict | None = None, headers: dict | None = None):
    body = None if data is None else json.dumps(data).encode("utf-8")
    request = Request(url, data=body, method=method, headers=headers or {})
    with urlopen(request, timeout=20) as response:
        raw = response.read()
    return json.loads(raw) if raw else {}


def refresh_access_token() -> str:
    form = urlencode({
        "grant_type": "refresh_token",
        "client_id": os.environ["KAKAO_REST_API_KEY"],
        "client_secret": os.environ["KAKAO_CLIENT_SECRET"],
        "refresh_token": os.environ["KAKAO_REFRESH_TOKEN"],
    }).encode("utf-8")
    request = Request(
        "https://kauth.kakao.com/oauth/token",
        data=form,
        method="POST",
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    with urlopen(request, timeout=20) as response:
        token_data = json.loads(response.read())
    rotated = token_data.get("refresh_token")
    if rotated and rotated != os.environ["KAKAO_REFRESH_TOKEN"]:
        update_repo_secret("KAKAO_REFRESH_TOKEN", rotated)
    return token_data["access_token"]


def update_repo_secret(name: str, value: str) -> None:
    repository = os.environ["GITHUB_REPOSITORY"]
    token = os.environ["KAKAO_SECRET_UPDATER_TOKEN"]
    base = f"https://api.github.com/repos/{repository}/actions/secrets"
    headers = {
        "Accept": "application/vnd.github+json",
        "Authorization": f"Bearer {token}",
        "User-Agent": "wooaeyoung-kakao-notifications",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    public_key = request_json(f"{base}/public-key", headers=headers)
    encrypted = SealedBox(PublicKey(base64.b64decode(public_key["key"]))).encrypt(value.encode())
    request_json(
        f"{base}/{name}",
        method="PUT",
        data={
            "encrypted_value": base64.b64encode(encrypted).decode("ascii"),
            "key_id": public_key["key_id"],
        },
        headers={**headers, "Content-Type": "application/json"},
    )


def send_message(access_token: str, text: str, url: str) -> None:
    template = {
        "object_type": "text",
        "text": text[:200],
        "link": {"web_url": url, "mobile_web_url": url},
        "button_title": "GitHub에서 보기",
    }
    form = urlencode({"template_object": json.dumps(template, ensure_ascii=False)}).encode("utf-8")
    request = Request(
        "https://kapi.kakao.com/v2/api/talk/memo/default/send",
        data=form,
        method="POST",
        headers={
            "Authorization": f"Bearer {access_token}",
            "Content-Type": "application/x-www-form-urlencoded;charset=utf-8",
        },
    )
    with urlopen(request, timeout=20) as response:
        response.read()


def main() -> None:
    event_name = os.environ["GITHUB_EVENT_NAME"]
    repository = os.environ["GITHUB_REPOSITORY"]
    with open(os.environ["GITHUB_EVENT_PATH"], encoding="utf-8") as event_file:
        event = json.load(event_file)
    access_token = refresh_access_token()

    if event_name == "push":
        commits = event.get("commits") or []
        if not commits:
            print("Push event contains no commits; nothing to notify.")
            return
        for commit in commits:
            sha = commit.get("id", "")
            url = commit.get("url") or f"https://github.com/{repository}/commit/{sha}"
            title = (commit.get("message") or "(메시지 없음)").splitlines()[0]
            author = (commit.get("author") or {}).get("name", "알 수 없음")
            text = f"[{repository}] 새 커밋\n{sha[:7]} {title}\n작성자: {author}"
            send_message(access_token, text, url)
        return

    if event_name == "issues":
        issue = event.get("issue", {})
        repository_url = event.get("repository", {}).get("html_url", f"https://github.com/{repository}")
        text = f"[{repository}] 새 이슈 #{issue.get('number')}\n{issue.get('title', '(제목 없음)')}\n작성자: {(issue.get('user') or {}).get('login', '알 수 없음')}"
        send_message(access_token, text, issue.get("html_url") or repository_url)
        return

    if event_name == "workflow_dispatch":
        url = f"https://github.com/{repository}/actions/workflows/kakao-notifications.yml"
        send_message(access_token, f"[{repository}] 카카오 알림 연결 테스트 성공", url)
        return

    print(f"Unsupported event: {event_name}", file=sys.stderr)
    raise SystemExit(1)


if __name__ == "__main__":
    main()
