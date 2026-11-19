import sys, time, wave, numpy as np, sherpa_onnx
from itertools import permutations
wav, seg, emb, truthf = sys.argv[1:5]
a = np.frombuffer(wave.open(wav).readframes(10**9), dtype=np.int16).astype(np.float32)/32768
truth=[tuple(map(float,l.split())) for l in open(truthf)]
for thr in [0.5, 0.6, 0.7]:
    cfg = sherpa_onnx.OfflineSpeakerDiarizationConfig(
        segmentation=sherpa_onnx.OfflineSpeakerSegmentationModelConfig(pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(model=seg), num_threads=4),
        embedding=sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=emb, num_threads=4),
        clustering=sherpa_onnx.FastClusteringConfig(num_clusters=-1, threshold=thr), min_duration_on=0.3, min_duration_off=0.5)
    sd = sherpa_onnx.OfflineSpeakerDiarization(cfg)
    t0=time.time(); res = sd.process(a).sort_by_start_time(); dt=time.time()-t0
    # Frame-genaue Trefferquote (10 ms) mit bester Label-Zuordnung
    T=int(len(a)/160); ref=-np.ones(T,int); hyp=-np.ones(T,int)
    for s,e,k in truth: ref[int(s*100):int(e*100)]=int(k)
    for r in res: hyp[int(r.start*100):int(r.end*100)]=r.speaker
    labs=sorted(set(hyp[hyp>=0])); best=0
    m=ref>=0
    for perm in permutations(range(max(3,len(labs))), len(labs)):
        mp={l:p for l,p in zip(labs,perm)}; h=np.array([mp.get(x,-1) for x in hyp])
        best=max(best,(h[m]==ref[m]).mean())
    print(f"{emb.split('/')[-1][:40]:40s} thr={thr}: {len(labs)} Sprecher (wahr 3), Treffer {best*100:.1f}%, Zeit {dt:.1f}s (RTF {dt/(len(a)/16000):.3f})")
