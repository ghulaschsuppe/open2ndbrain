# Alternative: Embedding je VAD-Abschnitt + inkrementelles Clustering (so wie später dateiübergreifend)
import sys, time, wave, numpy as np, sherpa_onnx
wav, vadp, emb, truthf = sys.argv[1:5]
sr=16000; a = np.frombuffer(wave.open(wav).readframes(10**9), dtype=np.int16).astype(np.float32)/32768
truth=[tuple(map(float,l.split())) for l in open(truthf)]
cfg = sherpa_onnx.VadModelConfig(); cfg.silero_vad.model=vadp; cfg.silero_vad.min_silence_duration=0.25; cfg.silero_vad.max_speech_duration=8; cfg.sample_rate=sr
vad = sherpa_onnx.VoiceActivityDetector(cfg, buffer_size_in_seconds=60); segs=[]
for i in range(0, len(a)+512, 512):
    vad.accept_waveform(a[i:i+512]) if i < len(a) else vad.flush()
    while not vad.empty(): segs.append((vad.front.start, np.array(vad.front.samples))); vad.pop()
ex = sherpa_onnx.SpeakerEmbeddingExtractor(sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=emb, num_threads=4))
t0=time.time(); E=[]
for s,x in segs:
    st=ex.create_stream(); st.accept_waveform(sr,x); st.input_finished(); v=np.array(ex.compute(st)); E.append(v/np.linalg.norm(v))
dt=time.time()-t0
def ref_of(s,x):
    mid=(s+len(x)/2)/sr
    for a0,b0,k in truth:
        if a0<=mid<=b0: return int(k)
    return -1
R=[ref_of(s,x) for s,x in segs]
for thr in [0.4,0.5,0.6]:
    C=[]; L=[]
    for v in E:
        sims=[c@v/np.linalg.norm(c) for c in C]
        if sims and max(sims)>=thr: k=int(np.argmax(sims)); C[k]+=v
        else: C.append(v.copy()); k=len(C)-1
        L.append(k)
    # Mehrheits-Zuordnung
    from collections import Counter
    ok=sum(1 for l,r in zip(L,R) if r>=0 and Counter(rr for ll,rr in zip(L,R) if ll==l).most_common(1)[0][0]==r)
    n=sum(1 for r in R if r>=0)
    print(f"{emb[:38]:38s} thr={thr}: {len(C)} Cluster (wahr 3), richtig {ok}/{n} = {ok/n*100:.0f}% der Abschnitte, Embeddings {dt:.1f}s")
