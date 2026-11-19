# Auswertung Sprecher-Clustering mit bekannten Profilen als Start-Cluster (siehe docs/dev/PHASE2-MESSUNGEN.md).
# Eingabe: Ordner mit c1.csv/c2.csv aus SpeakerEmbeddingDumpTest (Spalten: start,end,sprecher,embedding...).
# Aufruf: python3 speaker_seeded_eval.py <dump-ordner>
import sys, numpy as np
d = sys.argv[1]

def load(n):
    rows = [l.strip().split(",") for l in open(f"{d}/{n}.csv") if l.strip()]
    S = np.array([[int(r[0]), int(r[1])] for r in rows]); R = np.array([int(r[2]) for r in rows])
    E = np.array([[float(x) for x in r[3:]] for r in rows]); E /= np.linalg.norm(E, axis=1, keepdims=True)
    return S, R, E

def nz(v): return v / np.linalg.norm(v)

files = {n: load(n) for n in ["c1", "c2"]}
# Verteilungen: Abschnitt vs eigenes Zentrum (ohne sich selbst) / fremde Zentren, je Datei
for n, (S, R, E) in files.items():
    own, other = [], []
    for i in range(len(E)):
        if R[i] < 0: continue
        for k in set(R) - {-1}:
            m = (R == k); m[i] = False
            c = nz(E[m].sum(0)); s = c @ E[i]
            (own if k == R[i] else other).append(s)
    print(f"{n}: Abschnitt↔eigenes Zentrum min {min(own):.3f} p5 {np.percentile(own,5):.3f}; Abschnitt↔fremdes Zentrum max {max(other):.3f} p95 {np.percentile(other,95):.3f}")
    # paarweise Abschnitte
    same = [E[i] @ E[j] for i in range(len(E)) for j in range(i+1, len(E)) if R[i] == R[j] >= 0]
    diff = [E[i] @ E[j] for i in range(len(E)) for j in range(i+1, len(E)) if R[i] >= 0 and R[j] >= 0 and R[i] != R[j]]
    print(f"   Abschnitt↔Abschnitt gleich p5 {np.percentile(same,5):.3f} min {min(same):.3f}; verschieden p95 {np.percentile(diff,95):.3f} max {max(diff):.3f}")

def local(E, thr, refine=2, seeds=None, seed_thr=None):
    """greedy wie Kotlin; seeds: Liste (id, Zentrum) bekannter Profile als Start-Cluster (feste Zentren)"""
    sums = []; ids = []
    if seeds:
        for pid, c in seeds: sums.append(None); ids.append(pid)
    seedc = [c for _, c in (seeds or [])]
    lab = np.zeros(len(E), int)
    for i, v in enumerate(E):
        best, bs = -1, -2
        for k in range(len(ids)):
            c = seedc[k] if sums[k] is None else nz(sums[k]) if k >= len(seedc) else (nz(sums[k]) )
            s = c @ v
            t = seed_thr if k < len(seedc) else thr
            if s >= t and s > bs: best, bs = k, s
        if best >= 0:
            lab[i] = best
            if best >= len(seedc): sums[best] = sums[best] + v
        else:
            sums.append(v.copy()); ids.append(None); lab[i] = len(ids) - 1
    return lab, ids

def evaluate(name, R, lab, mapping):
    ok = sum(1 for l, r in zip(lab, R) if r >= 0 and mapping.get(l) == r); n = sum(1 for r in R if r >= 0)
    return ok / n

def run(strategy, verbose=False):
    profiles = []  # (id, centroid, weight)
    out = []
    order = [("c1", files["c1"]), ("c2", files["c2"]), ("c1", files["c1"])]
    mapping = None
    for ri, (n, (S, R, E)) in enumerate(order):
        global CUR_LEN; CUR_LEN = S[:, 1] - S[:, 0]
        labels_global = strategy(E, profiles)
        if mapping is None:
            mapping = {}
            for g in set(labels_global):
                rs = [r for l, r in zip(labels_global, R) if l == g and r >= 0]
                if rs: mapping[g] = max(set(rs), key=rs.count)
        acc = evaluate(n, R, labels_global, mapping)
        out.append((n, len(profiles), acc))
    return out

def make_strategy(local_thr, prof_thr, seeded=False, seed_thr=None, merge_thr=None, refine=0):
    counter = [0]
    def strat(E, profiles):
        if seeded and profiles:
            lab, ids = local(E, local_thr, seeds=[(p[0], p[1]) for p in profiles], seed_thr=seed_thr)
        else:
            lab, ids = local(E, local_thr); ids = [None] * len(ids)
        # Cluster bilden
        clusters = {}
        for i, l in enumerate(lab): clusters.setdefault(l, []).append(i)
        cents = {l: nz(E[ix].sum(0)) for l, ix in clusters.items()}
        # optional lokale Cluster mit hohem Zentren-Cos zusammenlegen
        if merge_thr:
            changed = True
            while changed:
                changed = False
                ks = sorted(cents)
                for a in ks:
                    for b in ks:
                        if a < b and a in cents and b in cents and (ids[a] is None or ids[b] is None or ids[a] == ids[b]) and cents[a] @ cents[b] >= merge_thr:
                            clusters[a] += clusters.pop(b); cents[a] = nz(E[clusters[a]].sum(0)); del cents[b]
                            if ids[a] is None: ids[a] = ids[b]
                            changed = True
        # global: seeded cluster schon zugeordnet; Rest eins-zu-eins gegen Profile
        res = {}
        used = set(ids[l] for l in clusters if ids[l] is not None)
        for l in clusters:
            if ids[l] is not None: res[l] = ids[l]
        pairs = sorted(((cents[l] @ p[1], l, pi) for l in clusters if l not in res for pi, p in enumerate(profiles) if p[0] not in used), reverse=True)
        for s, l, pi in pairs:
            if s < prof_thr: break
            if l in res or profiles[pi][0] in used: continue
            res[l] = profiles[pi][0]; used.add(profiles[pi][0])
        for l in clusters:
            if l not in res:
                counter[0] += 1; pid = f"P{counter[0]}"; profiles.append([pid, cents[l], len(clusters[l])]); res[l] = pid
            else:
                for p in profiles:
                    if p[0] == res[l]:
                        w = min(p[2], 50); p[1] = nz(p[1] * w + cents[l] * len(clusters[l])); p[2] += len(clusters[l])
        return [res[l] for l in lab]
    return strat

for desc, st in [
    ("aktuell: lokal 0,6, Profil 0,82", make_strategy(0.6, 0.82)),
    ("lokal 0,7, Profil 0,85", make_strategy(0.7, 0.85)),
    ("lokal 0,7 + merge 0,88, Profil 0,88", make_strategy(0.7, 0.88, merge_thr=0.88)),
    ("lokal 0,65 + merge 0,9, Profil 0,88", make_strategy(0.65, 0.88, merge_thr=0.9)),
    ("seeded 0,7 / lokal 0,7, Profil 0,88", make_strategy(0.7, 0.88, seeded=True, seed_thr=0.7)),
    ("seeded 0,75 / lokal 0,7 + merge 0,88", make_strategy(0.7, 0.88, seeded=True, seed_thr=0.75, merge_thr=0.88)),
    ("seeded 0,65 / lokal 0,65 + merge 0,9", make_strategy(0.65, 0.88, seeded=True, seed_thr=0.65, merge_thr=0.9)),
]:
    print(desc, [(n, p, f"{a*100:.0f}%") for n, p, a in run(st)])

print("\n--- v2: Seeds fest + Greedy + Nachzuordnung (argmax) + Zusammenlegen ---")
CUR_LEN = None
def make_v2(t_seed, t_local, t_merge, t_prof=None, refine=2, min_new=3):
    counter = [0]
    t_prof = t_prof or t_merge
    def strat(E, profiles):
        seeds = [(p[0], p[1].copy()) for p in profiles]
        ns = len(seeds)
        cent = [c for _, c in seeds]; sums = [None] * ns
        lab = np.zeros(len(E), int)
        for i, v in enumerate(E):
            best, bs = -1, -2
            for k in range(len(cent)):
                c = cent[k] if k < ns else nz(sums[k]); s = c @ v
                if s >= (t_seed if k < ns else t_local) and s > bs: best, bs = k, s
            if best < 0:
                cent.append(None); sums.append(v.copy()); lab[i] = len(cent) - 1
            else:
                lab[i] = best
                if best >= ns: sums[best] = sums[best] + v
        def centers():
            return [cent[k] if k < ns else (nz(E[lab == k].sum(0)) if (lab == k).any() else None) for k in range(len(cent))]
        for _ in range(refine):
            C = centers()
            for i, v in enumerate(E):
                sims = [(C[k] @ v if C[k] is not None else -9) for k in range(len(C))]
                lab[i] = int(np.argmax(sims))
        # Zusammenlegen: lokale Cluster untereinander bzw. in Seeds, wenn Zentren ≥ t_merge
        changed = True
        while changed:
            changed = False
            C = centers()
            for b in range(ns, len(C)):
                if C[b] is None: continue
                cands = [(C[a] @ C[b], a) for a in range(len(C)) if a != b and C[a] is not None and (a < ns or a < b)]
                if cands:
                    s, a = max(cands)
                    if s >= t_merge:
                        lab[lab == b] = a; changed = True; break
        res = {}
        for k in set(lab):
            if k < ns: res[k] = seeds[k][0]
        for k in sorted(set(lab)):
            if k in res: continue
            ix = lab == k
            if CUR_LEN is not None and CUR_LEN[ix].sum() < min_new * 16000:
                res[k] = None; continue
            counter[0] += 1; pid = f"P{counter[0]}"; profiles.append([pid, nz(E[ix].sum(0)), int(ix.sum())]); res[k] = pid
        for k in set(lab):
            if k < ns:
                ix = lab == k; c = nz(E[ix].sum(0))
                for p in profiles:
                    if p[0] == res[k]:
                        w = min(p[2], 50); p[1] = nz(p[1] * w + c * ix.sum()); p[2] += int(ix.sum())
        return [res[l] for l in lab]
    return strat

import itertools
grid = []
for ts, tl, tm in itertools.product([0.55, 0.6, 0.65, 0.7, 0.75], [0.6, 0.7], [0.85, 0.88, 0.92]):
    r = run(make_v2(ts, tl, tm))
    ok = all(p == 3 for _, p, _ in r) and all(a >= 0.9 for _, _, a in r)
    grid.append((ts, tl, tm, r, ok))
    print(f"seed {ts} lokal {tl} merge {tm}: " + " ".join(f"{n}:{p}/{a*100:.0f}%" for n, p, a in r) + ("  OK" if ok else ""))

print("\n--- Szenario: ein Sprecher ist neu (Profile nur für die anderen beiden aus c1) ---")
def scenario(strat_factory, known):
    S1, R1, E1 = files["c1"]
    S2, R2, E2 = files["c2"]
    profiles = []
    st = strat_factory()
    m = np.isin(R1, known)
    global CUR_LEN; CUR_LEN = (S1[:, 1] - S1[:, 0])[m]
    lab1 = st(E1[m], profiles)
    mapping = {}
    for g in set(lab1):
        rs = [r for l, r in zip(lab1, R1[m]) if l == g]
        mapping[g] = max(set(rs), key=rs.count)
    n_before = len(profiles)
    CUR_LEN = S2[:, 1] - S2[:, 0]
    lab2 = st(E2, profiles)
    new_ids = set(lab2) - set(mapping) - {None}
    # neuer Sprecher: Mehrheit seiner Abschnitte soll in einem NEUEN Profil landen
    newspk = [k for k in [0, 1, 2] if k not in known][0]
    for g in new_ids:
        rs = [r for l, r in zip(lab2, R2) if l == g]
        mapping[g] = max(set(rs), key=rs.count)
    ok = sum(1 for l, r in zip(lab2, R2) if r >= 0 and mapping.get(l) == r); n = sum(1 for r in R2 if r >= 0)
    return n_before, len(profiles), ok / n

for ts in [0.55, 0.6, 0.65, 0.7, 0.75]:
    for tm in [0.85, 0.88, 0.9]:
        res = [scenario(lambda: make_v2(ts, 0.65, tm), known) for known in ([0, 1], [1, 2], [0, 2])]
        print(f"seed {ts} merge {tm}: " + "  ".join(f"bekannt{k}: {a}→{b} Profile {acc*100:.0f}%" for k, (a, b, acc) in zip(["01", "12", "02"], res)))
