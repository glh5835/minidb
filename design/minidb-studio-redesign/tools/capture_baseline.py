from __future__ import annotations

import argparse
import json
from pathlib import Path

from playwright.sync_api import sync_playwright


def main() -> int:
    parser = argparse.ArgumentParser(description="Capture the verified MiniDB Studio baseline UI.")
    parser.add_argument("--url", default="http://127.0.0.1:8080")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    report: dict[str, object] = {
        "url": args.url,
        "viewport": {"width": 1440, "height": 900},
        "captures": [],
        "console_errors": [],
        "page_errors": [],
    }

    with sync_playwright() as pw:
        browser = pw.chromium.launch(channel="msedge")
        context = browser.new_context(
            viewport={"width": 1440, "height": 900},
            reduced_motion="reduce",
            locale="zh-CN",
        )
        page = context.new_page()
        page.on(
            "console",
            lambda msg: report["console_errors"].append(msg.text)
            if msg.type == "error"
            else None,
        )
        page.on("pageerror", lambda exc: report["page_errors"].append(str(exc)))

        page.goto(args.url, wait_until="networkidle", timeout=30_000)
        print("opened baseline", flush=True)
        health = page.evaluate(
            """async () => {
              const r = await fetch('/api/v1/health');
              return await r.json();
            }"""
        )
        report["health"] = health

        def capture(name: str) -> None:
            target = output / name
            page.screenshot(path=str(target), full_page=True)
            report["captures"].append(
                {
                    "file": name,
                    "hash": page.evaluate("location.hash"),
                    "title": page.title(),
                    "body_text_sample": page.locator("body").inner_text()[:500],
                }
            )

        capture("before-start-1440x900.png")
        print("captured start", flush=True)

        page.locator("#open-db-input").fill(r"D:\MiniDB-design-review\school.db")
        page.locator("#open-db-input").locator("xpath=following-sibling::button").click()
        print("opened isolated sample database", flush=True)
        page.wait_for_url("**/#/db", timeout=30_000)
        page.wait_for_timeout(2000)
        capture("before-database-1440x900.png")
        print("captured database", flush=True)

        page.locator("a").filter(has_text="SQL 工作台").click()
        page.wait_for_url("**/#/sql", timeout=30_000)
        page.wait_for_timeout(700)
        capture("before-sql-1440x900.png")
        print("captured sql", flush=True)

        report["passed"] = (
            bool(health.get("success"))
            and health.get("data", {}).get("status") == "ok"
            and health.get("data", {}).get("service") == "minidb-studio"
            and not report["page_errors"]
        )
        (output / "baseline-capture.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        browser.close()

    print(json.dumps({"passed": report["passed"], "output": str(output)}, ensure_ascii=False))
    return 0 if report["passed"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
