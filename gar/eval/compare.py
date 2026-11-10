#!/usr/bin/env python3
"""Compares two GAR router eval reports (the JSON files gar/eval/run-eval.sh writes).

usage: gar/eval/compare.py <before.json> <after.json> [--only-changed] [--tool NAME] [--json]
                           [--fail-on-broke]

Cases are matched by text. Prints, in this order:
  1. what each report is (model, format, date, guards, cases, prompt size) and how the prompts differ
  2. the summary metrics side by side with deltas, on all cases and on the cases both reports share
  3. accuracy per expected tool on the shared cases, with how many cases each tool fixed and broke
  4. where the calls went: the model's choice, the result after the filters, false calls by tool
  5. the shared cases that broke, got fixed or changed, each tagged with the cause: "model" (the raw
     output differs), "filter" (same raw output, another verdict: parsing, keywords, Guard A,
     resolvers), "label" (the expectation changed) or "engine" (a timeout or a dead server)
  6. the cases only one report has
  7. what each guard drops on its own, and which commands of the expected tool it newly loses

Options:
  --only-changed   hide unchanged rows in the per-tool and call tables
  --tool NAME      only rows and cases about NAME (expected, chosen or dropped)
  --json           the same data as JSON, for scripts
  --fail-on-broke  exit 1 when a shared case broke (to check a filter-only change on a replay)

Standard library only. Older reports without summary_without_filter, guard_drops, call or raw
print "-" where those are needed. See gar/eval/README.md.
"""

import argparse
import difflib
import json
import os
import re
import sys
from collections import Counter

NO_MATCH = "no_match"
DROPPED = "dropped"
FAILED = "failed"
# LlmRouter's failures that come from the engine rather than from the model's output
ENGINE_FAILURES = ("timed out", "engine error", "engine cancelled", "generation cancelled")


def positive(case):
    return case["expected_tool"] != NO_MATCH


def abstained(case):
    return case["got"] in (NO_MATCH, FAILED, DROPPED)


def correct(case):
    if "correct" in case:
        return case["correct"]
    return case.get("slots_exact", False) if positive(case) else abstained(case)


def engine_failure(case):
    return case["got"] == FAILED and case.get("failure", "").startswith(ENGINE_FAILURES)


def model_choice(case):
    """The tool the model called before the filters, or no_match / failed."""
    return case.get("call") or (DROPPED if case["got"] == DROPPED else case["got"])


def call_slots(case):
    return case.get("call_slots", case.get("slots")) or {}


def label(case):
    """What the case expects; slot labels only once the runner writes them."""
    return (case["expected_tool"], json.dumps(case.get("expected_slots"), sort_keys=True),
            json.dumps(case.get("optional"), sort_keys=True))


def verdict(case, calls=True):
    """What the router answered and how it scored, comparable across reports (not the wording
    of drop reasons and failures). Without [calls] (an older report on either side) only the
    slots of the result count."""
    return (case["got"], case.get("call") if calls else None,
            json.dumps(call_slots(case) if calls else case.get("slots") or {}, sort_keys=True),
            engine_failure(case), case.get("slots_exact"), bool(correct(case)))


def involves(case, tool):
    return tool in (case["expected_tool"], case["got"], case.get("call"))


class Report:
    def __init__(self, path):
        self.path = path
        with open(path) as f:
            self.data = json.load(f)
        self.cases = self.data.get("cases", [])
        self.by_text = {}
        self.duplicates = []
        for case in self.cases:
            if case["text"] in self.by_text:
                self.duplicates.append(case["text"])
            else:
                self.by_text[case["text"]] = case
        self.tools = prompt_tools(self.data.get("static_prefix"))
        self.meta = load_meta(path)
        self._resolve_lost()

    def _resolve_lost(self):
        """Works out whether the commands the filters dropped although the model chose the
        expected tool would have had exact slots. The report doesn't say per case (it has no
        expected slots), only in summary_without_filter's total."""
        positives = [c for c in self.cases if positive(c)]
        self.lost = [c for c in positives if c["got"] == DROPPED and c.get("call") == c["expected_tool"]]
        self.lost_exact = {}  # id(case) -> True, False or None (unknown)
        self.lost_exact_count = None
        if not self.lost:
            return
        total = (self.data.get("summary_without_filter") or {}).get("exact_slot_accuracy")
        if total is not None:
            known = sum(1 for c in positives if c["got"] != DROPPED and c.get("slots_exact"))
            count = round(total * len(positives)) - known
            if 0 <= count <= len(self.lost):
                self.lost_exact_count = count
        for case in self.lost:
            if self.lost_exact_count == 0:
                self.lost_exact[id(case)] = False
            elif self.lost_exact_count == len(self.lost):
                self.lost_exact[id(case)] = True
            else:
                self.lost_exact[id(case)] = None

    def unguarded_exact(self, cases):
        """Positives of [cases] with exact slots if nothing had been dropped, or None."""
        exact = 0
        unknown = []
        for case in cases:
            if not positive(case):
                continue
            if case["got"] != DROPPED:
                exact += bool(case.get("slots_exact"))
            elif case.get("call") is None:
                return None  # an older report: the dropped call isn't recorded
            elif case.get("call") == case["expected_tool"]:
                value = self.lost_exact.get(id(case))
                if value is None:
                    unknown.append(case)
                else:
                    exact += value
        if unknown:
            # only the total is known: usable when every such case is in [cases]
            if len(unknown) != len(self.lost) or self.lost_exact_count is None:
                return None
            exact += self.lost_exact_count
        return exact


def load_meta(path):
    """The run's metadata that run-eval.sh writes next to the report, or None."""
    meta = re.sub(r"\.json$", ".meta.json", path)
    if meta == path or not os.path.isfile(meta):
        return None
    try:
        with open(meta) as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def prompt_tools(prefix):
    """The tool declarations in a static prefix, in prompt order: name -> (description, rest)."""
    if not prefix:
        return None
    tools = {}
    if "<tools>" in prefix:  # Hermes: one JSON object per line
        for line in prefix.split("\n"):
            if not line.startswith('{"type": "function"'):
                continue
            try:
                function = json.loads(line)["function"]
            except (ValueError, KeyError):
                continue
            tools[function["name"]] = (function.get("description", ""), function.get("parameters"))
    else:  # FunctionGemma
        for m in re.finditer(r"<start_function_declaration>declaration:([^{]+)\{(.*?)"
                             r"<end_function_declaration>", prefix, re.S):
            body = re.match(r"description:<escape>(.*?)<escape>,(.*)", m.group(2), re.S)
            tools[m.group(1)] = (body.group(1), body.group(2)) if body else ("", m.group(2))
    return tools


def prompt_without_tools(prefix):
    prefix = re.sub(r"<start_function_declaration>.*?<end_function_declaration>", "", prefix, flags=re.S)
    return [line for line in prefix.split("\n") if not line.startswith('{"type": "function"')]


def prompt_diff(a, b):
    """How B's prompt differs from A's: tools added, removed or changed, and other lines."""
    pa, pb = a.data.get("static_prefix"), b.data.get("static_prefix")
    if pa is None or pb is None:
        return {"same": None, "tools": [], "lines": []}
    if pa == pb:
        return {"same": True, "tools": [], "lines": []}
    tools = []
    ta, tb = a.tools or {}, b.tools or {}
    for name in tb:
        if name not in ta:
            tools.append("+ " + name)
    for name in ta:
        if name not in tb:
            tools.append("- " + name)
    for name in tb:
        if name not in ta or ta[name] == tb[name]:
            continue
        (da, ra), (db, rb) = ta[name], tb[name]
        if da != db:
            tools.append('~ %s description: "%s" -> "%s"' % (name, da, db))
        if ra != rb:
            tools.append("~ %s parameters%s" % (name, parameter_changes(ra, rb)))
    common = [n for n in ta if n in tb]
    if common != [n for n in tb if n in ta]:
        tools.append("~ tool order")
    lines = [line for line in difflib.unified_diff(prompt_without_tools(pa), prompt_without_tools(pb),
                                                   n=0, lineterm="")
             if not line.startswith(("---", "+++", "@@"))]
    return {"same": False, "tools": tools, "lines": lines}


def parameter_changes(ra, rb):
    """Which slots of a Hermes declaration changed (FunctionGemma's are only compared whole)."""
    if not isinstance(ra, dict) or not isinstance(rb, dict):
        return ""
    sa, sb = ra.get("properties", {}), rb.get("properties", {})
    parts = ["+" + s for s in sb if s not in sa] + ["-" + s for s in sa if s not in sb] + \
        ["~" + s for s in sb if s in sa and sa[s] != sb[s]]
    if ra.get("required") != rb.get("required"):
        parts.append("required %s -> %s" % (ra.get("required"), rb.get("required")))
    return ": " + ", ".join(parts) if parts else ""


def percentile(values, p):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[int((len(ordered) - 1) * p)]


def metrics(report, cases):
    """Summary metrics over [cases]: (count, denominator) pairs, counts, or None if unknown."""
    pos = [c for c in cases if positive(c)]
    neg = [c for c in cases if not positive(c)]
    m = {
        "tool_accuracy": (sum(bool(c.get("tool_correct")) for c in pos), len(pos)),
        "exact_slot_accuracy": (sum(bool(c.get("slots_exact")) for c in pos), len(pos)),
        "abstention_accuracy": (sum(abstained(c) for c in neg), len(neg)),
        "false_calls": (sum(not abstained(c) for c in neg), len(neg)),
        "correct": (sum(bool(correct(c)) for c in cases), len(cases)),
        "abstained_on_positives": sum(abstained(c) for c in pos),
        "dropped": sum(c["got"] == DROPPED for c in cases),
        "failed_engine": sum(engine_failure(c) for c in cases),
        "failed_malformed": sum(c["got"] == FAILED and not engine_failure(c) for c in cases),
    }
    # without the filters: a dropped call counts as the call the model made
    if any(c["got"] == DROPPED and c.get("call") is None for c in cases):
        for key in ("tool_accuracy", "exact_slot_accuracy", "abstention_accuracy", "false_calls",
                    "correct"):
            m["unguarded_" + key] = (None, 0)
    else:
        u_abstained = sum(c["got"] in (NO_MATCH, FAILED) for c in neg)
        exact = report.unguarded_exact(cases)
        m["unguarded_tool_accuracy"] = (sum(model_choice(c) == c["expected_tool"] for c in pos),
                                        len(pos))
        m["unguarded_exact_slot_accuracy"] = (exact, len(pos))
        m["unguarded_abstention_accuracy"] = (u_abstained, len(neg))
        m["unguarded_false_calls"] = (len(neg) - u_abstained, len(neg))
        m["unguarded_correct"] = (None if exact is None else exact + u_abstained, len(cases))
    millis = [c["millis"] for c in cases if "millis" in c]
    m["latency_mean_ms"] = sum(millis) / len(millis) if millis else None
    m["latency_p50_ms"] = percentile(millis, 0.5)
    m["latency_p90_ms"] = percentile(millis, 0.9)
    return m


SUMMARY_ROWS = [
    ("tool accuracy", "tool_accuracy"),
    ("exact slots", "exact_slot_accuracy"),
    ("abstention", "abstention_accuracy"),
    ("false calls", "false_calls"),
    ("overall correct", "correct"),
    ("abstained on commands", "abstained_on_positives"),
    ("dropped by the filters", "dropped"),
    ("failed: engine", "failed_engine"),
    ("failed: malformed output", "failed_malformed"),
    ("no filter: tool accuracy", "unguarded_tool_accuracy"),
    ("no filter: exact slots", "unguarded_exact_slot_accuracy"),
    ("no filter: abstention", "unguarded_abstention_accuracy"),
    ("no filter: false calls", "unguarded_false_calls"),
    ("no filter: overall correct", "unguarded_correct"),
]

# the runner's summary keys, to check that the cases add up to the report's own summary
SUMMARY_CHECKS = {
    "tool_accuracy": ("summary", "tool_accuracy"),
    "exact_slot_accuracy": ("summary", "exact_slot_accuracy"),
    "abstention_accuracy": ("summary", "abstention_accuracy"),
    "abstained_on_positives": ("summary", "abstained_on_positives"),
    "dropped": ("summary", "dropped"),
    "unguarded_tool_accuracy": ("summary_without_filter", "tool_accuracy"),
    "unguarded_exact_slot_accuracy": ("summary_without_filter", "exact_slot_accuracy"),
    "unguarded_abstention_accuracy": ("summary_without_filter", "abstention_accuracy"),
    "unguarded_correct": ("summary_without_filter", "correct"),
}


def rate(pair):
    n, d = pair
    return None if n is None or d == 0 else n / d


def summary_mismatches(report, m):
    """Metrics that the cases compute differently from the report's own summary."""
    out = []
    for key, (section, field) in SUMMARY_CHECKS.items():
        expected = (report.data.get(section) or {}).get(field)
        value = m.get(key)
        if expected is None or value is None:
            continue
        actual = rate(value) if isinstance(value, tuple) else value
        if actual is not None and abs(actual - expected) > 1e-9:
            out.append("%s.%s" % (section, field))
    return out


def catalog_order(a, b):
    """Tools in prompt order (B's, then tools only A offers), then tools only the cases name."""
    order = list((b.tools or {}).keys()) + [t for t in (a.tools or {}) if t not in (b.tools or {})]
    for case in a.cases + b.cases:
        if case["expected_tool"] not in order:
            order.append(case["expected_tool"])
    order = [t for t in order if t != NO_MATCH]
    return order + [NO_MATCH]


def per_tool(a, b, shared, order):
    rows = []
    for tool in order:
        pairs = [(a.by_text[t], b.by_text[t]) for t in shared if b.by_text[t]["expected_tool"] == tool]
        n_a = sum(c["expected_tool"] == tool for c in a.by_text.values())
        n_b = sum(c["expected_tool"] == tool for c in b.by_text.values())
        if not pairs and not n_a and not n_b:
            continue
        ca, cb = [p[0] for p in pairs], [p[1] for p in pairs]
        if tool == NO_MATCH:
            acc = [(sum(abstained(c) for c in cs), len(cs)) for cs in (ca, cb)]
            exact = [None, None]
        else:
            acc = [(sum(bool(c.get("tool_correct")) for c in cs), len(cs)) for cs in (ca, cb)]
            exact = [(sum(bool(c.get("slots_exact")) for c in cs), len(cs)) for cs in (ca, cb)]
        fixed = sum(not correct(x) and correct(y) for x, y in pairs)
        broke = sum(correct(x) and not correct(y) for x, y in pairs)
        changed = acc[0] != acc[1] or exact[0] != exact[1] or fixed or broke or n_a != n_b
        rows.append({"tool": tool, "n": len(pairs), "n_a": n_a, "n_b": n_b, "tool_accuracy": acc,
                     "exact_slot_accuracy": exact, "fixed": fixed, "broke": broke,
                     "changed": bool(changed)})
    return rows


def call_distribution(a, b, shared, order):
    rows = []
    for name in order + [DROPPED, FAILED]:
        row = {"tool": name}
        for side, report in (("a", a), ("b", b)):
            cases = [report.by_text[t] for t in shared]
            row["model_" + side] = sum(model_choice(c) == name for c in cases)
            row["result_" + side] = sum(c["got"] == name for c in cases)
            # a negative whose result is no_match, dropped or failed is no false call
            row["false_calls_" + side] = 0 if name in (NO_MATCH, DROPPED, FAILED) else \
                sum(not positive(c) and c["got"] == name for c in cases)
        if not any(row[k] for k in row if k != "tool"):
            continue
        row["changed"] = any(row[k + "_a"] != row[k + "_b"] for k in ("model", "result", "false_calls"))
        rows.append(row)
    return rows


def describe_outcome(case):
    got = case["got"]
    if got == FAILED:
        return "failed (%s)" % case.get("failure", "?")
    if got == NO_MATCH:
        return NO_MATCH
    slots = ", ".join('%s="%s"' % kv for kv in call_slots(case).items())
    if got == DROPPED:
        return "dropped %s(%s) (%s)" % (case.get("call", "?"), slots, case.get("drop_reason", "?"))
    return "%s(%s)" % (got, slots)


def raw_changed(ca, cb):
    """Whether the model's output differs (an engine failure has none), or None if unknown."""
    ra, rb = ca.get("raw"), cb.get("raw")
    if ra is None and not engine_failure(ca) or rb is None and not engine_failure(cb):
        return None  # an older report without raw outputs
    return ra != rb


def cause(ca, cb, calls):
    if label(ca) != label(cb):
        return "label"
    if engine_failure(ca) or engine_failure(cb):
        return "engine"  # a timeout or a dead server: no output to compare
    changed = raw_changed(ca, cb)
    if changed is None:
        return "?"
    if changed:
        return "model"
    if verdict(ca, calls)[:4] != verdict(cb, calls)[:4]:
        return "filter"
    return "label"  # the same answer scored differently: the slot labels changed


def case_changes(a, b, shared, tool):
    groups = {"broke": [], "fixed": [], "changed": []}
    counts = Counter()
    # a report older than the call field has none: compare only the results then
    calls = all(any("call" in c for c in r.cases) for r in (a, b))
    for text in shared:
        ca, cb = a.by_text[text], b.by_text[text]
        if tool and not (involves(ca, tool) or involves(cb, tool)):
            continue
        why = cause(ca, cb, calls)
        verdict_changed = verdict(ca, calls) != verdict(cb, calls)
        if raw_changed(ca, cb):
            counts["raw_changed"] += 1
        if verdict_changed or label(ca) != label(cb):
            # the raw outputs are counted above, whatever their verdict
            if why == "filter":
                counts["same_raw_verdict_changed"] += 1
            elif why == "label":
                counts["labels_changed"] += 1
        if correct(ca) and not correct(cb):
            group = "broke"
        elif correct(cb) and not correct(ca):
            group = "fixed"
        elif verdict_changed:
            group = "changed"
        else:
            continue
        counts[group] += 1
        groups[group].append({
            "text": text, "expected_a": ca["expected_tool"], "expected_b": cb["expected_tool"],
            "a": describe_outcome(ca), "b": describe_outcome(cb), "cause": why,
        })
    return groups, counts


def only_in(report, texts, tool):
    cases = [report.by_text[t] for t in texts]
    if tool:
        cases = [c for c in cases if involves(c, tool)]
    by_tool = {}
    for case in cases:
        n, d = by_tool.get(case["expected_tool"], (0, 0))
        by_tool[case["expected_tool"]] = (n + bool(correct(case)), d + 1)
    return {
        "n": len(cases), "correct": sum(bool(correct(c)) for c in cases), "by_tool": by_tool,
        "cases": [{"text": c["text"], "expected": c["expected_tool"], "got": describe_outcome(c),
                   "correct": bool(correct(c))} for c in cases],
    }


def guard_kind(case):
    if not positive(case):
        return "negatives"
    return "lost" if case.get("call") == case["expected_tool"] else "misroutes"


def guards(a, b, shared, tool):
    names = []
    for report in (a, b):
        for g in list(report.data.get("guards") or []) + \
                [g for case in report.cases for g in case.get("guard_drops") or {}]:
            if g not in names:
                names.append(g)
    rows = []
    for g in names:
        row = {"guard": g}
        for side, report in (("a", a), ("b", b)):
            if not any("guard_drops" in c for c in report.cases):
                row[side] = None
                continue
            kinds = Counter(guard_kind(c) for c in report.cases if g in (c.get("guard_drops") or {}))
            row[side] = {"drops": sum(kinds.values()), "negatives": kinds["negatives"],
                         "misroutes": kinds["misroutes"], "lost": kinds["lost"]}

        def lost(case):
            return g in (case.get("guard_drops") or {}) and guard_kind(case) == "lost"

        def entry(case, note=""):
            call = describe_outcome(dict(case, got=case["call"])) if case.get("call") else "?"
            return {"text": case["text"], "expected": case["expected_tool"], "note": note,
                    "call": call, "reason": case["guard_drops"][g]}

        newly, recovered = [], []
        if row["a"] is None or row["b"] is None:
            both, only_b = [], []  # a report without guard data: nothing to compare with
        else:
            both, only_b = shared, [t for t in b.by_text if t not in a.by_text]
        for text in both:
            ca, cb = a.by_text[text], b.by_text[text]
            if tool and not (involves(ca, tool) or involves(cb, tool)):
                continue
            if lost(cb) and not lost(ca):
                newly.append(entry(cb))
            elif lost(ca) and not lost(cb):
                recovered.append(entry(ca))
        for text in only_b:
            cb = b.by_text[text]
            if lost(cb) and (not tool or involves(cb, tool)):
                newly.append(entry(cb, "only in B"))
        row["newly_lost"] = newly
        row["no_longer_lost"] = recovered
        rows.append(row)
    return rows


def describe_report(report):
    d = report.data
    pos = sum(positive(c) for c in report.cases)
    return {
        "path": report.path, "model": d.get("model"), "format": d.get("format"),
        "date": (d.get("date") or "").replace("T", " ")[:19], "server": d.get("server"),
        "guards": d.get("guards"), "cases": len(report.cases), "positives": pos,
        "negatives": len(report.cases) - pos, "static_prefix_tokens": d.get("static_prefix_tokens"),
        "meta": report.meta,
    }


def compare(a, b, tool=None):
    shared = [t for t in a.by_text if t in b.by_text]
    only_a = [t for t in a.by_text if t not in b.by_text]
    only_b = [t for t in b.by_text if t not in a.by_text]
    order = catalog_order(a, b)
    warnings = []
    for report, name in ((a, "A"), (b, "B")):
        if report.duplicates:
            warnings.append("%s has duplicate texts (only the first is compared): %s"
                            % (name, ", ".join(sorted(set(report.duplicates)))))
        missing = sum("no recorded output" in c.get("failure", "") for c in report.cases)
        if missing:
            warnings.append("%s: %d cases have no recorded output (a replay of a report with other "
                            "cases?)" % (name, missing))
        mismatches = summary_mismatches(report, metrics(report, report.cases))
        if mismatches:
            warnings.append("%s: the cases don't add up to the report's %s"
                            % (name, ", ".join(mismatches)))
    for key in ("model", "format", "guards"):
        if a.data.get(key) != b.data.get(key):
            warnings.append("%s differs: %s -> %s" % (key, a.data.get(key), b.data.get(key)))
    if only_a or only_b:
        warnings.append("the case sets differ: %d shared, %d only in A, %d only in B"
                        % (len(shared), len(only_a), len(only_b)))
    if a.meta and b.meta:
        for key in ("cases_sha256", "tools_sha256", "llama_server_version"):
            if a.meta.get(key) and b.meta.get(key) and a.meta[key] != b.meta[key]:
                warnings.append("%s differs (run metadata)" % key)
    if tool and tool not in order:
        warnings.append("no tool or case named %s" % tool)

    shared_a = [a.by_text[t] for t in shared]
    shared_b = [b.by_text[t] for t in shared]
    same_cases = not only_a and not only_b and not a.duplicates and not b.duplicates
    tools = per_tool(a, b, shared, order)
    calls = call_distribution(a, b, shared, order)
    if tool:
        tools = [r for r in tools if r["tool"] == tool]
        calls = [r for r in calls if r["tool"] == tool]
    changes, counts = case_changes(a, b, shared, tool)
    return {
        "reports": [describe_report(a), describe_report(b)],
        "prompt": prompt_diff(a, b),
        "warnings": warnings,
        "summary": {
            "all": [metrics(a, a.cases), metrics(b, b.cases)],
            "shared": None if same_cases else [metrics(a, shared_a), metrics(b, shared_b)],
            "shared_n": len(shared),
        },
        "per_tool": tools,
        "calls": calls,
        "changes": changes,
        "counts": dict(counts),
        "only_in_a": only_in(a, only_a, tool),
        "only_in_b": only_in(b, only_b, tool),
        "guards": guards(a, b, shared, tool),
    }


# text output

def pct(pair):
    value = rate(pair) if pair else None
    return "-" if value is None else "%.1f%%" % (100 * value)


def with_counts(pair):
    if pair is None:
        return "-"
    n, d = pair
    return "-" if n is None or d == 0 else "%s (%d/%d)" % (pct(pair), n, d)


def delta(va, vb):
    if isinstance(va, tuple) or isinstance(vb, tuple):
        ra, rb = rate(va or (None, 0)), rate(vb or (None, 0))
        return "" if ra is None or rb is None else "%+.1f pp" % (100 * (rb - ra))
    if va is None or vb is None:
        return ""
    return "%+d" % (vb - va) if vb != va else "0"


def print_summary(title, ma, mb):
    print(title)
    print("  %-28s %-20s %-20s %s" % ("metric", "A", "B", "delta"))
    for name, key in SUMMARY_ROWS:
        va, vb = ma.get(key), mb.get(key)
        if isinstance(va, tuple) or isinstance(vb, tuple):
            cells = (with_counts(va), with_counts(vb))
        else:
            cells = ("-" if va is None else str(va), "-" if vb is None else str(vb))
        print("  %-28s %-20s %-20s %s" % (name, cells[0], cells[1], delta(va, vb)))

    def ms(m, key):
        return "-" if m.get(key) is None else "%.0f" % m[key]

    print("  latency (depends on the machine's load): " + ", ".join(
        "%s %s -> %s ms" % (label_, ms(ma, key), ms(mb, key))
        for label_, key in (("mean", "latency_mean_ms"), ("p50", "latency_p50_ms"),
                            ("p90", "latency_p90_ms"))))


def print_text(result, only_changed):
    print("1. Reports")
    for name, r in zip("AB", result["reports"]):
        print("%s  %s" % (name, r["path"]))
        print("   %s, %s, %s, guards %s, server %s" % (
            r["model"], r["format"], r["date"] or "-",
            "-" if r["guards"] is None else ",".join(r["guards"]) or "none", r["server"] or "-"))
        print("   %s cases (%s commands, %s out of scope), static prefix %s tokens" % (
            r["cases"], r["positives"], r["negatives"],
            "-" if r["static_prefix_tokens"] is None else r["static_prefix_tokens"]))
        meta = r["meta"]
        if meta:
            if meta.get("replay_of"):
                how = "replay of %s" % meta["replay_of"]
            else:
                how = "llama-server %s, threads %s, timeout %s s" % (
                    meta.get("llama_server_version") or "?", meta.get("threads"),
                    meta.get("timeout_seconds"))
            print("   run: commit %s%s, %s (sha256 %s), tools.yml sha256 %s, %s" % (
                (meta.get("git_commit") or "?")[:8], " (dirty)" if meta.get("git_dirty") else "",
                meta.get("cases"), (meta.get("cases_sha256") or "?")[:8],
                (meta.get("tools_sha256") or "?")[:8], how))
    prompt = result["prompt"]
    if prompt["same"] is None:
        print("prompt: - (a report has no static_prefix)")
    elif prompt["same"]:
        print("prompt: same")
    else:
        print("prompt differs:")
        for line in prompt["tools"]:
            print("  " + line)
        for line in prompt["lines"][:40]:
            print("  " + line)
        if len(prompt["lines"]) > 40:
            print("  ... %d more lines" % (len(prompt["lines"]) - 40))
    for warning in result["warnings"]:
        print("warning: " + warning)

    print()
    summary = result["summary"]
    print_summary("2. Summary, all cases", *summary["all"])
    if summary["shared"]:
        print()
        print_summary("   Summary, the %d shared cases" % summary["shared_n"], *summary["shared"])
    else:
        print("   (both reports have the same cases)")

    print()
    print("3. Per expected tool, shared cases ([n in A -> n in B] where the sets differ; * changed)")
    print("  %-20s %-14s %-24s %-24s %5s %5s"
          % ("tool", "n", "tool A -> B", "exact A -> B", "fixed", "broke"))
    for row in result["per_tool"]:
        if only_changed and not row["changed"]:
            continue
        n = str(row["n"])
        if row["n_a"] != row["n"] or row["n_b"] != row["n"]:
            n += " [%d -> %d]" % (row["n_a"], row["n_b"])
        acc = "%s -> %s" % (pct(row["tool_accuracy"][0]), pct(row["tool_accuracy"][1]))
        if row["tool"] == NO_MATCH:
            exact = "(abstention)"
        else:
            exact = "%s -> %s" % (pct(row["exact_slot_accuracy"][0]), pct(row["exact_slot_accuracy"][1]))
        print("  %-20s %-14s %-24s %-24s %5d %5d %s" % (
            row["tool"], n, acc, exact, row["fixed"], row["broke"], "*" if row["changed"] else ""))

    print()
    print("4. Where the calls went, shared cases (model: its choice before the filters; "
          "false calls: results on out-of-scope cases)")
    print("  %-20s %-14s %-14s %-14s" % ("tool", "model A -> B", "result A -> B", "false calls"))
    for row in result["calls"]:
        if only_changed and not row["changed"]:
            continue
        cells = ["%d -> %d" % (row[k + "_a"], row[k + "_b"]) for k in ("model", "result", "false_calls")]
        if row["tool"] == DROPPED:
            cells[0] = ""  # only the filters drop
        if row["tool"] in (NO_MATCH, DROPPED, FAILED):
            cells[2] = ""
        print("  %-20s %-14s %-14s %-14s %s" % (row["tool"], cells[0], cells[1], cells[2],
                                                 "*" if row["changed"] else ""))

    print()
    print("5. Shared cases that changed (A -> B; cause in brackets)")
    for group in ("broke", "fixed", "changed"):
        entries = result["changes"][group]
        print("%s (%d)" % (group.upper(), len(entries)))
        for e in entries:
            expected = e["expected_a"] if e["expected_a"] == e["expected_b"] else \
                "%s -> %s" % (e["expected_a"], e["expected_b"])
            print("  [%s] %s: %s -> %s  [%s]" % (expected, e["text"], e["a"], e["b"], e["cause"]))
    c = result["counts"]
    print("%d raw outputs changed, %d verdicts changed with the same raw output (filters), %d with "
          "another label; %d fixed, %d broke, %d other changes" % (
              c.get("raw_changed", 0), c.get("same_raw_verdict_changed", 0), c.get("labels_changed", 0),
              c.get("fixed", 0), c.get("broke", 0), c.get("changed", 0)))

    print()
    print("6. Cases only in one report")
    for name, key in (("A", "only_in_a"), ("B", "only_in_b")):
        only = result[key]
        by_tool = "; ".join("%s %d/%d" % (t, n, d) for t, (n, d) in only["by_tool"].items())
        print("only in %s: %d cases, %d correct%s" % (name, only["n"], only["correct"],
                                                      "; " + by_tool if by_tool else ""))
        for e in only["cases"]:
            print("  [%s] %s: %s  (%s)" % (e["expected"], e["text"], e["got"],
                                           "correct" if e["correct"] else "wrong"))

    print()
    print("7. Guards, all cases: calls each guard drops on its own (out of scope, misroutes, "
          "LOST = the expected tool)")
    for row in result["guards"]:
        def count(side, key):
            return "-" if row[side] is None else str(row[side][key])

        print("  %s: drops %s -> %s; out of scope %s -> %s, misroutes %s -> %s, LOST %s -> %s" % (
            row["guard"], count("a", "drops"), count("b", "drops"), count("a", "negatives"),
            count("b", "negatives"), count("a", "misroutes"), count("b", "misroutes"),
            count("a", "lost"), count("b", "lost")))
        for title, key in (("newly LOST", "newly_lost"), ("no longer LOST", "no_longer_lost")):
            for e in row[key]:
                print("    %s: [%s] %s: %s (%s)%s" % (
                    title, e["expected"], e["text"], e["call"], e["reason"],
                    " (%s)" % e["note"] if e["note"] else ""))


def main():
    parser = argparse.ArgumentParser(
        description="Compares two GAR router eval reports (A = before, B = after).")
    parser.add_argument("before")
    parser.add_argument("after")
    parser.add_argument("--only-changed", action="store_true",
                        help="hide unchanged per-tool and call rows")
    parser.add_argument("--tool", help="only rows and cases about this tool")
    parser.add_argument("--json", action="store_true", help="print the comparison as JSON")
    parser.add_argument("--fail-on-broke", action="store_true", help="exit 1 when a shared case broke")
    args = parser.parse_args()
    try:
        a, b = Report(args.before), Report(args.after)
    except (OSError, ValueError, KeyError) as e:
        print("cannot read the reports: %s" % e, file=sys.stderr)
        return 2
    result = compare(a, b, args.tool)
    if args.json:
        json.dump(result, sys.stdout, indent=1)
        print()
    else:
        print_text(result, args.only_changed)
    if args.fail_on_broke and result["changes"]["broke"]:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
