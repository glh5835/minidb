from __future__ import annotations

import argparse
import functools
import json
import threading
import urllib.parse
from contextlib import contextmanager
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from playwright.sync_api import sync_playwright


class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, *_: object) -> None:
        return


@contextmanager
def serve(root: Path):
    server = ThreadingHTTPServer(
        ("127.0.0.1", 0), functools.partial(QuietHandler, directory=str(root))
    )
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}"
    finally:
        server.shutdown()
        server.server_close()
        thread.join()


def next_output(base: Path) -> Path:
    index = 1
    while (base / f"qa-run-{index:02}").exists():
        index += 1
    output = base / f"qa-run-{index:02}"
    output.mkdir(parents=True)
    return output


def main() -> int:
    parser = argparse.ArgumentParser(description="Browser QA for the MiniDB redesign prototype.")
    parser.add_argument("--root", required=True)
    parser.add_argument("--output-base", required=True)
    args = parser.parse_args()
    root = Path(args.root).resolve(strict=True)
    output = next_output(Path(args.output_base).resolve())
    report: dict[str, object] = {
        "root": str(root),
        "output": str(output),
        "captures": [],
        "checks": [],
        "console_errors": [],
        "page_errors": [],
    }

    def check(name: str, passed: bool, detail: str = "") -> None:
        report["checks"].append({"name": name, "passed": bool(passed), "detail": detail})

    with serve(root) as origin, sync_playwright() as pw:
        browser = pw.chromium.launch(channel="msedge")
        context = browser.new_context(locale="zh-CN", reduced_motion="reduce")
        page = context.new_page()
        page.on("console", lambda msg: report["console_errors"].append(msg.text) if msg.type == "error" else None)
        page.on("pageerror", lambda exc: report["page_errors"].append(str(exc)))

        def shot(name: str) -> None:
            path = output / name
            page.screenshot(path=str(path), full_page=False)
            report["captures"].append(
                {
                    "file": name,
                    "viewport": page.viewport_size,
                    "url": page.url,
                    "title": page.title(),
                }
            )

        prototype_url = f"{origin}/prototype/index.html"
        for width, height in ((1366, 768), (1440, 900), (1920, 1080), (1024, 768)):
            page.set_viewport_size({"width": width, "height": height})
            page.goto(f"{prototype_url}#/start", wait_until="networkidle")
            shot(f"after-start-{width}x{height}.png")
            metrics = page.evaluate(
                """() => ({
                  bodyScrollWidth: document.body.scrollWidth,
                  viewportWidth: innerWidth,
                  activeViews: document.querySelectorAll('.view.active').length,
                  ribbon: document.querySelector('.prototype-ribbon')?.innerText,
                })"""
            )
            check(
                f"start-{width}x{height}-single-active-view",
                metrics["activeViews"] == 1,
                json.dumps(metrics, ensure_ascii=False),
            )
            check(
                f"start-{width}x{height}-no-whole-page-horizontal-overflow",
                metrics["bodyScrollWidth"] <= metrics["viewportWidth"] + 1,
                json.dumps(metrics, ensure_ascii=False),
            )

        page.set_viewport_size({"width": 1440, "height": 900})
        page.goto(f"{prototype_url}#/database", wait_until="networkidle")
        shot("after-database-1440x900.png")
        check("wide-table-scroll-contained", page.locator(".data-grid-wrap").evaluate("e => e.scrollWidth > e.clientWidth"))
        page.locator('[data-open="deleteRowDialog"]').first.click()
        check("danger-dialog-open", page.locator("#deleteRowDialog").evaluate("e => e.open"))
        check("danger-dialog-names-scope", "school.db" in page.locator("#deleteRowDialog").inner_text() and "RID 3/0" in page.locator("#deleteRowDialog").inner_text())
        shot("after-danger-dialog-1440x900.png")
        page.keyboard.press("Escape")
        page.locator('[data-dbtab="schema"]').click()
        check("database-schema-tab", page.locator('[data-dbpanel="schema"]').is_visible())
        page.locator('[data-dbtab="indexes"]').click()
        check("database-index-tab", page.locator('[data-dbpanel="indexes"]').is_visible())

        page.goto(f"{prototype_url}#/sql", wait_until="networkidle")
        shot("after-sql-success-1440x900.png")
        page.locator("#sqlState").select_option("error")
        check("sql-error-state", page.locator('[data-sqlstate="error"]').is_visible())
        shot("after-sql-error-1440x900.png")
        page.locator("#sqlState").select_option("empty")
        check("sql-empty-state", page.locator('[data-sqlstate="empty"]').is_visible())
        page.locator("#sqlState").select_option("idle")
        check("sql-idle-state", page.locator('[data-sqlstate="idle"]').is_visible())
        page.locator("#sqlState").select_option("success")
        page.keyboard.press("Control+Enter")
        check("sql-running-state", page.locator('[data-sqlstate="running"]').is_visible())
        page.wait_for_timeout(1050)
        check("sql-success-after-shortcut", page.locator('[data-sqlstate="success"]').is_visible())
        page.locator("#beginTxn").click()
        check("transaction-state-visible", "ACTIVE" in page.locator("#txnLabel").inner_text() and "事务 ACTIVE" in page.locator("#globalTxn").inner_text())
        page.locator("#rollbackTxn").click()

        for route, filename in (
            ("index", "after-index-1440x900.png"),
            ("lab-storage", "after-storage-lab-1440x900.png"),
            ("lab-txn", "after-txn-lab-1440x900.png"),
            ("lab-pipeline", "after-pipeline-lab-1440x900.png"),
            ("lab-recovery", "after-recovery-lab-1440x900.png"),
            ("lab-perf", "after-perf-lab-1440x900.png"),
            ("lab-jdbc", "after-jdbc-lab-1440x900.png"),
            ("help", "after-help-1440x900.png"),
        ):
            page.goto(f"{prototype_url}#/{route}", wait_until="networkidle")
            check(f"route-{route}", page.locator(f'[data-route="{route}"]').is_visible())
            shot(filename)

        page.set_viewport_size({"width": 1024, "height": 768})
        page.goto(f"{prototype_url}#/database", wait_until="networkidle")
        shot("after-database-1024x768.png")
        page.goto(f"{prototype_url}#/sql", wait_until="networkidle")
        shot("after-sql-1024x768.png")

        # Zoom accessibility: CSS zoom exercises the same reduced effective canvas
        # as browser zoom while keeping the automation viewport deterministic.
        page.set_viewport_size({"width": 1440, "height": 900})
        page.goto(f"{prototype_url}#/start", wait_until="networkidle")
        page.evaluate("document.documentElement.style.zoom='1.25'")
        shot("after-start-zoom-125-1440x900.png")
        zoom_start = page.locator(".primary-task").bounding_box()
        check(
            "zoom-125-primary-task-reachable",
            bool(zoom_start and zoom_start["x"] < 1440 and zoom_start["y"] < 900),
            json.dumps(zoom_start),
        )
        page.goto(f"{prototype_url}#/sql", wait_until="networkidle")
        page.evaluate("document.documentElement.style.zoom='1.5'")
        shot("after-sql-zoom-150-1440x900.png")
        zoom_sql = page.locator("#runSql").bounding_box()
        check(
            "zoom-150-run-sql-reachable",
            bool(zoom_sql and zoom_sql["x"] < 1440 and zoom_sql["y"] < 900),
            json.dumps(zoom_sql),
        )
        page.evaluate("document.documentElement.style.zoom='1'")

        # Same content and viewport for the three visual directions, two screens each.
        page.set_viewport_size({"width": 1440, "height": 900})
        for slug in ("a-jade", "b-academy", "c-inklab"):
            page.goto(f"{origin}/style-previews/{urllib.parse.quote(slug)}.html", wait_until="networkidle")
            shot(f"style-{slug}-start-1440x900.png")
            page.locator('[data-screen="sql"]').click()
            check(f"style-{slug}-sql-toggle", page.locator('[data-preview="sql"]').is_visible())
            shot(f"style-{slug}-sql-1440x900.png")

        browser.close()

    report["passed"] = (
        all(item["passed"] for item in report["checks"])
        and not report["console_errors"]
        and not report["page_errors"]
    )
    (output / "prototype-validation.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps({"passed": report["passed"], "output": str(output), "checks": len(report["checks"]), "captures": len(report["captures"])}, ensure_ascii=False))
    return 0 if report["passed"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
