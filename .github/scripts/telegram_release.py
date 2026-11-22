#!/usr/bin/env python3
"""Announce a new WM Keyboard release to the Telegram community.

Posts release highlights, a changelog summary, and download links to the
announcements topic of the WM Keyboard Telegram supergroup, and attaches
the standard arm64 APK, which fits the Bot API's 50 MB upload cap now that
its feature libraries and interface languages download on demand.

Usage:
    python3 .github/scripts/telegram_release.py \\
        --tag v0.5.14 \\
        --version 0.5.14 \\
        --notes-file release-notes/0.5.14.md \\
        --dist dist

The bot token is read from TELEGRAM_TOKEN environment variable (or --token).

With --prerelease the post goes to the beta testing topic instead
(https://t.me/c/wmkeyboard/12671), says it is a test version, and says how
to get later ones.
"""

from __future__ import annotations

import argparse
import html
import json
import mimetypes
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import BinaryIO

DEFAULT_CHAT_ID = "@wmkeyboard"
DEFAULT_THREAD_ID = 19
# The beta testing topic, where pre-releases go (t.me/c/wmkeyboard/12671).
BETA_THREAD_ID = 12671
TELEGRAM_MAX_MESSAGE_LENGTH = 4096
TELEGRAM_MAX_FILE_SIZE = 50 * 1024 * 1024  # 50 MB Bot API hard limit


def format_size(bytes_count: int) -> str:
    """Format bytes into a human-readable string."""
    if bytes_count >= 1024 * 1024 * 1024:
        return f"{bytes_count / (1024 * 1024 * 1024):.1f} GB"
    if bytes_count >= 1024 * 1024:
        return f"{bytes_count / (1024 * 1024):.1f} MB"
    if bytes_count >= 1024:
        return f"{bytes_count / 1024:.0f} KB"
    return f"{bytes_count} B"


def markdown_to_telegram_html(text: str) -> str:
    """Convert common markdown formatting to Telegram-supported HTML.

    Escapes raw HTML characters first, then restores markdown formatting
    as valid Telegram HTML tags (<b>, <i>, <code>, <a href="...">).
    """
    # 1. Escape &, <, > (quotes do not need escaping in Telegram HTML text)
    safe = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    # 2. Convert markdown links: [text](url) -> <a href="url">text</a>
    def replace_link(match: re.Match[str]) -> str:
        label = match.group(1)
        raw_url = match.group(2)
        url = html.unescape(raw_url).replace('"', '%22')
        return f'<a href="{url}">{label}</a>'

    safe = re.sub(r'\[([^\]]+)\]\((https?://[^\s)]+)\)', replace_link, safe)

    # 3. Convert bold: **text** or __text__ -> <b>text</b>
    safe = re.sub(r'\*\*(.+?)\*\*', r'<b>\1</b>', safe)
    safe = re.sub(r'__(.+?)__', r'<b>\1</b>', safe)

    # 4. Convert italic: *text* or _text_ (when not part of a word)
    safe = re.sub(r'(?<!\w)\*([^*]+?)\*(?!\w)', r'<i>\1</i>', safe)
    safe = re.sub(r'(?<!\w)_([^_]+?)_(?!\w)', r'<i>\1</i>', safe)

    # 5. Convert inline code: `code` -> <code>code</code>
    safe = re.sub(r'`([^`]+)`', r'<code>\1</code>', safe)

    return safe


def parse_release_notes(raw_content: str) -> tuple[str, list[tuple[str, list[str]]]]:
    """Parse release notes into an intro highlight paragraph and section bullets."""
    # Strip HTML <details> and <summary> wrappers from release-body.md
    cleaned = re.sub(r'</?(?:details|summary)[^>]*>', '', raw_content)
    # Strip top-level headers like <h2>What's new in ...</h2>
    cleaned = re.sub(r'<h[1-6][^>]*>.*?</h[1-6]>', '', cleaned, flags=re.IGNORECASE)

    # Drop GitHub callouts (the pre-release warning): the post says it itself.
    cleaned = re.sub(r'(?m)^\s*>.*$', '', cleaned)

    # Cut off at download or verification sections if present
    for marker in ('## Download', '## Verify'):
        if marker in cleaned:
            cleaned = cleaned.split(marker, 1)[0]

    lines = [line.strip() for line in cleaned.splitlines()]

    intro_lines: list[str] = []
    sections: list[tuple[str, list[str]]] = []
    current_section: str = ""
    current_bullets: list[str] = []

    parsing_intro = True

    for line in lines:
        if not line:
            continue

        if line.startswith('### ') or line.startswith('## '):
            parsing_intro = False
            if current_section and current_bullets:
                sections.append((current_section, current_bullets))
            current_section = re.sub(r'^#+\s*', '', line).strip()
            current_bullets = []
        elif line.startswith('- ') or line.startswith('* '):
            parsing_intro = False
            bullet = line[2:].strip()
            current_bullets.append(bullet)
        elif parsing_intro:
            intro_lines.append(line)
        else:
            # Multi-line bullet point continuation
            if current_bullets:
                current_bullets[-1] += " " + line

    if current_section and current_bullets:
        sections.append((current_section, current_bullets))

    intro_text = " ".join(intro_lines).strip()
    return intro_text, sections


def build_announcement_message(
    version: str,
    tag: str,
    repo: str,
    intro: str,
    sections: list[tuple[str, list[str]]],
    full_apk_name: str | None = None,
    full_apk_size: int | None = None,
    has_attached_apk: bool = False,
    prerelease: bool = False,
) -> str:
    """Build the Telegram HTML announcement message respecting length limits."""
    release_url = f"https://github.com/{repo}/releases/tag/{tag}"

    if prerelease:
        header = (
            f"🧪 <b>WM Keyboard {html.escape(version)} is ready for testing</b>\n\n"
            "A pre-release of the next version: new things early, and more "
            "bugs than a release. Not on Play or F-Droid. Please report what "
            "breaks here or on GitHub.\n"
            "To get later test builds by itself, turn on <b>Settings › About › "
            "Updates › Include pre-releases</b> in the GitHub build.\n\n"
        )
    else:
        header = f"🚀 <b>WM Keyboard {html.escape(version)} is out!</b>\n\n"

    intro_block = ""
    if intro:
        formatted_intro = markdown_to_telegram_html(intro)
        intro_block = f"{formatted_intro}\n\n"

    # Downloads block
    downloads = "📦 <b>Downloads &amp; Links:</b>\n"
    downloads += f'• <a href="{release_url}">GitHub Release &amp; Full Changelog</a>\n'

    if full_apk_name:
        full_url = f"https://github.com/{repo}/releases/download/{tag}/{full_apk_name}"
        size_label = f" ({format_size(full_apk_size)})" if full_apk_size else ""
        downloads += f'• <a href="{full_url}">Download the standard APK (arm64)</a>{size_label}\n'

    if has_attached_apk:
        downloads += (
            "\n<i>Attached below: the standard APK (arm64). For other phones and the "
            "offline build, see the release page.</i>\n"
        )

    # Build changelog items from sections
    bullets_block = ""
    if sections:
        bullets_block = "<b>Highlights:</b>\n"
        items: list[str] = []
        for sec_title, bullets in sections:
            for b in bullets:
                # Clean up bullet text: take the first sentence or bold summary
                items.append((sec_title, b))

        # We will add items until we approach Telegram's message limit (~3600 chars)
        added = 0
        truncated = False
        temp_items_text = ""

        for sec_title, bullet in items:
            formatted_bullet = markdown_to_telegram_html(bullet)
            line = f"• {formatted_bullet}\n"

            # Check projected total length
            projected = (
                len(header)
                + len(intro_block)
                + len(bullets_block)
                + len(temp_items_text)
                + len(line)
                + len(downloads)
                + 120  # buffer for truncation note
            )

            if projected > 3600:
                truncated = True
                break

            temp_items_text += line
            added += 1

        bullets_block += temp_items_text
        if truncated:
            bullets_block += (
                f'• <i>…and more! <a href="{release_url}">See all changes on GitHub</a></i>\n'
            )
        bullets_block += "\n"

    return header + intro_block + bullets_block + downloads


class MultipartStreamer:
    """Streams multipart/form-data with a file without loading entire file in memory."""

    def __init__(
        self,
        fields: dict[str, str],
        file_field: str,
        file_path: Path,
        boundary: str = "----WMKeyboardTelegramBoundary",
    ) -> None:
        self.boundary = boundary
        self.file_path = file_path
        self.file_size = file_path.stat().st_size
        self.file_handle: BinaryIO | None = None

        # Build prefix
        prefix_parts: list[bytes] = []
        for name, value in fields.items():
            prefix_parts.append(
                f'--{boundary}\r\n'
                f'Content-Disposition: form-data; name="{name}"\r\n\r\n'
                f'{value}\r\n'.encode('utf-8')
            )

        mime_type = mimetypes.guess_type(str(file_path))[0] or "application/octet-stream"
        prefix_parts.append(
            f'--{boundary}\r\n'
            f'Content-Disposition: form-data; name="{file_field}"; filename="{file_path.name}"\r\n'
            f'Content-Type: {mime_type}\r\n\r\n'.encode('utf-8')
        )
        self.prefix = b"".join(prefix_parts)
        self.suffix = f"\r\n--{boundary}--\r\n".encode("utf-8")

        self.sent_prefix = 0
        self.sent_suffix = 0
        self.total_length = len(self.prefix) + self.file_size + len(self.suffix)

    def __len__(self) -> int:
        return self.total_length

    def read(self, size: int = 65536) -> bytes:
        if self.sent_prefix < len(self.prefix):
            chunk = self.prefix[self.sent_prefix : self.sent_prefix + size]
            self.sent_prefix += len(chunk)
            return chunk

        if self.file_handle is None:
            self.file_handle = open(self.file_path, "rb")

        data = self.file_handle.read(size)
        if data:
            return data

        if self.sent_suffix < len(self.suffix):
            chunk = self.suffix[self.sent_suffix : self.sent_suffix + size]
            self.sent_suffix += len(chunk)
            return chunk

        return b""

    def close(self) -> None:
        if self.file_handle and not self.file_handle.closed:
            self.file_handle.close()


def telegram_api_request(
    token: str,
    method: str,
    data: dict | MultipartStreamer | None = None,
    headers: dict[str, str] | None = None,
    max_retries: int = 3,
) -> dict:
    """Perform a request to the Telegram Bot API with retry and backoff."""
    url = f"https://api.telegram.org/bot{token}/{method}"
    req_headers = headers.copy() if headers else {}

    body = None
    if isinstance(data, MultipartStreamer):
        req_headers["Content-Type"] = f"multipart/form-data; boundary={data.boundary}"
        req_headers["Content-Length"] = str(len(data))
        body = data
    elif isinstance(data, dict):
        req_headers["Content-Type"] = "application/json"
        body = json.dumps(data).encode("utf-8")

    last_error: Exception | None = None

    for attempt in range(1, max_retries + 1):
        try:
            req = urllib.request.Request(url, data=body, headers=req_headers)
            with urllib.request.urlopen(req, timeout=120) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", errors="replace")
            try:
                err_data = json.loads(raw)
            except Exception:
                err_data = {"ok": False, "description": raw}

            # If rate limited, sleep for retry_after
            if e.code == 429 and "parameters" in err_data:
                retry_after = err_data["parameters"].get("retry_after", 5)
                print(f"[telegram] Rate limited. Waiting {retry_after}s before retry...")
                time.sleep(retry_after)
                continue

            last_error = RuntimeError(f"HTTP {e.code}: {err_data.get('description', raw)}")
            # Do not retry on client errors (400, 403, 404, etc.) except 429
            if 400 <= e.code < 500:
                raise last_error
        except (urllib.error.URLError, TimeoutError) as e:
            last_error = e
            if attempt < max_retries:
                time.sleep(2 * attempt)
                continue

    raise last_error or RuntimeError("Telegram API request failed after retries")


def ensure_topic_open(token: str, chat_id: str, thread_id: int) -> bool:
    """Attempt to reopen topic if closed. Returns True if successfully reopened."""
    try:
        telegram_api_request(
            token,
            "reopenForumTopic",
            {"chat_id": chat_id, "message_thread_id": thread_id},
        )
        print(f"[telegram] Successfully reopened topic {thread_id}")
        return True
    except Exception as e:
        print(f"[telegram] Note: Could not reopen topic {thread_id} ({e})")
        return False


def post_to_telegram(
    token: str,
    chat_id: str,
    thread_id: int,
    message_text: str,
    apk_file: Path | None = None,
    apk_caption: str = "",
    link_preview_url: str | None = None,
) -> None:
    """Send release announcement message and optionally attach the APK document."""
    # 1. Send text message
    send_msg_payload = {
        "chat_id": chat_id,
        "message_thread_id": thread_id,
        "text": message_text,
        "parse_mode": "HTML",
        # Preview the release page, not whatever link comes first in the
        # notes (usually an issue). When link_preview_url is None, no preview.
        "link_preview_options": (
            {"url": link_preview_url, "prefer_large_media": True}
            if link_preview_url
            else {"is_disabled": True}
        ),
    }

    try:
        res = telegram_api_request(token, "sendMessage", send_msg_payload)
        print(f"[telegram] Announcement posted (message_id: {res['result']['message_id']})")
    except RuntimeError as e:
        if "TOPIC_CLOSED" in str(e):
            print("[telegram] Topic is closed. Attempting to reopen...")
            if ensure_topic_open(token, chat_id, thread_id):
                res = telegram_api_request(token, "sendMessage", send_msg_payload)
                print(f"[telegram] Announcement posted after reopening (message_id: {res['result']['message_id']})")
            else:
                raise RuntimeError(
                    f"Topic {thread_id} is closed in {chat_id} and the bot cannot post or reopen it. "
                    "Please ensure topic is open or grant 'Manage Topics' permission to the bot."
                ) from e
        else:
            raise

    # 2. Upload APK document if provided
    if apk_file and apk_file.exists():
        if apk_file.stat().st_size > TELEGRAM_MAX_FILE_SIZE:
            print(
                f"[telegram] Warning: {apk_file.name} is {format_size(apk_file.stat().st_size)}, "
                f"which exceeds Telegram's 50 MB limit. Skipping file attachment."
            )
            return

        print(f"[telegram] Uploading {apk_file.name} ({format_size(apk_file.stat().st_size)})...")
        fields = {
            "chat_id": chat_id,
            "message_thread_id": str(thread_id),
            "caption": apk_caption,
            "parse_mode": "HTML",
        }
        streamer = MultipartStreamer(fields, "document", apk_file)
        try:
            doc_res = telegram_api_request(token, "sendDocument", streamer)
            print(f"[telegram] APK uploaded successfully (message_id: {doc_res['result']['message_id']})")
        finally:
            streamer.close()


def find_apks(dist_dir: Path) -> tuple[Path | None, Path | None]:
    """Find the standard arm64 APK, which is both linked and attached."""
    if not dist_dir.exists() or not dist_dir.is_dir():
        return None, None

    # Exact suffixes: the English-only, internet-enabled arm64 builds are
    # what most people download. Substring matching used to pick the
    # intl-nointernet build, since "intl" was preferred and it contains it.
    def pick(suffix: str) -> Path | None:
        return next(iter(sorted(dist_dir.glob(f"*{suffix}"))), None)

    standard = pick("-full-en-arm64-v8a.apk")
    return standard, standard


def main() -> None:
    parser = argparse.ArgumentParser(description="Announce release to Telegram community.")
    parser.add_argument("--tag", required=True, help="Release tag (e.g. v0.5.14)")
    parser.add_argument("--version", help="Version name (e.g. 0.5.14, defaults to tag without 'v')")
    parser.add_argument(
        "--repo",
        default=os.environ.get("GITHUB_REPOSITORY", "wasi-master/wmkeyboard"),
        help="GitHub repo owner/name",
    )
    parser.add_argument("--notes-file", help="Path to release notes or release-body.md")
    parser.add_argument("--dist", default="dist", help="Directory containing built APKs")
    parser.add_argument("--token", default=os.environ.get("TELEGRAM_TOKEN"), help="Telegram bot token")
    parser.add_argument("--chat-id", default=DEFAULT_CHAT_ID, help="Telegram chat/supergroup ID")
    parser.add_argument("--thread-id", type=int, help="Topic message thread ID (default: announcements, or beta testing with --prerelease)")
    parser.add_argument("--prerelease", action="store_true", help="A test version: post to the beta testing topic")
    parser.add_argument("--apk", help="Specific APK file path to attach instead of auto-detecting")
    parser.add_argument("--dry-run", action="store_true", help="Print message and exit without sending")

    args = parser.parse_args()
    if args.thread_id is None:
        args.thread_id = BETA_THREAD_ID if args.prerelease else DEFAULT_THREAD_ID

    tag = args.tag
    version = args.version or (tag[1:] if tag.startswith("v") else tag)
    repo = args.repo
    dist_dir = Path(args.dist)

    # 1. Read notes
    notes_content = ""
    if args.notes_file and Path(args.notes_file).exists():
        notes_content = Path(args.notes_file).read_text(encoding="utf-8")
    else:
        # Fallback to release-notes/{version}.md
        default_notes = Path(f"release-notes/{version}.md")
        if default_notes.exists():
            notes_content = default_notes.read_text(encoding="utf-8")

    intro, sections = parse_release_notes(notes_content) if notes_content else ("", [])

    # 2. Find APKs
    full_apk, attach_apk = find_apks(dist_dir)
    apk_to_attach: Path | None = Path(args.apk) if args.apk else attach_apk

    full_apk_name = full_apk.name if full_apk else None
    full_apk_size = full_apk.stat().st_size if full_apk else None

    # Check attachable status
    has_attached_apk = (
        apk_to_attach is not None
        and apk_to_attach.exists()
        and apk_to_attach.stat().st_size <= TELEGRAM_MAX_FILE_SIZE
    )

    # 3. Format message
    message = build_announcement_message(
        version=version,
        tag=tag,
        repo=repo,
        intro=intro,
        sections=sections,
        full_apk_name=full_apk_name,
        full_apk_size=full_apk_size,
        has_attached_apk=has_attached_apk,
        prerelease=args.prerelease,
    )

    kind = "test build · standard · arm64" if args.prerelease else "standard · arm64"
    caption = f"WM Keyboard {version} ({kind})" if has_attached_apk else ""

    if args.dry_run:
        print("=== DRY RUN MODE ===")
        print(f"Chat ID:   {args.chat_id}")
        print(f"Thread ID: {args.thread_id}")
        print(f"Full APK:  {full_apk_name} ({format_size(full_apk_size) if full_apk_size else 'None'})")
        print(f"Attach:    {apk_to_attach} (Valid attach: {has_attached_apk})")
        print(f"\n--- Telegram Message ({len(message)} chars) ---\n")
        print(message)
        print("\n--- Caption ---")
        print(caption)
        return

    # Verify token
    token = args.token or os.environ.get("TELEGRAM_TOKEN")
    if not token:
        # Fallback for local mac dev when token is in interactive shell rc
        try:
            sh_token = subprocess.check_output(
                ["zsh", "-i", "-c", "echo $TELEGRAM_TOKEN"],
                stderr=subprocess.DEVNULL,
                timeout=2,
            ).decode().strip()
            if sh_token:
                token = sh_token
        except Exception:
            pass

    if not token:
        print("::error::TELEGRAM_TOKEN is not set. Cannot send announcement.")
        sys.exit(1)

    print(f"[telegram] Sending release announcement for {version} to {args.chat_id} (topic {args.thread_id})...")
    post_to_telegram(
        token=token,
        chat_id=args.chat_id,
        thread_id=args.thread_id,
        message_text=message,
        apk_file=apk_to_attach if has_attached_apk else None,
        apk_caption=caption,
        link_preview_url=f"https://github.com/{repo}/releases/tag/{tag}",
    )
    print("[telegram] All done!")


if __name__ == "__main__":
    main()
