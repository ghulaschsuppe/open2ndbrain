# Runde 2: Bitrate, Rauschfilter (GTCRN), Whisper turbo – Alltagsbedingungen
import sys, os, re, time, subprocess, tempfile, numpy as np, sherpa_onnx
S, N = sys.argv[1], int(sys.argv[2]); M=f"{S}/model"
rows=[l.split("\t") for l in open(f"{S}/fleurs/dev.tsv", encoding="utf-8")]
wavs={os.path.basename(p):p for p in [os.path.join(d,f) for d,_,fs in os.walk(f"{S}/fleurs/d") for f in fs if f.endswith(".wav")]}
items=[(wavs[r[1]], r[2]) for r in rows if r[1] in wavs][:N]
def run(cmd,inp=None): return subprocess.run(cmd,check=True,capture_output=True,input=inp).stdout
def load(p): return np.frombuffer(run(["ffmpeg","-v","error","-i",p,"-f","s16le","-ac","1","-ar","16000","-"]),np.int16).astype(np.float32)/32768
def codec(a,kbps):
    pcm=(np.clip(a,-1,1)*32767).astype(np.int16).tobytes()
    with tempfile.TemporaryDirectory() as t:
        run(["ffmpeg","-v","error","-f","s16le","-ar","16000","-ac","1","-i","-","-c:a","libopus","-b:a",f"{kbps}k","-application","voip",f"{t}/a.ogg"],pcm)
        return load(f"{t}/a.ogg")
rng=np.random.default_rng(0)
def room(a):
    ir=np.exp(-np.arange(0,4000)/800)*rng.normal(0,1,4000); ir[0]=1; ir/=np.abs(ir).sum()/3
    b=np.convolve(a,ir)[:len(a)]; b*=0.1*10**(-22/20)/max(1e-6,np.sqrt(np.mean(b**2)))
    n=np.cumsum(rng.normal(0,1,len(b))); n-=np.convolve(n,np.ones(400)/400,'same'); n/=np.std(n)+1e-9
    return b+n*np.sqrt(np.mean(b**2))/10**(8/20)
def normalize(a): r=np.sqrt(np.mean(a**2))+1e-9; return np.clip(a*min(30.0,0.08/r),-1,1).astype(np.float32)
den=sherpa_onnx.OfflineSpeechDenoiser(sherpa_onnx.OfflineSpeechDenoiserConfig(model=sherpa_onnx.OfflineSpeechDenoiserModelConfig(gtcrn=sherpa_onnx.OfflineSpeechDenoiserGtcrnModelConfig(model=f"{M}/denoise/gtcrn_simple.onnx"),num_threads=4)))
def denoise(a):
    r=den.run(normalize(a),16000); x=np.array(r.samples,np.float32)
    if r.sample_rate!=16000: x=np.interp(np.arange(0,len(x),r.sample_rate/16000),np.arange(len(x)),x).astype(np.float32)
    return x
def norm_text(s): return re.sub(r"[^\wäöüß ]"," ",s.lower()).split()
def wer(r,h):
    d=np.arange(len(h)+1)
    for i,x in enumerate(r,1):
        p=d.copy(); d[0]=i
        for j,y in enumerate(h,1): d[j]=min(p[j]+1,d[j-1]+1,p[j-1]+(x!=y))
    return d[-1],len(r)
clean=[(load(p),t) for p,t in items]; dur=sum(len(a) for a,_ in clean)/16000
roomed=[(room(a),t) for a,t in clean]
cond={k:[(codec(a,k),t) for a,t in roomed] for k in (16,24,32)}
t0=time.time(); dn16=[(denoise(a),t) for a,t in cond[16]]; dnt=(time.time()-t0)/dur
print(f"{len(clean)} Sätze, {dur:.0f} s; Rauschfilter RTF {dnt:.3f}",flush=True)
T=4
mk={"fastconformer":lambda: sherpa_onnx.OfflineRecognizer.from_nemo_ctc(model=f"{M}/de-fc/model.int8.onnx",tokens=f"{M}/de-fc/tokens.txt",num_threads=T),
    "canary":lambda: sherpa_onnx.OfflineRecognizer.from_nemo_canary(encoder=f"{M}/canary/encoder.int8.onnx",decoder=f"{M}/canary/decoder.int8.onnx",tokens=f"{M}/canary/tokens.txt",src_lang="de",tgt_lang="de",num_threads=T),
    "whisper-turbo":lambda: sherpa_onnx.OfflineRecognizer.from_whisper(encoder=f"{M}/whisper-turbo/turbo-encoder.int8.onnx",decoder=f"{M}/whisper-turbo/turbo-decoder.int8.onnx",tokens=f"{M}/whisper-turbo/turbo-tokens.txt",language="de",task="transcribe",num_threads=T)}
plan={"fastconformer":[("Opus16",cond[16]),("Opus24",cond[24]),("Opus32",cond[32]),("Opus16+Filter",dn16)],
      "canary":[("Opus16",cond[16]),("Opus32",cond[32]),("Opus16+Filter",dn16)],
      "whisper-turbo":[("sauber",clean),("Opus16",cond[16]),("Opus16+Filter",dn16)]}
only=os.environ.get("ONLY")
for name,conds in plan.items():
    if only and name not in only.split(","): continue
    if name=="whisper-turbo" and not os.path.exists(f"{M}/whisper-turbo/turbo-tokens.txt"): print("turbo fehlt"); continue
    rec=mk[name]()
    for cn,data in conds:
        E=R=0; t0=time.time()
        for a,ref in data:
            st=rec.create_stream(); st.accept_waveform(16000,normalize(a)); rec.decode_stream(st)
            e,r=wer(norm_text(ref),norm_text(st.result.text)); E+=e; R+=r
        print(f"{name:14s} {cn:14s} WER {100*E/R:5.1f} %  RTF {(time.time()-t0)/dur:.3f}",flush=True)
