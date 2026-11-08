#!/usr/bin/env bash
# Checks the Grafana provisioning files (M6-2) without starting Grafana:
#   - the dashboard is valid JSON with 9 panels, and every panel and rule uses the provisioned datasource uid;
#   - the alert rules file has the 3 rules from decision #34;
#   - every PromQL expression parses: they are copied into a Prometheus rule file and checked by promtool, which
#     ships in the prom/prometheus image (Docker must be running).
# Needs python with PyYAML (`pip install pyyaml`).
set -euo pipefail
cd "$(dirname "$0")"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# `python` first: on Windows `python3` is often a Microsoft Store stub.
py="$(command -v python || command -v python3)"
"$py" - "$work/rules.yml" <<'PY'
import json, sys, yaml

ds = yaml.safe_load(open("provisioning/datasources/prometheus.yaml"))["datasources"][0]
dash = json.load(open("provisioning/dashboards/dropoff-overview.json"))
alerts = yaml.safe_load(open("provisioning/alerting/rules.yaml"))
rules = alerts["groups"][0]["rules"]

assert len(dash["panels"]) == 9, f"expected 9 panels, got {len(dash['panels'])}"
assert len(rules) == 3, f"expected 3 alert rules, got {len(rules)}"
assert len({r["uid"] for r in rules}) == 3, "alert rule uids must be unique"
assert len({p["id"] for p in dash["panels"]}) == 9, "panel ids must be unique"

exprs = []
for panel in dash["panels"]:
    assert panel["datasource"]["uid"] == ds["uid"], f"panel {panel['id']}: wrong datasource"
    for t in panel["targets"]:
        assert t["datasource"]["uid"] == ds["uid"], f"panel {panel['id']}: wrong target datasource"
        exprs.append(t["expr"])
for rule in rules:
    queries = [d for d in rule["data"] if d["datasourceUid"] != "__expr__"]
    assert queries, f"{rule['uid']}: no query"
    for q in queries:
        assert q["datasourceUid"] == ds["uid"], f"{rule['uid']}: wrong datasource"
        exprs.append(q["model"]["expr"])
    assert rule["condition"] in {d["refId"] for d in rule["data"]}, f"{rule['uid']}: condition refId missing"

group = {"groups": [{"name": "validate", "rules": [
    {"record": f"validate:{i}", "expr": e} for i, e in enumerate(exprs)]}]}
yaml.safe_dump(group, open(sys.argv[1], "w"))
print(f"{len(dash['panels'])} panels, {len(rules)} alert rules, {len(exprs)} PromQL expressions")
PY

# MSYS_NO_PATHCONV stops Git Bash on Windows from rewriting the container paths.
# cygpath turns the Git Bash /tmp path into a Windows path Docker can mount.
mount="$work"
if command -v cygpath >/dev/null; then mount="$(cygpath -m "$work")"; fi
MSYS_NO_PATHCONV=1 docker run --rm --entrypoint promtool -v "$mount:/w:ro" prom/prometheus:v3.13.5 check rules /w/rules.yml
