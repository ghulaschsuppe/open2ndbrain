import sys, wave, numpy as np, sherpa_onnx
M=sys.argv[1]; files=sys.argv[2:]
ex = sherpa_onnx.SpeakerEmbeddingExtractor(sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=f"{M}/wespeaker_en_voxceleb_resnet34.onnx", num_threads=4))
cfg = sherpa_onnx.VadModelConfig(); cfg.silero_vad.model=f"{M}/silero_vad.onnx"; cfg.silero_vad.min_silence_duration=0.25; cfg.silero_vad.max_speech_duration=8; cfg.sample_rate=16000
C={}
for wf,tf in zip(files[::2],files[1::2]):
    a=np.frombuffer(wave.open(wf).readframes(10**9),dtype=np.int16).astype(np.float32)/32768
    truth=[tuple(map(float,l.split())) for l in open(tf)]
    vad=sherpa_onnx.VoiceActivityDetector(cfg,60)
    for i in range(0,len(a)+512,512):
        vad.accept_waveform(a[i:i+512]) if i<len(a) else vad.flush()
        while not vad.empty():
            s=vad.front.start; x=np.array(vad.front.samples); vad.pop()
            if len(x)<16000: continue
            mid=(s+len(x)/2)/16000; k=[int(t[2]) for t in truth if t[0]<=mid<=t[1]]
            if not k: continue
            st=ex.create_stream(); st.accept_waveform(16000,x); st.input_finished(); v=np.array(ex.compute(st)); v/=np.linalg.norm(v)
            C.setdefault((wf.split('/')[-1][:5],k[0]),[]).append(v)
keys=sorted(C); cent={k:(lambda m:m/np.linalg.norm(m))(np.mean(C[k],0)) for k in keys}
print("Zentren-Cosinus (Datei, Sprecher):"); print(" "*12+"".join(f"{str(k):>14s}" for k in keys))
for a_ in keys: print(f"{str(a_):12s}"+"".join(f"{cent[a_]@cent[b]:14.2f}" for b in keys))
