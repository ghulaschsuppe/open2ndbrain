# Vergleich deutscher ASR-Modelle auf FLEURS-de (dev) – sauber und "Alltag" (leise, Raumrauschen, Opus 16k)
import sys, os, re, time, wave, subprocess, tempfile, numpy as np, sherpa_onnx, csv
S, N = sys.argv[1], int(sys.argv[2]); M=f"{S}/model"
rows=[l.split("\t") for l in open(f"{S}/fleurs/dev.tsv", encoding="utf-8")]
wavs={os.path.basename(p):p for p in [os.path.join(d,f) for d,_,fs in os.walk(f"{S}/fleurs/d") for f in fs if f.endswith(".wav")]}
items=[(wavs[r[1]], r[2]) for r in rows if r[1] in wavs][:N]
def load(p):
    raw=subprocess.run(["ffmpeg","-v","error","-i",p,"-f","s16le","-ac","1","-ar","16000","-"],check=True,capture_output=True).stdout
    return np.frombuffer(raw,np.int16).astype(np.float32)/32768
rng=np.random.default_rng(0)
def degrade(a):
    # leise (−22 dB), Raumhall (einfache IR), Rauschen (SNR 8 dB), danach Opus 16k
    ir=np.exp(-np.arange(0,int(0.25*16000))/(0.05*16000))*rng.normal(0,1,int(0.25*16000)); ir[0]=1; ir/=np.abs(ir).sum()/3
    b=np.convolve(a,ir)[:len(a)]; b*=10**(-22/20)/max(1e-6,np.sqrt(np.mean(b**2)))*0.1
    n=np.cumsum(rng.normal(0,1,len(b))); n-=np.convolve(n,np.ones(400)/400,'same'); n/=np.std(n)+1e-9
    b=b+n*np.sqrt(np.mean(b**2))/10**(8/20)
    with tempfile.TemporaryDirectory() as t:
        w=wave.open(f"{t}/a.wav","wb"); w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000); w.writeframes((np.clip(b,-1,1)*32767).astype(np.int16).tobytes()); w.close()
        subprocess.run(["ffmpeg","-v","error","-y","-i",f"{t}/a.wav","-c:a","libopus","-b:a","16k","-application","voip",f"{t}/a.ogg"],check=True)
        subprocess.run(["ffmpeg","-v","error","-y","-i",f"{t}/a.ogg","-ar","16000","-ac","1",f"{t}/b.wav"],check=True)
        return load(f"{t}/b.wav")
def normalize(a):
    r=np.sqrt(np.mean(a**2))+1e-9; return np.clip(a*min(30.0,0.08/r),-1,1).astype(np.float32)
def norm_text(s): return re.sub(r"[^\wäöüß ]"," ",s.lower()).split()
def wer(r,h):
    d=np.arange(len(h)+1)
    for i,x in enumerate(r,1):
        p=d.copy(); d[0]=i
        for j,y in enumerate(h,1): d[j]=min(p[j]+1,d[j-1]+1,p[j-1]+(x!=y))
    return d[-1],len(r)
T=4
models={
 "parakeet-v3": lambda: sherpa_onnx.OfflineRecognizer.from_transducer(encoder=f"{M}/parakeet/encoder.int8.onnx",decoder=f"{M}/parakeet/decoder.int8.onnx",joiner=f"{M}/parakeet/joiner.int8.onnx",tokens=f"{M}/parakeet/tokens.txt",num_threads=T,model_type="nemo_transducer"),
 "de-fastconformer": lambda: sherpa_onnx.OfflineRecognizer.from_nemo_ctc(model=f"{M}/de-fc/model.int8.onnx",tokens=f"{M}/de-fc/tokens.txt",num_threads=T),
 "canary-180m-de": lambda: sherpa_onnx.OfflineRecognizer.from_nemo_canary(encoder=f"{M}/canary/encoder.int8.onnx",decoder=f"{M}/canary/decoder.int8.onnx",tokens=f"{M}/canary/tokens.txt",src_lang="de",tgt_lang="de",num_threads=T),
 "whisper-small-de": lambda: sherpa_onnx.OfflineRecognizer.from_whisper(encoder=f"{M}/whisper-small/small-encoder.int8.onnx",decoder=f"{M}/whisper-small/small-decoder.int8.onnx",tokens=f"{M}/whisper-small/small-tokens.txt",language="de",task="transcribe",num_threads=T),
}
only=os.environ.get("ONLY")
audio=[(load(p),t) for p,t in items]; deg=[(degrade(a),t) for a,t in audio]
dur=sum(len(a) for a,_ in audio)/16000
print(f"{len(audio)} Sätze, {dur:.0f} s Audio")
for name,mk in models.items():
    if only and name not in only.split(","): continue
    rec=mk()
    for cond,data,nm in [("sauber",audio,False),("Alltag",deg,False),("Alltag+Norm",deg,True)]:
        E=R=0; t0=time.time(); ex=None
        for a,ref in data:
            st=rec.create_stream(); st.accept_waveform(16000, normalize(a) if nm else a); rec.decode_stream(st)
            e,r=wer(norm_text(ref),norm_text(st.result.text)); E+=e; R+=r
            if ex is None: ex=st.result.text
        print(f"{name:18s} {cond:12s} WER {100*E/R:5.1f} %  RTF {(time.time()-t0)/dur:.3f}   z.B.: {ex[:70]}", flush=True)
