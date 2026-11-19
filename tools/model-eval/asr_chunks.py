import sys, time, wave, numpy as np, sherpa_onnx
wav, mdir, vadp, secs, maxlen = sys.argv[1], sys.argv[2], sys.argv[3], float(sys.argv[4]), float(sys.argv[5])
w = wave.open(wav); sr = w.getframerate(); n = min(w.getnframes(), int(secs*sr))
a = np.frombuffer(w.readframes(n), dtype=np.int16).astype(np.float32)/32768
rec = sherpa_onnx.OfflineRecognizer.from_transducer(encoder=f"{mdir}/encoder.int8.onnx", decoder=f"{mdir}/decoder.int8.onnx",
    joiner=f"{mdir}/joiner.int8.onnx", tokens=f"{mdir}/tokens.txt", num_threads=4, model_type="nemo_transducer")
cfg = sherpa_onnx.VadModelConfig(); cfg.silero_vad.model=vadp; cfg.silero_vad.min_silence_duration=0.5; cfg.silero_vad.max_speech_duration=25; cfg.sample_rate=16000
vad = sherpa_onnx.VoiceActivityDetector(cfg, buffer_size_in_seconds=60)
segs=[]
for i in range(0, len(a)+512, 512):
    vad.accept_waveform(a[i:i+512]) if i < len(a) else vad.flush()
    while not vad.empty(): segs.append((vad.front.start, vad.front.start+len(vad.front.samples))); vad.pop()
# Zusammenfassen: Lücke <= 2 s und Gesamtlänge <= maxlen; Originalaudio inkl. kurzer Pausen verwenden
chunks=[]
for s,e in segs:
    if chunks and s-chunks[-1][1] <= 2*sr and e-chunks[-1][0] <= maxlen*sr: chunks[-1][1]=e
    else: chunks.append([s,e])
t0=time.time(); eng=0
for s,e in chunks:
    st=rec.create_stream(); st.accept_waveform(sr, a[max(0,s-int(0.2*sr)):e+int(0.2*sr)]); rec.decode_stream(st)
    r=st.result; print(f"[{s/sr:6.1f}-{e/sr:6.1f}s] {r.text}")
print(f"{len(segs)} VAD-Segmente -> {len(chunks)} Abschnitte, ASR {time.time()-t0:.1f}s, RTF {(time.time()-t0)/(n/sr):.3f}")
print("Zeitstempel je Token vorhanden:", len(r.timestamps) > 0, r.timestamps[:5])
