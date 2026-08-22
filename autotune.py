#!/usr/bin/env python3
"""Search the mesh parameter space for the best printable STL, automatically.

Two judges, deliberately unequal:

  the score   -- objective, from meshkit.metrics: drift against the dense cloud,
                 coverage of it, watertightness, component count. This does the
                 ranking. It cannot be argued with and it cannot hallucinate.
  the critic  -- an LLM looking at renders of the finalists. It only sees what
                 the numbers are blind to: a base that is not flat, a surface
                 gone soapy from over-smoothing, a limb fused into a blob. It
                 may propose one more round of parameters; it never overrides
                 the score by itself.

Usage:
  ./.venv/bin/python autotune.py projects/<name> [--scale-to 80] [--no-llm]
"""
from __future__ import annotations

import argparse
import base64
import io
import itertools
import json
import time
from dataclasses import asdict, replace
from pathlib import Path

import numpy as np

import meshkit
from meshkit import Params

ROOT = Path(__file__).resolve().parent


# --------------------------------------------------------------------------- #
# stage 1: objective sweep
# --------------------------------------------------------------------------- #

def grid() -> list[Params]:
    """Coarse sweep. Poisson depth and trim first, because they decide what
    geometry exists at all; repair and smoothing only shape what survived.

    Ordered so that all candidates sharing a (depth, trim) run back to back --
    the Poisson output is cached per pair, so this turns 36 mesher runs into 9.
    """
    out = []
    for depth, trim in itertools.product((8, 9, 10), (5.0, 7.0, 10.0)):
        for repair, smooth in itertools.product(("cap", "meshfix"), (0, 4)):
            out.append(Params(poisson_depth=depth, poisson_trim=trim,
                              repair=repair, smooth_iters=smooth))
    return out


# Only the finalists' meshes are ever looked at again -- the critic renders them.
FINALISTS = 3


def sweep(fused: Path, cache: Path, cloud, log=print) -> list[dict]:
    results = []
    cands = grid()
    for i, p in enumerate(cands, 1):
        try:
            r = meshkit.evaluate(fused, p, cache, cloud)
        except Exception as e:
            log(f"[{i}/{len(cands)}] d{p.poisson_depth} t{p.poisson_trim:g} "
                f"{p.repair} s{p.smooth_iters}: FAILED ({type(e).__name__})")
            continue
        m = r["metrics"]
        log(f"[{i}/{len(cands)}] d{p.poisson_depth} t{p.poisson_trim:g} "
            f"{p.repair} s{p.smooth_iters}: score {r['score']:+.2f}  "
            f"drift {m['drift_rms_pct']:.2f}%  cov {m['coverage']:.3f}  "
            f"{m['faces']}f  {'watertight' if m['watertight'] else 'OPEN'}")
        results.append(r)
        # Drop every mesh that is no longer in contention as we go. Holding all
        # 36 Trimeshes plus their cached adjacency/normal arrays is several GB on
        # a 350k-face sweep and tens of GB on a large capture -- the numbers and
        # the score are all that the rest of the run needs from a loser.
        results.sort(key=lambda x: -x["score"])
        for stale in results[FINALISTS:]:
            stale["mesh"] = None
    results.sort(key=lambda r: -r["score"])
    return results


# --------------------------------------------------------------------------- #
# stage 2: visual critic
# --------------------------------------------------------------------------- #

def render(mesh, size: int = 420) -> str:
    """Three shaded views as one base64 JPEG. Matplotlib, because it needs no
    GL context -- this runs headless on the same box as the pipeline."""
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from mpl_toolkits.mplot3d.art3d import Poly3DCollection

    m = mesh.copy()
    m.apply_translation(-m.centroid)
    if len(m.faces) > 60_000:                    # keep the render itself cheap
        m = meshkit.decimate(m, 60_000)
    fig = plt.figure(figsize=(9, 3), facecolor="white")
    for i, (az, el) in enumerate([(0, 12), (120, 12), (240, 70)]):
        ax = fig.add_subplot(1, 3, i + 1, projection="3d")
        shade = np.clip(m.face_normals @ np.array([0.4, 0.5, 0.75]), 0.15, 1.0)
        ax.add_collection3d(Poly3DCollection(m.vertices[m.faces],
                                             facecolors=plt.cm.gray(shade),
                                             linewidths=0))
        r = float(np.abs(m.vertices).max())
        ax.set_xlim(-r, r); ax.set_ylim(-r, r); ax.set_zlim(-r, r)
        ax.set_axis_off(); ax.view_init(el, az); ax.set_box_aspect((1, 1, 1))
    buf = io.BytesIO()
    plt.tight_layout(); plt.savefig(buf, format="jpeg", dpi=size / 3, pil_kwargs={"quality": 78})
    plt.close(fig)
    return base64.b64encode(buf.getvalue()).decode()


CRITIC = """Bạn chấm mesh 3D chuẩn bị đem in. Vật thể được dựng từ ảnh chụp quanh nó
bằng COLMAP; mục tiêu là ĐÚNG KHỐI và ĐÚNG BIẾN DẠNG, không cần chi tiết bề mặt.

Bạn nhận vài ứng viên, mỗi ứng viên gồm tham số, số liệu đo, và 3 góc render.
Số liệu đã xếp hạng sẵn theo điểm; việc của bạn là nhìn ảnh và bắt những lỗi mà
số liệu không thấy: đáy không phẳng, bề mặt bị làm mượt quá thành nhão, chi tiết
mảnh bị dính thành cục, mảnh rác còn sót, thủng lỗ nhìn thấy được.

Trả về DUY NHẤT một object JSON, không kèm chữ nào khác:
{"pick": <index ứng viên tốt nhất>,
 "why": "<1-2 câu tiếng Việt>",
 "problems": ["<lỗi nhìn thấy>", ...],
 "suggest": {"poisson_depth": int, "poisson_trim": float, "repair": "cap|meshfix",
             "smooth_iters": int, "target_faces": int}
}
"suggest" là một bộ tham số đáng thử thêm; bỏ trống {} nếu ứng viên đã đủ tốt."""


def critique(finalists: list[dict], model_env: dict) -> dict | None:
    import httpx
    content: list = [{"type": "text", "text":
                      "Các ứng viên (đã xếp hạng theo điểm khách quan):"}]
    for i, r in enumerate(finalists):
        content.append({"type": "text", "text":
                        f"\n### Ứng viên {i}\nparams: "
                        f"{json.dumps(r['params'], ensure_ascii=False)}\n"
                        f"metrics: {json.dumps(r['metrics'], ensure_ascii=False)}\n"
                        f"score: {r['score']}"})
        content.append({"type": "image_url", "image_url":
                        {"url": "data:image/jpeg;base64," + render(r["mesh"])}})
    resp = httpx.post(
        model_env.get("OPENAI_BASE_URL", "").rstrip("/") + "/chat/completions",
        headers={"Authorization": f"Bearer {model_env['OPENAI_API_KEY']}"},
        json={"model": model_env.get("OPENAI_MODEL", "claude-haiku-4-5"),
              "max_tokens": 900, "temperature": 0.1,
              "messages": [{"role": "system", "content": CRITIC},
                           {"role": "user", "content": content}]},
        timeout=240)
    resp.raise_for_status()
    txt = resp.json()["choices"][0]["message"]["content"]
    a, b = txt.find("{"), txt.rfind("}")
    if a < 0 or b < a:
        return None
    try:
        return json.loads(txt[a:b + 1])
    except json.JSONDecodeError:
        return None


# --------------------------------------------------------------------------- #

def tune(project: Path, scale_to: float = 0.0, target_faces: int = 0,
         use_llm: bool = True, log=print) -> dict:
    project = project.resolve()
    dense = project / "workspace" / "dense"
    fused = dense / "fused.ply"
    if not fused.exists():
        raise FileNotFoundError(f"Chưa có {fused}")
    cache = dense / ".meshcache"
    t0 = time.time()

    cloud = meshkit.cloud_points(fused)
    log(f"==> tham chiếu: {len(cloud)} điểm (đã bỏ mặt phẳng nền)")
    log(f"==> quét {len(grid())} tổ hợp tham số")
    results = sweep(fused, cache, cloud, log)
    if not results:
        raise RuntimeError("Không tổ hợp nào dựng được mesh")

    report = {"candidates": [{k: r[k] for k in ("params", "metrics", "score")}
                             for r in results]}
    best = results[0]
    log(f"==> tốt nhất theo điểm: {json.dumps(best['params'])} -> {best['score']}")

    if use_llm:
        env = None
        try:
            import analyze
            env = analyze.load_env()
            verdict = (critique(results[:FINALISTS], env)
                       if env.get("OPENAI_API_KEY") else None)
        except Exception as e:
            log(f"==> critic lỗi: {type(e).__name__}: {e}")
            verdict = None
        if verdict:
            report["critic"] = verdict
            log(f"==> critic chọn #{verdict.get('pick')}: {verdict.get('why','')}")
            for p in verdict.get("problems", []):
                log(f"    - {p}")
            pick = verdict.get("pick")
            # The critic reorders only among candidates the score already rated
            # top-3, so a hallucinated pick cannot drag in a bad mesh -- which
            # means the bound is the number of candidates it was SHOWN, not the
            # length of the whole sweep.
            if isinstance(pick, int) and not isinstance(pick, bool) \
                    and 0 <= pick < min(FINALISTS, len(results)):
                best = results[pick]
            sug = verdict.get("suggest") or {}
            fields = {f.name for f in Params.__dataclass_fields__.values()}
            sug = {k: v for k, v in sug.items() if k in fields and v is not None}
            if sug:
                try:
                    log(f"==> thử thêm gợi ý của critic: {json.dumps(sug)}")
                    extra = meshkit.evaluate(fused, replace(Params(), **sug), cache, cloud)
                    report["suggested"] = {k: extra[k] for k in ("params", "metrics", "score")}
                    log(f"    score {extra['score']:+.2f} (best {best['score']:+.2f})")
                    if extra["score"] > best["score"]:
                        best = extra
                        log("    -> tốt hơn, lấy bộ này")
                except Exception as e:
                    log(f"    gợi ý chạy lỗi: {type(e).__name__}: {e}")

    # Decimation and scaling are applied last, on the winner only: they never
    # improve the shape, so letting them into the search would only add noise.
    final = replace(Params(**best["params"]),
                    target_faces=target_faces or best["params"]["target_faces"],
                    scale_to_mm=scale_to or best["params"]["scale_to_mm"])
    r = meshkit.evaluate(fused, final, cache, cloud)
    out = dense / "auto-best.stl"
    r["mesh"].export(out)

    report["best"] = {"params": r["params"], "metrics": r["metrics"], "score": r["score"]}
    report["output"] = out.name
    report["seconds"] = round(time.time() - t0, 1)
    (dense / "autotune.json").write_text(json.dumps(report, ensure_ascii=False, indent=1))
    log(f"==> {out.name}: {r['metrics']['faces']} mặt, "
        f"{'kín' if r['metrics']['watertight'] else 'HỞ'}, "
        f"drift {r['metrics']['drift_rms_pct']:.2f}%, "
        f"cov {r['metrics']['coverage']:.3f}  ({report['seconds']}s)")
    return report


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("project", type=Path)
    ap.add_argument("--scale-to", type=float, default=0.0,
                    help="cạnh dài nhất, tính bằng mm")
    ap.add_argument("--target-faces", type=int, default=0)
    ap.add_argument("--no-llm", action="store_true")
    a = ap.parse_args()
    tune(a.project, a.scale_to, a.target_faces, use_llm=not a.no_llm)


if __name__ == "__main__":
    main()
