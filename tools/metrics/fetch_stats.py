#!/usr/bin/env python3
"""
WMKeyboard Metrics Collector
Fetches download metrics and statistics from F-Droid and GitHub Releases.
"""

import argparse
import json
import re
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
import yaml


DEFAULT_PACKAGE = "com.wasimaster.wmkeyboard"
DEFAULT_GITHUB_REPO = "wasi-master/wmkeyboard"

BENCHMARK_PACKAGES = {
    "helium314.keyboard": "HeliBoard",
    "org.dslul.openboard.inputmethod.latin": "OpenBoard",
    "com.menny.android.anysoftkeyboard": "AnySoftKeyboard",
    "dev.patrickgold.florisboard": "FlorisBoard",
    "org.fcitx.fcitx5.android": "Fcitx5",
}

USER_AGENT = "WMKeyboard-Metrics-Tracker/1.0"


def fetch_url(url: str, timeout: int = 15) -> bytes:
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": USER_AGENT,
            "Accept": "application/json, text/html, */*",
        },
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read()


def fetch_json(url: str, timeout: int = 15):
    raw = fetch_url(url, timeout=timeout)
    return json.loads(raw.decode("utf-8"))


def load_local_fdroid_recipe(package: str):
    """Load local F-Droid recipe to see all builds including disabled ones."""
    recipe_path = Path(f"fdroid/{package}.yml")
    if not recipe_path.exists():
        return None

    try:
        with open(recipe_path, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f)

        builds = []
        for b in data.get("Builds", []):
            builds.append({
                "version_name": b.get("versionName"),
                "version_code": b.get("versionCode"),
                "disabled": b.get("disable") is not None,
                "disable_reason": b.get("disable"),
            })

        return {
            "recipe_file": str(recipe_path),
            "total_builds": len(builds),
            "active_builds": [b for b in builds if not b["disabled"]],
            "disabled_builds": [b for b in builds if b["disabled"]],
            "all_builds": builds,
        }
    except Exception as e:
        return {"error": str(e)}


def fetch_fdroid_distilled_metrics(package: str):
    """Fetch pre-distilled metrics from Torsten Grote's pipeline."""
    url = f"https://grote.gitlab.io/fdroid-metrics-distilled/apps/{package}.json"
    try:
        data = fetch_json(url)
        total_downloads = sum(data.values())
        return {
            "source": url,
            "status": "available",
            "weekly_downloads": data,
            "total_recorded": total_downloads,
            "last_recorded_week": max(data.keys()) if data else None,
        }
    except urllib.error.HTTPError as e:
        return {
            "source": url,
            "status": f"HTTP {e.code}",
            "weekly_downloads": {},
            "total_recorded": 0,
            "last_recorded_week": None,
        }
    except Exception as e:
        return {
            "source": url,
            "status": f"Error: {e}",
            "weekly_downloads": {},
            "total_recorded": 0,
            "last_recorded_week": None,
        }


def fetch_fdroid_package_info(package: str):
    """Fetch published version history from f-droid.org package listing."""
    url = f"https://f-droid.org/packages/{package}/"
    try:
        html = fetch_url(url).decode("utf-8")
        versions = []
        pattern = re.compile(
            r'Added on\s+([A-Za-z]+\s+\d{1,2},\s+\d{4}).*?'
            r'guaranteed to correspond to <a href="[^"]*?%s_(\d+)_src\.tar\.gz"'
            % re.escape(package),
            re.DOTALL,
        )
        for match in pattern.finditer(html):
            date_str, vcode = match.groups()
            versions.append({
                "version_code": int(vcode),
                "added_on": date_str.strip(),
            })

        return {
            "status": "published",
            "url": url,
            "currently_served_versions": len(versions),
            "versions": versions,
            "currently_served_first": versions[-1]["added_on"] if versions else None,
            "currently_served_latest": versions[0]["added_on"] if versions else None,
        }
    except urllib.error.HTTPError as e:
        return {
            "status": f"HTTP {e.code}",
            "url": url,
            "currently_served_versions": 0,
            "versions": [],
            "currently_served_first": None,
            "currently_served_latest": None,
        }
    except Exception as e:
        return {
            "status": f"Error: {e}",
            "url": url,
            "currently_served_versions": 0,
            "versions": [],
            "currently_served_first": None,
            "currently_served_latest": None,
        }


def fetch_github_release_stats(repo: str):
    """Fetch download counts across all GitHub releases."""
    url = f"https://api.github.com/repos/{repo}/releases?per_page=100"
    try:
        releases_data = fetch_json(url)
        if not isinstance(releases_data, list):
            return {
                "status": "error",
                "message": releases_data.get("message", "Unknown API error"),
                "total_releases": 0,
                "total_downloads": 0,
                "releases": [],
            }

        total_downloads = 0
        releases = []

        for rel in releases_data:
            tag = rel.get("tag_name")
            published_at = rel.get("published_at")
            assets = rel.get("assets", [])

            rel_downloads = 0
            apk_assets = []
            for asset in assets:
                name = asset.get("name", "")
                count = asset.get("download_count", 0)
                rel_downloads += count
                apk_assets.append({
                    "name": name,
                    "downloads": count,
                    "size_mb": round(asset.get("size", 0) / (1024 * 1024), 2),
                })

            total_downloads += rel_downloads
            releases.append({
                "tag": tag,
                "published_at": published_at,
                "downloads": rel_downloads,
                "assets": apk_assets,
            })

        return {
            "status": "ok",
            "total_releases": len(releases),
            "total_downloads": total_downloads,
            "releases": releases,
        }
    except Exception as e:
        return {
            "status": f"Error: {e}",
            "total_releases": 0,
            "total_downloads": 0,
            "releases": [],
        }


def fetch_benchmarks():
    """Fetch 1-year stats for benchmark keyboards from Grote's metrics."""
    benchmarks = {}
    for pkg, name in BENCHMARK_PACKAGES.items():
        stats = fetch_fdroid_distilled_metrics(pkg)
        benchmarks[pkg] = {
            "name": name,
            "total_downloads": stats["total_recorded"],
            "last_week": stats["last_recorded_week"],
        }
    return benchmarks


def print_report(summary: dict):
    print("=" * 68)
    print(f"📊 WMKeyboard Download & Distribution Metrics")
    print(f"Timestamp: {summary['timestamp']}")
    print("=" * 68)

    # F-Droid
    fdroid = summary["fdroid"]
    pkg_info = fdroid["package_info"]
    recipe = fdroid.get("local_recipe")

    print("\n[1] F-Droid Version & Build History:")
    print(f"    Package ID:       {summary['package']}")
    print(f"    Listing:          {pkg_info.get('url')}")
    print(f"    Listing Status:   {pkg_info.get('status')}")

    if recipe and "all_builds" in recipe:
        print(f"\n    F-Droid Recipe Builds ({recipe['total_builds']} total builds):")
        print("    • Active Reproducible Builds (on repo today):")
        for b in recipe["active_builds"]:
            print(f"        - v{b['version_name']} (vc {b['version_code']})")
        print("    • Previous Builds (disabled when switching to reproducible signing):")
        for b in recipe["disabled_builds"]:
            print(f"        - v{b['version_name']} (vc {b['version_code']}): {b['disable_reason']}")

    print(f"\n    Currently Served on f-droid.org: {pkg_info.get('currently_served_versions')} versions")
    if pkg_info.get('versions'):
        for v in pkg_info['versions']:
            print(f"      - Version Code {v['version_code']}: Added on {v['added_on']}")

    distilled = fdroid["distilled_metrics"]
    print(f"\n    F-Droid Public Server Metrics Status:")
    print(f"    Source:           {distilled.get('source')}")
    print(f"    Recorded In Logs: {distilled.get('total_recorded')}")
    print(f"    Last Log Dump:    {distilled.get('last_recorded_week')}")
    print(f"    Timeline context:")
    print(f"      • First released to F-Droid in mid-September 2026 (Discussion #218).")
    print(f"      • F-Droid public mirror metrics (Clean Insights) end at Aug 24, 2026.")
    print(f"      • Because F-Droid has not published mirror logs for Sept/Oct 2026,")
    print(f"        all active F-Droid downloads fall into the unreleased log period.")

    # GitHub Releases
    gh = summary["github"]
    print("\n[2] GitHub Releases Downloads:")
    print(f"    Repository:       https://github.com/{summary['github_repo']}")
    print(f"    Total Releases:   {gh.get('total_releases')}")
    print(f"    Total Downloads:  {gh.get('total_downloads'):,}")
    print("\n    Breakdown by Release (recent):")
    for rel in gh.get("releases", [])[:10]:
        pub_date = rel.get("published_at", "")[:10]
        print(f"      - {rel['tag']:<8} ({pub_date}): {rel['downloads']:>6,} downloads")

    # Grand Total
    combined = distilled.get("total_recorded", 0) + gh.get("total_downloads", 0)
    print("\n[3] Combined Distribution Total:")
    print(f"    Verified GitHub Downloads: {gh.get('total_downloads'):,}")
    print(f"    F-Droid Mirror Logs:       Pending upstream release from F-Droid")

    # Benchmarks
    if "benchmarks" in summary:
        print("\n[4] F-Droid Keyboard Ecosystem Context (Trailing 1-Year Downloads):")
        for pkg, b in summary["benchmarks"].items():
            print(f"    - {b['name']:<18} ({pkg}): {b['total_downloads']:>10,} downloads")

    print("\n" + "=" * 68)


def main():
    parser = argparse.ArgumentParser(description="Fetch WMKeyboard metrics from F-Droid and GitHub.")
    parser.add_argument("--package", default=DEFAULT_PACKAGE, help="Android package ID")
    parser.add_argument("--repo", default=DEFAULT_GITHUB_REPO, help="GitHub repository (owner/repo)")
    parser.add_argument("--benchmark", action="store_true", help="Include F-Droid keyboard ecosystem benchmarks")
    parser.add_argument("--out", type=Path, default=Path("tools/metrics/stats.json"), help="Output JSON path")
    args = parser.parse_args()

    print("Fetching metrics from F-Droid and GitHub...")
    fdroid_distilled = fetch_fdroid_distilled_metrics(args.package)
    fdroid_info = fetch_fdroid_package_info(args.package)
    local_recipe = load_local_fdroid_recipe(args.package)
    github_stats = fetch_github_release_stats(args.repo)

    summary = {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "package": args.package,
        "github_repo": args.repo,
        "fdroid": {
            "local_recipe": local_recipe,
            "package_info": fdroid_info,
            "distilled_metrics": fdroid_distilled,
        },
        "github": github_stats,
    }

    if args.benchmark:
        summary["benchmarks"] = fetch_benchmarks()

    print_report(summary)

    # Save to file
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2)
    print(f"\nSaved structured stats to {args.out}")


if __name__ == "__main__":
    main()
