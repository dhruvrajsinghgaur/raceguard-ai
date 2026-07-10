#!/usr/bin/env python3
"""
RaceGuard AI — report generator.

Reads output/project_graph.json (Stage 1: deterministic static analysis IR)
and output/risk_explanations.json (Stage 2: LLM narration, run on AMD GPU
via vLLM), merges them, and renders a single self-contained HTML report.

Risks and explanations are paired BY POSITION, not by matching field names —
Stage2Runner iterates graph.projectRisks in order and appends exactly one
explanation (success or failure placeholder) per risk in that same order,
so index-based pairing is the correct join even when multiple risks share
the same owningClass+field+pattern (e.g. several CHECK_THEN_ACT findings on
GameState.players from different caller methods).

Usage (run from the project root, after both ProjectAnalyzer and
Stage2Runner have produced their output files):
    python3 tools/generate_report.py
Writes: output/report.html
"""
import json
import html
import re
from pathlib import Path

OUTPUT_DIR = Path("output")
GRAPH_PATH = OUTPUT_DIR / "project_graph.json"
EXPLANATIONS_PATH = OUTPUT_DIR / "risk_explanations.json"
REPORT_PATH = OUTPUT_DIR / "report.html"


def load_data(output_dir: Path):
    graph = json.loads((output_dir / "project_graph.json").read_text())
    explanations = json.loads((output_dir / "risk_explanations.json").read_text())
    risks = graph.get("projectRisks", [])
    if len(risks) != len(explanations):
        print(f"WARNING: {len(risks)} risks but {len(explanations)} explanations — "
              f"pairing will be misaligned. Rerun ProjectAnalyzer + Stage2Runner together.")
    paired = list(zip(risks, explanations + [None] * max(0, len(risks) - len(explanations))))
    return graph, paired


def esc(s):
    return html.escape(str(s)) if s is not None else ""


def pattern_label(pattern):
    return (pattern or "UNKNOWN").replace("_", " ").title().replace(" ", "-")


def parse_lane(line):
    """Splits an interleaving line like 'T1: reads x' into (lane, text)."""
    m = re.match(r"^\s*(T\d+)\s*:\s*(.*)$", line or "", re.IGNORECASE)
    if m:
        return m.group(1).upper(), m.group(2)
    return None, line or ""


def render_trace(interleaving):
    if not interleaving:
        return '<p class="trace-empty">No interleaving trace available.</p>'
    rows = []
    for line in interleaving:
        lane, text = parse_lane(line)
        lane_class = "lane-t1" if lane == "T1" else ("lane-t2" if lane == "T2" else "lane-none")
        lane_label = lane or "—"
        rows.append(f'''
        <div class="trace-row {lane_class}">
          <span class="trace-rail"></span>
          <span class="trace-lane">{esc(lane_label)}</span>
          <code class="trace-text">{esc(text)}</code>
        </div>''')
    return f'<div class="trace-ladder">{"".join(rows)}</div>'


def render_fix_suggestions(fix_suggestions, recommended_approach):
    if not fix_suggestions:
        return ""
    items = []
    for fs in fix_suggestions:
        is_chosen = recommended_approach and fs.get("approach", "").strip() == recommended_approach.strip()
        cls = "fix-chosen" if is_chosen else "fix-alt"
        badge = '<span class="fix-badge">chosen</span>' if is_chosen else ""
        items.append(f'''
        <div class="fix-option {cls}">
          <div class="fix-head">{esc(fs.get("approach"))} {badge}</div>
          <div class="fix-row"><span class="fix-tag pro">pro</span>{esc(fs.get("pros"))}</div>
          <div class="fix-row"><span class="fix-tag con">con</span>{esc(fs.get("cons"))}</div>
        </div>''')
    return f'<div class="fix-options">{"".join(items)}</div>'


def render_risk_card(idx, risk, explanation):
    severity = (risk.get("severity") or "MEDIUM").upper()
    pattern = risk.get("pattern") or "UNKNOWN"
    owning_class = risk.get("owningClass") or "?"
    field = risk.get("field") or "?"

    explanation_text = (explanation or {}).get("explanation") or risk.get("summary") or ""
    interleaving = (explanation or {}).get("interleaving") or []
    recommended_approach = (explanation or {}).get("recommendedFixApproach")
    recommended_rationale = (explanation or {}).get("recommendedFixRationale") or ""

    reasons = risk.get("reasons") or []
    reason_chips = "".join(f'<span class="chip">{esc(r)}</span>' for r in reasons)

    return f'''
    <article class="card sev-{severity.lower()}" data-severity="{esc(severity)}" data-pattern="{esc(pattern)}">
      <header class="card-head">
        <div class="card-head-left">
          <span class="sev-badge">{esc(severity)}</span>
          <span class="pattern-badge">{esc(pattern_label(pattern))}</span>
        </div>
        <h3 class="card-title"><span class="class-name">{esc(owning_class)}</span><span class="dot">.</span><span class="field-name">{esc(field)}</span></h3>
      </header>

      <p class="card-explanation">{esc(explanation_text)}</p>

      <div class="chips">{reason_chips}</div>

      <div class="section-label">Predicted interleaving</div>
      {render_trace(interleaving)}

      <div class="section-label">Fix</div>
      {f'<p class="fix-rationale"><strong>{esc(recommended_approach)}</strong> — {esc(recommended_rationale)}</p>' if recommended_approach else ""}
      {render_fix_suggestions(risk.get("fixSuggestions"), recommended_approach)}
    </article>'''


def build_report(graph, paired):
    classes = graph.get("classes", [])
    cross = graph.get("crossClassAccesses", [])
    risks = [r for r, _ in paired]

    high = sum(1 for r in risks if (r.get("severity") or "").upper() == "HIGH")
    medium = sum(1 for r in risks if (r.get("severity") or "").upper() == "MEDIUM")

    patterns = sorted(set(r.get("pattern") or "UNKNOWN" for r in risks))
    pattern_filter_buttons = "".join(
        f'<button class="filter-btn" data-filter-pattern="{esc(p)}">{esc(pattern_label(p))}</button>'
        for p in patterns
    )

    cards = "".join(
        render_risk_card(i, risk, explanation)
        for i, (risk, explanation) in enumerate(paired)
    )

    return HTML_TEMPLATE.format(
        num_classes=len(classes),
        num_cross=len(cross),
        num_risks=len(risks),
        num_high=high,
        num_medium=medium,
        pattern_filter_buttons=pattern_filter_buttons,
        cards=cards,
    )


HTML_TEMPLATE = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>RaceGuard AI — Concurrency Validation Report</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link href="https://fonts.googleapis.com/css2?family=IBM+Plex+Sans:wght@400;500;600;700&family=IBM+Plex+Mono:wght@400;500;600&display=swap" rel="stylesheet">
<style>
  :root {{
    --bg: #10141A;
    --surface: #171D26;
    --surface-2: #1D2530;
    --border: #2A323E;
    --text: #E7ECF2;
    --text-muted: #8B96A5;
    --t1: #E8A33D;
    --t2: #3DC4B0;
    --high: #E2574C;
    --medium: #E8A33D;
    --fix: #4CAF7D;
    --font-display: 'IBM Plex Sans', system-ui, sans-serif;
    --font-mono: 'IBM Plex Mono', 'SF Mono', Consolas, monospace;
  }}

  * {{ box-sizing: border-box; }}

  body {{
    margin: 0;
    background: var(--bg);
    color: var(--text);
    font-family: var(--font-display);
    line-height: 1.5;
    -webkit-font-smoothing: antialiased;
  }}

  a {{ color: var(--t2); }}

  .wrap {{ max-width: 1080px; margin: 0 auto; padding: 48px 24px 96px; }}

  header.hero {{
    border-bottom: 1px solid var(--border);
    padding-bottom: 40px;
    margin-bottom: 40px;
  }}

  .eyebrow {{
    font-family: var(--font-mono);
    font-size: 13px;
    letter-spacing: 0.08em;
    color: var(--t2);
    text-transform: uppercase;
    margin-bottom: 12px;
  }}

  h1 {{
    font-size: clamp(32px, 5vw, 48px);
    font-weight: 700;
    margin: 0 0 12px;
    letter-spacing: -0.02em;
  }}

  .tagline {{
    color: var(--text-muted);
    font-size: 17px;
    max-width: 640px;
    margin: 0 0 32px;
  }}

  .stats {{
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
    gap: 1px;
    background: var(--border);
    border: 1px solid var(--border);
    border-radius: 10px;
    overflow: hidden;
  }}

  .stat {{
    background: var(--surface);
    padding: 20px;
  }}

  .stat-num {{
    font-family: var(--font-mono);
    font-size: 28px;
    font-weight: 600;
    line-height: 1;
  }}

  .stat-num.high {{ color: var(--high); }}
  .stat-num.medium {{ color: var(--medium); }}

  .stat-label {{
    font-size: 12px;
    color: var(--text-muted);
    margin-top: 6px;
    text-transform: uppercase;
    letter-spacing: 0.05em;
  }}

  .pipeline-note {{
    font-size: 13px;
    color: var(--text-muted);
    margin-top: 24px;
    font-family: var(--font-mono);
  }}

  .pipeline-note b {{ color: var(--text); font-weight: 500; }}

  .filters {{
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-bottom: 28px;
  }}

  .filter-btn {{
    font-family: var(--font-mono);
    font-size: 12px;
    background: var(--surface);
    border: 1px solid var(--border);
    color: var(--text-muted);
    padding: 7px 12px;
    border-radius: 6px;
    cursor: pointer;
    transition: all 0.15s ease;
  }}

  .filter-btn:hover {{ border-color: var(--t2); color: var(--text); }}
  .filter-btn.active {{ background: var(--t2); border-color: var(--t2); color: #0A1210; }}

  .cards {{ display: flex; flex-direction: column; gap: 20px; }}

  .card {{
    background: var(--surface);
    border: 1px solid var(--border);
    border-left: 3px solid var(--border);
    border-radius: 10px;
    padding: 24px 28px;
  }}

  .card.sev-high {{ border-left-color: var(--high); }}
  .card.sev-medium {{ border-left-color: var(--medium); }}

  .card.hidden {{ display: none; }}

  .card-head {{
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    gap: 16px;
    flex-wrap: wrap;
    margin-bottom: 14px;
  }}

  .card-head-left {{ display: flex; gap: 8px; }}

  .sev-badge {{
    font-family: var(--font-mono);
    font-size: 11px;
    font-weight: 600;
    letter-spacing: 0.05em;
    padding: 3px 8px;
    border-radius: 4px;
    background: var(--surface-2);
  }}

  .sev-high .sev-badge {{ color: var(--high); }}
  .sev-medium .sev-badge {{ color: var(--medium); }}

  .pattern-badge {{
    font-family: var(--font-mono);
    font-size: 11px;
    padding: 3px 8px;
    border-radius: 4px;
    background: var(--surface-2);
    color: var(--text-muted);
  }}

  .card-title {{
    font-family: var(--font-mono);
    font-size: 18px;
    font-weight: 600;
    margin: 0;
  }}

  .class-name {{ color: var(--t2); }}
  .dot {{ color: var(--text-muted); }}
  .field-name {{ color: var(--text); }}

  .card-explanation {{
    color: var(--text);
    font-size: 15px;
    margin: 0 0 16px;
  }}

  .chips {{ display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 20px; }}

  .chip {{
    font-size: 11px;
    font-family: var(--font-mono);
    color: var(--text-muted);
    background: var(--surface-2);
    border: 1px solid var(--border);
    padding: 3px 9px;
    border-radius: 20px;
  }}

  .section-label {{
    font-family: var(--font-mono);
    font-size: 11px;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    color: var(--text-muted);
    margin: 20px 0 10px;
  }}

  .trace-ladder {{
    background: #0B0E13;
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 14px 16px;
  }}

  .trace-row {{
    display: grid;
    grid-template-columns: 4px 34px 1fr;
    align-items: center;
    gap: 12px;
    padding: 5px 0;
  }}

  .trace-rail {{ width: 4px; height: 100%; border-radius: 2px; align-self: stretch; }}
  .lane-t1 .trace-rail {{ background: var(--t1); }}
  .lane-t2 .trace-rail {{ background: var(--t2); }}
  .lane-none .trace-rail {{ background: var(--border); }}

  .trace-lane {{
    font-family: var(--font-mono);
    font-size: 11px;
    font-weight: 600;
  }}

  .lane-t1 .trace-lane {{ color: var(--t1); }}
  .lane-t2 .trace-lane {{ color: var(--t2); }}
  .lane-none .trace-lane {{ color: var(--text-muted); }}

  .trace-text {{
    font-family: var(--font-mono);
    font-size: 13px;
    color: var(--text);
    background: none;
  }}

  .trace-empty {{ color: var(--text-muted); font-size: 13px; font-style: italic; }}

  .fix-rationale {{
    font-size: 14px;
    color: var(--text);
    background: rgba(76, 175, 125, 0.08);
    border: 1px solid rgba(76, 175, 125, 0.3);
    border-radius: 8px;
    padding: 12px 14px;
    margin: 0 0 12px;
  }}

  .fix-options {{ display: flex; flex-direction: column; gap: 8px; }}

  .fix-option {{
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 10px 14px;
    font-size: 13px;
  }}

  .fix-option.fix-chosen {{ border-color: var(--fix); background: rgba(76, 175, 125, 0.05); }}
  .fix-option.fix-alt {{ opacity: 0.55; }}

  .fix-head {{
    font-weight: 600;
    margin-bottom: 4px;
    display: flex;
    align-items: center;
    gap: 8px;
  }}

  .fix-badge {{
    font-family: var(--font-mono);
    font-size: 10px;
    text-transform: uppercase;
    letter-spacing: 0.05em;
    background: var(--fix);
    color: #0A1210;
    padding: 2px 7px;
    border-radius: 10px;
  }}

  .fix-row {{ color: var(--text-muted); margin-top: 3px; }}
  .fix-tag {{
    font-family: var(--font-mono);
    font-size: 10px;
    text-transform: uppercase;
    padding: 1px 6px;
    border-radius: 4px;
    margin-right: 6px;
  }}
  .fix-tag.pro {{ background: rgba(76,175,125,0.15); color: var(--fix); }}
  .fix-tag.con {{ background: rgba(226,87,76,0.15); color: var(--high); }}

  footer {{
    margin-top: 60px;
    padding-top: 24px;
    border-top: 1px solid var(--border);
    color: var(--text-muted);
    font-size: 13px;
    font-family: var(--font-mono);
  }}

  @media (max-width: 640px) {{
    .wrap {{ padding: 32px 16px 64px; }}
    .card {{ padding: 20px; }}
  }}
</style>
</head>
<body>
<div class="wrap">

  <header class="hero">
    <div class="eyebrow">Concurrency Validation Report</div>
    <h1>RaceGuard AI</h1>
    <p class="tagline">
      An AI-assisted concurrency validation platform that combines static analysis, structured
      concurrency modeling, and AI reasoning to identify, explain, and help reproduce potential
      concurrency defects.
    </p>

    <div class="stats">
      <div class="stat">
        <div class="stat-num">{num_classes}</div>
        <div class="stat-label">Classes analyzed</div>
      </div>
      <div class="stat">
        <div class="stat-num">{num_cross}</div>
        <div class="stat-label">Cross-class accesses traced</div>
      </div>
      <div class="stat">
        <div class="stat-num">{num_risks}</div>
        <div class="stat-label">Risks detected</div>
      </div>
      <div class="stat">
        <div class="stat-num high">{num_high}</div>
        <div class="stat-label">High severity</div>
      </div>
      <div class="stat">
        <div class="stat-num medium">{num_medium}</div>
        <div class="stat-label">Medium severity</div>
      </div>
    </div>

    <div class="pipeline-note">
      <b>Stage 1</b> — deterministic AST-level static analysis (JavaParser) &nbsp;→&nbsp;
      <b>Stage 2</b> — LLM reasoning over the resulting IR, run on AMD GPU via vLLM
    </div>
  </header>

  <div class="filters">
    <button class="filter-btn active" data-filter-severity="ALL">All</button>
    <button class="filter-btn" data-filter-severity="HIGH">High severity</button>
    <button class="filter-btn" data-filter-severity="MEDIUM">Medium severity</button>
    {pattern_filter_buttons}
  </div>

  <div class="cards">
    {cards}
  </div>

  <footer>
    Generated by RaceGuard AI · every risk above was found by deterministic static analysis first;
    the LLM narrates and helps prioritize a fix, it does not decide what counts as a risk.
  </footer>

</div>

<script>
  const severityButtons = document.querySelectorAll('[data-filter-severity]');
  const patternButtons = document.querySelectorAll('[data-filter-pattern]');
  const cards = document.querySelectorAll('.card');

  let activeSeverity = 'ALL';
  let activePattern = null;

  function applyFilters() {{
    cards.forEach(card => {{
      const sevMatch = activeSeverity === 'ALL' || card.dataset.severity === activeSeverity;
      const patMatch = !activePattern || card.dataset.pattern === activePattern;
      card.classList.toggle('hidden', !(sevMatch && patMatch));
    }});
  }}

  severityButtons.forEach(btn => {{
    btn.addEventListener('click', () => {{
      activeSeverity = btn.dataset.filterSeverity;
      severityButtons.forEach(b => b.classList.toggle('active', b === btn));
      applyFilters();
    }});
  }});

  patternButtons.forEach(btn => {{
    btn.addEventListener('click', () => {{
      const wasActive = btn.classList.contains('active');
      patternButtons.forEach(b => b.classList.remove('active'));
      activePattern = wasActive ? null : btn.dataset.filterPattern;
      if (!wasActive) btn.classList.add('active');
      applyFilters();
    }});
  }});
</script>
</body>
</html>
"""


def generate(output_dir: Path = OUTPUT_DIR) -> Path:
    """Callable entry point — used by both the CLI (main, below) and the web app."""
    graph, paired = load_data(output_dir)
    report_html = build_report(graph, paired)
    report_path = output_dir / "report.html"
    report_path.write_text(report_html)
    return report_path


def main():
    report_path = generate(OUTPUT_DIR)
    print(f"Wrote {report_path}")


if __name__ == "__main__":
    main()